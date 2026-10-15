package world.gregs.voidps.cache

import org.drynor.core.Logger.logWarn
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * [Cache] which reads data directly from file.
 * Thread-safe for concurrent reads from multiple threads.
 * Average read speeds, fast loading and low but variable memory usage.
 *
 * Opened writable it also serves [write]: the group is repacked, recompressed with the
 * archive's existing compression, its version bumped, and the index's reference table and the
 * signed version table rewritten so the cache stays internally consistent for a JS5 client.
 */
class FileCache(
    private val store: FileStore,
    indexCount: Int,
    val xteas: Map<Int, IntArray>?,
    private val exponent: BigInteger? = null,
    private val modulus: BigInteger? = null
) : ReadOnlyCache(indexCount) {

    private val dataCache = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val sectorCache = ConcurrentHashMap<Int, ByteArray?>()
    private val context = ThreadLocal.withInitial { DecompressionContext() }
    private val writeLock = Any()

    @Volatile
    private var crcs: IntArray? = null

    val writable: Boolean
        get() = store.writable

    override fun indexCrcs(): IntArray {
        crcs?.let { return it }
        synchronized(writeLock) {
            crcs?.let { return it }
            val calculated = IntArray(indices.size) {
                val data = sector(255, it) ?: return@IntArray 0
                CRC.calculate(data, 0, data.size)
            }
            crcs = calculated
            return calculated
        }
    }

    override fun sector(index: Int, archive: Int): ByteArray? {
        val key = index + (archive shl 6)
        sectorCache[key]?.let { return it }
        val result = store.read(index, archive) ?: return null
        sectorCache[key] = result
        return result
    }

    override fun container(index: Int, archive: Int): ByteArray? = store.read(index, archive)

    override fun data(index: Int, archive: Int, file: Int, xtea: IntArray?): ByteArray? {
        if (index >= files.size) {
            return null
        }
        val archives = files[index]
        if (archives == null || archive >= archives.size) {
            return null
        }
        val fileIds = archives[archive] ?: return null
        val matchingIndex = fileIds.indexOf(file)
        if (matchingIndex == -1) {
            return null
        }

        // If XTEA key is provided directly, use it; otherwise fall back to the class field map
        // For MAPS index, create a temporary map with the archive ID as key
        val effectiveXteas = if (xtea != null && index == Index.MAPS) {
            mapOf(archive to xtea)
        } else {
            xteas
        }

        val hash = index + (archive shl 6) + (if (xtea != null) xtea.contentHashCode() else 0)
        val cached = dataCache[hash]
        if (cached != null) return cached[matchingIndex]

        val sectorData = store.read(index, archive) ?: return null
        val ctx = context.get()
        val keys = if (effectiveXteas != null && index == Index.MAPS) effectiveXteas[archive] else null
        val decompressed = ctx.decompress(sectorData, keys) ?: return null

        val fileCount = fileCounts[index]?.getOrNull(archive) ?: return null
        val result = if (fileCount == 1) {
            // One file, whose id is not necessarily 0 - index 23's area archives hold file 4 - and the
            // array is indexed by position in [fileIds], which for a single file archive is only ever 0.
            arrayOf<ByteArray?>(decompressed)
        } else {
            parseMultiFileArchive(decompressed, fileCount)
        }

        dataCache[hash] = result
        return result[matchingIndex]
    }

    /**
     * Parses a multi-file archive from decompressed data into individual file byte arrays.
     * The archive format stores chunk counts and per-file sizes at the end of the data,
     * with the actual file data packed sequentially from the beginning.
     */
    private fun parseMultiFileArchive(decompressed: ByteArray, fileCount: Int): Array<ByteArray?> {
        val files = ArchiveGroup.split(decompressed, IntArray(fileCount) { it }).files
        return Array(fileCount) { files[it] }
    }

    override fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray?) {
        val id = archiveId(index, archive)
        require(id != -1) { "No archive named '$archive' in index $index." }
        write(index, id, 0, data, xteas)
    }

    /**
     * Replace [file] of [archive] with [data].
     *
     * The rest of the group is read back and repacked around it, so this costs a decompress and
     * a recompress of the whole archive. The archive's existing compression and gzip OS byte are
     * kept, its version is bumped by one, and the reference table's CRC, version, whirlpool and
     * file list are brought in line with what was written before the table itself is rewritten
     * and the version table rebuilt.
     *
     * A three chunk group (index 0) keeps its layout only when nothing about the file lengths
     * changed; any other edit is written as a single chunk, which every reader accepts.
     */
    override fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray?) {
        check(store.writable) { "Read only cache." }
        require(archive >= 0 && file >= 0) { "Archive $archive file $file is not a valid address." }
        synchronized(writeLock) {
            val table = referenceTables.getOrNull(index)
                ?: throw IllegalArgumentException("Index $index has no reference table to write into.")
            val entry = table.archive(archive)
            val keys = xteas ?: if (index == Index.MAPS) this.xteas?.get(archive) else null

            var compression = Compression.GZIP
            var gzipOs = 0
            var chunks: List<IntArray>? = null
            val contents = HashMap<Int, ByteArray>()
            val nameHashes = HashMap<Int, Int>()
            if (entry != null) {
                for (existing in entry.files) {
                    nameHashes[existing.id] = existing.nameHash
                }
                val current = store.read(index, archive)
                if (current != null) {
                    val container = Container.decode(current, keys)
                    compression = container.compression
                    gzipOs = container.gzipOs
                    val ids = entry.fileIds
                    val group = ArchiveGroup.split(container.data(), ids)
                    chunks = group.chunks
                    for ((position, id) in ids.withIndex()) {
                        contents[id] = group.files[position]
                    }
                }
            }

            val previous = contents.put(file, data)
            val layout = if (chunks != null && chunks.size > 1 && previous != null && previous.size == data.size) {
                chunks
            } else {
                null
            }
            val ids = contents.keys.sorted()
            val group = ArchiveGroup.join(ids.map { contents.getValue(it) }, layout)

            val version = (entry?.version ?: 0) + 1
            val container = compress(group, compression, version and 0xffff, gzipOs, index, archive)
            val bytes = container.encode(keys)
            store.write(index, archive, bytes)

            val target = table.archiveOrCreate(archive)
            target.crc = CRC.calculate(bytes, 0, bytes.size - 2)
            target.version = version
            if (table.whirlpool) {
                target.whirlpool = container.whirlpool(keys)
            }
            target.files.clear()
            for (id in ids) {
                target.files.add(FileEntry(id, nameHashes[id] ?: 0))
            }

            writeReferenceTable(index, table)
            indexTable(index, table)
            sectorCache.remove(index + (archive shl 6))
            sectorCache.remove(255 + (index shl 6))
            // Data cache keys are salted with the xtea keys used to read them, so the entries
            // for this archive cannot be picked out one by one.
            dataCache.clear()
            crcs = null
            rebuildVersionTable()
        }
    }

    /**
     * Compress with [compression], falling back to gzip while an encoder for it is missing.
     * Everything the client checks - the reference table CRC, the whirlpool and the version
     * table - is derived from the bytes actually written, so the fallback stays consistent; it
     * only means the archive stops being byte-identical to the one the cache shipped.
     */
    private fun compress(group: ByteArray, compression: Compression, version: Int?, gzipOs: Int, index: Int, archive: Int): Container {
        return try {
            Container.compress(group, compression, version, gzipOs)
        } catch (e: UnsupportedOperationException) {
            logWarn("No $compression encoder yet; writing index $index archive $archive as gzip instead (${e.message}).")
            Container.compress(group, Compression.GZIP, version, gzipOs)
        }
    }

    private fun writeReferenceTable(index: Int, table: ReferenceTable) {
        val current = store.read(FileStore.REFERENCE_INDEX, index)
        val compression = if (current == null) Compression.GZIP else Container.decode(current).compression
        // A reference table container has no version trailer.
        val container = compress(table.encode(), compression, null, 0, FileStore.REFERENCE_INDEX, index)
        store.write(FileStore.REFERENCE_INDEX, index, container.encode())
    }

    private fun rebuildVersionTable() {
        if (exponent == null || modulus == null) {
            return
        }
        val builder = VersionTableBuilder(exponent, modulus, indices.size)
        val whirlpool = Whirlpool()
        for (index in indices) {
            val sector = store.read(FileStore.REFERENCE_INDEX, index)
            if (sector == null) {
                builder.skip(index)
                continue
            }
            builder.sector(index, sector, whirlpool)
            val table = referenceTables.getOrNull(index)
            if (table != null && table.format >= 6) {
                builder.revision(index, table.revision)
            }
        }
        versionTable = builder.build(whirlpool)
    }

    override fun close() {
        store.close()
    }

    companion object : CacheLoader {
        const val CACHE_FILE_NAME = FileStore.CACHE_FILE_NAME

        operator fun invoke(
            path: String,
            exponent: BigInteger? = null,
            modulus: BigInteger? = null,
            xteas: Map<Int, IntArray>? = null,
            writable: Boolean = false
        ): Cache {
            return load(path, exponent, modulus, xteas, writable = writable)
        }

        /**
         * Open the store at [path] and load every index's reference table, leaving archive data
         * on disk to be read on demand.
         */
        override fun load(
            path: String,
            mainFile: File,
            main: RandomAccessFile,
            index255File: File,
            index255: RandomAccessFile,
            indexCount: Int,
            versionTable: VersionTableBuilder?,
            xteas: Map<Int, IntArray>?,
            threadUsage: Double,
            writable: Boolean,
            exponent: BigInteger?,
            modulus: BigInteger?
        ): Cache {
            // The caller only opened these to count the indices; the store keeps its own handles.
            runCatching { main.close() }
            runCatching { index255.close() }
            val store = FileStore.open(File(path), writable)
            val context = DecompressionContext()
            val whirlpool = Whirlpool()
            val cache = FileCache(store, indexCount, xteas, exponent, modulus)
            for (indexId in 0 until indexCount) {
                cache.load(context, store, indexId, versionTable, whirlpool)
            }
            cache.versionTable = versionTable?.build(whirlpool) ?: ByteArray(0)
            return cache
        }
    }

    private fun load(
        context: DecompressionContext,
        store: FileStore,
        indexId: Int,
        versionTable: VersionTableBuilder?,
        whirlpool: Whirlpool
    ) {
        val sector = store.read(FileStore.REFERENCE_INDEX, indexId)
        if (sector == null) {
            versionTable?.skip(indexId)
            return
        }
        versionTable?.sector(indexId, sector, whirlpool)
        val decompressed = context.decompress(sector) ?: return
        val table = ReferenceTable.decode(decompressed)
        if (table.format >= 6) {
            versionTable?.revision(indexId, table.revision)
        }
        indexTable(indexId, table)
    }
}
