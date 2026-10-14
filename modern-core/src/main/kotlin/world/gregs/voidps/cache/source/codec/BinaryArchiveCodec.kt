package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [RawArchiveCodec] with the extension the payload actually deserves: `<archive><extension>`.
 *
 * A `.dat` says nothing. An index whose archives hold one file of a format that is not going to be
 * decoded is still better off saying what that format is - `1234.jagvorbis` is a Jagex-framed
 * vorbis stream and `56.jagmidi` is Jagex's compact MIDI encoding - so that anyone opening the tree
 * knows what they are looking at and no tool mistakes it for something it can read.
 *
 * The bytes are the payload verbatim, so the codec is exact by construction.
 */
class BinaryArchiveCodec(
    override val id: String,
    /** The extension, dot included. */
    val extension: String
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        return UnpackedArchive(listOf(SourceFile("${archive.archive}$extension", archive.files[0])))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$extension")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no data file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(extension) || path.contains('/')) {
            return null
        }
        return path.dropLast(extension.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$extension")
        return if (Files.exists(file)) listOf(file) else emptyList()
    }
}
