package world.gregs.voidps.cache.definition

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.EnumEncoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.definition.encoder.VarBitEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decodes every definition file of indices 16 to 22 and encodes it straight back, byte for byte.
 *
 * The cache source tree (`docs/cache-source.md`) stores these types as editable JSON, and only keeps the
 * original bytes beside a file - the pristine fallback - when its encoder cannot reproduce them. So anything
 * short of byte identity here is a file that can never be edited as JSON. The bar is 100% of every file, and
 * the comparison is against the shipped bytes, not against a re-decode.
 */
class DefinitionEncoderParityTest {

    @Test
    fun `every loc file re-encodes byte for byte`() {
        parity("loc", Index.OBJECTS, 8, ObjectDecoder(), ObjectEncoder()) { ObjectDefinition(it) }
    }

    @Test
    fun `every enum file re-encodes byte for byte`() {
        parity("enum", Index.ENUMS, 8, EnumDecoder(), EnumEncoder()) { EnumDefinition(it) }
    }

    @Test
    fun `every npc file re-encodes byte for byte`() {
        parity("npc", Index.NPCS, 7, NPCDecoder(), NPCEncoder()) { NPCDefinition(it) }
    }

    @Test
    fun `every obj file re-encodes byte for byte`() {
        parity("obj", Index.ITEMS, 8, ItemDecoder(), ItemEncoder()) { ItemDefinition(it) }
    }

    @Test
    fun `every seq file re-encodes byte for byte`() {
        parity("seq", Index.ANIMATIONS, 7, AnimationDecoder(), AnimationEncoder()) { AnimationDefinition(it) }
    }

    @Test
    fun `every spotanim file re-encodes byte for byte`() {
        parity("spotanim", Index.SPOTANIMS, 8, SpotAnimDecoder(), SpotAnimEncoder()) { SpotAnimDefinition(it) }
    }

    @Test
    fun `every varbit file re-encodes byte for byte`() {
        parity("varbit", Index.VAR_BIT, 10, VarBitDecoder(), VarBitEncoder()) { VarBitDefinition(it) }
    }

    /**
     * Walks every file the index holds - through the reference table, not through the decoder's `size`, which
     * rounds up to whole archives and so invents ids that have no file - and compares the encoder's output to
     * the bytes the cache shipped.
     *
     * @param bits how many low bits of a definition id are its file id within the archive
     * @param residuals how many files are known not to reproduce, asserted so a regression still fails
     */
    private fun <T> parity(
        name: String,
        index: Int,
        bits: Int,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        residuals: Int = 0,
        create: (Int) -> T
    ) where T : Definition, T : OpcodeOrdered {
        val cache = cache() ?: return
        var files = 0
        var identical = 0
        var differing = 0
        var canonical = 0
        var shadowed = 0
        val examples = StringBuilder()
        for (archive in cache.archives(index)) {
            for (file in cache.files(index, archive)) {
                val data = cache.data(index, archive, file) ?: continue
                val id = (archive shl bits) or file
                files++
                val definition = create(id)
                decoder.readLoop(definition, BufferReader(data))
                val writer = BufferWriter(data.size * 2 + 64)
                val encoded = try {
                    with(encoder) { writer.encode(definition) }
                    writer.toArray()
                } catch (e: Throwable) {
                    differing++
                    if (differing <= EXAMPLE_LIMIT) {
                        examples.append("\n  $id: encoding threw ${e::class.simpleName}: ${e.message}")
                    }
                    continue
                }
                if (definition.opcodeOrder == null) {
                    canonical++
                }
                if (definition.shadowedPayloads != null) {
                    shadowed++
                }
                if (encoded.contentEquals(data)) {
                    identical++
                    continue
                }
                differing++
                if (differing <= EXAMPLE_LIMIT) {
                    val offset = (0 until minOf(data.size, encoded.size)).firstOrNull { data[it] != encoded[it] }
                        ?: minOf(data.size, encoded.size)
                    examples.append("\n  $id: ${data.size} bytes in, ${encoded.size} out, first differs at $offset")
                    examples.append(" in opcode ${opcodeAt(decoder, create(id), data, offset)}")
                    examples.append(" (expected 0x${byteAt(data, offset)} was 0x${byteAt(encoded, offset)})")
                    examples.append(", order ${definition.opcodeOrder?.toList() ?: "canonical"}")
                }
            }
        }
        println("$name: $files files, $identical identical, $differing differing")
        println("  $canonical in the encoder's own opcode order, $shadowed with an overwritten record$examples")
        assertEquals(residuals, differing, "$name definitions do not re-encode byte for byte$examples")
        assertEquals(files, identical + differing, "$name files were not all compared")
    }

    /** The opcode whose record covers [offset], for explaining a differing byte. */
    private fun <T : Definition> opcodeAt(decoder: DefinitionDecoder<T>, definition: T, data: ByteArray, offset: Int): Int {
        var opcode = -1
        decoder.readLoopTraced(definition, BufferReader(data)) { current, position ->
            if (position <= offset) {
                opcode = current
            }
        }
        return opcode
    }

    private fun byteAt(data: ByteArray, offset: Int): String =
        if (offset >= data.size) "--" else (data[offset].toInt() and 0xff).toString(16).padStart(2, '0')

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping definition encoder parity; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }

    private companion object {
        private const val EXAMPLE_LIMIT = 8
    }
}
