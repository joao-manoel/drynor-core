package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** What one [Builder.build] did. */
class BuildResult(
    /** Whether the whole cache was packed rather than patched. */
    val full: Boolean,
    /** Why it was a full pack, or `incremental`. */
    val reason: String,
    /** Reference tables rewritten. */
    val indices: Int,
    /** Archives repacked. */
    val archives: Int,
    /** Archives dropped because the tree no longer holds them. */
    val removed: Int,
    /** Tree files that are new or whose contents changed. */
    val changedFiles: Int,
    /** Tree files that had to be hashed because their size or mtime moved. */
    val hashedFiles: Int,
    val millis: Long
) {
    /** Whether the tree was already what the output was built from. */
    val unchanged: Boolean
        get() = !full && archives == 0 && removed == 0 && indices == 0

    override fun toString(): String = if (full) {
        "full pack ($reason): $indices indices, $archives archives, in ${millis}ms"
    } else {
        "incremental: $changedFiles changed files, $archives archives repacked, $removed removed, " +
            "$indices tables rewritten, in ${millis}ms"
    }
}

/**
 * Rebuilds a packed cache from a source tree, repacking only what changed.
 *
 * This is what the server runs on startup, so its cost when nothing changed is the number that
 * matters: a stat of every file in the tree, a parse of the manifest, and nothing else - the
 * output files are not opened, let alone written. When something did change, the changed paths
 * are mapped back through the indices' codecs onto the archive each belongs to
 * ([world.gregs.voidps.cache.source.codec.SourceCodec.owner]), `index.json` is diffed archive by
 * archive against the digests the manifest kept, and only the affected archives and their
 * reference tables are rewritten - into the existing store, reusing each archive's sector chain
 * where the new container still fits. A changed file the codec owns at index level rather than per
 * archive repacks nothing and is handed to its `packIndex` instead.
 *
 * A missing or stale manifest, a manifest from a different tree, or a missing cache means there
 * is nothing to patch, and the build falls back to [Packer].
 */
object Builder {

    fun build(tree: SourceTree, output: File, report: (String) -> Unit = {}): BuildResult {
        val start = System.nanoTime()
        val manifest = BuildManifest.read(output)
        val reason = fullReason(tree, output, manifest)
        if (reason != null) {
            report("full pack: $reason")
            val packed = Packer.pack(tree, output, report)
            return BuildResult(
                full = true,
                reason = reason,
                indices = packed.indices,
                archives = packed.archives,
                removed = 0,
                changedFiles = packed.archives,
                hashedFiles = packed.archives,
                millis = (System.nanoTime() - start) / 1_000_000
            )
        }
        return incremental(tree, output, manifest!!, start, report)
    }

    private fun fullReason(tree: SourceTree, output: File, manifest: BuildManifest?): String? {
        if (!File(output, "${FileStore.CACHE_FILE_NAME}.dat2").isFile) {
            return "no packed cache in ${output.absolutePath}"
        }
        if (manifest == null) {
            return "no usable manifest"
        }
        if (manifest.tree != tree.directory.toAbsolutePath().toString()) {
            return "the manifest was built from ${manifest.tree}"
        }
        return null
    }

    private fun incremental(
        tree: SourceTree,
        output: File,
        manifest: BuildManifest,
        start: Long,
        report: (String) -> Unit
    ): BuildResult {
        val threads = Runtime.getRuntime().availableProcessors()
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "cache-build").apply { isDaemon = true }
        }
        try {
            val scan = BuildManifest.scan(tree, manifest.files, pool)
            if (scan.changed.contains(SourceTree.CACHE_FILE) || scan.removed.contains(SourceTree.CACHE_FILE)) {
                pool.shutdown()
                report("full pack: ${SourceTree.CACHE_FILE} changed")
                val packed = Packer.pack(tree, output, report)
                return BuildResult(
                    true, "${SourceTree.CACHE_FILE} changed", packed.indices, packed.archives, 0,
                    packed.archives, packed.archives, (System.nanoTime() - start) / 1_000_000
                )
            }

            val affected = HashMap<Int, MutableSet<Int>>()
            val metadataChanged = HashSet<Int>()
            val indexFiles = HashMap<Int, MutableList<String>>()
            val unclaimed = ArrayList<String>()
            for (path in scan.changed + scan.removed) {
                claim(tree, path, affected, metadataChanged, indexFiles, unclaimed)
            }
            for (path in unclaimed.take(UNCLAIMED)) {
                report("ignored: $path belongs to no codec")
            }
            val indices = (affected.keys + metadataChanged + indexFiles.keys).sorted()
            if (indices.isEmpty()) {
                if (scan.hashed > 0) {
                    // Nothing changed, but files were touched; recording that saves the next
                    // build the same hashing.
                    manifest.files.clear()
                    manifest.files.putAll(scan.files)
                    manifest.write(output)
                }
                return BuildResult(
                    false, "incremental", 0, 0, 0, scan.changed.size, scan.hashed,
                    (System.nanoTime() - start) / 1_000_000
                )
            }

            var archives = 0
            var removed = 0
            var tables = 0
            FileStore.open(output, writable = true).use { store ->
                for (index in indices) {
                    val result = buildIndex(
                        tree, index, store, pool, manifest,
                        affected[index] ?: HashSet(), indexFiles[index] ?: emptyList(), threads, report
                    )
                    archives += result.archives
                    removed += result.removed
                    if (result.table) {
                        tables++
                    }
                }
            }
            manifest.files.clear()
            manifest.files.putAll(scan.files)
            manifest.write(output)
            return BuildResult(
                false, "incremental", tables, archives, removed, scan.changed.size, scan.hashed,
                (System.nanoTime() - start) / 1_000_000
            )
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Map a tree relative path onto what its change forces a rebuild of.
     *
     * Three answers, which is what [Owner] is: an archive, which is repacked; the index itself, for
     * a file that is a property of the whole index rather than of any archive (index 12's project
     * files), which repacks nothing on its own but is handed to
     * [SourceCodec.packIndex][world.gregs.voidps.cache.source.codec.SourceCodec.packIndex] so the
     * codec can decide; or nothing here, which is reported and ignored. Every one of them is in the
     * manifest either way - the scan hashes every file in the tree - so a claimed index level file
     * stops being reported as changed the moment the build records it.
     */
    private fun claim(
        tree: SourceTree,
        path: String,
        affected: MutableMap<Int, MutableSet<Int>>,
        metadataChanged: MutableSet<Int>,
        indexFiles: MutableMap<Int, MutableList<String>>,
        unclaimed: MutableList<String>
    ) {
        val separator = path.indexOf('/')
        if (separator <= 0) {
            return
        }
        val index = IndexNames.index(path.substring(0, separator))
        if (index < 0) {
            // gamevals and anything else beside the indices; part of the tree, not of the cache.
            return
        }
        val relative = path.substring(separator + 1)
        if (relative == SourceTree.INDEX_FILE) {
            metadataChanged.add(index)
            return
        }
        if (relative.endsWith(SourceTree.OPAQUE_EXTENSION) && !relative.contains('/')) {
            // An opaque archive belongs to no codec: its container never opened, so the framework
            // owns the file and the codec has never seen it.
            val archive = relative.dropLast(SourceTree.OPAQUE_EXTENSION.length).toIntOrNull()
            if (archive == null) {
                unclaimed.add(path)
            } else {
                affected.getOrPut(index) { HashSet() }.add(archive)
            }
            return
        }
        when (val owner = SourceCodecs.codec(index).owner(tree.indexDirectory(index), relative)) {
            is Owner.Archive -> affected.getOrPut(index) { HashSet() }.add(owner.id)
            Owner.Index -> indexFiles.getOrPut(index) { ArrayList() }.add(relative)
            Owner.None -> unclaimed.add(path)
        }
    }

    /** What one index's rebuild came to. */
    private class IndexBuild(val archives: Int, val removed: Int, val table: Boolean)

    /** Repack one index's affected archives and rewrite its reference table if that changed it. */
    private fun buildIndex(
        tree: SourceTree,
        index: Int,
        store: FileStore,
        pool: ExecutorService,
        manifest: BuildManifest,
        changed: MutableSet<Int>,
        indexFiles: List<String>,
        threads: Int,
        report: (String) -> Unit
    ): IndexBuild {
        val name = IndexNames.name(index)
        val states = manifest.archives(index)
        if (!Files.isRegularFile(tree.indexFile(index))) {
            for (archive in states.keys) {
                store.remove(index, archive)
            }
            val dropped = states.size
            store.remove(FileStore.REFERENCE_INDEX, index)
            states.clear()
            report("$name: index removed, $dropped archives dropped")
            return IndexBuild(0, dropped, true)
        }

        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)

        // An archive whose metadata line moved has to be repacked even when none of its files did:
        // a version bump, a new key or a changed name hash all change the bytes served.
        val digests = HashMap<Int, String>(metadata.archives.size * 2)
        for ((archive, archiveMetadata) in metadata.archives) {
            val digest = SourceFiles.digest(archiveMetadata.write())
            digests[archive] = digest
            val state = states[archive]
            if (state == null || state.metadata != digest) {
                changed.add(archive)
            }
        }
        val dropped = states.keys.filter { !metadata.archives.containsKey(it) }

        val stored = store.read(FileStore.REFERENCE_INDEX, index)
        var table = if (stored == null) null else ReferenceTable.decode(Container.decode(stored).data())
        if (table == null || table.named != metadata.named || table.whirlpool != metadata.whirlpool) {
            // The flags decide which per-archive fields the table carries, and the ones it would
            // gain cannot be recovered without reading every container, so repack the lot.
            table = ReferenceTable(metadata.format, metadata.revision, metadata.named, metadata.whirlpool)
            changed.addAll(metadata.archives.keys)
        }
        table.format = metadata.format
        table.revision = metadata.revision

        val ids = changed.filter { metadata.archives.containsKey(it) }.sorted()
        if (ids.isNotEmpty() || indexFiles.isNotEmpty()) {
            // Only when there is something to pack, which is what keeps a build that touches no
            // clientscript from paying for the corpus analysis.
            codec.packIndex(directory, index, ids.toIntArray(), indexFiles)
        }
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
            table.remove(archive)
            table.add(packed.entry)
            states[archive] = ArchiveState(packed.entry.crc, digests.getValue(archive))
        }
        for (archive in dropped) {
            store.remove(index, archive)
            table.remove(archive)
            states.remove(archive)
        }

        // The table is always rebuilt and only written when it comes out different, which is what
        // makes a bumped revision or a changed table compression land without a special case, and
        // a reformatted index.json land as nothing at all.
        val encoded = SourcePack.tableContainer(metadata, table)
        val rewritten = stored == null || !stored.contentEquals(encoded)
        if (rewritten) {
            store.write(FileStore.REFERENCE_INDEX, index, encoded)
        }
        if (ids.isNotEmpty() || dropped.isNotEmpty() || rewritten || indexFiles.isNotEmpty()) {
            report(
                "$name: ${ids.size} archives repacked" +
                    (if (dropped.isEmpty()) "" else ", ${dropped.size} removed") +
                    (if (indexFiles.isEmpty()) "" else ", ${indexFiles.size} index files changed") +
                    (if (rewritten) "" else ", table unchanged")
            )
        }
        return IndexBuild(ids.size, dropped.size, rewritten)
    }

    private const val UNCLAIMED = 5

    /** How many archives per thread may be compressed ahead of the store writer. */
    private const val PIPELINE = 8
}
