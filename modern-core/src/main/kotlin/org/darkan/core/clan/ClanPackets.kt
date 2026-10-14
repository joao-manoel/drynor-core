package org.darkan.core.clan

import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.model.ClanSetting
import org.darkan.core.model.NO_CLIENT_ID
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.ClanChannelChatter
import org.darkan.core.net.prot.dto.ClanSettingsMember
import org.darkan.core.net.prot.dto.ClanVarSetting
import org.darkan.core.net.prot.packet.ClanChannelFull
import org.darkan.core.net.prot.packet.ClanSettingsFull
import org.darkan.core.net.prot.packet.MessageClanChannel
import org.darkan.core.net.prot.packet.MessageQuickChatClanChannel
import org.darkan.core.net.prot.packet.VarclanEnable
import org.darkan.core.net.prot.packet.VarclanSetByte
import org.darkan.core.net.prot.packet.VarclanSetInt
import org.darkan.core.net.prot.packet.VarclanSetLong
import org.darkan.core.net.prot.packet.VarclanSetString
import org.darkan.core.social.QuickChatMessage
import world.gregs.voidps.cache.Cache

/** The stack type of a clan var, which picks the `VarclanSet*` packet that carries it. */
enum class ClanVarKind { INT, LONG, STRING }

/**
 * The client packets both servers build from the shared clan state, so a member in a world and a
 * member on the lobby screen see exactly the same thing. Every builder is pure; the codec in
 * `Rev727ServerCodecsUpdate` writes the version byte, the filler rows and the setting type tags.
 */
object ClanPackets {

    /**
     * `ClanSettingsFull` for [clan]. [main] is true for a member's own clan, false for a guest or
     * an interface that only looks at the clan. [displayName] turns a username into what the
     * client shows; the lobby resolves it from the accounts, a world from its players. Members are
     * in join order (then username) so every send lists them the same way; the codec pads to the
     * client's five-row minimum with filler rows.
     */
    fun settingsFull(
        clan: Clan,
        main: Boolean,
        updateCount: Int = 0,
        displayName: (String) -> String = { it.formatPlayerNameForDisplay() },
    ): ClanSettingsFull {
        val members = clan.members.entries
            .sortedWith(compareBy<Map.Entry<String, ClanMemberData>> { it.value.joinDate }.thenBy { it.key })
            .map { (username, data) -> ClanSettingsMember(displayName = displayName(username), rank = data.rank.iconId) }
            .toTypedArray()
        val banned = clan.bannedUsers.sorted().map(displayName).toTypedArray()
        val settings = clan.settings.mapNotNull { (key, value) -> clientSetting(key, value) }.toTypedArray()
        return ClanSettingsFull(
            main = main,
            clanName = clan.name,
            updateCount = updateCount,
            allowGuests = (clan.getSetting(ClanSetting.GUESTS_CAN_ENTER_CC) as? Number)?.toInt() == 1,
            talkRank = clan.ccChatRank.iconId,
            kickRank = clan.ccKickRank.iconId,
            members = members,
            bannedUsers = banned,
            settings = settings,
        )
    }

    /** The one-byte `ClanSettingsFull` that tells the client it is no longer in a clan. */
    fun settingsLeft(main: Boolean): ClanSettingsFull = ClanSettingsFull(main = main)

    /** `ClanChannelFull` for [state]; [main] as in [settingsFull]. */
    fun channelFull(state: ClanChannelState, main: Boolean): ClanChannelFull = ClanChannelFull(
        main = main,
        clanName = state.clanName,
        updateNum = state.updateNum,
        kickRank = state.kickRank.iconId,
        talkRank = state.talkRank.iconId,
        chatters = state.chatters
            .map { ClanChannelChatter(displayName = it.displayName, rank = it.rank.iconId, worldId = it.worldId) }
            .toTypedArray(),
    )

    /** The one-byte `ClanChannelFull` that tells the client it left the channel. */
    fun channelLeft(main: Boolean): ClanChannelFull = ClanChannelFull(main = main)

    /**
     * `VarclanEnable` followed by one `VarclanSet*` per var of [clan], the packet chosen by the
     * var's cache type ([varKind], the cache by default). A var the cache does not define, or
     * whose stored value does not fit its type, is skipped rather than sent wrong.
     */
    fun clanVarPackets(clan: Clan, varKind: (Int) -> ClanVarKind? = ::cacheVarKind): List<ServerProt> {
        val packets = ArrayList<ServerProt>(clan.vars.size + 1)
        packets += VarclanEnable()
        for ((key, value) in clan.vars) {
            val id = key.toIntOrNull() ?: continue
            packets += clanVarPacket(id, value, varKind(id) ?: continue) ?: continue
        }
        return packets
    }

    /** The single `VarclanSet*` for one var, or null if [value] does not fit [kind]. */
    fun clanVarPacket(id: Int, value: Any, kind: ClanVarKind): ServerProt? = when (kind) {
        ClanVarKind.STRING -> VarclanSetString(id, value.toString())
        ClanVarKind.LONG -> (value as? Number)?.let { VarclanSetLong(id, it.toLong()) }
        ClanVarKind.INT -> (value as? Number)?.toInt()?.let {
            if (it < Byte.MIN_VALUE || it > Byte.MAX_VALUE) VarclanSetInt(id, it) else VarclanSetByte(id, it)
        }
    }

    /** A line of clan chat as one recipient sees it; [guest] is true when they only listen in. */
    fun chatMessage(guest: Boolean, crown: Int, displayName: String, text: String): MessageClanChannel =
        MessageClanChannel(guest = guest, crown = crown, displayName = displayName, message = text)

    fun quickChatMessage(guest: Boolean, crown: Int, displayName: String, message: QuickChatMessage): MessageQuickChatClanChannel =
        MessageQuickChatClanChannel(guest = guest, crown = crown, displayName = displayName, message = message)

    /** The kind of clan var [id] according to the loaded cache, null for an id it does not define. */
    fun cacheVarKind(id: Int): ClanVarKind? {
        val type = Cache.clanVars.getOrNull(id)?.type ?: return null
        return when {
            type.stringStackSize > 0 -> ClanVarKind.STRING
            type.longStackSize > 0 -> ClanVarKind.LONG
            else -> ClanVarKind.INT
        }
    }

    /**
     * One stored setting as the client's var-setting: settings are keyed by enum name, the client
     * wants its own ids, and anything with no client id or an unexpected value is not sent.
     */
    private fun clientSetting(name: String, value: Any): ClanVarSetting? {
        val id = ClanSetting.forName(name)?.id ?: return null
        if (id == NO_CLIENT_ID) return null
        return when (value) {
            is Int -> ClanVarSetting(key = id, intValue = value)
            is Long -> ClanVarSetting(key = id, longValue = value)
            is String -> ClanVarSetting(key = id, stringValue = value)
            is Number -> ClanVarSetting(key = id, intValue = value.toInt())
            else -> null
        }
    }
}
