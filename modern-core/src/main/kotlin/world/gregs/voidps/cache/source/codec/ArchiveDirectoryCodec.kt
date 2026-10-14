package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * An index whose archives are unrelated things, each with its own name and its own codec.
 *
 * The generalisation of what [ConfigCodec] does for index 2, for the four other indices in 727 that are
 * built the same way: a world map area is not another of the same type as `details`, a quick chat menu is
 * not a quick chat phrase, and index 28's six archives are six unrelated settings files. Each archive is
 * given a name and a codec, and the codec is handed a directory to lay its files out in without repeating
 * the archive id ([DefinitionLayout.Flat], [RawFilesCodec.FLAT]).
 *
 * [nested] chooses between the two shapes:
 *
 * - `true` gives each archive a directory of its own, `worldmapdata/main_staticelements/119.json`, which
 *   is what an archive with many files wants.
 * - `false` puts the archive's files straight in the index directory, `defaults/equipment.json`, which is
 *   what an archive with exactly one file wants; its sub-codec uses [NamedLayout].
 *
 * The name is a display concern and never a source of a hash: `index.json` keeps the archive's name hash
 * as the number the reference table holds, exactly as it does for every other named index, and a codec
 * that gets a name wrong costs a directory name and not a byte. Whoever builds the table is expected to
 * check its names against the hashes it is naming - see [WorldMapCodec].
 */
class ArchiveDirectoryCodec(
    override val id: String,
    /** [archive]'s directory or file name. */
    val name: (archive: Int) -> String,
    /** The archive [name] names, or -1 when it names none. */
    private val archive: (name: String) -> Int,
    /** [archive]'s codec. */
    val codec: (archive: Int) -> SourceCodec,
    private val nested: Boolean = true
) : SourceCodec {

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val unpacked = codec(archive.archive).unpack(folder(directory, archive.archive), archive)
        if (!nested) {
            return unpacked
        }
        val name = name(archive.archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(folder(directory, archive), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        val separator = editable.indexOf('/')
        val key = if (nested) {
            if (separator <= 0) {
                return null
            }
            editable.substring(0, separator)
        } else {
            if (separator >= 0) {
                return null
            }
            val extension = editable.lastIndexOf('.')
            if (extension <= 0) {
                return null
            }
            editable.substring(0, extension)
        }
        val id = archive(key)
        return if (id < 0) null else id
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = folder(directory, archive)
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }

    private fun folder(directory: Path, archive: Int): Path =
        if (nested) directory.resolve(name(archive)) else directory
}
