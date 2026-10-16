package org.drynor.core.social

import org.drynor.core.formatPlayerNameForProtocol
import org.drynor.core.model.ChatMessageType
import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.dto.FriendStatusUpdate
import org.drynor.core.net.prot.dto.FriendsChatPlayer
import org.drynor.core.net.prot.dto.IgnoreEntry
import org.drynor.core.net.prot.packet.ChatFilterSettingsPrivateChat
import org.drynor.core.net.prot.packet.FriendStatus
import org.drynor.core.net.prot.packet.FriendlistLoaded
import org.drynor.core.net.prot.packet.FriendsChatChannel
import org.drynor.core.net.prot.packet.GameMessage
import org.drynor.core.net.prot.packet.MessageFriendsChat
import org.drynor.core.net.prot.packet.MessagePrivate
import org.drynor.core.net.prot.packet.MessagePrivateEcho
import org.drynor.core.net.prot.packet.MessageQuickChatFriendsChat
import org.drynor.core.net.prot.packet.MessageQuickChatPrivate
import org.drynor.core.net.prot.packet.MessageQuickChatPrivateEcho
import org.drynor.core.net.prot.packet.UpdateIgnoreList
import org.drynor.core.net.prot.packet.AddIgnore

object SocialPackets {

    fun render(viewer: String, event: SocialEvent): List<ServerProt> = when (event) {
        is SocialEvent.Snapshot -> listOfNotNull(
            FriendlistLoaded(),
            friendStatus(event.friends),
            ignoreList(event.ignores),
            ChatFilterSettingsPrivateChat(event.privateStatus),
            if (event.includeFriendsChat) friendsChatChannel(viewer, event.friendsChat) else null,
        )
        is SocialEvent.FriendUpdated -> listOf(friendStatus(listOf(event.friend)))
        is SocialEvent.FriendRemoved -> emptyList()
        is SocialEvent.IgnoresChanged -> listOf(ignoreList(event.ignores))
        is SocialEvent.TemporaryIgnoreAdded -> listOf(AddIgnore(event.player.displayName, event.player.previousDisplayName, temporary = true))
        is SocialEvent.PrivateStatusChanged -> listOf(ChatFilterSettingsPrivateChat(event.status))
        is SocialEvent.FriendsChatChanged -> listOf(friendsChatChannel(viewer, event.channel))
        is SocialEvent.FriendsChatLine -> listOf(friendsChatLine(event))
        is SocialEvent.PrivateMessageReceived -> listOf(privateMessage(event))
        is SocialEvent.PrivateMessageSent -> listOf(privateMessageEcho(event))
        is SocialEvent.Notice -> listOf(GameMessage(noticeType(event.kind), event.text))
    }

    fun friendStatus(friends: List<FriendEntry>): FriendStatus = FriendStatus(
        friends.map { friend ->
            FriendStatusUpdate(
                warnMessage = false,
                displayName = friend.displayName,
                previousDisplayName = friend.previousDisplayName,
                world = friend.worldId,
                friendsChatRank = friend.rank.id,
                referrer = false,
                worldName = friend.worldName,
                platform = 0,
            )
        }.toTypedArray(),
    )

    fun ignoreList(ignores: List<IgnoredPlayer>): UpdateIgnoreList =
        UpdateIgnoreList(ignores.map { IgnoreEntry(username = it.displayName, displayName = it.previousDisplayName) }.toTypedArray())

    fun friendsChatChannel(viewer: String, state: FriendsChatState?): FriendsChatChannel {
        if (state == null) return FriendsChatChannel(clear = true)
        return FriendsChatChannel(
            ownerDisplayName = state.ownerDisplayName,
            ownerUsername = state.ownerUsername,
            chatName = state.chatName.formatPlayerNameForProtocol(),
            minRankCanKick = state.kickRank.id,
            players = state.members.map {
                FriendsChatPlayer(
                    displayName = it.displayName,
                    username = it.username,
                    worldId = it.worldId,
                    rank = it.rank.id,
                    worldName = it.worldName,
                )
            }.toTypedArray(),
        )
    }

    private fun friendsChatLine(event: SocialEvent.FriendsChatLine): ServerProt {
        val chatName = event.chatName.formatPlayerNameForProtocol()
        val speaker = event.speaker
        return when (val message = event.message) {
            is QuickChatMessage -> MessageQuickChatFriendsChat(
                chatName = chatName,
                crown = speaker.crown,
                displayName = speaker.displayName,
                quickResponseName = speaker.username,
                message = message,
            )
            else -> MessageFriendsChat(
                crown = speaker.crown,
                displayName = speaker.displayName,
                quickResponseName = speaker.username,
                chatName = chatName,
                message = message.message,
            )
        }
    }

    private fun privateMessage(event: SocialEvent.PrivateMessageReceived): ServerProt {
        val speaker = event.speaker
        return when (val message = event.message) {
            is QuickChatMessage -> MessageQuickChatPrivate(
                crown = speaker.crown,
                displayName = speaker.displayName,
                quickResponseName = speaker.username,
                message = message,
            )
            else -> MessagePrivate(
                crown = speaker.crown,
                displayName = speaker.displayName,
                quickResponseName = speaker.username,
                message = message.message,
            )
        }
    }

    private fun privateMessageEcho(event: SocialEvent.PrivateMessageSent): ServerProt = when (val message = event.message) {
        is QuickChatMessage -> MessageQuickChatPrivateEcho(senderDisplayName = event.recipient, message = message)
        else -> MessagePrivateEcho(senderDisplayName = event.recipient, message = message.message)
    }

    private fun noticeType(kind: NoticeKind): ChatMessageType = when (kind) {
        NoticeKind.FRIEND -> ChatMessageType.FRIEND_NOTIFICATION
        NoticeKind.IGNORE -> ChatMessageType.IGNORE_NOTIFICATION
        NoticeKind.FRIENDS_CHAT -> ChatMessageType.FC_NOTIFICATION
    }
}
