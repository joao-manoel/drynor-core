package org.drynor.core.worldlist

import kotlinx.serialization.Serializable

@Serializable
data class WorldMetadata(
    val number: Int,
    val ipAddress: String,
    val port: Int,
    val activity: String,
    val country: Country,
    val quickchat: Boolean,
    val lootShare: Boolean,
    val members: Boolean,
    val botsAllowed: Boolean,
    val highlighted: Boolean)
{
    val isLobby get() = number >= 1000

    /** A world the lobby can actually hand players to: a real id, a host, and a usable port. */
    val isValid get() = number > 0 && ipAddress.isNotBlank() && port in 1..65535
}