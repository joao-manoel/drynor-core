package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceTree
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One file per archive, at the archive's own name: `dlls/windows/x86/hw3d.dll`.
 *
 * For an index whose archives are named things rather than numbered ones and whose payload is
 * nothing but bytes - index 30's native libraries are a PE, an ELF or a Mach-O image, byte for byte
 * what the client writes to disk and hands to `System.load`, so there is nothing to decode and
 * everything to preserve. What the codec adds over [RawArchiveCodec] is the name: a reference table
 * records only a hash, and [names] is the formula that hashes back to a string
 * (`docs/cache-archive-names.md`).
 *
 * The name is *display* only, exactly as `index.json`'s `nameHash` field promises: packing hashes
 * the path back and looks the hash up in the metadata, so the hash stays the truth and a renamed
 * file is an unrecognised file rather than a silently re-hashed archive. An archive whose hash no
 * name accounts for falls back to `<archive>.dat`, so an index is never blocked on a recovered name.
 */
class NamedBytesCodec(
    override val id: String,
    /** Hash to name, for the names this index's formula builds. */
    private val names: (Int) -> String?
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        return UnpackedArchive(listOf(SourceFile(path(archive.metadata.nameHash, archive.archive), archive.files[0])))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve(path(metadata.nameHash, archive))
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    /** Nothing but the tree's own metadata can turn a name back into an archive. */
    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val fallback = fallback(path)
        if (fallback != null) {
            return fallback
        }
        return ArchiveNames.archive(directory, path.hashCode())
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = ArchiveNames.nameHash(directory, archive) ?: return emptyList()
        val file = directory.resolve(path(hash, archive))
        return if (Files.exists(file)) listOf(file) else emptyList()
    }

    /** [hash]'s name, or `<archive>.dat` when no name is known for it. */
    private fun path(hash: Int, archive: Int): String =
        names(hash) ?: "$archive${RawArchiveCodec.EXTENSION}"

    /** The archive a `<archive>.dat` fallback path names, or null when [path] is a real name. */
    private fun fallback(path: String): Int? {
        if (path.contains('/') || !path.endsWith(RawArchiveCodec.EXTENSION)) {
            return null
        }
        return path.dropLast(RawArchiveCodec.EXTENSION.length).toIntOrNull()
    }
}

/**
 * The archive name hashes a tree records, read back out of `index.json`.
 *
 * A codec that lays its files out by name has to answer "which archive is this path?" from the path
 * alone, and the only thing that knows is the index's own metadata - the hash is what the reference
 * table carries and what `index.json` keeps, and a name is never a source of hashes at pack time.
 * The file is small (thirty-three lines for index 30, two for index 31) and the questions are asked
 * once per archive or per changed path, so it is read rather than cached: a cache keyed on nothing
 * would go stale the moment a build wrote a new table.
 */
internal object ArchiveNames {

    /** The archive in [directory]'s index whose name hash is [hash], or null. */
    fun archive(directory: Path, hash: Int): Int? {
        val metadata = read(directory) ?: return null
        for ((archive, entry) in metadata.archives) {
            if (entry.nameHash == hash) {
                return archive
            }
        }
        return null
    }

    /** [archive]'s recorded name hash, or null when the tree has no metadata for it. */
    fun nameHash(directory: Path, archive: Int): Int? = read(directory)?.archive(archive)?.nameHash

    private fun read(directory: Path): IndexMetadata? {
        val file = directory.resolve(SourceTree.INDEX_FILE)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return IndexMetadata.read(Files.readString(file), 0)
    }
}
