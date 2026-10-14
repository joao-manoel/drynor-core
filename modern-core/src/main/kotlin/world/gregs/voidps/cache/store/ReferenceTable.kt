package world.gregs.voidps.cache.store

import world.gregs.voidps.cache.ReadOnlyCache

/** One file of an archive, as the reference table describes it. */
class FileEntry(
    val id: Int,
    var nameHash: Int = 0
)

/** One archive of an index, as the reference table describes it. */
class ArchiveEntry(
    val id: Int,
    var nameHash: Int = 0,
    var crc: Int = 0,
    var whirlpool: ByteArray? = null,
    var version: Int = 0,
    /** The archive's files, ascending by id. Never empty for an archive that exists. */
    val files: MutableList<FileEntry> = ArrayList()
) {
    val fileIds: IntArray
        get() = IntArray(files.size) { files[it].id }

    fun file(id: Int): FileEntry? = files.firstOrNull { it.id == id }
}

/**
 * An index's reference table - the decompressed contents of index 255's archive `<index>`.
 *
 * ```
 * u8  format                    5, 6 or 7; 6 and 7 both appear in the 727 cache
 * i32 revision                  format >= 6 only
 * u8  flags                     0x1 names, 0x2 whirlpool; 727 uses no others
 * ?   archiveCount              big-smart for format 7, u16 otherwise
 * ?   archive id deltas         one per archive, same encoding
 * i32 archive name hashes       if 0x1
 * i32 archive crcs
 * i32 archive unknown           if 0x8 - absent from 727
 * b64 archive whirlpools        if 0x2 - index 30 only
 * i64 archive sizes             if 0x4 - absent from 727
 * i32 archive versions          full ints; a container's trailer is the low 16 bits
 * ?   file counts               one per archive
 * ?   file id deltas            per archive, its own files
 * i32 file name hashes          if 0x1, per archive, its own files
 * ```
 *
 * Archives are held in encoded order, which is ascending by id because the ids are stored as
 * non-negative deltas. [encode] reproduces the shipped table byte for byte for all 37 indices.
 */
class ReferenceTable(
    var format: Int = MAX_FORMAT,
    var revision: Int = 0,
    val named: Boolean = false,
    val whirlpool: Boolean = false
) {

    private val entries = ArrayList<ArchiveEntry>()

    /** Every archive, ascending by id. */
    val archives: List<ArchiveEntry>
        get() = entries

    val archiveCount: Int
        get() = entries.size

    val flags: Int
        get() = (if (named) NAME_FLAG else 0) or (if (whirlpool) WHIRLPOOL_FLAG else 0)

    fun archive(id: Int): ArchiveEntry? {
        val index = indexOf(id)
        return if (index < 0) null else entries[index]
    }

    fun contains(id: Int): Boolean = indexOf(id) >= 0

    /** The existing entry for [id], or a new empty one inserted in id order. */
    fun archiveOrCreate(id: Int): ArchiveEntry {
        require(id >= 0) { "Archive id $id is negative." }
        val index = indexOf(id)
        if (index >= 0) {
            return entries[index]
        }
        val entry = ArchiveEntry(id)
        entries.add(-(index + 1), entry)
        return entry
    }

    fun add(entry: ArchiveEntry) {
        val index = indexOf(entry.id)
        require(index < 0) { "Archive ${entry.id} is already in this table." }
        entries.add(-(index + 1), entry)
    }

    fun remove(id: Int): ArchiveEntry? {
        val index = indexOf(id)
        return if (index < 0) null else entries.removeAt(index)
    }

    /** Binary search over the id-ordered entries; the insertion point encoded the same way as [List.binarySearch]. */
    private fun indexOf(id: Int): Int {
        var low = 0
        var high = entries.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = entries[middle].id
            when {
                value < id -> low = middle + 1
                value > id -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }

    fun encode(): ByteArray {
        require(format in MIN_FORMAT..MAX_FORMAT) { "Unknown reference table format $format." }
        val smart = format >= 7
        val fileTotal = entries.sumOf { it.files.size }
        // Per archive: id delta, name hash, crc, whirlpool, version and file count, each at
        // most four bytes but for the whirlpool. Per file: an id delta and a name hash.
        val capacity = 1 + 4 + 1 + 4 +
            entries.size * (4 + 4 + 4 + 4 + 4 + ReadOnlyCache.WHIRLPOOL_SIZE) +
            fileTotal * (4 + 4)
        val output = ByteArray(capacity)
        var offset = 0
        output[offset++] = format.toByte()
        if (format >= 6) {
            putInt(output, offset, revision)
            offset += 4
        }
        output[offset++] = flags.toByte()
        offset = putSmart(output, offset, entries.size, smart)
        var previous = 0
        for (entry in entries) {
            offset = putSmart(output, offset, entry.id - previous, smart)
            previous = entry.id
        }
        if (named) {
            for (entry in entries) {
                putInt(output, offset, entry.nameHash)
                offset += 4
            }
        }
        for (entry in entries) {
            putInt(output, offset, entry.crc)
            offset += 4
        }
        if (whirlpool) {
            for (entry in entries) {
                val hash = entry.whirlpool
                    ?: throw IllegalStateException("Archive ${entry.id} has no whirlpool but the table needs one.")
                require(hash.size == ReadOnlyCache.WHIRLPOOL_SIZE) {
                    "Archive ${entry.id}'s whirlpool is ${hash.size} bytes, not ${ReadOnlyCache.WHIRLPOOL_SIZE}."
                }
                System.arraycopy(hash, 0, output, offset, hash.size)
                offset += hash.size
            }
        }
        for (entry in entries) {
            putInt(output, offset, entry.version)
            offset += 4
        }
        for (entry in entries) {
            offset = putSmart(output, offset, entry.files.size, smart)
        }
        for (entry in entries) {
            var previousFile = 0
            for (file in entry.files) {
                offset = putSmart(output, offset, file.id - previousFile, smart)
                previousFile = file.id
            }
        }
        if (named) {
            for (entry in entries) {
                for (file in entry.files) {
                    putInt(output, offset, file.nameHash)
                    offset += 4
                }
            }
        }
        return output.copyOf(offset)
    }

    companion object {
        const val NAME_FLAG = 0x1
        const val WHIRLPOOL_FLAG = 0x2
        const val SIZES_FLAG = 0x4
        const val UNKNOWN_FLAG = 0x8

        const val MIN_FORMAT = 5
        const val MAX_FORMAT = 7

        fun decode(bytes: ByteArray): ReferenceTable {
            var offset = 0
            val format = bytes[offset++].toInt() and 0xff
            require(format in MIN_FORMAT..MAX_FORMAT) { "Unknown reference table format $format." }
            var revision = 0
            if (format >= 6) {
                revision = getInt(bytes, offset)
                offset += 4
            }
            val flags = bytes[offset++].toInt() and 0xff
            if (flags and (SIZES_FLAG or UNKNOWN_FLAG) != 0) {
                throw UnsupportedOperationException(
                    "Reference table flags $flags use a section no 727 table has; nothing can encode it back."
                )
            }
            val smart = format >= 7
            val table = ReferenceTable(format, revision, flags and NAME_FLAG != 0, flags and WHIRLPOOL_FLAG != 0)
            val archiveCount = getSmart(bytes, offset, smart).also { offset += smartSize(it, smart) }
            var previous = 0
            val ids = IntArray(archiveCount) {
                val delta = getSmart(bytes, offset, smart)
                offset += smartSize(delta, smart)
                previous += delta
                previous
            }
            val entries = ArrayList<ArchiveEntry>(archiveCount)
            for (id in ids) {
                entries.add(ArchiveEntry(id))
            }
            if (table.named) {
                for (entry in entries) {
                    entry.nameHash = getInt(bytes, offset)
                    offset += 4
                }
            }
            for (entry in entries) {
                entry.crc = getInt(bytes, offset)
                offset += 4
            }
            if (table.whirlpool) {
                for (entry in entries) {
                    entry.whirlpool = bytes.copyOfRange(offset, offset + ReadOnlyCache.WHIRLPOOL_SIZE)
                    offset += ReadOnlyCache.WHIRLPOOL_SIZE
                }
            }
            for (entry in entries) {
                entry.version = getInt(bytes, offset)
                offset += 4
            }
            val fileCounts = IntArray(archiveCount) {
                val count = getSmart(bytes, offset, smart)
                offset += smartSize(count, smart)
                count
            }
            for ((index, entry) in entries.withIndex()) {
                var fileId = 0
                for (file in 0 until fileCounts[index]) {
                    val delta = getSmart(bytes, offset, smart)
                    offset += smartSize(delta, smart)
                    fileId += delta
                    entry.files.add(FileEntry(fileId))
                }
            }
            if (table.named) {
                for (entry in entries) {
                    for (file in entry.files) {
                        file.nameHash = getInt(bytes, offset)
                        offset += 4
                    }
                }
            }
            // Entries are already in encoded order, which is ascending: the ids come from
            // non-negative deltas. Adding them one by one keeps the ordering invariant honest.
            for (entry in entries) {
                table.add(entry)
            }
            return table
        }

        /**
         * Big-smart as the reference table uses it: two bytes for 0..0x7fff, four with the top
         * bit set otherwise. Unlike the packet reader's big-smart there is no `-1` sentinel -
         * a table only ever stores counts and non-negative deltas.
         */
        private fun getSmart(data: ByteArray, offset: Int, smart: Boolean): Int {
            if (!smart) {
                return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            }
            return if (data[offset].toInt() < 0) {
                getInt(data, offset) and 0x7fffffff
            } else {
                ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            }
        }

        private fun smartSize(value: Int, smart: Boolean) = if (smart && value > 0x7fff) 4 else 2

        private fun putSmart(data: ByteArray, offset: Int, value: Int, smart: Boolean): Int {
            require(value >= 0) { "A reference table cannot store the negative value $value." }
            if (smart && value > 0x7fff) {
                putInt(data, offset, value or 0x80000000.toInt())
                return offset + 4
            }
            require(value <= 0xffff) { "$value does not fit in a reference table's 16 bit field." }
            data[offset] = (value shr 8).toByte()
            data[offset + 1] = value.toByte()
            return offset + 2
        }

        private fun getInt(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)

        private fun putInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value shr 24).toByte()
            data[offset + 1] = (value shr 16).toByte()
            data[offset + 2] = (value shr 8).toByte()
            data[offset + 3] = value.toByte()
        }
    }
}
