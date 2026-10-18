package world.gregs.voidps.cache

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ClanVarDefinition
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.encoder.ClanVarEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClanVarSettingsCodecTest {
    @Test fun `archive 54 setting bit layout round trips opcode 2 instead of varclan opcode 3`() {
        val bytes = byteArrayOf(1, 'i'.code.toByte(), 2, 0, 24, 1, 1, 0)
        val value = ClanVarDefinition(8)
        ClanVarDecoder(Config.VAR_CLAN_SETTINGS, 2).readLoop(value, BufferReader(bytes))
        assertEquals(24, value.baseVar)
        assertEquals(1, value.startBit)
        assertEquals(1, value.endBit)
        val output = BufferWriter()
        with(ClanVarEncoder(2)) { output.encode(value) }
        assertContentEquals(bytes, output.toArray())
        val regular = BufferWriter()
        with(ClanVarEncoder()) { regular.encode(value) }
        assertEquals(3, regular.toArray()[2].toInt())
    }

    @Test fun `noncanonical setting order is retained byte exactly`() {
        val bytes = byteArrayOf(2, 0, 24, 0, 31, 1, 'i'.code.toByte(), 0)
        val value = ClanVarDefinition(4)
        ClanVarDecoder(Config.VAR_CLAN_SETTINGS, 2).readLoop(value, BufferReader(bytes))
        val output = BufferWriter()
        with(ClanVarEncoder(2)) { output.encode(value) }
        assertContentEquals(bytes, output.toArray())
    }
}
