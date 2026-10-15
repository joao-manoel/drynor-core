/**
 * Chat, friends list, ignore list and clan packets sent by the client.
 */
package org.drynor.core.net.prot.packet

import kotlinx.serialization.Serializable
import org.drynor.core.net.prot.ClientProt

// Communication
@Serializable data class Chat(val type: Int = -1, val color: Int, val effect: Int, val message: String) : ClientProt

@Serializable data class PrivateMessage(val toDisplayName: String, val message: String) : ClientProt

@Serializable data class ChatType(val type: Int) : ClientProt

@Serializable data class ChatSetFilter(val publicFilter: Int, val privateFilter: Int, val tradeFilter: Int) : ClientProt

@Serializable data class QuickChatPublic(val chatType: Int, val qcId: Int, val messageData: ByteArray?) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QuickChatPublic
        if (chatType != other.chatType) return false
        if (qcId != other.qcId) return false
        if (!messageData.contentEquals(other.messageData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = chatType
        result = 31 * result + qcId
        result = 31 * result + messageData.contentHashCode()
        return result
    }
}

@Serializable data class QuickChatPrivate(val toUsername: String, val qcId: Int, val messageData: ByteArray?) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QuickChatPrivate
        if (qcId != other.qcId) return false
        if (toUsername != other.toUsername) return false
        if (!messageData.contentEquals(other.messageData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = qcId
        result = 31 * result + toUsername.hashCode()
        result = 31 * result + messageData.contentHashCode()
        return result
    }
}

// Friends/Ignore List
@Serializable data class AddFriend(val name: String) : ClientProt

@Serializable data class RemoveFriend(val name: String) : ClientProt

@Serializable data class AddIgnoreReq(val username: String, val temporary: Boolean) : ClientProt

@Serializable data class RemoveIgnore(val name: String) : ClientProt

// Friend Chat
@Serializable data class FcJoin(val fcName: String?) : ClientProt

@Serializable data class FcKick(val username: String) : ClientProt

@Serializable data class FcSetRank(val username: String, val rank: Int) : ClientProt

// Clan Chat
@Serializable data class ClanChannelKickUser(val guest: Boolean, val pid: Int, val username: String) : ClientProt

/**
 * ClientProt opcode 39 in the 727 client (`UNUSED_CLAN_OP`).
 *
 * Referenced by a single CS2 instruction that no shipped script uses, so it is never sent in
 * practice. Payload is a short pid followed by a null terminated name.
 */
data class UnusedClanOp(val pid: Int, val username: String) : ClientProt

// Reporting/Bug Tracking
data class ReportAbuse(val username: String, val type: Int, val mute: Boolean, val reason: String) : ClientProt
