package org.drynor.core.clan

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.drynor.core.model.Clan
import org.drynor.core.model.ClanMemberData
import org.drynor.core.model.ClanRank
import org.drynor.core.model.ClanSetting
import org.drynor.core.social.PublicChatMessage
import org.drynor.core.social.QuickChatMessage
import org.drynor.core.social.gateway.ClanChannelUpdate
import org.drynor.core.social.gateway.ClanChatMessage
import org.drynor.core.social.gateway.ClanCommand
import org.drynor.core.social.gateway.ClanCommandResult
import org.drynor.core.social.gateway.ClanDeleted
import org.drynor.core.social.gateway.ClanFetch
import org.drynor.core.social.gateway.ClanMembership
import org.drynor.core.social.gateway.ClanQuickChatMessage
import org.drynor.core.social.gateway.ClanSnapshot
import org.drynor.core.social.gateway.SocialGatewayWireJson
import org.drynor.core.social.gateway.SocialGatewayWireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClanGatewayProtocolTest {

    private val json = SocialGatewayWireJson.json

    private fun roundTrip(message: SocialGatewayWireMessage): SocialGatewayWireMessage {
        val encoded = json.encodeToString(SocialGatewayWireMessage.serializer(), message)
        return json.decodeFromString(SocialGatewayWireMessage.serializer(), encoded)
    }

    private val actions: List<ClanAction> = listOf(
        ClanAction.CheckName("Drynor"),
        ClanAction.Create("Drynor"),
        ClanAction.Leave,
        ClanAction.AcceptInvite("Drynor", "owner"),
        ClanAction.RemoveMember("bob"),
        ClanAction.UpdateMember("bob", rank = ClanRank.CAPTAIN, job = 4, banFromKeep = true, banFromCitadel = false, banFromIsland = null),
        ClanAction.UpdateMember("bob"),
        ClanAction.Ban("bob"),
        ClanAction.Unban("bob"),
        ClanAction.UpdateSettings(
            mapOf(
                ClanSetting.MOTTO.name to "Hello",
                ClanSetting.FORUM_QFC.name to 123456789012L,
                ClanSetting.MOTIF_TOP_ICON.name to 3,
            ),
        ),
        ClanAction.SetChannelRanks(talkRank = ClanRank.NONE, kickRank = ClanRank.ADMIN),
        ClanAction.SetChannelRanks(),
        ClanAction.SetVar(0, "motto"),
        ClanAction.SetVar(1, 300),
        ClanAction.SetVar(3, 7_000_000_000L),
        ClanAction.JoinChannel,
        ClanAction.LeaveChannel,
        ClanAction.JoinGuestChannel("Drynor"),
        ClanAction.LeaveGuestChannel,
        ClanAction.KickFromChannel("bob", guest = true),
        ClanAction.Chat(guest = false, message = PublicChatMessage("hello", effects = 5)),
        ClanAction.QuickChat(guest = true, message = QuickChatMessage("", fileId = 12, data = byteArrayOf(1, 2, 3))),
    )

    @Test
    fun `every clan action round trips inside a clan command`() {
        for (action in actions) {
            val command = ClanCommand(username = "bob", requestId = 7_000_000_017L, action = action)
            val decoded = assertIs<ClanCommand>(roundTrip(command))
            assertEquals(command, decoded, "round trip of ${action.serialName}")
        }
    }

    @Test
    fun `serial name matches the wire discriminator and covers every action`() {
        val seen = HashSet<String>()
        for (action in actions) {
            val element = json.encodeToJsonElement(ClanAction.serializer(), action).jsonObject
            assertEquals(action.serialName, element.getValue("_type").jsonPrimitive.content)
            seen += action.serialName
        }
        assertEquals(18, seen.size, "one serial name per action class")
    }

    @Test
    fun `clan events round trip`() {
        val events = listOf(
            ClanFetch("Drynor"),
            ClanCommandResult("bob", 17, "Create", ok = true),
            ClanCommandResult("bob", 18, "Ban", ok = false, message = "You do not have permission to ban users."),
            ClanDeleted("Drynor"),
            ClanChannelUpdate(
                ClanChannelState(
                    clanName = "Drynor",
                    updateNum = 9,
                    talkRank = ClanRank.NONE,
                    kickRank = ClanRank.ADMIN,
                    chatters = listOf(
                        ClanChatter("owner", "Owner", ClanRank.OWNER, 300),
                        ClanChatter("guest", "Guest", ClanRank.NONE, ClanChatter.LOBBY_WORLD_ID),
                    ),
                ),
            ),
            ClanMembership("bob", clanName = "Drynor", inChannel = true, guestChannel = null),
            ClanMembership("bob", clanName = null, inChannel = false, guestChannel = "Other"),
            ClanChatMessage("Drynor", "Owner", rights = 2, message = PublicChatMessage("hi", effects = 1)),
            ClanQuickChatMessage("Drynor", "Owner", rights = 0, message = QuickChatMessage("", fileId = 3, data = byteArrayOf(9))),
        )
        for (event in events) {
            assertEquals(event, roundTrip(event), "round trip of ${event::class.simpleName}")
        }
    }

    @Test
    fun `a clan snapshot keeps settings vars and members with their types`() {
        val clan = Clan(
            name = "Drynor",
            leaderUsername = "owner",
            members = mutableMapOf(
                "owner" to ClanMemberData(ClanRank.OWNER, joinDate = 1, job = 2, banFromKeep = true, firstWeek = true),
                "bob" to ClanMemberData(ClanRank.RECRUIT, joinDate = 50),
            ),
            bannedUsers = mutableSetOf("troll"),
            settings = mutableMapOf(
                ClanSetting.MOTTO.name to "Hello",
                ClanSetting.FORUM_QFC.name to 123456789012L,
                ClanSetting.MOTIF_TOP_ICON.name to 3,
            ),
            vars = mutableMapOf("0" to "motto", "1" to 300, "3" to 7_000_000_000L),
            ccChatRank = ClanRank.CORPORAL,
            ccKickRank = ClanRank.OWNER,
            motto = "Old motto field",
        )
        val decoded = assertIs<ClanSnapshot>(roundTrip(ClanSnapshot(clan))).clan
        assertEquals(clan.name, decoded.name)
        assertEquals(clan.leaderUsername, decoded.leaderUsername)
        assertEquals(clan.members, decoded.members)
        assertEquals(clan.bannedUsers, decoded.bannedUsers)
        assertEquals(clan.settings, decoded.settings)
        assertEquals(clan.vars, decoded.vars)
        assertEquals(clan.ccChatRank, decoded.ccChatRank)
        assertEquals(clan.ccKickRank, decoded.ccKickRank)
        assertEquals(clan.motto, decoded.motto)
        assertTrue(decoded.settings[ClanSetting.FORUM_QFC.name] is Long)
        assertTrue(decoded.settings[ClanSetting.MOTIF_TOP_ICON.name] is Int)
        assertTrue(decoded.settings[ClanSetting.MOTTO.name] is String)
        assertTrue(decoded.vars["3"] is Long)
        assertTrue(decoded.vars["1"] is Int)
    }
}
