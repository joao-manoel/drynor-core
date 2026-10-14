package org.darkan.core.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.darkan.core.model.FriendsChatRank

@Serializable
data class FriendEntry(
    val username: String,
    val displayName: String,
    val previousDisplayName: String = "",
    val worldId: Int = 0,
    val worldName: String = "",
    val rank: FriendsChatRank = FriendsChatRank.UNRANKED,
) {
    val online: Boolean get() = worldId > 0
}

@Serializable
data class IgnoredPlayer(val username: String, val displayName: String)

@Serializable
data class FriendsChatMember(
    val username: String,
    val displayName: String,
    val worldId: Int,
    val worldName: String,
    val rank: FriendsChatRank,
)

@Serializable
data class FriendsChatState(
    val ownerUsername: String,
    val ownerDisplayName: String,
    val chatName: String,
    val kickRank: FriendsChatRank,
    val members: List<FriendsChatMember>,
)

@Serializable
data class Speaker(val username: String, val displayName: String, val crown: Int)

@Serializable
enum class NoticeKind { FRIEND, IGNORE, FRIENDS_CHAT }

@Serializable
sealed interface SocialAction {
    @Serializable @SerialName("AddFriend") data class AddFriend(val name: String) : SocialAction
    @Serializable @SerialName("RemoveFriend") data class RemoveFriend(val name: String) : SocialAction
    @Serializable @SerialName("AddIgnore") data class AddIgnore(val name: String, val untilLogout: Boolean = false) : SocialAction
    @Serializable @SerialName("RemoveIgnore") data class RemoveIgnore(val name: String) : SocialAction
    @Serializable @SerialName("SetPrivateStatus") data class SetPrivateStatus(val status: Int) : SocialAction
    @Serializable @SerialName("JoinFriendsChat") data class JoinFriendsChat(val owner: String) : SocialAction
    @Serializable @SerialName("LeaveFriendsChat") data object LeaveFriendsChat : SocialAction
    @Serializable @SerialName("KickFromFriendsChat") data class KickFromFriendsChat(val name: String) : SocialAction
    @Serializable @SerialName("SetFriendsChatRank") data class SetFriendsChatRank(val name: String, val rank: FriendsChatRank) : SocialAction
    @Serializable @SerialName("UpdateFriendsChatSettings")
    data class UpdateFriendsChatSettings(
        val name: String?,
        val rankToEnter: FriendsChatRank,
        val rankToSpeak: FriendsChatRank,
        val rankToKick: FriendsChatRank,
        val rankToLootShare: FriendsChatRank,
    ) : SocialAction
    @Serializable @SerialName("SayInFriendsChat") data class SayInFriendsChat(val message: ChatMessage) : SocialAction
    @Serializable @SerialName("SendPrivateMessage") data class SendPrivateMessage(val to: String, val message: ChatMessage) : SocialAction
}

@Serializable
sealed interface SocialEvent {
    @Serializable @SerialName("Snapshot")
    data class Snapshot(
        val friends: List<FriendEntry>,
        val ignores: List<IgnoredPlayer>,
        val privateStatus: Int,
        val friendsChat: FriendsChatState? = null,
    ) : SocialEvent
    @Serializable @SerialName("FriendUpdated") data class FriendUpdated(val friend: FriendEntry) : SocialEvent
    @Serializable @SerialName("FriendRemoved") data class FriendRemoved(val username: String) : SocialEvent
    @Serializable @SerialName("IgnoresChanged") data class IgnoresChanged(val ignores: List<IgnoredPlayer>) : SocialEvent
    @Serializable @SerialName("PrivateStatusChanged") data class PrivateStatusChanged(val status: Int) : SocialEvent
    @Serializable @SerialName("FriendsChatChanged") data class FriendsChatChanged(val channel: FriendsChatState? = null) : SocialEvent
    @Serializable @SerialName("FriendsChatLine") data class FriendsChatLine(val chatName: String, val speaker: Speaker, val message: ChatMessage) : SocialEvent
    @Serializable @SerialName("PrivateMessageReceived") data class PrivateMessageReceived(val speaker: Speaker, val message: ChatMessage) : SocialEvent
    @Serializable @SerialName("PrivateMessageSent") data class PrivateMessageSent(val recipient: String, val message: ChatMessage) : SocialEvent
    @Serializable @SerialName("Notice") data class Notice(val kind: NoticeKind, val text: String) : SocialEvent
}

object SocialJson {
    val serializersModule: SerializersModule = SerializersModule {
        polymorphic(ChatMessage::class) {
            subclass(PublicChatMessage::class)
            subclass(QuickChatMessage::class)
        }
    }

    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = this@SocialJson.serializersModule
    }
}
