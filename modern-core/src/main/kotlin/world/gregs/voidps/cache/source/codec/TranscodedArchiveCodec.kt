package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One file per archive, in a standard format the cache's own encoding expands into:
 * `midi_songs/42.mid`.
 *
 * For an index whose payload is a well known format wearing a private coat. Indices 6 and 11 hold
 * Jagex's compact MIDI encoding, which is a column store of exactly the events a standard MIDI file
 * carries ([world.gregs.voidps.cache.audio.JagexMidi]), so the tree stores the standard file and
 * rebuilds the compact one on the way back. What comes out is a `.mid` any sequencer opens.
 *
 * That is only allowed because the rebuild is exact: [encode] of [decode] of every archive of the
 * 727 cache reproduces the shipped bytes. The pristine fallback is here anyway, per archive and
 * guarded by the SHA-256 of the editable file, so an archive that ever stopped reproducing would
 * cost a sidecar rather than the parity guarantee - the same contract [DefinitionJsonCodec] makes.
 */
class TranscodedArchiveCodec(
    override val id: String,
    /** The extension of the standard file, dot included. */
    val extension: String,
    /** The cache's bytes as the standard file. */
    private val decode: (ByteArray) -> ByteArray,
    /** The standard file back as the cache's bytes. */
    private val encode: (ByteArray) -> ByteArray
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    /** A `.mid` is not the shipped bytes, so the unpacker packs the archive back and proves it. */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        val data = archive.files[0]
        val path = "${archive.archive}$extension"
        val standard = decode(data)
        val files = ArrayList<SourceFile>(2)
        files.add(SourceFile(path, standard))
        if (encode(standard).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        files.add(SourceFile(SourceFiles.pristine(path), data))
        return UnpackedArchive(files, mapOf(archive.fileIds[0] to SourceFiles.sha256(standard)))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$extension")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        val standard = Files.readAllBytes(file)
        val sidecar = SourceFiles.pristine(file)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(standard) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(encode(standard), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (path.contains('/')) {
            return null
        }
        val name = SourceFiles.guarded(path) ?: path
        if (!name.endsWith(extension)) {
            return null
        }
        return name.dropLast(extension.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$extension")
        if (!Files.exists(file)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(file)
        return if (Files.exists(sidecar)) listOf(file, sidecar) else listOf(file)
    }
}
