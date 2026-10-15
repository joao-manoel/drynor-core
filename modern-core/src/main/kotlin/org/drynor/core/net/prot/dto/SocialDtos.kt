/**
 * Payload types carried by the friends list, ignore list and friends chat packets.
 */
package org.drynor.core.net.prot.dto

import kotlinx.serialization.Serializable

@Serializable data class IgnoreEntry(val username: String, val displayName: String)

@Serializable data class FriendStatusUpdate(
    val warnMessage: Boolean,
    val displayName: String,
    val previousDisplayName: String,
    val world: Int,
    val friendsChatRank: Int,
    val referrer: Boolean,
    val worldName: String? = null,
    val platform: Int = -1,
    val worldFlags: Int = 0,
)

@Serializable data class FriendsChatPlayer(
    val displayName: String,
    val username: String = displayName,
    val worldId: Int,
    val rank: Int,
    val worldName: String,
)
