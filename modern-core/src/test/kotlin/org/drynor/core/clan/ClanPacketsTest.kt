package org.drynor.core.clan

import kotlinx.coroutines.runBlocking
import org.drynor.core.model.Clan
import org.drynor.core.model.ClanMemberData
import org.drynor.core.model.ClanRank
import org.drynor.core.model.ClanSetting
import org.drynor.core.net.prot.ProtDebug
import org.drynor.core.net.prot.dto.ClanVarSetting
import org.drynor.core.net.prot.packet.VarclanEnable
import org.drynor.core.net.prot.packet.VarclanSetByte
import org.drynor.core.net.prot.packet.VarclanSetInt
import org.drynor.core.net.prot.packet.VarclanSetLong
import org.drynor.core.net.prot.packet.VarclanSetString
import org.drynor.core.net.prot.revision.rev727.register727
import org.drynor.core.social.QuickChatMessage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClanPacketsTest {

    @BeforeTest
    fun registerCodec() {
        register727()
    }

    private fun clan(): Clan = Clan(
        name = "Drynor",
        leaderUsername = "owner",
        members = mutableMapOf(
            "zed" to ClanMemberData(ClanRank.RECRUIT, joinDate = 20),
            "owner" to ClanMemberData(ClanRank.OWNER, joinDate = 1),
            "amy" to ClanMemberData(ClanRank.ADMIN, joinDate = 20),
        ),
        bannedUsers = mutableSetOf("troll", "bot"),
        settings = mutableMapOf(
            ClanSetting.MOTTO.name to "Hi there",
            ClanSetting.FORUM_QFC.name to 123456789012L,
            ClanSetting.MOTIF_TOP_ICON.name to 3,
            ClanSetting.GUESTS_CAN_ENTER_CC.name to 1,
            ClanSetting.RECRUIT_PERMISSION_LOCK_KEEP.name to 1,
            "NOT_A_SETTING" to 9,
        ),
        vars = mutableMapOf("0" to "motto var", "1" to 300, "2" to 5, "3" to 7_000_000_000L, "99" to 1),
        ccChatRank = ClanRank.CORPORAL,
        ccKickRank = ClanRank.ADMIN,
    )

    @Test
    fun `settings full carries the clan in join order with client setting ids`() {
        val packet = ClanPackets.settingsFull(clan(), main = true, updateCount = 4) { it.uppercase() }
        assertTrue(packet.main)
        assertEquals("Drynor", packet.clanName)
        assertEquals(4, packet.updateCount)
        assertTrue(packet.allowGuests)
        assertEquals(ClanRank.CORPORAL.iconId, packet.talkRank)
        assertEquals(ClanRank.ADMIN.iconId, packet.kickRank)

        val members = assertNotNull(packet.members)
        assertEquals(listOf("OWNER", "AMY", "ZED"), members.map { it.displayName })
        assertEquals(listOf(ClanRank.OWNER.iconId, ClanRank.ADMIN.iconId, ClanRank.RECRUIT.iconId), members.map { it.rank })
        assertEquals(listOf("BOT", "TROLL"), assertNotNull(packet.bannedUsers).toList())

        val settings = assertNotNull(packet.settings).associateBy { it.key }
        assertEquals(4, settings.size, "no-client-id and unknown settings are dropped")
        assertEquals(ClanVarSetting(key = ClanSetting.MOTTO.id, stringValue = "Hi there"), settings[ClanSetting.MOTTO.id])
        assertEquals(ClanVarSetting(key = ClanSetting.FORUM_QFC.id, longValue = 123456789012L), settings[ClanSetting.FORUM_QFC.id])
        assertEquals(ClanVarSetting(key = ClanSetting.MOTIF_TOP_ICON.id, intValue = 3), settings[ClanSetting.MOTIF_TOP_ICON.id])
        assertEquals(ClanVarSetting(key = ClanSetting.GUESTS_CAN_ENTER_CC.id, intValue = 1), settings[ClanSetting.GUESTS_CAN_ENTER_CC.id])
    }

    @Test
    fun `allow guests follows the guests can enter setting not the talk rank`() {
        val clan = clan()
        clan.setSetting(ClanSetting.GUESTS_CAN_ENTER_CC, 0)
        clan.ccChatRank = ClanRank.NONE
        assertFalse(ClanPackets.settingsFull(clan, main = false).allowGuests)
        assertFalse(ClanPackets.settingsFull(clan, main = false).main)
    }

    @Test
    fun `the one byte forms carry no clan`() {
        val settings = ClanPackets.settingsLeft(main = true)
        assertTrue(settings.main)
        assertNull(settings.clanName)
        assertNull(settings.members)
        val channel = ClanPackets.channelLeft(main = false)
        assertFalse(channel.main)
        assertNull(channel.clanName)
        assertNull(channel.chatters)
        val bytes = runBlocking { ProtDebug.encodeServerBody(727, settings) }
        assertContentEquals(byteArrayOf(1), bytes)
    }

    @Test
    fun `the codec writes version 3 with only the three actual members`() {
        val bytes = runBlocking { ProtDebug.encodeServerBody(727, ClanPackets.settingsFull(clan(), main = true)) }
        assertEquals(1, bytes[0].toInt(), "main")
        assertEquals(3, bytes[1].toInt(), "version")
        assertEquals(2, bytes[2].toInt(), "names as strings")
        val totalSlots = (bytes[11].toInt() and 0xFF shl 8) or (bytes[12].toInt() and 0xFF)
        assertEquals(3, totalSlots, "only actual members")
        assertEquals(2, bytes[13].toInt(), "banned count")
    }

    @Test
    fun `channel full mirrors the channel state`() {
        val state = ClanChannelState(
            clanName = "Drynor",
            updateNum = 7,
            talkRank = ClanRank.NONE,
            kickRank = ClanRank.OWNER,
            chatters = listOf(
                ClanChatter("owner", "Owner", ClanRank.OWNER, 300),
                ClanChatter("guest", "Guest", ClanRank.NONE, ClanChatter.LOBBY_WORLD_ID),
            ),
        )
        val packet = ClanPackets.channelFull(state, main = false)
        assertFalse(packet.main)
        assertEquals("Drynor", packet.clanName)
        assertEquals(7, packet.updateNum)
        assertEquals(ClanRank.NONE.iconId, packet.talkRank)
        assertEquals(ClanRank.OWNER.iconId, packet.kickRank)
        val chatters = assertNotNull(packet.chatters)
        assertEquals(listOf("Owner", "Guest"), chatters.map { it.displayName })
        assertEquals(listOf(ClanRank.OWNER.iconId, ClanRank.NONE.iconId), chatters.map { it.rank })
        assertEquals(listOf(300, 1115), chatters.map { it.worldId })
        assertTrue(state.chatters[1].guest)
        assertTrue("guest" in state)
        assertFalse("nobody" in state)
    }

    @Test
    fun `clan var packets enable then set each var by its cache type`() {
        val kinds = mapOf(0 to ClanVarKind.STRING, 1 to ClanVarKind.INT, 2 to ClanVarKind.INT, 3 to ClanVarKind.LONG)
        val packets = ClanPackets.clanVarPackets(clan()) { kinds[it] }
        assertEquals(VarclanEnable(), packets.first())
        val sets = packets.drop(1).associateBy {
            when (it) {
                is VarclanSetString -> it.id
                is VarclanSetInt -> it.id
                is VarclanSetByte -> it.id
                is VarclanSetLong -> it.id
                else -> error("unexpected $it")
            }
        }
        assertEquals(setOf(0, 1, 2, 3), sets.keys, "var 99 has no definition and is skipped")
        assertEquals(VarclanSetString(0, "motto var"), sets[0])
        assertEquals(VarclanSetInt(1, 300), sets[1])
        assertEquals(VarclanSetByte(2, 5), sets[2])
        assertEquals(VarclanSetLong(3, 7_000_000_000L), sets[3])
        assertNull(ClanPackets.clanVarPacket(5, "text", ClanVarKind.INT), "a string does not fit an int var")
    }

    @Test
    fun `chat packets carry the recipient's guest flag`() {
        val member = ClanPackets.chatMessage(guest = false, crown = 2, displayName = "Owner", text = "hello")
        assertFalse(member.guest)
        assertEquals(2, member.crown)
        assertEquals("Owner", member.displayName)
        assertEquals("hello", member.message)
        val guest = ClanPackets.chatMessage(guest = true, crown = 0, displayName = "Owner", text = "hello")
        assertTrue(guest.guest)

        val qc = QuickChatMessage(message = "", fileId = 12, data = byteArrayOf(1, 2))
        val quick = ClanPackets.quickChatMessage(guest = true, crown = 1, displayName = "Owner", message = qc)
        assertTrue(quick.guest)
        assertEquals(qc, quick.message)
    }
}
