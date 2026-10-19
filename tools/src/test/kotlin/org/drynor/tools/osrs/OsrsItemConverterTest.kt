package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import kotlin.test.*

class OsrsItemConverterTest {
    @Test fun `unsigned OSRS models become big smart native references with signed presentation`() {
        val raw = byteArrayOf(1, 0xff.toByte(), 0xfe.toByte(), 2, 0x80.toByte(), 0,
            7, 0xff.toByte(), 0xff.toByte(), 23, 0x80.toByte(), 0, 0, 25, 0, 1, 0, 0)
        val item = OsrsItemConverter.decode(51295, raw)
        assertEquals(265534, item.modelId)
        assertEquals(232768, item.primaryMaleModel)
        assertEquals(200001, item.primaryFemaleModel)
        assertEquals(-1, item.graphicTranslateX)
        assertEquals("€", item.name)
        val bytes = BufferWriter(1024).also { with(ItemEncoder()) { it.encode(item) } }.toArray()
        val readback = ItemDefinition(51295)
        ItemDecoder().readLoop(readback, BufferReader(bytes))
        assertEquals(item, readback)
    }

    @Test fun `unknown opcodes truncated fields and trailing bytes are rejected`() {
        assertFailsWith<IllegalStateException> { OsrsItemConverter.decode(1, byteArrayOf(99, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsItemConverter.decode(1, byteArrayOf(1, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsItemConverter.decode(1, byteArrayOf(0, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsItemConverter.decode(1, byteArrayOf(23, 0, 1, 1, 0)) }
    }

    @Test fun `placeholder links require explicit omission and retain physical source references`() {
        val raw = byteArrayOf(148.toByte(), 0x53, 0x30, 0)
        assertFailsWith<IllegalArgumentException> { OsrsItemConverter.decode(30000, raw) }
        val links = linkedMapOf<Int, Int>()
        val item = OsrsItemConverter.decode(30000, raw, links)
        assertEquals(mapOf(148 to 51296), links)
        assertEquals(-1, item.singleNoteId)
        assertEquals(-1, item.noteId)
    }

    @Test fun `presentation opcodes retain native axis order and scaled signed diffusion`() {
        val item = OsrsItemConverter.decode(30000, byteArrayOf(111, 0, 96, 112, 0, 64, 114, (-2).toByte(), 0))
        assertEquals(96, item.floorScaleZ)
        assertEquals(64, item.floorScaleY)
        assertEquals(-10, item.diffusion)
        val bytes = BufferWriter(1024).also { with(ItemEncoder()) { it.encode(item) } }.toArray()
        val decoded = ItemDefinition(30000)
        ItemDecoder().readLoop(decoded, BufferReader(bytes))
        assertEquals(item, decoded)
    }
}
