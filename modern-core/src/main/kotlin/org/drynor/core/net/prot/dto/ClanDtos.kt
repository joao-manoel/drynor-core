/**
 * Payload types carried by the clan channel and clan settings packets.
 */
package org.drynor.core.net.prot.dto

import kotlinx.serialization.Serializable

@Serializable data class ClanChannelChatter(
    val displayName: String,
    val rank: Int,
    val worldId: Int,
)

@Serializable data class ClanSettingsMember(
    val displayName: String,
    val rank: Int,
    val flags: Int = 0,
)

@Serializable data class ClanVarSetting(
    val key: Int,
    val intValue: Int? = null,
    val longValue: Long? = null,
    val stringValue: String? = null,
)
