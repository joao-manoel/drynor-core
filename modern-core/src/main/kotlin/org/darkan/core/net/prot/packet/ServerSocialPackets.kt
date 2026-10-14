/**
 * Friends list, ignore list, friends chat and clan channel packets.
 */
package org.darkan.core.net.prot.packet

import kotlinx.serialization.Serializable
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.ClanChannelChatter
import org.darkan.core.net.prot.dto.ClanSettingsMember
import org.darkan.core.net.prot.dto.ClanVarSetting
import org.darkan.core.net.prot.dto.FriendStatusUpdate
import org.darkan.core.net.prot.dto.FriendsChatPlayer
import org.darkan.core.net.prot.dto.IgnoreEntry

data class ClanChannelDelta(val main: Boolean, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanChannelDelta
        if (main != other.main) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * main.hashCode() + data.contentHashCode()
}

@Serializable data class ClanChannelFull(
    val main: Boolean,
    val clanName: String? = null,
    val updateNum: Long = 0,
    val kickRank: Int = -1,
    val talkRank: Int = -1,
    val chatters: Array<ClanChannelChatter>? = null,
) : ServerProt {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanChannelFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (updateNum != other.updateNum) return false
        if (kickRank != other.kickRank) return false
        if (talkRank != other.talkRank) return false
        return chatters.contentEquals(other.chatters)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + updateNum.hashCode()
        result = 31 * result + kickRank
        result = 31 * result + talkRank
        result = 31 * result + (chatters?.contentHashCode() ?: 0)
        return result
    }
}

data class ClanSettingsFullRaw(val main: Boolean, val data: ByteArray?) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsFullRaw
        if (main != other.main) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * main.hashCode() + (data?.contentHashCode() ?: 0)
}

data class ClanSettingsDelta(val main: Boolean, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsDelta
        if (main != other.main) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * main.hashCode() + data.contentHashCode()
}

@Serializable data class ClanSettingsFull(
    val main: Boolean,
    val clanName: String? = null,
    val updateCount: Int = 0,
    val allowGuests: Boolean = false,
    val talkRank: Int = -1,
    val kickRank: Int = -1,
    val members: Array<ClanSettingsMember>? = null,
    val bannedUsers: Array<String>? = null,
    val settings: Array<ClanVarSetting>? = null,
) : ServerProt {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (updateCount != other.updateCount) return false
        if (allowGuests != other.allowGuests) return false
        if (talkRank != other.talkRank) return false
        if (kickRank != other.kickRank) return false
        if (!members.contentEquals(other.members)) return false
        if (!bannedUsers.contentEquals(other.bannedUsers)) return false
        return settings.contentEquals(other.settings)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + updateCount
        result = 31 * result + allowGuests.hashCode()
        result = 31 * result + talkRank
        result = 31 * result + kickRank
        result = 31 * result + (members?.contentHashCode() ?: 0)
        result = 31 * result + (bannedUsers?.contentHashCode() ?: 0)
        result = 31 * result + (settings?.contentHashCode() ?: 0)
        return result
    }
}

@Serializable data class UpdateIgnoreList(val ignores: Array<IgnoreEntry>) : ServerProt {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateIgnoreList
        return ignores.contentEquals(other.ignores)
    }

    override fun hashCode(): Int = ignores.contentHashCode()
}

@Serializable data class FriendStatus(val updates: Array<FriendStatusUpdate>) : ServerProt {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FriendStatus
        return updates.contentEquals(other.updates)
    }

    override fun hashCode(): Int = updates.contentHashCode()
}

@Serializable data class FriendlistLoaded(val dummy: Int = 0) : ServerProt

data class AddIgnore(val username: String, val displayName: String, val temporary: Boolean = false, val editDisplayName: Boolean = false) : ServerProt

// Friend and clan chat protocols
@Serializable data class FriendsChatChannel(
    val clear: Boolean = false,
    val ownerDisplayName: String? = null,
    val ownerUsername: String? = ownerDisplayName,
    val chatName: String? = null,
    val minRankCanKick: Int = 0,
    val players: Array<FriendsChatPlayer>? = null,
) : ServerProt {

    init {
        if (!clear) {
            require(ownerDisplayName != null) { "ownerDisplayName is required unless clear=true" }
            require(chatName != null) { "chatName is required unless clear=true" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FriendsChatChannel
        if (clear != other.clear) return false
        if (ownerDisplayName != other.ownerDisplayName) return false
        if (ownerUsername != other.ownerUsername) return false
        if (chatName != other.chatName) return false
        if (minRankCanKick != other.minRankCanKick) return false
        return players.contentEquals(other.players)
    }

    override fun hashCode(): Int {
        var result = clear.hashCode()
        result = 31 * result + (ownerDisplayName?.hashCode() ?: 0)
        result = 31 * result + (ownerUsername?.hashCode() ?: 0)
        result = 31 * result + (chatName?.hashCode() ?: 0)
        result = 31 * result + minRankCanKick
        result = 31 * result + (players?.contentHashCode() ?: 0)
        return result
    }
}
