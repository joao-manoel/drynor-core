package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.InvSlotUpdate
import org.darkan.core.net.prot.dto.InvTooltip
import org.darkan.core.net.prot.packet.UpdateInvFull
import org.darkan.core.net.prot.packet.UpdateInvPartial
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Rev727InvEncoderTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    @Test
    fun `partial without tooltips keeps the original layout`() {
        val packet = UpdateInvPartial(
            93, false,
            arrayOf(InvSlotUpdate(0, 4151, 1), InvSlotUpdate(1, 995, 1_000_000), InvSlotUpdate(2, -1, 0, InvTooltip.Keep)),
        )
        val expected = byteArrayOf(
            0x00, 0x5D, 0x00,
            0x00, 0x10, 0x38, 0x01,
            0x01, 0x03, 0xE4.toByte(), 0xFF.toByte(), 0x00, 0x0F, 0x42, 0x40,
            0x02, 0x00, 0x00,
        )
        assertContentEquals(expected, encode(packet))
    }

    @Test
    fun `partial with tooltips packs the mode into the slot smart`() {
        val packet = UpdateInvPartial(
            93, true,
            arrayOf(
                InvSlotUpdate(0, 4151, 1, InvTooltip.Keep),
                InvSlotUpdate(1, 995, 5, InvTooltip.Clear),
                InvSlotUpdate(40, 1, 300, InvTooltip.Set("Hi")),
                InvSlotUpdate(3, -1, 0, InvTooltip.Set("ignored")),
            ),
        )
        val reader = BufferReader(encode(packet))
        assertEquals(93, reader.readUnsignedShort())
        assertEquals(0x1 or 0x2, reader.readUnsignedByte())

        assertEquals(0 shl 2 or 0, reader.readSmart())
        assertEquals(4152, reader.readUnsignedShort())
        assertEquals(1, reader.readUnsignedByte())

        assertEquals(1 shl 2 or 1, reader.readSmart())
        assertEquals(996, reader.readUnsignedShort())
        assertEquals(5, reader.readUnsignedByte())

        assertEquals(40 shl 2 or 2, reader.readSmart())
        assertEquals(2, reader.readUnsignedShort())
        assertEquals(255, reader.readUnsignedByte())
        assertEquals(300, reader.readInt())
        assertEquals("Hi", reader.readString())

        assertEquals(3 shl 2 or 1, reader.readSmart())
        assertEquals(0, reader.readUnsignedShort())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `full without tooltips sets no tooltip flag and ends after the items`() {
        val packet = UpdateInvFull(93, false, intArrayOf(4151, -1), intArrayOf(1, 0), arrayOfNulls(2))
        val reader = BufferReader(encode(packet))
        reader.readUnsignedShort()
        assertEquals(0, reader.readUnsignedByte())
        assertEquals(2, reader.readUnsignedShort())
        repeat(2) { readItem(reader) }
        assertEquals(0, reader.remaining)
        assertEquals(UpdateInvFull(93, false, intArrayOf(4151, -1), intArrayOf(1, 0)), packet)
    }

    @Test
    fun `full picks the bitmask for a dense inventory`() {
        val size = 28
        val tooltips = arrayOfNulls<String>(size)
        val tooltipSlots = listOf(0, 5, 9, 17, 27)
        tooltipSlots.forEach { tooltips[it] = "t$it" }
        val itemIds = IntArray(size) { 1000 + it }
        val reader = BufferReader(encode(UpdateInvFull(93, true, itemIds, IntArray(size) { 1 }, tooltips)))
        reader.readUnsignedShort()
        assertEquals(0x1 or 0x2, reader.readUnsignedByte())
        assertEquals(size, reader.readUnsignedShort())
        repeat(size) { assertEquals(itemIds[it], readItem(reader)) }
        val mask = ByteArray(4)
        reader.readBytes(mask)
        val setSlots = (0 until size).filter { mask[it shr 3].toInt() and (1 shl (it and 7)) != 0 }
        assertEquals(tooltipSlots, setSlots)
        tooltipSlots.forEach { assertEquals("t$it", reader.readString()) }
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `full picks the sparse list for a large inventory with few tooltips`() {
        val size = 516
        val tooltips = arrayOfNulls<String>(size)
        tooltips[3] = "low"
        tooltips[400] = "high"
        tooltips[10] = "empty slot"
        val itemIds = IntArray(size) { if (it == 10) -1 else it + 1 }
        val reader = BufferReader(encode(UpdateInvFull(95, false, itemIds, IntArray(size) { 1 }, tooltips)))
        reader.readUnsignedShort()
        assertEquals(0x4, reader.readUnsignedByte())
        assertEquals(size, reader.readUnsignedShort())
        repeat(size) { readItem(reader) }
        assertEquals(2, reader.readSmart())
        assertEquals(3, reader.readSmart())
        assertEquals("low", reader.readString())
        assertEquals(400, reader.readSmart())
        assertEquals("high", reader.readString())
        assertEquals(0, reader.remaining)
    }

    private fun readItem(reader: BufferReader): Int {
        if (reader.readByteSubtract() and 0xff == 255) {
            reader.readIntInverseMiddle()
        }
        return (reader.readShortAddLittle() and 0xffff) - 1
    }

    private fun encode(packet: ServerProt): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        codec.encodeServerProt(packet, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead)
        channel.readFully(bytes)
        channel.close()
        bytes
    }
}
