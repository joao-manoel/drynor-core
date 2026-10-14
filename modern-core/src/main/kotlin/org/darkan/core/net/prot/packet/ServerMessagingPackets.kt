/**
 * Chat and game message packets.
 */
package org.darkan.core.net.prot.packet

import kotlinx.serialization.Serializable
import org.darkan.core.model.ChatMessageType
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.QuickChatMessage

data class UpdateFriendchatChannelSingleUser(
    val displayName: String,
    val username: String = displayName,
    val worldId: Int,
    val rank: Int,
    val worldName: String? = null,
) : ServerProt

// Messaging protocols
@Serializable data class MessagePrivate(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: String) : ServerProt

@Serializable data class MessagePrivateEcho(val senderDisplayName: String, val message: String) : ServerProt

@Serializable data class MessageQuickChatPrivate(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt

@Serializable data class MessageQuickChatPrivateEcho(val senderDisplayName: String, val message: QuickChatMessage) : ServerProt

@Serializable data class MessageFriendsChat(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val chatName: String, val message: String) : ServerProt

@Serializable data class MessageQuickChatFriendsChat(val chatName: String, val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt

@Serializable data class MessageClanChannel(val guest: Boolean, val crown: Int, val displayName: String, val message: String) : ServerProt

@Serializable data class MessageQuickChatClanChannel(val guest: Boolean, val crown: Int, val displayName: String, val message: QuickChatMessage) : ServerProt

data class MessagePlayerGroup(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: String) : ServerProt

data class MessageQuickChatPlayerGroup(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt

data class MessagePublic(val pid: Int, val messageIcon: Int, val message: ChatMessage) : ServerProt

@Serializable data class GameMessage(val type: ChatMessageType, val message: String, val targetDisplayName: String? = null, val effectFlags: Int = 0) : ServerProt

data class ChatFilterSettings(val tradeFilter: Int, val publicFilter: Int) : ServerProt

@Serializable data class ChatFilterSettingsPrivateChat(val privateFilter: Int) : ServerProt
