package org.drynor.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.drynor.core.net.prot.assertServerRoundTrip
import org.drynor.core.net.prot.decodeServer
import org.drynor.core.net.prot.dto.ClanChannelChatter
import org.drynor.core.net.prot.dto.ClanSettingsMember
import org.drynor.core.net.prot.dto.ClanVarSetting
import org.drynor.core.net.prot.dto.GrandExchangeOffer
import org.drynor.core.net.prot.dto.InvSlotUpdate
import org.drynor.core.net.prot.dto.InvTooltip
import org.drynor.core.net.prot.packet.ClanChannelDelta
import org.drynor.core.net.prot.packet.ClanChannelFull
import org.drynor.core.net.prot.packet.ClanSettingsDelta
import org.drynor.core.net.prot.packet.ClanSettingsFull
import org.drynor.core.net.prot.packet.ClanSettingsFullRaw
import org.drynor.core.net.prot.packet.NpcUpdate
import org.drynor.core.net.prot.packet.NpcUpdateLarge
import org.drynor.core.net.prot.packet.PlayerOption
import org.drynor.core.net.prot.packet.PlayerUpdate
import org.drynor.core.net.prot.packet.PlayerWeight
import org.drynor.core.net.prot.packet.ReduceAttackPriority
import org.drynor.core.net.prot.packet.RunEnergy
import org.drynor.core.net.prot.packet.SetTarget
import org.drynor.core.net.prot.packet.UpdateDob
import org.drynor.core.net.prot.packet.UpdateGESlot
import org.drynor.core.net.prot.packet.UpdateInvFull
import org.drynor.core.net.prot.packet.UpdateInvPartial
import org.drynor.core.net.prot.packet.UpdateInvStopTransmit
import org.drynor.core.net.prot.packet.UpdateStat
import org.drynor.core.net.prot.packet.UpdateUid192
import org.drynor.core.net.prot.serverBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Rev727ServerUpdateDecoderTest {
    private val codec = register727()

    private val payload = ByteArray(300) { (it * 7).toByte() }

    @Test
    fun `entity updates decode their raw payload`() {
        for (data in listOf(payload, ByteArray(0))) {
            assertContentEquals(data, (codec.decodeServer(PlayerUpdate(data), codec.serverBytes(PlayerUpdate(data))) as PlayerUpdate).data)
            assertContentEquals(data, (codec.decodeServer(NpcUpdate(data), codec.serverBytes(NpcUpdate(data))) as NpcUpdate).data)
            assertContentEquals(data, (codec.decodeServer(NpcUpdateLarge(data), codec.serverBytes(NpcUpdateLarge(data))) as NpcUpdateLarge).data)
        }
    }

    @Test
    fun `partial inventory without tooltips decodes every slot as cleared`() {
        codec.assertServerRoundTrip(
            UpdateInvPartial(93, false, arrayOf(InvSlotUpdate(0, 4151, 1), InvSlotUpdate(1, 995, 1_000_000), InvSlotUpdate(500, 65534, 254), InvSlotUpdate(2, -1, 0, InvTooltip.Keep))),
            UpdateInvPartial(93, false, arrayOf(InvSlotUpdate(0, 4151, 1), InvSlotUpdate(1, 995, 1_000_000), InvSlotUpdate(500, 65534, 254), InvSlotUpdate(2, -1, 0, InvTooltip.Clear))),
        )
    }

    @Test
    fun `partial inventory with tooltips decodes each slot mode`() {
        codec.assertServerRoundTrip(
            UpdateInvPartial(
                65535, true,
                arrayOf(
                    InvSlotUpdate(0, 4151, 1, InvTooltip.Keep),
                    InvSlotUpdate(1, 995, 5, InvTooltip.Clear),
                    InvSlotUpdate(40, 1, 300, InvTooltip.Set("Hi")),
                    InvSlotUpdate(0x7FFF shr 2, 0, 255, InvTooltip.Set("")),
                    InvSlotUpdate(3, -1, 7, InvTooltip.Set("ignored")),
                ),
            ),
            UpdateInvPartial(
                65535, true,
                arrayOf(
                    InvSlotUpdate(0, 4151, 1, InvTooltip.Keep),
                    InvSlotUpdate(1, 995, 5, InvTooltip.Clear),
                    InvSlotUpdate(40, 1, 300, InvTooltip.Set("Hi")),
                    InvSlotUpdate(0x7FFF shr 2, 0, 255, InvTooltip.Set("")),
                    InvSlotUpdate(3, -1, 0, InvTooltip.Clear),
                ),
            ),
        )
    }

    @Test
    fun `partial inventory with no updates decodes empty`() {
        codec.assertServerRoundTrip(UpdateInvPartial(0, true, emptyArray()))
    }

    @Test
    fun `full inventory without tooltips decodes items and amounts`() {
        codec.assertServerRoundTrip(UpdateInvFull(93, false, intArrayOf(4151, -1, 995, 65534), intArrayOf(1, 0, Int.MAX_VALUE, 255)))
        codec.assertServerRoundTrip(UpdateInvFull(93, true, intArrayOf(4151, -1), intArrayOf(254, 0), arrayOfNulls(2)))
        codec.assertServerRoundTrip(UpdateInvFull(65535, false, IntArray(0), IntArray(0)))
    }

    @Test
    fun `full inventory decodes bitmask tooltips`() {
        val itemIds = IntArray(28) { if (it % 5 == 0) -1 else it + 100 }
        val amounts = IntArray(28) { it * 20 }
        val tooltips = Array(28) { if (it % 2 == 0) "tip $it" else null }
        val expected = Array(28) { if (it % 2 == 0 && it % 5 != 0) "tip $it" else null }
        codec.assertServerRoundTrip(UpdateInvFull(93, true, itemIds, amounts, tooltips), UpdateInvFull(93, true, itemIds, amounts, expected))
    }

    @Test
    fun `full inventory decodes sparse tooltips`() {
        val itemIds = IntArray(516) { it }
        val amounts = IntArray(516) { 1 }
        val tooltips = arrayOfNulls<String>(516).also { it[3] = "three"; it[515] = "" }
        codec.assertServerRoundTrip(UpdateInvFull(95, false, itemIds, amounts, tooltips))
    }

    @Test
    fun `full inventory tooltips only on empty slots decode as none`() {
        codec.assertServerRoundTrip(UpdateInvFull(93, false, intArrayOf(-1, 1), intArrayOf(0, 1), arrayOf("gone", null)), UpdateInvFull(93, false, intArrayOf(-1, 1), intArrayOf(0, 1)))
    }

    @Test
    fun `inventory stop transmit decodes key and container`() {
        codec.assertServerRoundTrip(UpdateInvStopTransmit(93, true))
        codec.assertServerRoundTrip(UpdateInvStopTransmit(65535, false))
    }

    @Test
    fun `stat decodes xp skill and level`() {
        codec.assertServerRoundTrip(UpdateStat(200_000_000, 24, 99))
        codec.assertServerRoundTrip(UpdateStat(0, 0, 1))
        codec.assertServerRoundTrip(UpdateStat(Int.MAX_VALUE, 255, 255))
    }

    @Test
    fun `grand exchange slot decodes an offer`() {
        codec.assertServerRoundTrip(UpdateGESlot(5, GrandExchangeOffer(13, 65535, Int.MAX_VALUE, 10_000, 5_000, -1)))
        codec.assertServerRoundTrip(UpdateGESlot(0, GrandExchangeOffer(255, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `grand exchange slot without progress decodes as cleared`() {
        codec.assertServerRoundTrip(UpdateGESlot(7, null))
        codec.assertServerRoundTrip(UpdateGESlot(3, GrandExchangeOffer(0, 4151, 100, 1, 0, 0)), UpdateGESlot(3, null))
    }

    @Test
    fun `uid192 decodes its data and drops the crc`() {
        val data = ByteArray(24) { (it * 31 - 100).toByte() }
        val bytes = codec.serverBytes(UpdateUid192(data))
        assertEquals(28, bytes.size)
        assertContentEquals(data, (codec.decodeServer(UpdateUid192(data), bytes) as UpdateUid192).data)
    }

    @Test
    fun `clan deltas decode the main flag and raw block`() {
        codec.assertServerRoundTrip(ClanChannelDelta(true, payload))
        codec.assertServerRoundTrip(ClanChannelDelta(false, ByteArray(0)))
        codec.assertServerRoundTrip(ClanSettingsDelta(true, payload))
        codec.assertServerRoundTrip(ClanSettingsDelta(false, ByteArray(0)))
    }

    @Test
    fun `clan channel full decodes a left channel`() {
        codec.assertServerRoundTrip(ClanChannelFull(main = true))
        codec.assertServerRoundTrip(ClanChannelFull(main = false))
    }

    @Test
    fun `clan channel full decodes header and chatters`() {
        val chatters = arrayOf(ClanChannelChatter("Alice", 126, 1), ClanChannelChatter("Bob", -1, 65535), ClanChannelChatter("", 127, 0))
        codec.assertServerRoundTrip(ClanChannelFull(false, "Drynor", Long.MIN_VALUE, -1, 127, chatters))
        codec.assertServerRoundTrip(ClanChannelFull(true, "", 0, 0, -1, null), ClanChannelFull(true, "", 0, 0, -1, emptyArray()))
    }

    @Test
    fun `clan settings full decodes a left clan`() {
        codec.assertServerRoundTrip(ClanSettingsFull(main = true))
        codec.assertServerRoundTrip(ClanSettingsFull(main = false))
    }

    @Test
    fun `clan settings full decodes members bans and settings`() {
        val members = Array(6) { ClanSettingsMember("Member$it", it * 25 - 1, it shl 28) }
        val settings = arrayOf(
            ClanVarSetting(0, intValue = -5),
            ClanVarSetting(0x3FFFFFFF, longValue = Long.MAX_VALUE),
            ClanVarSetting(12, stringValue = "motd"),
            ClanVarSetting(0x7FFFFFFF, intValue = 1),
            ClanVarSetting(13),
        )
        codec.assertServerRoundTrip(
            ClanSettingsFull(true, "Drynor", -1, true, -1, 126, members, arrayOf("griefer", ""), settings),
            ClanSettingsFull(
                true, "Drynor", -1, true, -1, 126, members, arrayOf("griefer", ""),
                arrayOf(settings[0], settings[1], settings[2], ClanVarSetting(0x3FFFFFFF, intValue = 1), ClanVarSetting(13, intValue = 0)),
            ),
        )
    }

    @Test
    fun `clan settings full preserves small rosters without invented members`() {
        val members = arrayOf(ClanSettingsMember("Owner", 126, 1), ClanSettingsMember("Alice", 0))
        codec.assertServerRoundTrip(
            ClanSettingsFull(false, "Small", 3, false, 0, 100, members, null, null),
            ClanSettingsFull(false, "Small", 3, false, 0, 100, members, emptyArray(), emptyArray()),
        )
    }

    @Test
    fun `raw clan settings block decodes as clan settings full`() {
        val block = Buffer().apply {
            writeByte(5)
            writeByte(1)
            writeInt(77)
            writeInt(4000)
            writeShort(1)
            writeByte(1)
            write("Hashed".toByteArray())
            writeByte(0)
            writeInt(123)
            writeByte(1)
            writeByte(-1)
            writeByte(2)
            writeByte(0)
            writeByte(0)
            writeLong(ALICE_BASE37)
            writeByte(126)
            writeInt(9)
            writeShort(321)
            writeLong(BOB_BASE37)
            writeShort(1)
            writeInt((2 shl 30) or 4)
            write("x".toByteArray())
            writeByte(0)
        }.readByteArray()
        val bytes = codec.serverBytes(ClanSettingsFullRaw(true, block))
        val source = Buffer().apply { write(bytes) }
        val decoded = runBlocking { codec.decodeServerProt(137, source) }
        assertTrue(source.exhausted())
        assertEquals(
            ClanSettingsFull(true, "Hashed", 77, true, -1, 2, arrayOf(ClanSettingsMember("alice", 126, 9)), arrayOf("bob"), arrayOf(ClanVarSetting(4, stringValue = "x"))),
            decoded,
        )
    }

    @Test
    fun `run energy and weight decode their values`() {
        codec.assertServerRoundTrip(RunEnergy(100))
        codec.assertServerRoundTrip(RunEnergy(0))
        codec.assertServerRoundTrip(RunEnergy(255))
        codec.assertServerRoundTrip(PlayerWeight(-32768))
        codec.assertServerRoundTrip(PlayerWeight(-5))
        codec.assertServerRoundTrip(PlayerWeight(32767))
    }

    @Test
    fun `player option decodes slot priority and cursor`() {
        codec.assertServerRoundTrip(PlayerOption("Attack", 1, top = true, cursor = -1))
        codec.assertServerRoundTrip(PlayerOption("null", 8, top = false, cursor = 0))
        codec.assertServerRoundTrip(PlayerOption("", 255, top = false, cursor = 65534))
    }

    @Test
    fun `attack priority target and dob decode their values`() {
        codec.assertServerRoundTrip(ReduceAttackPriority(true))
        codec.assertServerRoundTrip(ReduceAttackPriority(false))
        codec.assertServerRoundTrip(SetTarget(-1))
        codec.assertServerRoundTrip(SetTarget(-32768))
        codec.assertServerRoundTrip(SetTarget(2048))
        codec.assertServerRoundTrip(UpdateDob(0x7FFFFF, true))
        codec.assertServerRoundTrip(UpdateDob(-1, false))
        codec.assertServerRoundTrip(UpdateDob(0, false))
    }

    private companion object {
        const val ALICE_BASE37 = 1L * 37 * 37 * 37 * 37 + 12L * 37 * 37 * 37 + 9L * 37 * 37 + 3L * 37 + 5L
        const val BOB_BASE37 = 2L * 37 * 37 + 15L * 37 + 2L
    }
}
