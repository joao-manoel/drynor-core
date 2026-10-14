package world.gregs.voidps.cache.definition

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.decoder.BillboardDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleProducerDecoder
import world.gregs.voidps.cache.definition.encoder.BillboardEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleProducerEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three indices a model is drawn from - its geometry, its particle configs and its billboard
 * configs - decoded and encoded back, against the shipped bytes.
 *
 * A model is not an opcode keyed definition: it is a dozen fixed sections and a 21 byte footer
 * that declares their lengths, so there is no opcode order to record and nothing to shadow, and
 * either the decoder reached every byte or the encoder cannot put them back. The particle and
 * billboard configs *are* opcode keyed and go through the same
 * [OpcodeOrdered]/[OpcodeEncoder] mechanism as every other config.
 *
 * This is the measurement `docs/cache-source.md`'s *models*, *billboards* and *particles*
 * sections report. Skipped when there is no cache to test against, like the other whole-cache
 * tests.
 */
class ModelEncoderParityTest {

    @Test
    fun `every model re-encodes to its own bytes`() {
        val cache = cache() ?: return
        var files = 0
        var identical = 0
        var empty = 0
        var newFormat = 0
        val differences = ArrayList<String>()
        val versions = HashMap<Int, Int>()
        val flags = HashMap<Int, Int>()
        var derivedFlags = 0
        var derivedTypes = 0
        var trailing = 0
        var orphanPointers = 0
        var bytes = 0L
        val start = System.nanoTime()
        for (archive in cache.archiveIds(Index.MODELS)) {
            val data = cache.data(Index.MODELS, archive, 0) ?: continue
            files++
            bytes += data.size
            if (ModelDecoder.newFormat(data)) {
                newFormat++
            }
            val model = ModelDecoder.read(archive, data)
            if (model.vertexCount == 0 || model.faceCount == 0) {
                empty++
            }
            versions.merge(model.version, 1, Int::plus)
            flags.merge(model.footerFlags, 1, Int::plus)
            if (model.vertexFlags.contentEquals(ModelEncoder.deriveVertexFlags(model))) {
                derivedFlags++
            }
            if (model.faceIndexTypes.contentEquals(ModelEncoder.deriveFaceIndexTypes(model))) {
                derivedTypes++
            }
            if (model.trailing != null) {
                trailing++
            }
            if (model.unusedTexturePointers != null) {
                orphanPointers++
            }
            val encoded = ModelEncoder.encode(model)
            if (encoded.contentEquals(data)) {
                identical++
            } else if (differences.size < EXAMPLES) {
                differences.add("$archive: ${describe(data, encoded)}")
            }
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("index ${Index.MODELS}: $files archives, ${bytes / 1_000_000} MB, $newFormat new format")
        println("  identical $identical of $files, in ${millis}ms")
        println("  versions ${versions.toSortedMap()}")
        println("  footer flags ${flags.toSortedMap()}")
        println("  empty placeholders $empty, unaccounted trailing bytes $trailing, orphan texture indices $orphanPointers")
        println("  derivable vertex flags $derivedFlags, derivable face index types $derivedTypes")
        for (difference in differences) {
            println("  DIFFERS $difference")
        }
        assertTrue(files >= VANILLA_MODELS, "$files model archives compared, fewer than the $VANILLA_MODELS of vanilla 727")
        assertEquals(EMPTY_PLACEHOLDERS, empty, "the cache has $EMPTY_PLACEHOLDERS models with no geometry")
        assertEquals(ORPHAN_TEXTURE_INDICES, orphanPointers, "only model 73412 declares texture indices with no triangles")
        assertEquals(0, trailing, "no model may have bytes nothing accounts for")
        assertEquals(files, derivedFlags, "every model's vertex flags must be the derived ones")
        assertEquals(files, derivedTypes, "every model's face index types must be the derived ones")
        assertEquals(files, identical, "every model must re-encode to its own bytes")
    }

    @Test
    fun `every billboard re-encodes to its own bytes`() {
        val cache = cache() ?: return
        val decoder = BillboardDecoder()
        val encoder = BillboardEncoder()
        val counts = compare(cache, Index.BILLBOARDS, BillboardDecoder.ARCHIVE, decoder, encoder) { BillboardType(id = it) }
        println("index ${Index.BILLBOARDS} archive ${BillboardDecoder.ARCHIVE} (billboard): $counts")
        assertTrue(counts.files >= VANILLA_BILLBOARDS, "${counts.files} billboards, fewer than the $VANILLA_BILLBOARDS of vanilla 727")
        assertEquals(counts.files, counts.identical, "every billboard must re-encode to its own bytes")
    }

    @Test
    fun `every particle producer re-encodes to its own bytes`() {
        val cache = cache() ?: return
        val decoder = ParticleProducerDecoder()
        val encoder = ParticleProducerEncoder()
        val counts = compare(cache, Index.PARTICLES, ParticleProducerDecoder.ARCHIVE, decoder, encoder) {
            ParticleProducerDefinition(id = it)
        }
        println("index ${Index.PARTICLES} archive ${ParticleProducerDecoder.ARCHIVE} (producer): $counts")
        assertTrue(counts.files >= VANILLA_PRODUCERS, "${counts.files} particle producers, fewer than the $VANILLA_PRODUCERS of vanilla 727")
        assertEquals(counts.files, counts.identical, "every particle producer must re-encode to its own bytes")
    }

    @Test
    fun `every particle re-encodes to its own bytes`() {
        val cache = cache() ?: return
        val decoder = ParticleDecoder()
        val encoder = ParticleEncoder()
        val counts = compare(cache, Index.PARTICLES, ParticleDecoder.ARCHIVE, decoder, encoder) {
            ParticleDefinition(id = it)
        }
        println("index ${Index.PARTICLES} archive ${ParticleDecoder.ARCHIVE} (particle): $counts")
        assertTrue(counts.files >= VANILLA_PARTICLES, "${counts.files} particles, fewer than the $VANILLA_PARTICLES of vanilla 727")
        assertEquals(counts.files, counts.identical, "every particle must re-encode to its own bytes")
    }

    /** How one opcode keyed archive compared, and how much metadata its files needed. */
    private class Counts(val files: Int, val identical: Int, val order: Int, val shadow: Int, val examples: List<String>) {
        override fun toString(): String =
            "$files files, $identical identical, $order with a recorded opcode order, $shadow with a shadowed payload" +
                if (examples.isEmpty()) "" else " (${examples.joinToString("; ")})"
    }

    /** Decode and re-encode every file of one archive, as the definition parity tests do. */
    private fun <T> compare(
        cache: Cache,
        index: Int,
        archive: Int,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): Counts where T : Definition, T : OpcodeOrdered {
        var files = 0
        var identical = 0
        var order = 0
        var shadow = 0
        val examples = ArrayList<String>()
        for (file in cache.files(index, archive)) {
            val data = cache.data(index, archive, file) ?: continue
            files++
            val definition = create(file)
            decoder.readLoop(definition, BufferReader(data))
            if (definition.opcodeOrder != null) {
                order++
            }
            if (definition.shadowedPayloads != null) {
                shadow++
            }
            val writer = BufferWriter(data.size * 2 + 64)
            with(encoder) { writer.encode(definition) }
            val encoded = writer.toArray()
            if (encoded.contentEquals(data)) {
                identical++
            } else if (examples.size < EXAMPLES) {
                examples.add("$file: ${describe(data, encoded)}")
            }
        }
        return Counts(files, identical, order, shadow, examples)
    }

    /** Where two encodings first differ, and by how much, for a failure that has to be explained. */
    private fun describe(expected: ByteArray, actual: ByteArray): String {
        if (expected.size != actual.size) {
            return "${expected.size} bytes became ${actual.size}"
        }
        for (index in expected.indices) {
            if (expected[index] != actual[index]) {
                return "byte $index of ${expected.size}: ${expected[index].toInt() and 0xff} became " +
                    "${actual[index].toInt() and 0xff}"
            }
        }
        return "identical"
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping model encoder parity; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }

    private companion object {
        private const val EXAMPLES = 12

        /** The archives that declare no geometry at all; see [ModelDecoder]. */
        private const val EMPTY_PLACEHOLDERS = 8

        /** Only model 73412 declares a texture index section with no texture triangle behind it. */
        private const val ORPHAN_TEXTURE_INDICES = 1

        /** What vanilla 727 shipped; the cache only ever grows, so these are floors. */
        private const val VANILLA_MODELS = 73_417
        private const val VANILLA_BILLBOARDS = 360
        private const val VANILLA_PRODUCERS = 741
        private const val VANILLA_PARTICLES = 18
    }
}
