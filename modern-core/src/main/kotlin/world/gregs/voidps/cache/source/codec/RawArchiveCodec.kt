package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The decompressed archive as one file, `<archive>.dat`.
 *
 * What the indices whose archives hold a single file use - models, graphics, textures, maps,
 * sounds, client scripts and the rest - until something better than bytes exists for them. The
 * file id is almost always 0 and then says nothing; when it is not, it goes in the metadata,
 * because the file name cannot carry both ids.
 */
object RawArchiveCodec : SourceCodec {

    const val EXTENSION = ".dat"

    override val id: String
        get() = "raw-archive"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive, so this index needs ${RawFilesCodec::class.simpleName}."
        }
        return UnpackedArchive(listOf(SourceFile("${archive.archive}$EXTENSION", archive.files[0])))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no data file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(EXTENSION) || path.contains('/')) {
            return null
        }
        return path.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$EXTENSION")
        return if (Files.exists(file)) listOf(file) else emptyList()
    }
}
