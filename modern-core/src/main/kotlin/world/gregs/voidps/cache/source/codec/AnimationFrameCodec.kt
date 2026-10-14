package world.gregs.voidps.cache.source.codec

import org.darkan.core.Logger.logWarn
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index 0 as one glTF 2.0 binary per frame set, `anims/<archive>.glb`.
 *
 * A frame set is an *archive* of frames, and a glTF animation is a list of keyframes, so the two
 * line up: one file per archive - 4,085 of them instead of 490,049 - holding a node per label group
 * of the skeleton the frames name, one animation whose channels are the frames, and `extras.rs`
 * carrying the frames exactly as the files hold them. It is the same choice index 7's models made
 * ([ModelCodec], [ModelGltf]): the format Blender opens natively, so an animation in the tree is an
 * animation an artist can edit and a plugin has a standard file to work with. [AnimationGltf] is
 * what a file looks like.
 *
 * **The three chunk group layout still comes back.** Index 0 is the one index whose *group layout*
 * has to be reproduced as well as its bytes: every multi-frame group is stored in three chunks, and
 * a group joined as one chunk is different - equally valid, but different - bytes. The split is the
 * frame format's own three sections, so it is derived rather than recorded in `index.json`:
 *
 * | chunk | holds | length |
 * |---|---|---|
 * | 0 | the header: a byte, the base id and the transform count | always 4 |
 * | 1 | the flag byte per transform | the transform count |
 * | 2 | the transform values, as signed smarts | whatever is left |
 *
 * The two facts the codec derives - how many chunks, and how long chunk 0 is - are written into
 * `extras.rs` as `layout`, so the file says what it is instead of the codec assuming it, and adding
 * or removing a frame stays an edit to one file. The eleven single-frame archives carry no chunk
 * table at all and get none.
 *
 * **Unpacking needs the skeletons.** A node is a label group of the base, and its transform type -
 * which decides whether the group becomes a `translation`, `rotation` or `scale` channel - is a
 * field of index 1, not of index 0. [SourceCodec] hands a codec one archive and no cache, so
 * [cache] is the seam, exactly as `Cs2SourceCodec`'s is: it defaults to [Cache.get], which is right
 * for every tool and every test, because unpacking a cache means having one. **Packing needs
 * nothing** - `extras.rs` is the whole truth for the re-encoder - so a build from a tree stays
 * self-sufficient.
 *
 * The guarantee is the definition codec's: the group is decoded, written as a `.glb`, read straight
 * back and re-encoded during the unpack, and an archive whose bytes do not come out identical keeps
 * them in a `<archive>.glb.pristine` sidecar guarded by the SHA-256 of the `.glb`. It is zero for
 * the 727 cache; `AnimationGltfParityTest` is the proof and `docs/cache-source.md`, *anims*, the
 * measurement.
 */
object AnimationFrameCodec : SourceCodec {

    /** The extension a frame set file has. */
    const val EXTENSION = ".glb"

    override val id: String
        get() = "gltf-anim"

    /**
     * Where the skeletons are read from when [unpack] runs. Replaceable so a caller unpacking a
     * cache other than [Cache.get]'s can say which. Nothing in [pack] reads it.
     */
    @Volatile
    var cache: () -> Cache = { Cache.get() }

    /**
     * False: a `.glb` is not the shipped bytes, so the unpacker packs the tree back and proves both
     * the bytes and the chunk table anyway - a second, independent check of what [unpack] already
     * fell back for.
     */
    override fun exact(archive: Int): Boolean = false

    /** The file ids are in the file: one `frames` entry per frame, each naming its own. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val glb = glb(archive)
        val name = "${archive.archive}$EXTENSION"
        // A group whose split is not the frame format's three sections, or a frame the encoder
        // cannot reproduce, has to keep its bytes; both arrive here as a group that is not the one
        // that went in.
        val rebuilt = try {
            encode(AnimationGltf.read(glb, archive.archive))
        } catch (e: Exception) {
            logWarn("Frame set ${archive.archive} did not survive its glb: ${e.message}")
            null
        }
        if (rebuilt != null && rebuilt.contentEquals(archive.group)) {
            return UnpackedArchive(listOf(SourceFile(name, glb)))
        }
        // The `.glb` is still the editable form; the shipped group rides along beside it until
        // somebody edits it, which is what keeps parity independent of the encoder being exact.
        return UnpackedArchive(
            listOf(SourceFile(name, glb), SourceFile(SourceFiles.pristine(name), archive.group)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(glb))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            throw IOException("Frame set $archive has no glb at ${path.toAbsolutePath()}.")
        }
        val glb = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        // The sidecar is the whole group, guarded by the hash of the `.glb`; it is recorded under
        // the archive's first file id because a frame set is one file on disk.
        val hash = metadata.pristine.values.firstOrNull()
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(glb) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), AnimationGltf.read(glb, archive).fileIds())
            }
            // The edit is what the cache is built from now, so the stale guard goes rather than
            // being left to confuse the next reader; `cache status` counts the ones still waiting.
            Files.delete(sidecar)
        }
        val set = AnimationGltf.read(glb, archive)
        check(set.frames.isNotEmpty()) { "Frame set $archive has no frames in ${path.toAbsolutePath()}." }
        return PackedArchive(encode(set), set.fileIds())
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

    /**
     * Forget the skeletons read so far and put [cache] back to its default.
     *
     * For tests, and for a tool that swapped the cache underneath a tree: the bases are keyed by id
     * and would otherwise still be the old cache's.
     */
    fun reset() {
        synchronized(this) {
            bases.clear()
            resolved = null
            cache = { Cache.get() }
        }
    }

    /**
     * [archive] as the exact bytes of its `.glb` - the unpack, without the pristine check.
     *
     * Internal because the parity test wants the two directions separately, to time them and to
     * compare the group it gets back against the one that went in without going through a disk.
     */
    internal fun glb(archive: SourceArchive): ByteArray {
        val set = decode(archive)
        return AnimationGltf.write(set, base(set.baseId))
    }

    /** The frame set [archive] holds: its frames decoded, and the group layout it arrived in. */
    private fun decode(archive: SourceArchive): AnimationFrameSet {
        val frames = ArrayList<AnimationFrameDefinition>(archive.files.size)
        for ((position, file) in archive.fileIds.withIndex()) {
            frames.add(decoded(archive.archive, file, archive.files[position]))
        }
        val chunks = if (archive.chunks.isEmpty()) {
            if (frames.size == 1) 1 else AnimationGltf.CHUNKS
        } else {
            archive.chunks.size
        }
        return AnimationFrameSet(
            archive = archive.archive,
            baseId = frames.firstOrNull()?.baseId ?: -1,
            frames = frames,
            chunks = chunks,
            header = AnimationGltf.HEADER
        )
    }

    /** One frame set's group: the frames encoded, joined under the layout the file records. */
    internal fun encode(set: AnimationFrameSet): ByteArray {
        val files = ArrayList<ByteArray>(set.frames.size)
        for (frame in set.frames) {
            files.add(encoded(frame))
        }
        return ArchiveGroup.join(files, chunks(set, files))
    }

    /**
     * The three chunk split, taken from the frames themselves: the header, the flag bytes, the rest.
     *
     * Null for a group the file says is one chunk - a single frame archive - which carries no chunk
     * table at all.
     */
    private fun chunks(set: AnimationFrameSet, files: List<ByteArray>): List<IntArray>? {
        if (set.chunks <= 1 || files.size == 1) {
            return null
        }
        check(set.chunks == AnimationGltf.CHUNKS) {
            "Frame set ${set.archive} says its group is ${set.chunks} chunks; index 0 stores " +
                "${AnimationGltf.CHUNKS}, whose lengths are the frame format's own three sections."
        }
        val header = IntArray(files.size)
        val flags = IntArray(files.size)
        val values = IntArray(files.size)
        for (index in files.indices) {
            val count = set.frames[index].flags.size
            val rest = files[index].size - set.header - count
            check(rest >= 0) {
                "Frame set ${set.archive} frame ${set.frames[index].id} is ${files[index].size} bytes, " +
                    "which cannot hold a ${set.header} byte header and $count flag bytes."
            }
            header[index] = set.header
            flags[index] = count
            values[index] = rest
        }
        return listOf(header, flags, values)
    }

    private fun decoded(archive: Int, file: Int, data: ByteArray): AnimationFrameDefinition {
        val definition = AnimationFrameDefinition((archive shl AnimationFrameDecoder.BITS) or file)
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    private fun encoded(frame: AnimationFrameDefinition): ByteArray {
        val writer = BufferWriter(AnimationGltf.HEADER + frame.flags.size + frame.values.size * 2)
        with(encoder) { writer.encode(frame) }
        return writer.toArray()
    }

    /**
     * The skeleton [id], decoded once and shared.
     *
     * Null when the cache has no such base: 727 has one for every frame set, and a tree unpacked
     * from a cache that does not gets nodes without a transform type rather than no nodes at all.
     */
    private fun base(id: Int): AnimationBaseDefinition? {
        if (id < 0) {
            return null
        }
        return bases.computeIfAbsent(id) { key -> skeleton(key) }
    }

    private fun skeleton(id: Int): AnimationBaseDefinition {
        val definition = AnimationBaseDefinition(id)
        val data = skeletons().data(Index.ANIMATION_SKELETONS, id, 0)
        if (data == null) {
            logWarn("Animation base $id is not in the cache; its frame sets get untyped nodes.")
        } else {
            baseDecoder.readLoop(definition, BufferReader(data))
        }
        return definition
    }

    private fun skeletons(): Cache = resolved ?: synchronized(this) {
        resolved ?: cache().also { resolved = it }
    }

    @Volatile
    private var resolved: Cache? = null

    private val bases = ConcurrentHashMap<Int, AnimationBaseDefinition>()

    private val decoder = AnimationFrameDecoder()

    private val encoder = AnimationFrameEncoder()

    private val baseDecoder = AnimationBaseDecoder()
}
