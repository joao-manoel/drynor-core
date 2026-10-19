package world.gregs.voidps.cache.definition.decoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder

class ObjectOffsetTest {
    @Test
    fun offsetsUseSignedShortsInClientUnitsAndReencodeIdentically() {
        val bytes = byteArrayOf(70, -1, -2, 71, 0, 3, 72, -128, 0, 0)
        val definition = ObjectDefinition(1)
        ObjectDecoder().readLoop(definition, BufferReader(bytes))
        assertEquals(-8, definition.offsetX)
        assertEquals(12, definition.offsetZ)
        assertEquals(-131072, definition.offsetY)
        val writer = BufferWriter(256)
        with(ObjectEncoder()) { writer.encode(definition) }
        assertContentEquals(bytes, writer.toArray())
    }
}
