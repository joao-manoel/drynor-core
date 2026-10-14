package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Index 7 as one glTF 2.0 binary per model, `models/<id>.glb`.
 *
 * A model is an archive rather than a file inside one - 73,417 of them, one file each - so this is
 * its own codec rather than a [DefinitionJsonCodec], which stores a file per *file*. It is a `.glb`
 * rather than JSON because a model is geometry: Blender imports and exports the format natively, so
 * a model in the tree is a model an artist can open, and everything the format cannot say rides in
 * glTF `extras` - see [ModelGltf].
 *
 * The guarantee is the definition codec's: the archive is decoded, written as a `.glb`, read
 * straight back and re-encoded during the unpack, and a model whose bytes do not come out identical
 * keeps them in a `<id>.glb.pristine` sidecar guarded by the SHA-256 of the `.glb`. So the tree
 * reproduces index 7 whether or not the encoder is perfect, and `cache status`'s pristine count is
 * the honest measure of how perfect it is. It is zero for the 727 cache.
 *
 * See [ModelGltf] for what a file looks like and `docs/cache-source.md`, "models", for the
 * measurement.
 */
object ModelCodec : SourceCodec {

    /** The extension a model file has. */
    const val EXTENSION = ".glb"

    override val id: String
        get() = "gltf-model"

    /**
     * False: a `.glb` is not the shipped bytes, so the unpacker packs the tree back and proves it
     * anyway - a second, independent check of what [unpack] already fell back for.
     */
    override fun exact(archive: Int): Boolean = false

    /** One file per archive, and its id is not in the file's name; see [RawArchiveCodec]. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one model per archive."
        }
        val data = archive.files[0]
        val name = "${archive.archive}$EXTENSION"
        val glb = ModelGltf.write(ModelDecoder.read(archive.archive, data))
        if (compile(glb, archive.archive).contentEquals(data)) {
            return UnpackedArchive(listOf(SourceFile(name, glb)))
        }
        // The `.glb` is still the editable form; the shipped bytes ride along beside it until
        // somebody edits it, which is what keeps parity independent of the encoder being exact.
        return UnpackedArchive(
            listOf(SourceFile(name, glb), SourceFile(SourceFiles.pristine(name), data)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(glb))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            throw IOException("Model $archive has no glb at ${path.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Model $archive is one file on disk but its metadata lists ${ids.size}." }
        val glb = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(glb) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            // The edit is what the cache is built from now, so the stale guard goes rather than
            // being left to confuse the next reader; `cache status` counts the ones still waiting.
            Files.delete(sidecar)
        }
        return PackedArchive(compile(glb, archive), ids)
    }

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        if (!editable.endsWith(EXTENSION) || editable.contains('/')) {
            return null
        }
        return editable.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(path)
        return if (Files.isRegularFile(sidecar)) listOf(path, sidecar) else listOf(path)
    }

    /** [glb] read back into a definition and encoded, which is what a pack emits. */
    private fun compile(glb: ByteArray, archive: Int): ByteArray =
        ModelEncoder.encode(ModelGltf.read(glb, archive))
}
