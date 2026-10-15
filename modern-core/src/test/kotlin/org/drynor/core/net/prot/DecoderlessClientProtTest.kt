package org.drynor.core.net.prot

import org.drynor.core.net.prot.revision.rev727.register727
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the payload-less client packets: the prots registered with no decoder at all.
 *
 * `Session.readPackets` has no bytes to hand such a prot, so it falls back to
 * [Codec.createInstanceForOpcode], which builds the packet by reflection. When that returns null
 * the packet is dropped on the floor and the handler never runs - the shape of the bug where
 * `CloseInterface` (opcode 60) was silently discarded on every interface close, because Kotlin
 * reflection does not expose the synthetic JVM no-arg constructor that an all-default-parameter
 * class gets, so a search for a zero-parameter constructor found nothing.
 *
 * Registering a decoder-less prot whose constructor needs a real argument fails this test, which
 * is the point: the failure belongs at build time, not in a production log line nobody reads.
 */
class DecoderlessClientProtTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    /** Opcodes registered without a decoder, deduplicated by opcode and sorted for stable output. */
    private fun decoderlessOpcodes(): List<Int> =
        codec.clientProtsByOpcode.filterValues { it.decoder == null }.keys.sorted()

    @Test
    fun `every client prot registered without a decoder can be instantiated`() {
        val opcodes = decoderlessOpcodes()
        assertTrue(opcodes.isNotEmpty(), "Expected at least one decoder-less client prot in the 727 codec")

        val broken = opcodes.mapNotNull { opcode ->
            val protClass = codec.clientProtsByOpcode.getValue(opcode).protClass
            if (codec.createInstanceForOpcode<ClientProt>(opcode) == null) {
                "opcode $opcode (${protClass.simpleName})"
            } else {
                null
            }
        }
        assertTrue(
            broken.isEmpty(),
            "Codec.createInstanceForOpcode returned null for decoder-less prots, so these packets " +
                "would be dropped: ${broken.joinToString()}"
        )
    }

    @Test
    fun `an instantiated payload-less prot is of the registered type`() {
        for (opcode in decoderlessOpcodes()) {
            val expected = codec.clientProtsByOpcode.getValue(opcode).protClass
            val instance = codec.createInstanceForOpcode<ClientProt>(opcode)
                ?: throw AssertionError("No instance for opcode $opcode (${expected.simpleName})")
            assertTrue(
                expected.isInstance(instance),
                "Opcode $opcode built a ${instance::class.simpleName}, expected a ${expected.simpleName}"
            )
        }
    }

    @Test
    fun `the decoder-less prots are the ones we expect`() {
        val actual = decoderlessOpcodes().map { opcode ->
            opcode to codec.clientProtsByOpcode.getValue(opcode).protClass.simpleName
        }
        assertEquals(
            listOf(
                0 to "Ping",
                60 to "CloseInterface",
                76 to "RegionLoadedConfirm"
            ),
            actual,
            "The set of client prots registered without a decoder changed"
        )
    }

    @Test
    fun `every decoder-less prot declares a zero-length fixed size`() {
        val wrong = decoderlessOpcodes().mapNotNull { opcode ->
            val entry = codec.clientProtsByOpcode.getValue(opcode)
            val size = entry.size
            if (size is ProtSize.Fixed && size.length == 0) null
            else "opcode $opcode (${entry.protClass.simpleName}): $size"
        }
        assertTrue(
            wrong.isEmpty(),
            "A prot with no decoder must carry no payload, or its bytes are read as the next " +
                "packet's opcode: ${wrong.joinToString()}"
        )
    }

    @Test
    fun `an unregistered opcode yields no instance`() {
        val unregistered = (0..255).first { it !in codec.clientProtsByOpcode }
        assertEquals(null, codec.createInstanceForOpcode<ClientProt>(unregistered))
    }
}
