package org.darkan.core.clan

import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.model.ClanRank
import org.darkan.core.model.ClanSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClanRulesTest {

    private fun clan(
        vararg members: Pair<String, ClanRank>,
        chatRank: ClanRank = ClanRank.NONE,
        kickRank: ClanRank = ClanRank.ADMIN,
        banned: Set<String> = emptySet(),
    ): Clan = Clan(
        name = "Darkan",
        leaderUsername = "owner",
        members = mutableMapOf("owner" to ClanMemberData(ClanRank.OWNER, joinDate = 1)).apply {
            members.forEachIndexed { i, (name, rank) -> put(name, ClanMemberData(rank, joinDate = 10L + i)) }
        },
        bannedUsers = banned.toMutableSet(),
        ccChatRank = chatRank,
        ccKickRank = kickRank,
    )

    private fun channel(clan: Clan, vararg usernames: String) = ClanChannelState(
        clanName = clan.name,
        updateNum = 1,
        talkRank = clan.ccChatRank,
        kickRank = clan.ccKickRank,
        chatters = usernames.map { ClanChatter(it, it, ClanRules.rankOf(clan, it), 1) },
    )

    private fun assertDenied(decision: ClanDecision, message: String) {
        val denied = assertIs<ClanDecision.Denied>(decision)
        assertEquals(message, denied.message)
    }

    private fun assertAllowed(decision: ClanDecision) = assertEquals(ClanDecision.Allowed, decision)

    // ─── Names ───────────────────────────────────────────────────────

    @Test
    fun `valid names pass and invalid names carry the legacy message`() {
        assertAllowed(ClanRules.validateName("Darkan"))
        assertAllowed(ClanRules.validateName("Clan 727 x"))
        assertAllowed(ClanRules.validateName("A"))
        assertAllowed(ClanRules.validateName("abcdefghijkl"))
        val message = "Clan names must be 12 or fewer characters and be alphanumeric."
        assertDenied(ClanRules.validateName(""), message)
        assertDenied(ClanRules.validateName("abcdefghijklm"), message)
        assertDenied(ClanRules.validateName(" Darkan"), message)
        assertDenied(ClanRules.validateName("Darkan "), message)
        assertDenied(ClanRules.validateName("Dar  kan"), message)
        assertDenied(ClanRules.validateName("Dark-an"), message)
        assertDenied(ClanRules.validateName("Därkan"), message)
    }

    @Test
    fun `check name and create need an actor without a clan`() {
        assertAllowed(ClanRules.checkName(null, "Darkan"))
        assertAllowed(ClanRules.create(null, "Darkan"))
        assertDenied(
            ClanRules.create("Other", "Darkan"),
            "You are already in the clan Other. You must leave before joining a new one.",
        )
        assertIs<ClanDecision.Denied>(ClanRules.checkName(null, "toolongforaclan"))
    }

    // ─── Membership ──────────────────────────────────────────────────

    @Test
    fun `leave is for members only`() {
        val clan = clan("bob" to ClanRank.RECRUIT)
        assertAllowed(ClanRules.leave(clan, "bob"))
        assertAllowed(ClanRules.leave(clan, "owner"))
        assertDenied(ClanRules.leave(clan, "nobody"), "You aren't in a clan.")
    }

    @Test
    fun `succession prefers the deputy then the highest rank then the earliest join`() {
        val clan = clan("gen" to ClanRank.GENERAL, "dep" to ClanRank.DEPUTY_OWNER, "adm" to ClanRank.ADMIN)
        assertEquals("dep", ClanRules.successor(clan))
        clan.members.remove("dep")
        assertEquals("adm", ClanRules.successor(clan))
        clan.members.remove("adm")
        clan.members["gen2"] = ClanMemberData(ClanRank.GENERAL, joinDate = 5)
        assertEquals("gen2", ClanRules.successor(clan))
        clan.members["staff"] = ClanMemberData(ClanRank.JMOD, joinDate = 0)
        assertEquals("gen2", ClanRules.successor(clan))
        assertNull(ClanRules.successor(clan()))
    }

    @Test
    fun `accept invite checks clan membership bans inviter permission and room`() {
        val clan = clan("gen" to ClanRank.GENERAL, "rec" to ClanRank.RECRUIT, banned = setOf("banned"))
        assertAllowed(ClanRules.acceptInvite(clan, "new", null, "gen"))
        assertAllowed(ClanRules.acceptInvite(clan, "new", null, "owner"))
        assertDenied(
            ClanRules.acceptInvite(clan, "new", "Other", "gen"),
            "You are already in the clan Other. You must leave before joining a new one.",
        )
        assertDenied(ClanRules.acceptInvite(clan, "banned", null, "gen"), "You have been banned from that clan.")
        assertDenied(
            ClanRules.acceptInvite(clan, "new", null, "rec"),
            "That player does not have permission to invite you to the clan.",
        )
        assertDenied(
            ClanRules.acceptInvite(clan, "new", null, "stranger"),
            "That player does not have permission to invite you to the clan.",
        )
        clan.setSetting(ClanSetting.RECRUIT_PERMISSION_INVITE, 1)
        assertAllowed(ClanRules.acceptInvite(clan, "new", null, "rec"))
        repeat(Clan.MAX_MEMBERS) { clan.members["m$it"] = ClanMemberData(ClanRank.RECRUIT) }
        assertDenied(ClanRules.acceptInvite(clan, "new", null, "gen"), "That clan is full.")
    }

    @Test
    fun `remove member needs an admin above the target and never the owner`() {
        val clan = clan("adm" to ClanRank.ADMIN, "adm2" to ClanRank.ADMIN, "rec" to ClanRank.RECRUIT, "gen" to ClanRank.GENERAL)
        assertAllowed(ClanRules.removeMember(clan, "adm", "rec"))
        assertAllowed(ClanRules.removeMember(clan, "owner", "adm"))
        assertDenied(ClanRules.removeMember(clan, "gen", "rec"), "Only clan admins can kick clan members.")
        assertDenied(ClanRules.removeMember(clan, "nobody", "rec"), "You must be in a clan to do that.")
        assertDenied(ClanRules.removeMember(clan, "adm", "stranger"), "That player is not a clan member.")
        assertDenied(ClanRules.removeMember(clan, "adm", "owner"), "The clan owner cannot be kicked from the clan.")
        assertDenied(ClanRules.removeMember(clan, "adm", "adm2"), "You can only kick players with lesser rank than you.")
        assertDenied(ClanRules.removeMember(clan, "adm", "adm"), "You can only kick players with lesser rank than you.")
    }

    @Test
    fun `update member enforces rank ordering and the assignable ranks`() {
        val clan = clan("adm" to ClanRank.ADMIN, "adm2" to ClanRank.ADMIN, "rec" to ClanRank.RECRUIT, "gen" to ClanRank.GENERAL)
        assertAllowed(ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("rec", rank = ClanRank.GENERAL)))
        assertAllowed(ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("rec", job = 3, banFromKeep = true)))
        assertAllowed(ClanRules.updateMember(clan, "owner", ClanAction.UpdateMember("adm", rank = ClanRank.DEPUTY_OWNER)))
        assertDenied(
            ClanRules.updateMember(clan, "gen", ClanAction.UpdateMember("rec", rank = ClanRank.CORPORAL)),
            "Only clan admins can edit ranks and jobs.",
        )
        assertDenied(
            ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("stranger", rank = ClanRank.CORPORAL)),
            "That player is not a clan member.",
        )
        for (rank in listOf(ClanRank.OWNER, ClanRank.JMOD, ClanRank.NONE)) {
            assertDenied(
                ClanRules.updateMember(clan, "owner", ClanAction.UpdateMember("rec", rank = rank)),
                "That rank cannot be assigned.",
            )
        }
        assertDenied(
            ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("adm2", rank = ClanRank.RECRUIT)),
            "You can only edit the rank of players with lesser rank than you.",
        )
        assertDenied(
            ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("rec", rank = ClanRank.ADMIN)),
            "You cannot promote someone to your own rank or higher.",
        )
        assertDenied(
            ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("rec", rank = ClanRank.DEPUTY_OWNER)),
            "You cannot promote someone to your own rank or higher.",
        )
    }

    @Test
    fun `an owner may only demote themself with a deputy owner in place`() {
        val clan = clan("adm" to ClanRank.ADMIN)
        assertDenied(
            ClanRules.updateMember(clan, "owner", ClanAction.UpdateMember("owner", rank = ClanRank.ADMIN)),
            "You are currently the clan owner, you must at least have one deputy owner in the clan to demote yourself.",
        )
        clan.members["dep"] = ClanMemberData(ClanRank.DEPUTY_OWNER)
        assertAllowed(ClanRules.updateMember(clan, "owner", ClanAction.UpdateMember("owner", rank = ClanRank.ADMIN)))
        assertDenied(
            ClanRules.updateMember(clan, "adm", ClanAction.UpdateMember("adm", rank = ClanRank.RECRUIT)),
            "You can only edit the rank of players with lesser rank than you.",
        )
        assertDenied(
            ClanRules.updateMember(clan, "owner", ClanAction.UpdateMember("owner", job = 2)),
            "You can only edit the rank of players with lesser rank than you.",
        )
    }

    // ─── Bans ────────────────────────────────────────────────────────

    @Test
    fun `ban and unban follow the channel kick rank`() {
        val clan = clan("adm" to ClanRank.ADMIN, "adm2" to ClanRank.ADMIN, "rec" to ClanRank.RECRUIT, banned = setOf("out"))
        assertAllowed(ClanRules.ban(clan, "adm", "rec"))
        assertAllowed(ClanRules.ban(clan, "adm", "guest"))
        assertAllowed(ClanRules.ban(clan, "owner", "adm"))
        assertDenied(ClanRules.ban(clan, "rec", "guest"), "You do not have permission to ban users.")
        assertDenied(ClanRules.ban(clan, "stranger", "guest"), "You do not have permission to ban users.")
        assertDenied(ClanRules.ban(clan, "adm", "adm"), "You cannot ban yourself.")
        assertDenied(ClanRules.ban(clan, "adm", "owner"), "The clan owner cannot be banned.")
        assertDenied(ClanRules.ban(clan, "adm", "out"), "That player is already banned.")
        assertDenied(ClanRules.ban(clan, "adm", "adm2"), "You cannot ban that user.")

        assertAllowed(ClanRules.unban(clan, "adm", "out"))
        assertDenied(ClanRules.unban(clan, "rec", "out"), "You do not have permission to unban users.")
        assertDenied(ClanRules.unban(clan, "adm", "guest"), "That player is not banned.")

        val lenient = clan("rec" to ClanRank.RECRUIT, kickRank = ClanRank.RECRUIT)
        assertAllowed(ClanRules.ban(lenient, "rec", "guest"))
        assertDenied(ClanRules.ban(lenient, "rec", "owner"), "The clan owner cannot be banned.")
    }

    // ─── Settings ────────────────────────────────────────────────────

    @Test
    fun `settings delta types every entry and rejects the whole map on one bad entry`() {
        val clan = clan()
        val delta = ClanRules.settingsDelta(
            clan,
            mapOf(
                ClanSetting.MOTTO.name to "Hello",
                ClanSetting.FORUM_QFC.name to 42,
                ClanSetting.MOTIF_TOP_ICON.name to 7,
                ClanSetting.IS_RECRUITING.name to 1,
            ),
        ).getOrThrow()
        assertEquals("Hello", delta[ClanSetting.MOTTO])
        assertEquals(42L, delta[ClanSetting.FORUM_QFC])
        assertEquals(7, delta[ClanSetting.MOTIF_TOP_ICON])
        assertTrue(ClanSetting.IS_RECRUITING !in delta, "an unchanged entry is not part of the delta")

        assertTrue(ClanRules.settingsDelta(clan, mapOf("NOT_A_SETTING" to 1)).isFailure)
        assertTrue(ClanRules.settingsDelta(clan, mapOf(ClanSetting.MOTTO.name to 1)).isFailure)
        assertTrue(ClanRules.settingsDelta(clan, mapOf(ClanSetting.MOTIF_TOP_ICON.name to "x")).isFailure)
        assertTrue(ClanRules.settingsDelta(clan, mapOf(ClanSetting.MOTIF_TOP_ICON.name to 5L)).isFailure)
        val mixed = ClanRules.settingsDelta(clan, mapOf(ClanSetting.MOTTO.name to "ok", "BAD" to 1))
        assertEquals("Unknown clan setting: BAD.", mixed.exceptionOrNull()?.message)
    }

    @Test
    fun `update settings channel ranks and vars are admin only`() {
        val clan = clan("adm" to ClanRank.ADMIN, "gen" to ClanRank.GENERAL)
        assertAllowed(ClanRules.updateSettings(clan, "adm", mapOf(ClanSetting.MOTTO.name to "x")))
        assertDenied(
            ClanRules.updateSettings(clan, "gen", mapOf(ClanSetting.MOTTO.name to "x")),
            "Only clan admins can edit the clan settings.",
        )
        assertDenied(ClanRules.updateSettings(clan, "adm", mapOf("BAD" to "x")), "Unknown clan setting: BAD.")

        assertAllowed(ClanRules.setChannelRanks(clan, "adm", ClanRank.NONE, ClanRank.OWNER))
        assertAllowed(ClanRules.setChannelRanks(clan, "owner", null, ClanRank.GENERAL))
        assertDenied(
            ClanRules.setChannelRanks(clan, "gen", ClanRank.RECRUIT, null),
            "Only clan admins can edit the clan settings.",
        )
        assertDenied(ClanRules.setChannelRanks(clan, "adm", ClanRank.JMOD, null), "That rank cannot be required.")

        assertAllowed(ClanRules.setVar(clan, "adm"))
        assertDenied(ClanRules.setVar(clan, "gen"), "You do not have permission to change clan settings.")
        assertDenied(ClanRules.setVar(clan, "stranger"), "You do not have permission to change clan settings.")
    }

    // ─── Channels ────────────────────────────────────────────────────

    @Test
    fun `join and leave channel need a member`() {
        val clan = clan("rec" to ClanRank.RECRUIT)
        assertAllowed(ClanRules.joinChannel(clan, "rec"))
        assertAllowed(ClanRules.joinChannel(clan, "owner"))
        assertDenied(ClanRules.joinChannel(clan, "stranger"), "You must be in a clan to do that.")
        assertDenied(ClanRules.joinChannel(null, "rec"), "You must be in a clan to do that.")

        val inChannel = channel(clan, "rec")
        assertAllowed(ClanRules.leaveChannel(clan, inChannel, "rec"))
        assertDenied(ClanRules.leaveChannel(clan, inChannel, "owner"), "You are not currently connected to a clan chat.")
        assertDenied(ClanRules.leaveChannel(clan, null, "rec"), "You are not currently connected to a clan chat.")
        assertDenied(ClanRules.leaveChannel(null, inChannel, "rec"), "You must be in a clan to do that.")
    }

    @Test
    fun `guest channel join needs an open clan and a clean record`() {
        val clan = clan("rec" to ClanRank.RECRUIT, banned = setOf("out"))
        assertAllowed(ClanRules.joinGuestChannel(clan, "guest", tempBanned = false))
        assertDenied(ClanRules.joinGuestChannel(null, "guest", false), "The clan chat you tried to join does not exist.")
        assertDenied(ClanRules.joinGuestChannel(clan, "rec", false), "You are already a member of that clan.")
        assertDenied(ClanRules.joinGuestChannel(clan, "out", false), "You have been banned from that clan.")
        assertDenied(ClanRules.joinGuestChannel(clan, "guest", true), "You are temporarily banned from this clan channel.")
        clan.setSetting(ClanSetting.GUESTS_CAN_ENTER_CC, 0)
        assertDenied(ClanRules.joinGuestChannel(clan, "guest", false), "This clan doesn't allow guests.")

        val listening = channel(clan, "guest")
        assertAllowed(ClanRules.leaveGuestChannel(listening, "guest"))
        assertDenied(ClanRules.leaveGuestChannel(listening, "other"), "You are not currently connected to a clan chat.")
        assertDenied(ClanRules.leaveGuestChannel(null, "guest"), "You are not currently connected to a clan chat.")
    }

    @Test
    fun `kick from channel needs the kick rank and a lower or guest target`() {
        val clan = clan("adm" to ClanRank.ADMIN, "adm2" to ClanRank.ADMIN, "rec" to ClanRank.RECRUIT)
        val state = channel(clan, "owner", "adm", "adm2", "rec", "guest")
        assertAllowed(ClanRules.kickFromChannel(clan, state, "adm", "rec"))
        assertAllowed(ClanRules.kickFromChannel(clan, state, "adm", "guest"))
        assertAllowed(ClanRules.kickFromChannel(clan, state, "owner", "adm"))
        assertDenied(ClanRules.kickFromChannel(clan, state, "rec", "guest"), "You do not have permission to kick from this clan chat.")
        assertDenied(ClanRules.kickFromChannel(clan, state, "guest", "rec"), "You do not have permission to kick from this clan chat.")
        assertDenied(ClanRules.kickFromChannel(clan, state, "adm", "adm2"), "You cannot kick that user.")
        assertDenied(ClanRules.kickFromChannel(clan, state, "adm", "owner"), "You cannot kick that user.")
        assertDenied(ClanRules.kickFromChannel(clan, state, "adm", "adm"), "You cannot kick yourself.")
        assertDenied(ClanRules.kickFromChannel(clan, state, "adm", "absent"), "That player is not in the clan chat.")
        assertDenied(ClanRules.kickFromChannel(clan, null, "adm", "rec"), "You are not currently connected to a clan chat.")
        assertDenied(ClanRules.kickFromChannel(clan, channel(clan, "rec"), "adm", "rec"), "You are not currently connected to a clan chat.")

        val lenient = clan("rec" to ClanRank.RECRUIT, kickRank = ClanRank.RECRUIT)
        assertAllowed(ClanRules.kickFromChannel(lenient, channel(lenient, "rec", "guest"), "rec", "guest"))
    }

    @Test
    fun `chat follows the talk rank for members and the guest setting for guests`() {
        val clan = clan("gen" to ClanRank.GENERAL, "rec" to ClanRank.RECRUIT, chatRank = ClanRank.GENERAL)
        val state = channel(clan, "owner", "gen", "rec", "guest")
        assertAllowed(ClanRules.chat(clan, state, "gen", muted = false))
        assertAllowed(ClanRules.chat(clan, state, "owner", muted = false))
        assertAllowed(ClanRules.chat(clan, state, "guest", muted = false))
        assertDenied(ClanRules.chat(clan, state, "rec", false), "You are not allowed to speak in that clan chat.")
        assertDenied(ClanRules.chat(clan, state, "gen", true), "You are muted.")
        assertDenied(ClanRules.chat(clan, null, "gen", false), "You are not currently connected to a clan chat.")
        assertDenied(ClanRules.chat(clan, state, "absent", false), "You are not currently connected to a clan chat.")
        clan.setSetting(ClanSetting.GUESTS_CAN_TALK_CC, 0)
        assertDenied(ClanRules.chat(clan, state, "guest", false), "You are not allowed to speak in that clan chat.")
        assertAllowed(ClanRules.chat(clan, state, "gen", false))

        val open = clan("rec" to ClanRank.RECRUIT, chatRank = ClanRank.NONE)
        assertAllowed(ClanRules.chat(open, channel(open, "rec"), "rec", false))
    }

    @Test
    fun `rank of treats the leader as owner whatever the entry says`() {
        val clan = clan("rec" to ClanRank.RECRUIT)
        clan.members["owner"] = ClanMemberData(ClanRank.RECRUIT)
        assertEquals(ClanRank.OWNER, ClanRules.rankOf(clan, "owner"))
        assertEquals(ClanRank.RECRUIT, ClanRules.rankOf(clan, "rec"))
        assertEquals(ClanRank.NONE, ClanRules.rankOf(clan, "stranger"))
        assertTrue(ClanRules.isMember(clan, "owner"))
        assertTrue(!ClanRules.isMember(clan, "stranger"))
    }
}
