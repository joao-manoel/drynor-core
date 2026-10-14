package world.gregs.voidps.cache.source.codec

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Where a definition codec puts its files, and how an archive and a file id become a definition id.
 *
 * The three shapes the 727 cache needs, all of them naming files by id so adding a definition is adding
 * a file and nothing else has to be told:
 *
 * | layout | file | definition id |
 * |---|---|---|
 * | [Packed] | `config_obj/4151.json` | `archive shl bits or file`, the packing the decoder uses |
 * | [Nested] | `interfaces/1477/32.json` | `archive shl bits or file`, with the interface's 16 bits |
 * | [Flat] | `config/struct/26.json` | the file id; the directory is the one archive |
 */
sealed class DefinitionLayout(
    /** How many low bits of a definition id hold the file id within its archive. */
    val bits: Int
) {

    /** The path of [file] of [archive], relative to the codec's directory. */
    abstract fun path(archive: Int, file: Int): String

    /** The archive a codec relative [path] belongs to, or null when it belongs to none. */
    abstract fun archiveOf(path: String): Int?

    /** The file ids of [archive] present under [directory], ascending. */
    abstract fun fileIds(directory: Path, archive: Int): IntArray

    /** The definition id of [file] of [archive]. */
    fun definition(archive: Int, file: Int): Int = (archive shl bits) or file

    /** [path] with the JSON extension, which is what a codec actually writes. */
    fun file(archive: Int, file: Int): String = "${path(archive, file)}$EXTENSION"

    /**
     * One flat file per definition, named by the definition id: `config_obj/4151.json`.
     *
     * The whole index is one directory, so a file's own name carries both ids and the file ids of an
     * archive are the ids in `archive shl bits` that exist. There are only `1 shl bits` of those, so they
     * are found by asking for each in turn rather than by listing a directory of 74,000 files once per
     * archive.
     */
    class Packed(bits: Int) : DefinitionLayout(bits) {

        private val mask = (1 shl bits) - 1

        override fun path(archive: Int, file: Int): String = definition(archive, file).toString()

        override fun archiveOf(path: String): Int? {
            if (path.contains('/')) {
                return null
            }
            return id(path)?.ushr(bits)
        }

        override fun fileIds(directory: Path, archive: Int): IntArray {
            val ids = ArrayList<Int>()
            for (id in 0..mask) {
                if (Files.isRegularFile(directory.resolve(file(archive, id)))) {
                    ids.add(id)
                }
            }
            return ids.toIntArray()
        }

        private fun id(path: String): Int? {
            if (!path.endsWith(EXTENSION)) {
                return null
            }
            return path.dropLast(EXTENSION.length).toIntOrNull()
        }
    }

    /**
     * A directory per archive and a file per file: `interfaces/1477/32.json`.
     *
     * What an index whose archives are a thing in their own right wants - an interface is its components,
     * and they are edited together.
     */
    class Nested(bits: Int) : DefinitionLayout(bits) {

        override fun path(archive: Int, file: Int): String = "$archive/$file"

        override fun archiveOf(path: String): Int? {
            val separator = path.indexOf('/')
            if (separator <= 0 || path.indexOf('/', separator + 1) >= 0 || !path.endsWith(EXTENSION)) {
                return null
            }
            if (path.substring(separator + 1).dropLast(EXTENSION.length).toIntOrNull() == null) {
                return null
            }
            return path.substring(0, separator).toIntOrNull()
        }

        override fun fileIds(directory: Path, archive: Int): IntArray =
            ids(directory.resolve(archive.toString()))
    }

    /**
     * A file per file, in a directory that is itself the one archive: `config/struct/26.json`.
     *
     * For a sub-codec of [ConfigCodec], which has already put the archive in the path.
     */
    object Flat : DefinitionLayout(0) {

        override fun path(archive: Int, file: Int): String = file.toString()

        // The directory names the archive, and only whatever owns it knows which.
        override fun archiveOf(path: String): Int? = null

        override fun fileIds(directory: Path, archive: Int): IntArray = ids(directory)
    }

    companion object {

        /** The extension every definition file has. */
        const val EXTENSION = ".json"

        /** Every `<id>.json` directly under [directory], ascending. */
        internal fun ids(directory: Path): IntArray {
            if (!Files.isDirectory(directory)) {
                return IntArray(0)
            }
            val ids = ArrayList<Int>()
            Files.list(directory).use { stream ->
                for (path in stream) {
                    val name = path.name
                    if (!name.endsWith(EXTENSION)) {
                        continue
                    }
                    name.dropLast(EXTENSION.length).toIntOrNull()?.let { ids.add(it) }
                }
            }
            ids.sort()
            return ids.toIntArray()
        }
    }
}
