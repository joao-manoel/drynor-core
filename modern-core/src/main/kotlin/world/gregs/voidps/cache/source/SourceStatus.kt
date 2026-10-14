package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** A pristine sidecar that no longer applies, and why. */
class StaleFile(
    /** The tree relative path of the file the sidecar guards. */
    val path: String,
    val reason: String
) {
    override fun toString(): String = "$path: $reason"
}

/** What one index of a tree holds, as `cache status` reports it. */
class IndexStatus(
    val index: Int,
    /** The index's directory name, which is Jagex's own - `config_obj`, `clientscripts`. */
    val name: String,
    /** The codec's [SourceCodec.id]. */
    val codec: String,
    val archives: Int,
    /** Every regular file under the index directory but its own `index.json`. */
    val files: Int,
    /** Editable files whose codec could not reproduce the shipped bytes, from `index.json`. */
    val pristine: Int,
    /** Archives kept as container bytes because nothing could open them. */
    val opaque: Int,
    /** The sidecars whose guard no longer holds, so the next pack compiles the edit instead. */
    val stale: List<StaleFile>
) {
    override fun toString(): String =
        "$name: $codec, $archives archives, $files files, $pristine pristine, $opaque opaque, " +
            "${stale.size} stale"
}

/**
 * A read-only inventory of a source tree: what each index holds and what it is still keeping
 * original bytes for.
 *
 * This is what `cache status` prints, and it lives here rather than in the tool because every
 * number in it is a property of the tree's contract: the pristine count is what `index.json`
 * recorded, a *stale* sidecar is one whose editable file no longer hashes to the guard - which is
 * how a pack knows to compile the edit rather than emit the original - and which archive a path
 * belongs to is the codec's answer ([SourceCodec.owner]) and nobody else's.
 *
 * Nothing is written and nothing is packed; the cost is one walk of the tree plus a SHA-256 of
 * each guarded file, so it is proportional to the number of sidecars and not to the tree.
 */
class SourceStatus(
    val tree: SourceTree,
    val indices: List<IndexStatus>,
    /** How many gameval catalogs the tree carries, or -1 when it has no `gamevals` directory. */
    val gamevals: Int
) {

    val archives: Int
        get() = indices.sumOf { it.archives }

    val files: Int
        get() = indices.sumOf { it.files }

    val pristine: Int
        get() = indices.sumOf { it.pristine }

    val opaque: Int
        get() = indices.sumOf { it.opaque }

    /** Every stale sidecar in the tree, in index order. */
    val stale: List<StaleFile>
        get() = indices.flatMap { it.stale }

    override fun toString(): String =
        "$archives archives, $files files, $pristine pristine, $opaque opaque, ${stale.size} stale"

    companion object {

        /** Walk [tree] and report what it holds. */
        fun of(tree: SourceTree): SourceStatus {
            val indices = ArrayList<IndexStatus>()
            for (index in tree.indices()) {
                indices.add(index(tree, index))
            }
            return SourceStatus(tree, indices, gamevals(tree))
        }

        private fun index(tree: SourceTree, index: Int): IndexStatus {
            val metadata = tree.metadata(index)
            val directory = tree.indexDirectory(index)
            val codec = SourceCodecs.codec(index)
            val walk = walk(directory)
            val stale = ArrayList<StaleFile>()
            for (sidecar in walk.sidecars) {
                val guarded = SourceFiles.guarded(sidecar) ?: continue
                val relative = directory.relativize(guarded).joinToString("/") { it.name }
                val reason = staleReason(directory, guarded, relative, metadata, codec) ?: continue
                stale.add(StaleFile("${directory.name}/$relative", reason))
            }
            return IndexStatus(
                index = index,
                name = IndexNames.name(index),
                codec = codec.id,
                archives = metadata.archives.size,
                files = walk.files,
                pristine = metadata.archives.values.sumOf { it.pristine.size },
                opaque = metadata.archives.values.count { it.opaque },
                stale = stale
            )
        }

        /**
         * Why the sidecar guarding [guarded] no longer applies, or null when it still does.
         *
         * The hash `index.json` records is filed by file id, and only the codec's layout knows which
         * file a path is; matching against the *set* of an archive's hashes needs nothing from the
         * layout but the archive the path belongs to, which every codec answers.
         */
        private fun staleReason(
            directory: Path,
            guarded: Path,
            relative: String,
            metadata: IndexMetadata,
            codec: SourceCodec
        ): String? {
            if (!Files.isRegularFile(guarded)) {
                return "the file it guards is gone"
            }
            val archive = when (val owner = codec.owner(directory, relative)) {
                is Owner.Archive -> owner.id
                else -> return "belongs to no archive"
            }
            val recorded = metadata.archive(archive) ?: return "archive $archive is not in index.json"
            if (recorded.pristine.isEmpty()) {
                return "archive $archive records no pristine hash"
            }
            val hash = try {
                SourceFiles.sha256(guarded)
            } catch (e: IOException) {
                return "could not be read: ${e.message}"
            }
            if (!recorded.pristine.containsValue(hash)) {
                return "edited since it was unpacked; the pack will compile it"
            }
            return null
        }

        /** What one walk of an index directory found: the file count and the sidecars, together. */
        private class Walk(val files: Int, val sidecars: List<Path>)

        private fun walk(directory: Path): Walk {
            if (!Files.isDirectory(directory)) {
                return Walk(0, emptyList())
            }
            var files = 0
            val sidecars = ArrayList<Path>()
            Files.walk(directory).use { stream ->
                for (path in stream) {
                    if (!Files.isRegularFile(path)) {
                        continue
                    }
                    if (path.name != SourceTree.INDEX_FILE) {
                        files++
                    }
                    if (SourceFiles.isPristine(path.name)) {
                        sidecars.add(path)
                    }
                }
            }
            return Walk(files, sidecars)
        }

        /** How many catalogs the tree's `gamevals` holds, or -1 when it has none at all. */
        private fun gamevals(tree: SourceTree): Int {
            if (!Files.isDirectory(tree.gamevals)) {
                return -1
            }
            Files.list(tree.gamevals).use { stream ->
                return stream.filter { it.name.endsWith(CATALOG_EXTENSION) }.count().toInt()
            }
        }

        private const val CATALOG_EXTENSION = ".json"
    }
}
