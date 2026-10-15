package rs.drynor.core.protocol

import org.drynor.core.clan.ClanAction
import org.drynor.core.model.FriendsChatRank
import org.drynor.core.social.*
import kotlin.test.*

class SocialCommandsTest {
    @Test fun `closed commands round trip text binary messages settings and filters`() {
        val messages = listOf<ChatMessage>(PublicChatMessage("Drynor", 4), QuickChatMessage("", 42, byteArrayOf(0, -1, 3)))
        val actions = listOf(
            SocialAction.AddFriend("Alice"), SocialAction.RemoveFriend("Alice"),
            SocialAction.AddIgnore("Bob", true), SocialAction.RemoveIgnore("Bob"),
            SocialAction.SetPrivateStatus(1), SocialAction.SetFriendsChatStatus(2),
            SocialAction.JoinFriendsChat("Alice"), SocialAction.LeaveFriendsChat,
            SocialAction.KickFromFriendsChat("Bob"), SocialAction.SetFriendsChatRank("Bob", FriendsChatRank.CAPTAIN),
            SocialAction.UpdateFriendsChatSettings(null, FriendsChatRank.FRIEND, FriendsChatRank.RECRUIT, FriendsChatRank.OWNER, FriendsChatRank.CAPTAIN),
        ) + messages.flatMap { listOf(SocialAction.SendPrivateMessage("Alice", it), SocialAction.SayInFriendsChat(it)) }
        for (action in actions) {
            val request = SocialCommandRequest("actor", action)
            assertEquals(request, SocialCommandJson.decode(SocialCommandJson.encode(request)))
        }
        for (message in messages) {
            val action = ClanAction.Chat(true, message)
            val request = ClanCommandRequest("actor", action)
            assertEquals(request, ClanCommandJson.decode(ClanCommandJson.encode(request)))
        }
    }

    @Test fun `arbitrary type names and fields are rejected`() {
        assertFails { SocialCommandJson.decode("""{"username":"actor","action":{"_type":"java.lang.Runtime"}}""") }
        assertFails { SocialCommandJson.decode("""{"username":"actor","action":{"_type":"AddFriend","name":"Bob","rights":"OWNER"}}""") }
    }
}
