package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A cache source tree: the whole packed cache unpacked into one file per asset, plus the little
 * metadata the files cannot carry. See `docs/cache-source.md` for the layout and the contract.
 *
 * ```
 * <tree>/cache.json                 format version, revision, index count
 * <tree>/gamevals/<type>.json       the name catalogs, which are cache assets too
 * <tree>/<index name>/index.json    the index's reference table, minus everything derivable
 * <tree>/<index name>/...           the archives, laid out by the index's codec
 * ```
 *
 * This class is the directory and nothing more: it knows where things go, reads and writes
 * `cache.json`, and leaves the contents to [Unpacker], [Packer] and [Builder].
 */
class SourceTree private constructor(
    val directory: Path,
    /** The layout version this tree was written with. */
    val format: Int,
    /** The cache revision the tree holds - 727. */
    val revision: Int,
    /** How many indices the tree describes, which is how many the packed cache will have. */
    val indexCount: Int
) {

    /** `<tree>/cache.json`. */
    val cacheFile: Path
        get() = directory.resolve(CACHE_FILE)

    /** `<tree>/gamevals`, the catalogs, copied in by [Unpacker] and read from here at runtime. */
    val gamevals: Path
        get() = directory.resolve(GAMEVALS)

    /** [index]'s directory, whether or not it exists. */
    fun indexDirectory(index: Int): Path = directory.resolve(IndexNames.name(index))

    /** [index]'s `index.json`. */
    fun indexFile(index: Int): Path = indexDirectory(index).resolve(INDEX_FILE)

    /** Every index the tree actually holds - those with an `index.json` - ascending. */
    fun indices(): List<Int> {
        val found = ArrayList<Int>(indexCount)
        for (index in 0 until indexCount) {
            if (Files.isRegularFile(indexFile(index))) {
                found.add(index)
            }
        }
        return found
    }

    /** Read [index]'s metadata. */
    fun metadata(index: Int): IndexMetadata =
        IndexMetadata.read(Files.readString(indexFile(index)), index)

    /** Write [index]'s metadata, leaving the file alone when it already says exactly this. */
    fun writeMetadata(metadata: IndexMetadata): Boolean =
        SourceFiles.write(indexFile(metadata.index), metadata.write().toByteArray(Charsets.UTF_8))

    /**
     * A read-only inventory of the tree: per index the codec, the archive, file, pristine and
     * opaque counts, and every pristine sidecar whose guard no longer holds. See [SourceStatus].
     */
    fun status(): SourceStatus = SourceStatus.of(this)

    /** The tree relative, `/` separated path of [path], or null when it is outside the tree. */
    fun relative(path: Path): String? {
        if (!path.startsWith(directory)) {
            return null
        }
        return directory.relativize(path).joinToString("/") { it.name }
    }

    /** `cache.json`'s exact text. */
    fun describe(): String = buildString {
        append("{\n")
        append("  \"format\": ").append(format).append(",\n")
        append("  \"revision\": ").append(revision).append(",\n")
        append("  \"indices\": ").append(indexCount).append('\n')
        append("}\n")
    }

    override fun toString(): String = "SourceTree(${directory.toAbsolutePath()}, revision $revision)"

    companion object {
        /** The layout version. Bumped when the tree's shape changes in a way readers must notice. */
        const val FORMAT = 1

        const val CACHE_FILE = "cache.json"
        const val INDEX_FILE = "index.json"
        const val GAMEVALS = "gamevals"

        /** An archive nothing can open, kept as its container bytes minus the version trailer. */
        const val OPAQUE_EXTENSION = ".container"

        /** The revision the cache and everything built on it is locked to. */
        const val REVISION = 727

        private val json = Json { ignoreUnknownKeys = true }

        /** Open the tree at [directory], which must already have a `cache.json`. */
        fun open(directory: File): SourceTree = open(directory.toPath())

        fun open(directory: Path): SourceTree {
            val file = directory.resolve(CACHE_FILE)
            if (!Files.isRegularFile(file)) {
                throw IOException("No $CACHE_FILE in ${directory.toAbsolutePath()}; this is not a cache source tree.")
            }
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            val format = root["format"]?.jsonPrimitive?.int ?: FORMAT
            require(format == FORMAT) {
                "The tree at ${directory.toAbsolutePath()} is format $format; this build reads format $FORMAT."
            }
            return SourceTree(
                directory = directory,
                format = format,
                revision = root["revision"]?.jsonPrimitive?.int ?: REVISION,
                indexCount = root["indices"]?.jsonPrimitive?.int ?: IndexNames.COUNT
            )
        }

        /** Create (or re-describe) the tree at [directory] and write its `cache.json`. */
        fun create(directory: File, revision: Int = REVISION, indexCount: Int = IndexNames.COUNT): SourceTree =
            create(directory.toPath(), revision, indexCount)

        fun create(directory: Path, revision: Int = REVISION, indexCount: Int = IndexNames.COUNT): SourceTree {
            Files.createDirectories(directory)
            val tree = SourceTree(directory, FORMAT, revision, indexCount)
            SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
            return tree
        }
    }
}
