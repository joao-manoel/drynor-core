package org.darkan.core.clan

import kotlinx.serialization.Serializable
import org.darkan.core.model.ClanRank

/**
 * One player in a clan channel. [rank] is the rank the channel shows, [ClanRank.NONE] for a
 * guest. [worldId] is where the player is: a world id, or [LOBBY_WORLD_ID] for a player sitting on
 * the lobby screen.
 */
@Serializable
data class ClanChatter(
    val username: String,
    val displayName: String,
    val rank: ClanRank,
    val worldId: Int,
) {
    val guest: Boolean get() = rank == ClanRank.NONE

    companion object {
        /**
         * The world id the lobby reports for its own players, so a chatter on the lobby screen
         * shows as online rather than in "world 0". The legacy lobby registered itself as this
         * world (`Lobby.WORLD_INFO`) and the modern lobby keeps the number
         * (`SocialGateway.LOBBY_WORLD_ID`).
         */
        const val LOBBY_WORLD_ID = 1115
    }
}

/**
 * The chatter list of one clan channel, owned by the lobby and replicated to every world. Built
 * into the client's `ClanChannelFull` by [ClanPackets.channelFull] on either server.
 */
@Serializable
data class ClanChannelState(
    val clanName: String,
    val updateNum: Long,
    val talkRank: ClanRank,
    val kickRank: ClanRank,
    val chatters: List<ClanChatter>,
) {
    fun chatter(username: String): ClanChatter? = chatters.firstOrNull { it.username == username }

    operator fun contains(username: String): Boolean = chatter(username) != null
}
