package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.Int2IntMaps
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import org.drynor.core.Logger.logTrace
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.RandomAccessFile

/**
 * [Cache] which efficiently stores information about its indexes, archives and files.
 */
abstract class ReadOnlyCache(indexCount: Int) : Cache {
    val indices: IntArray = IntArray(indexCount) { it }
    val archives: Array<IntArray?> = arrayOfNulls(indexCount)
    val fileCounts: Array<IntArray?> = arrayOfNulls(indexCount)
    val files: Array<Array<IntArray?>?> = arrayOfNulls(indexCount)
    private val hashes: MutableMap<Int, Int> = Int2IntOpenHashMap(32767)
    // [index] -> (archiveId -> archiveNameHash). Unlike [hashes] this is keyed per-index and by
    // archive id, so name hashes shared between indices (or repeated within one) aren't lost.
    private val archiveHashes: Array<Int2IntOpenHashMap?> = arrayOfNulls(indexCount)
    // [index][archive] -> (fileNameHash -> fileId). File name hashes are only unique within an archive.
    private val fileHashes: Array<Array<Int2IntOpenHashMap?>?> = arrayOfNulls(indexCount)
    // Per-index name introspection. NAME_FLAG governs both archive and file name sections at the format
    // level, but actual hash values can be 0 for entries without a real name, so we count non-zero hashes.
    private val nameFlags: BooleanArray = BooleanArray(indexCount)
    private val archiveNameCounts: IntArray = IntArray(indexCount)
    private val fileNameCounts: IntArray = IntArray(indexCount)
    /** [index] -> its decoded reference table, the model everything above is projected from. */
    protected val referenceTables: Array<ReferenceTable?> = arrayOfNulls(indexCount)

    override lateinit var versionTable: ByteArray

    @Suppress("UNCHECKED_CAST")
    internal fun fileData(
        context: DecompressionContext,
        main: RandomAccessFile,
        mainLength: Long,
        indexRaf: RandomAccessFile,
        indexId: Int,
        archiveId: Int,
        xteas: Map<Int, IntArray>?,
        sectors: Array<Array<ByteArray?>?>? = null
    ): Array<ByteArray?>? {
        val fileCounts = fileCounts[indexId] ?: return null
        val fileIds = files[indexId] ?: return null
        val fileCount = fileCounts.getOrNull(archiveId) ?: return null
        val sectorData = readSector(main, mainLength, indexRaf, indexId, archiveId) ?: return null
        if (sectors != null) {
            sectors[indexId]!![archiveId] = sectorData
        }
        val keys = if (xteas != null && indexId == Index.MAPS) xteas[archiveId] else null
        val decompressed = context.decompress(sectorData, keys) ?: return null
        if (fileCount == 1) {
            val fileId = fileIds[archiveId]?.last() ?: return null
            return Array(fileId + 1) {
                if (it == fileId) decompressed else null
            }
        }

        val reader = BufferReader(decompressed)
        val rawArray = reader.array()
        var fileDataSizesOffset = decompressed.size
        val chunkSize: Int = rawArray[--fileDataSizesOffset].toInt() and 0xFF
        fileDataSizesOffset -= chunkSize * (fileCount * 4)
        val offsets = IntArray(fileCount)
        reader.position(fileDataSizesOffset)
        for (i in 0 until chunkSize) {
            var previousLength = 0
            for (fileIndex in 0 until fileCount) {
                previousLength += reader.readInt()
                offsets[fileIndex] += previousLength
            }
        }
        val archiveFiles = Array(fileCount) { index ->
            val array = ByteArray(offsets[index])
            offsets[index] = 0
            array
        }
        var offset = 0
        reader.position(fileDataSizesOffset)
        for (i in 0 until chunkSize) {
            var length = 0
            for (fileIndex in 0 until fileCount) {
                val read = reader.readInt()
                val fileData = archiveFiles[fileIndex]
                length += read
                System.arraycopy(rawArray, offset, fileData, offsets[fileIndex], length)
                offset += length
                offsets[fileIndex] += length
            }
        }
        return archiveFiles as Array<ByteArray?>
    }

    internal fun archiveData(
        context: DecompressionContext,
        main: RandomAccessFile,
        length: Long,
        index255: RandomAccessFile,
        indexId: Int,
        versionTable: VersionTableBuilder?,
        whirlpool: Whirlpool,
        sectors: Array<ByteArray?>? = null
    ): Int {
        val archiveSector = readSector(main, length, index255, 255, indexId)
        if (sectors != null) {
            sectors[indexId] = archiveSector
        }
        if (archiveSector == null) {
            logTrace("Empty index $indexId.")
            versionTable?.skip(indexId)
            return -1
        }
        versionTable?.sector(indexId, archiveSector, whirlpool)
        val decompressed = context.decompress(archiveSector) ?: return -1
        val table = ReferenceTable.decode(decompressed)
        if (table.format >= 6) {
            versionTable?.revision(indexId, table.revision)
        }
        return indexTable(indexId, table)
    }

    /**
     * Project [table] onto the flat per-index lookups this class serves reads from, replacing
     * whatever was there. Returns the highest archive id in the table, 0 when it is empty.
     */
    internal fun indexTable(indexId: Int, table: ReferenceTable): Int {
        referenceTables[indexId] = table
        val archiveIds = IntArray(table.archiveCount) { table.archives[it].id }
        val highest = archiveIds.maxOrNull() ?: 0
        archives[indexId] = archiveIds
        nameFlags[indexId] = table.named
        archiveNameCounts[indexId] = 0
        fileNameCounts[indexId] = 0
        if (table.named) {
            val indexArchiveHashes = Int2IntOpenHashMap(table.archiveCount)
            archiveHashes[indexId] = indexArchiveHashes
            for (entry in table.archives) {
                hashes[entry.nameHash] = entry.id
                indexArchiveHashes[entry.id] = entry.nameHash
                if (entry.nameHash != 0) {
                    archiveNameCounts[indexId]++
                }
            }
        }
        val archiveSizes = IntArray(highest + 1)
        val fileIds = arrayOfNulls<IntArray>(highest + 1)
        val indexFileHashes = if (table.named) arrayOfNulls<Int2IntOpenHashMap>(highest + 1) else null
        for (entry in table.archives) {
            archiveSizes[entry.id] = entry.files.size
            fileIds[entry.id] = entry.fileIds
            if (indexFileHashes != null) {
                val archiveFileHashes = Int2IntOpenHashMap(entry.files.size)
                indexFileHashes[entry.id] = archiveFileHashes
                for (file in entry.files) {
                    archiveFileHashes[file.nameHash] = file.id
                    if (file.nameHash != 0) {
                        fileNameCounts[indexId]++
                    }
                }
            }
        }
        fileCounts[indexId] = archiveSizes
        files[indexId] = fileIds
        if (indexFileHashes != null) {
            fileHashes[indexId] = indexFileHashes
        }
        return highest
    }

    override fun referenceTable(index: Int): ReferenceTable? = referenceTables.getOrNull(index)

    override fun archiveVersion(index: Int, archive: Int): Int =
        referenceTables.getOrNull(index)?.archive(archive)?.version ?: -1

    override fun archiveCrc(index: Int, archive: Int): Int =
        referenceTables.getOrNull(index)?.archive(archive)?.crc ?: 0

    override fun indexCount() = indices.size

    override fun indices() = indices

    override fun archives(index: Int) = archives.getOrNull(index) ?: IntArray(0)

    override fun archiveCount(index: Int) = archives.size

    override fun lastArchiveId(indexId: Int) = archives.getOrNull(indexId)?.lastOrNull() ?: -1

    override fun archiveId(index: Int, hash: Int) = hashes[hash] ?: -1

    override fun fileId(index: Int, archive: Int, hash: Int): Int {
        val archiveFileHashes = fileHashes.getOrNull(index)?.getOrNull(archive) ?: return -1
        return if (archiveFileHashes.containsKey(hash)) archiveFileHashes.get(hash) else -1
    }

    override fun hasNameFlag(index: Int) = nameFlags.getOrNull(index) ?: false

    override fun archiveIds(index: Int): IntArray {
        val ids = archives.getOrNull(index) ?: return IntArray(0)
        val copy = ids.copyOf()
        copy.sort()
        return copy
    }

    override fun archiveNameHashes(index: Int): Map<Int, Int> {
        val map = archiveHashes.getOrNull(index) ?: return emptyMap()
        return Int2IntMaps.unmodifiable(map)
    }

    override fun archiveNameCount(index: Int) = archiveNameCounts.getOrNull(index) ?: 0

    override fun fileNameCount(index: Int) = fileNameCounts.getOrNull(index) ?: 0

    override fun files(index: Int, archive: Int) = files.getOrNull(index)?.getOrNull(archive) ?: IntArray(0)

    override fun fileCount(indexId: Int, archiveId: Int) = fileCounts.getOrNull(indexId)?.getOrNull(archiveId) ?: 0

    override fun lastFileId(indexId: Int, archive: Int) = files.getOrNull(indexId)?.getOrNull(archive)?.lastOrNull() ?: -1

    override fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray?) {
        throw UnsupportedOperationException("Read only cache.")
    }

    override fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray?) {
        throw UnsupportedOperationException("Read only cache.")
    }

    override fun update(): Boolean {
        return false
    }

    override fun close() {
    }

    fun calcSize(indexId: Int, archiveName: String? = null): Int {
        if (archiveName != null) {
            val archiveId = archiveId(indexId, archiveName)
            if (archiveId < 0) return 0
            return (sector(indexId, archiveId(indexId, archiveName))?.size ?: 2) - 2
        }
        var size = 0
        archives(indexId).forEach {
            size += sector(indexId, it)?.size ?: 0
        }
        size += sector(255, indexId)?.size ?: 0
        return size
    }

    companion object {
        const val INDEX_SIZE = 6
        const val WHIRLPOOL_SIZE = 64
        internal const val SECTOR_SIZE = 520
        internal const val SECTOR_HEADER_SIZE_SMALL = 8
        internal const val SECTOR_DATA_SIZE_SMALL = 512
        internal const val SECTOR_HEADER_SIZE_BIG = 10
        internal const val SECTOR_DATA_SIZE_BIG = 510

        /**
         * Reads a section of a cache's archive
         */
        internal fun readSector(mainFile: RandomAccessFile, length: Long, raf: RandomAccessFile, indexId: Int, sectorId: Int): ByteArray? {
            if (length < INDEX_SIZE * sectorId + INDEX_SIZE) {
                return null
            }
            raf.seek(sectorId.toLong() * INDEX_SIZE)
            val sectorData = ByteArray(SECTOR_SIZE)
            raf.read(sectorData, 0, INDEX_SIZE)
            val bigSector = sectorId > 65535
            val buffer = BufferReader(sectorData)
            val sectorSize = buffer.readUnsignedMedium()
            var sectorPosition = buffer.readUnsignedMedium()
            if (sectorSize < 0 || sectorPosition <= 0 || sectorPosition > mainFile.length() / SECTOR_SIZE) {
                return null
            }
            var read = 0
            var chunk = 0
            val sectorHeaderSize = if (bigSector) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
            val sectorDataSize = if (bigSector) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
            val output = ByteArray(sectorSize)
            while (read < sectorSize) {
                if (sectorPosition == 0) {
                    return null
                }
                var requiredToRead = sectorSize - read
                if (requiredToRead > sectorDataSize) {
                    requiredToRead = sectorDataSize
                }
                mainFile.seek(sectorPosition.toLong() * SECTOR_SIZE)
                mainFile.read(sectorData, 0, requiredToRead + sectorHeaderSize)
                buffer.position(0)
                val id = if (bigSector) buffer.readInt() else buffer.readUnsignedShort()
                val sectorChunk = buffer.readUnsignedShort()
                val sectorNextPosition = buffer.readUnsignedMedium()
                val sectorIndex = buffer.readUnsignedByte()
                if (sectorIndex != indexId || id != sectorId || sectorChunk != chunk) {
                    return null
                } else if (sectorNextPosition < 0 || sectorNextPosition > mainFile.length() / SECTOR_SIZE) {
                    return null
                }
                System.arraycopy(sectorData, sectorHeaderSize, output, read, requiredToRead)
                read += requiredToRead
                sectorPosition = sectorNextPosition
                chunk++
            }
            return output
        }
    }

}