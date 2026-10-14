package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The decompressed group as one file, chunk table and all, `<archive>.grp`.
 *
 * Index 0 is why this exists, and index 0 no longer needs it: all 4,074 of its multi-file archives are
 * stored in three chunks, and keeping the group whole records that split for free. What replaced it is
 * [AnimationFrameCodec], which stores a frame as JSON and rebuilds the split from the frame format
 * itself, so the layout is derived rather than stored and the frames became editable.
 *
 * **No index in 727 claims this codec any more.** It stays because it is the answer for an index whose
 * chunk layout follows from nothing - the fallback the next multi-chunk index would reach for - and
 * because it is the cheapest possible exact codec for a group nobody has decoded yet.
 *
 * The file ids a group holds are not recoverable from the group - its trailer only makes sense
 * once you already know the file count - so they come from the metadata.
 */
object VerbatimGroupCodec : SourceCodec {

    const val EXTENSION = ".grp"

    override val id: String
        get() = "verbatim-group"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive =
        UnpackedArchive(listOf(SourceFile("${archive.archive}$EXTENSION", archive.group)))

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no group file at ${file.toAbsolutePath()}.")
        }
        return PackedArchive(Files.readAllBytes(file), metadata.fileIds())
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
