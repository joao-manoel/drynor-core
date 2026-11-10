package world.gregs.voidps.cache.definition.decoder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder

class AnimationHandOverrideTest {
    @Test fun `unsigned hand IDs and hidden-equipment sentinel survive the native binary round trip`() {
        for (value in listOf(0, 32767, 32768, 65534, 65535)) {
            // Deliberately noncanonical order also exercises opcode-order preservation.
            val bytes = byteArrayOf(7, (value ushr 8).toByte(), value.toByte(), 6, (value ushr 8).toByte(), value.toByte(), 0)
            val definition = AnimationDefinition(1)
            AnimationDecoder().readLoop(definition, BufferReader(bytes))
            assertEquals(value, definition.leftHandItem)
            assertEquals(value, definition.rightHandItem)
            val writer = BufferWriter(32)
            with(AnimationEncoder()) { writer.encode(definition) }
            assertContentEquals(bytes, writer.toArray())
        }
    }

    @Test fun `absent overrides remain distinct from explicit hide and do not gain new opcodes`() {
        val definition = AnimationDefinition(1)
        AnimationDecoder().readLoop(definition, BufferReader(byteArrayOf(0)))
        assertEquals(-1, definition.leftHandItem)
        assertEquals(-1, definition.rightHandItem)
        val writer = BufferWriter(32)
        with(AnimationEncoder()) { writer.encode(definition) }
        assertContentEquals(byteArrayOf(0), writer.toArray())
    }
}
