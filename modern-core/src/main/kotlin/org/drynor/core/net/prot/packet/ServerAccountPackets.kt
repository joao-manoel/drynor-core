/**
 * Account packets: creation replies, login details and loyalty balances.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.dto.CheckEmailReplyOpcode
import org.drynor.core.net.prot.dto.CreateAccountReplyOpcode

@JvmInline value class CreateCheckEmailReply(val code: CheckEmailReplyOpcode) : ServerProt

@JvmInline value class CreateAccountReply(val code: CreateAccountReplyOpcode) : ServerProt

data class UpdateDob(val dob: Int, val quickChatOnly: Boolean) : ServerProt

/**
 * Opcode 52, fixed size 1. Client enum `DEPRECATED_52_CLIENTPACKET_97`.
 *
 * Sets the client's pending display-name change state. Values `0` and `1` also
 * flag a display name change as pending; any other value only stores the state.
 */
@JvmInline value class DisplayNameChangeState(val state: Int) : ServerProt

data class LoyaltyUpdate(val balance: Int, val enabled: Boolean) : ServerProt

@JvmInline value class JCoinsUpdate(val amount: Int) : ServerProt

// Note: These pre-world packets would need special handling
data class WorldLoginDetails(
    val rights: Int,
    val modLevel: Int,
    val quickChat: Boolean,
    val verifiedEmail: Boolean,
    val aBool7322: Boolean,
    val quickChatOnly: Boolean,
    val playerIndex: Int,
    val members: Boolean,
    val dob: Int,
    val memberWorld: Boolean,
    val worldName: String,
) : ServerProt

class WorldReconnectDetails(
    val localPlayerPid: Int,
    val localPlayerBaseTileHash: Int,
    val otherPlayerRegionIds: IntArray,
) : ServerProt {
    override fun equals(other: Any?): Boolean =
        other is WorldReconnectDetails && localPlayerPid == other.localPlayerPid && localPlayerBaseTileHash == other.localPlayerBaseTileHash && otherPlayerRegionIds.contentEquals(other.otherPlayerRegionIds)

    override fun hashCode(): Int = (31 * localPlayerPid + localPlayerBaseTileHash) * 31 + otherPlayerRegionIds.contentHashCode()
}

/**
 * Lobby login response, sent once the account has been authenticated.
 *
 * Carries only the fields the client reads, rather than the `Account` itself: the account is a
 * mutable persistence model, and the wire layer should not be able to observe — or be broken by —
 * later changes to it. Build one with `Account.toLobbyLoginDetails`.
 *
 * @param crown Rights crown id rendered next to the player's name.
 * @param lastIpHash Last known IPv4 address packed into an int, or 0 when unknown.
 * @param worldLoginToken Short-lived token the client hands to a world server to log in there.
 * @param defaultWorldId Number of the world the client jumps to when the player picks "play now".
 * @param defaultWorldAddress Host that world is reachable at.
 */
data class LobbyLoginDetails(
    val crown: Int,
    val displayName: String,
    val lastIpHash: Int,
    val worldLoginToken: String,
    val defaultWorldId: Int = DEFAULT_WORLD_ID,
    val defaultWorldAddress: String = DEFAULT_WORLD_ADDRESS,
) : ServerProt {
    companion object {
        /** Stand-in world number used when no world has registered with the lobby yet. */
        const val DEFAULT_WORLD_ID = 0

        /** Stand-in host used alongside [DEFAULT_WORLD_ID]. */
        const val DEFAULT_WORLD_ADDRESS = "127.0.0.1"
    }
}
