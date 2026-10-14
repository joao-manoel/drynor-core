/**
 * Payload types carried by the world list packet.
 *
 * These are an immutable snapshot. The live `WorldList` is mutable and lock-guarded, so the encoder
 * must not read it directly: encoding happens on the writer coroutine, long after the packet was
 * queued, and a world registering or dropping in between would otherwise change the payload halfway
 * through being written.
 */
package org.darkan.core.net.prot.dto

/** One world as the client's world list renders it. */
data class WorldListEntry(
    val number: Int,
    val index: Int,
    val countryId: Int,
    val activity: String,
    val address: String,
    val port: Int,
    val members: Boolean,
    val quickChat: Boolean,
    val botsAllowed: Boolean,
    val lootShare: Boolean,
    val highlighted: Boolean,
    /** Players currently online, or -1 when the world is offline. */
    val playersOnline: Int,
    val customCache: Boolean = false,
)

/**
 * A point-in-time copy of the world list.
 *
 * @param revision List version. The client sends back the version it holds so the server can skip
 *   resending the full list when nothing has changed.
 */
data class WorldListSnapshot(
    val entries: List<WorldListEntry>,
    val maxWorlds: Int,
    val revision: Int,
)
