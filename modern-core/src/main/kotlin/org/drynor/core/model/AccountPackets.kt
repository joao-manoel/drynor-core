package org.drynor.core.model

import org.drynor.core.net.prot.packet.LobbyLoginDetails

/**
 * Maps persistence models onto the wire messages that carry them.
 *
 * Keeping the mapping here rather than inside the packet definitions means the dependency runs one
 * way — the model knows about the protocol, the protocol does not know about the model — so a
 * change to how accounts are stored cannot silently change what goes on the wire.
 */

/**
 * Builds the lobby login response for this account.
 *
 * @param worldLoginToken Short-lived token the client presents to a world server.
 * @param defaultWorldId World the client jumps to on "play now"; defaults to the placeholder used
 * when the lobby has no world registered yet.
 * @param defaultWorldAddress Host for [defaultWorldId].
 */
fun Account.toLobbyLoginDetails(
    worldLoginToken: String,
    defaultWorldId: Int = LobbyLoginDetails.DEFAULT_WORLD_ID,
    defaultWorldAddress: String = LobbyLoginDetails.DEFAULT_WORLD_ADDRESS,
) = LobbyLoginDetails(
    crown = rights.crown,
    displayName = displayName,
    lastIpHash = packIpv4(lastIp),
    worldLoginToken = worldLoginToken,
    defaultWorldId = defaultWorldId,
    defaultWorldAddress = defaultWorldAddress,
)

/**
 * Packs a dotted-quad IPv4 address into an int, most significant octet first.
 *
 * Returns 0 for anything that is not a dotted quad — a missing address, or an IPv6 address, which
 * the client has no field for. Brackets are stripped because socket addresses arrive wrapped.
 */
private fun packIpv4(address: String?): Int {
    val octets = address?.removeSurrounding("[", "]")?.split('.') ?: return 0
    if (octets.size != 4) return 0
    var packed = 0
    for (octet in octets) {
        val value = octet.toIntOrNull() ?: return 0
        if (value !in 0..255) return 0
        packed = (packed shl 8) or value
    }
    return packed
}
