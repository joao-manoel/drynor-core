package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** What one [Packer.pack] produced. */
class PackResult(
    val indices: Int,
    val archives: Int,
    /** Total size of the containers written, trailers included. */
    val bytes: Long,
    val millis: Long
) {
    override fun toString(): String = "$indices indices, $archives archives, $bytes bytes, in ${millis}ms"
}

/**
 * Builds a packed cache from a source tree, from nothing.
 *
 * Every archive is repacked, recompressed, re-encrypted and written into a fresh sector store,
 * and every reference table is rebuilt from the metadata and the archives that were just written,
 * so nothing in the output can disagree with anything else in it. [Builder] is the incremental
 * version and does the same thing to the archives that changed; this is what it falls back to.
 *
 * The version table is not written: it is derived from the reference table containers whenever
 * the cache is opened ([world.gregs.voidps.cache.FileCache]), signed with whichever RSA key the
 * server is configured with, and is never stored on disk in the first place.
 */
object Packer {

    /** Pack [tree] into [output], replacing whatever cache is already there. */
    fun pack(tree: SourceTree, output: File, report: (String) -> Unit = {}): PackResult {
        val start = System.nanoTime()
        val threads = Runtime.getRuntime().availableProcessors()
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "cache-pack").apply { isDaemon = true }
        }
        val manifest = BuildManifest(tree.directory.toAbsolutePath().toString())
        var archives = 0
        var bytes = 0L
        var indices = 0
        try {
            clear(output)
            FileStore.create(output, tree.indexCount).use { store ->
                // Nothing in a from-scratch build survives a crash anyway, and half a million
                // fsync calls would cost more than the compression does.
                store.durable = false
                for (index in tree.indices()) {
                    val written = packIndex(tree, index, store, pool, manifest, threads, report)
                    archives += written.first
                    bytes += written.second
                    indices++
                }
            }
            manifest.files.putAll(BuildManifest.scan(tree, emptyMap(), pool).files)
            manifest.write(output)
        } finally {
            pool.shutdown()
        }
        return PackResult(indices, archives, bytes, (System.nanoTime() - start) / 1_000_000)
    }

    /** Pack one index into [store], returning its archive count and byte total. */
    private fun packIndex(
        tree: SourceTree,
        index: Int,
        store: FileStore,
        pool: ExecutorService,
        manifest: BuildManifest,
        threads: Int,
        report: (String) -> Unit
    ): Pair<Int, Long> {
        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)
        val ids = metadata.archiveIds()
        val entries = ArrayList<ArchiveEntry>(ids.size)
        val states = manifest.archives(index)
        var bytes = 0L
        if (ids.isNotEmpty()) {
            // Before the loop, once: a codec with shared state to build - index 12's corpus
            // analysis - must not build it per archive, and a pack of every archive is the one
            // case where every one of them is going to need it.
            codec.packIndex(directory, index, ids, emptyList())
        }

        // Compression runs ahead of the store, which can only be written one archive at a time,
        // but only so far ahead: a whole index's containers at once is hundreds of megabytes.
        val window = threads * PIPELINE
        val pending = ArrayDeque<Pair<Int, Future<PackedContainer>>>()
        var next = 0
        while (next < ids.size || pending.isNotEmpty()) {
            while (next < ids.size && pending.size < window) {
                val archive = ids[next++]
                val archiveMetadata = metadata.archives.getValue(archive)
                pending.addLast(
                    archive to pool.submit<PackedContainer> {
                        SourcePack.container(directory, archive, archiveMetadata, codec, metadata.whirlpool)
                    }
                )
            }
            val (archive, future) = pending.removeFirst()
            val packed = future.get()
            store.write(index, archive, packed.bytes)
            entries.add(packed.entry)
            states[archive] = ArchiveState(packed.entry.crc, SourceFiles.digest(metadata.archives.getValue(archive).write()))
            bytes += packed.bytes.size
        }

        val table = SourcePack.tableContainer(metadata, SourcePack.table(metadata, entries))
        store.write(FileStore.REFERENCE_INDEX, index, table)
        bytes += table.size
        report("${IndexNames.name(index)}: ${ids.size} archives, $bytes bytes")
        return ids.size to bytes
    }

    /** A pack writes a whole cache; anything already in [output] is last build's, not this one's. */
    private fun clear(output: File) {
        output.mkdirs()
        for (file in output.listFiles() ?: emptyArray()) {
            if (file.isFile && (file.name.startsWith("${FileStore.CACHE_FILE_NAME}.") || file.name == BuildManifest.FILE)) {
                file.delete()
            }
        }
    }

    /** How many archives per thread may be compressed ahead of the store writer. */
    private const val PIPELINE = 8
}
