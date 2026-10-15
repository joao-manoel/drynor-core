/**
 * Session lifecycle packets: logout, keep-alive, world hopping and the world list.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.dto.WorldListSnapshot

@JvmInline value class LogoutLobby(val dummy: Int = 0) : ServerProt

@JvmInline value class LogoutFull(val dummy: Int = 0) : ServerProt

data class OpenUrl(val url: String, val displayUrl: String? = null) : ServerProt

/**
 * Opcode 86, VarShort. Client enum `OPEN_SOCIAL_NETWORK_URL`.
 *
 * Drops the client out of full screen and opens [url] in a browser using the
 * `"opensn"` open method. The payload is the raw (un-terminated) url bytes,
 * the packet length supplies the string length.
 */
@JvmInline value class OpenSocialNetworkUrl(val url: String) : ServerProt

/**
 * Opcode 156, VarShort. Client enum `WINDOW_CALL_WITH_ARGS`.
 *
 * Applet-only javascript window call. The payload is the raw (un-terminated)
 * argument string, the packet length supplies the string length. The 727 client
 * decodes the string and then discards it.
 */
@JvmInline value class WindowCallWithArgs(val args: String) : ServerProt

/**
 * The lobby world list.
 *
 * Carries an immutable [WorldListSnapshot] rather than the live world list: encoding happens on
 * the writer coroutine, so reading the mutable list at that point could describe a set of worlds
 * that never existed at any single instant.
 *
 * @param refreshOnClient Whether the client should decode the payload now rather than buffer it.
 * @param refreshOnlyPlayerCounts Whether to send just the per-world player counts, which is all
 *   the client needs when its copy of the list is already at [WorldListSnapshot.revision].
 */
data class WorldListPacket(
    val worlds: WorldListSnapshot,
    val refreshOnClient: Boolean,
    val refreshOnlyPlayerCounts: Boolean,
) : ServerProt

@JvmInline value class IdentifyHostName(val ip: Int) : ServerProt

@JvmInline value class UpdateRebootTimer(val seconds: Int) : ServerProt

data class RequestFps(val key: Int, val flags: Int) : ServerProt

@JvmInline value class UpdateSitesettingsCookie(val dummy: Int = 0) : ServerProt

// Miscellaneous protocols
class Pong : ServerProt

@JvmInline value class KeepAlive(val dummy: Int = 0) : ServerProt

data class QuickHopWorlds(val host: String, val port: Int, val reconnecting: Boolean) : ServerProt
