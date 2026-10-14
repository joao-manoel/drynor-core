package org.darkan.tools.rs3port

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleProducerDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleProducerEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.definition.encoder.TextureEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.GraphicCodec
import world.gregs.voidps.cache.source.codec.MaterialCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.store.ArchiveGroup
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class Converted(val files: List<SourceFile>, val packed: List<ByteArray>)

object SourceCodecFiles {

    private const val CAPACITY = 1 shl 20

    private const val SEQ_BITS = 7

    private const val SPOTANIM_BITS = 8

    private const val FRAME_HEADER = 4

    private val seqs = AnimationEncoder()

    private val spotanims = SpotAnimEncoder()

    private val producers = ParticleProducerEncoder()

    private val effectors = ParticleEncoder()

    private val bases = AnimationBaseEncoder()

    private val frames = AnimationFrameEncoder()

    private val textures = TextureEncoder()

    fun seq(tree: Path, definition: AnimationDefinition): Converted =
        packed(tree, Index.ANIMATIONS, definition.id, SEQ_BITS, encode { with(seqs) { it.encode(definition) } })

    fun spotanim(tree: Path, definition: SpotAnimDefinition): Converted =
        packed(tree, Index.SPOTANIMS, definition.id, SPOTANIM_BITS, encode { with(spotanims) { it.encode(definition) } })

    fun producer(tree: Path, definition: ParticleProducerDefinition): Converted = unpack(
        tree, Index.PARTICLES, ParticleProducerDecoder.ARCHIVE, intArrayOf(definition.id),
        listOf(encode { with(producers) { it.encode(definition) } })
    )

    fun effector(tree: Path, definition: ParticleDefinition): Converted = unpack(
        tree, Index.PARTICLES, ParticleDecoder.ARCHIVE, intArrayOf(definition.id),
        listOf(encode { with(effectors) { it.encode(definition) } })
    )

    fun base(tree: Path, definition: AnimationBaseDefinition): Converted =
        unpack(tree, Index.ANIMATION_SKELETONS, definition.id, intArrayOf(0), listOf(baseBytes(definition)))

    fun texture(tree: Path, definition: TextureDefinition): Converted =
        unpack(tree, Index.TEXTURES, definition.id, intArrayOf(0), listOf(encode { with(textures) { it.encode(definition) } }))

    fun model(tree: Path, definition: ModelDefinition): Converted =
        unpack(tree, Index.MODELS, definition.id, intArrayOf(0), listOf(ModelEncoder.encode(definition)))

    fun materials(tree: Path, definitions: List<MaterialDefinition>): Converted {
        val byId = definitions.associateBy { it.id }
        val dense = List(byId.keys.max() + 1) { byId[it] ?: MaterialDefinition(it, defined = false) }
        val blob = MaterialCodec.encode(dense)
        val names = byId.keys.map { "${IndexNames.name(Index.TEXTURE_DEFINITIONS)}/$it.json" }.toSet()
        val unpacked = unpack(tree, Index.TEXTURE_DEFINITIONS, 0, intArrayOf(0), listOf(blob), check = false)
        return Converted(unpacked.files.filter { it.path in names }, unpacked.packed)
    }

    fun graphic(tree: Path, id: Int, image: BufferedImage): Converted {
        val scratch = Files.createTempDirectory("rs3port-graphic")
        try {
            val folder = Files.createDirectories(scratch.resolve(id.toString()))
            ImageIO.write(image, "png", folder.resolve("0.png").toFile())
            val group = GraphicCodec.pack(scratch, id, ArchiveMetadata()).group
            return unpack(tree, Index.GRAPHICS, id, intArrayOf(0), listOf(group))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    fun frames(tree: Path, archive: Int, definitions: List<AnimationFrameDefinition>, base: AnimationBaseDefinition): Converted {
        val files = definitions.map { frame -> encode { with(frames) { it.encode(frame) } } }
        val chunks = if (files.size == 1) {
            emptyList()
        } else {
            listOf(
                IntArray(files.size) { FRAME_HEADER },
                IntArray(files.size) { definitions[it].flags.size },
                IntArray(files.size) { files[it].size - FRAME_HEADER - definitions[it].flags.size }
            )
        }
        val fileIds = IntArray(definitions.size) { definitions[it].id and ((1 shl AnimationFrameDecoder.BITS) - 1) }
        AnimationFrameCodec.reset()
        AnimationFrameCodec.cache = { SkeletonCache(mapOf(base.id to baseBytes(base))) }
        try {
            return unpack(tree, Index.ANIMATION_FRAMES, archive, fileIds, files, chunks)
        } finally {
            AnimationFrameCodec.reset()
        }
    }

    private fun baseBytes(definition: AnimationBaseDefinition): ByteArray = encode { with(bases) { it.encode(definition) } }

    private fun packed(tree: Path, index: Int, id: Int, bits: Int, bytes: ByteArray): Converted =
        unpack(tree, index, id ushr bits, intArrayOf(id and ((1 shl bits) - 1)), listOf(bytes))

    private fun unpack(
        tree: Path,
        index: Int,
        archive: Int,
        fileIds: IntArray,
        files: List<ByteArray>,
        chunks: List<IntArray> = emptyList(),
        check: Boolean = true
    ): Converted {
        val directory = IndexNames.name(index)
        val group = ArchiveGroup.join(files, chunks.ifEmpty { null })
        val source = SourceArchive(index, archive, null, fileIds, group, files, chunks, ArchiveMetadata())
        val unpacked = SourceCodecs.codec(index).unpack(tree.resolve(directory), source)
        if (check) {
            check(unpacked.pristine.isEmpty()) {
                "Index $index archive $archive did not survive its own codec; the tree form would not pack back to it."
            }
        }
        return Converted(unpacked.files.map { SourceFile("$directory/${it.path}", it.bytes) }, files)
    }

    private fun encode(write: (Writer) -> Unit): ByteArray {
        val writer = BufferWriter(CAPACITY)
        write(writer)
        return writer.toArray()
    }
}

class SkeletonCache(private val bases: Map<Int, ByteArray>) : Cache {

    override val versionTable: ByteArray = ByteArray(0)

    override fun indexCount(): Int = 0

    override fun indices(): IntArray = IntArray(0)

    override fun indexCrcs(): IntArray = IntArray(0)

    override fun sector(index: Int, archive: Int): ByteArray? = null

    override fun archives(index: Int): IntArray = IntArray(0)

    override fun archiveCount(index: Int): Int = 0

    override fun lastArchiveId(indexId: Int): Int = -1

    override fun archiveId(index: Int, hash: Int): Int = -1

    override fun fileId(index: Int, archive: Int, hash: Int): Int = -1

    override fun files(index: Int, archive: Int): IntArray = IntArray(0)

    override fun fileCount(indexId: Int, archiveId: Int): Int = 0

    override fun lastFileId(indexId: Int, archive: Int): Int = -1

    override fun data(index: Int, archive: Int, file: Int, xtea: IntArray?): ByteArray? =
        if (index == Index.ANIMATION_SKELETONS && file == 0) bases[archive] else null

    override fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray?) =
        throw UnsupportedOperationException("A skeleton cache is read only.")

    override fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray?) =
        throw UnsupportedOperationException("A skeleton cache is read only.")

    override fun update(): Boolean = false

    override fun close() {}
}
