package org.drynor.core.clan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.drynor.core.model.ClanRank
import org.drynor.core.model.ClanSettingValueSerializer
import org.drynor.core.social.ChatMessage
import org.drynor.core.social.QuickChatMessage

/**
 * One user intent against the clan system, exactly as `docs/clans.md` lists them. A world turns
 * a button into one of these and sends it in a `ClanCommand`; the lobby-screen handlers build the
 * same values directly. Every rule that decides whether the intent is allowed lives in
 * [ClanRules], so both servers judge it identically.
 *
 * Usernames are protocol-formatted (`formatPlayerNameForProtocol`); the actor's own username
 * travels on the enclosing `ClanCommand`, never inside the action.
 */
@Serializable
sealed interface ClanAction {

    /** The `_type` this action is written as on the wire; `ClanCommandResult.action` echoes it. */
    val serialName: String
        get() = when (this) {
            is CheckName -> "CheckName"
            is Create -> "Create"
            is Leave -> "Leave"
            is AcceptInvite -> "AcceptInvite"
            is RemoveMember -> "RemoveMember"
            is UpdateMember -> "UpdateMember"
            is Ban -> "Ban"
            is Unban -> "Unban"
            is UpdateSettings -> "UpdateSettings"
            is SetChannelRanks -> "SetChannelRanks"
            is SetVar -> "SetVar"
            is JoinChannel -> "JoinChannel"
            is LeaveChannel -> "LeaveChannel"
            is JoinGuestChannel -> "JoinGuestChannel"
            is LeaveGuestChannel -> "LeaveGuestChannel"
            is KickFromChannel -> "KickFromChannel"
            is Chat -> "Chat"
            is QuickChat -> "QuickChat"
        }

    /** Is [name] free to be used for a new clan? The actor must not already be in one. */
    @Serializable
    @SerialName("CheckName")
    data class CheckName(val name: String) : ClanAction

    /** Create the clan [name] with the actor as its owner. */
    @Serializable
    @SerialName("Create")
    data class Create(val name: String) : ClanAction

    /** Leave the clan; an owner hands over to [ClanRules.successor], the last member deletes it. */
    @Serializable
    @SerialName("Leave")
    data object Leave : ClanAction

    /** Join [clanName] on an invitation [inviter] (a member holding INVITE) extended. */
    @Serializable
    @SerialName("AcceptInvite")
    data class AcceptInvite(val clanName: String, val inviter: String) : ClanAction

    /** Kick [target] out of the clan. */
    @Serializable
    @SerialName("RemoveMember")
    data class RemoveMember(val target: String) : ClanAction

    /** Edit [target]'s membership; every null field is left as it is. */
    @Serializable
    @SerialName("UpdateMember")
    data class UpdateMember(
        val target: String,
        val rank: ClanRank? = null,
        val job: Int? = null,
        val banFromKeep: Boolean? = null,
        val banFromCitadel: Boolean? = null,
        val banFromIsland: Boolean? = null,
    ) : ClanAction

    /** Ban [target] from the clan and its channel; this also removes their membership. */
    @Serializable
    @SerialName("Ban")
    data class Ban(val target: String) : ClanAction

    @Serializable
    @SerialName("Unban")
    data class Unban(val target: String) : ClanAction

    /**
     * Change clan settings, keyed by [org.drynor.core.model.ClanSetting] name with an Int, Long or
     * String value of the setting's own type. Rejected as a whole if any entry is unknown or of
     * the wrong type ([ClanRules.settingsDelta]).
     */
    @Serializable
    @SerialName("UpdateSettings")
    data class UpdateSettings(
        val settings: Map<String, @Serializable(with = ClanSettingValueSerializer::class) Any>,
    ) : ClanAction

    /** Set the channel's talk and/or kick rank; a null leaves that rank alone. */
    @Serializable
    @SerialName("SetChannelRanks")
    data class SetChannelRanks(val talkRank: ClanRank? = null, val kickRank: ClanRank? = null) : ClanAction

    /** Set clan var [id] (a `Cache.clanVars` id) to an Int, Long or String [value]. */
    @Serializable
    @SerialName("SetVar")
    data class SetVar(val id: Int, val value: @Serializable(with = ClanSettingValueSerializer::class) Any) : ClanAction

    @Serializable
    @SerialName("JoinChannel")
    data object JoinChannel : ClanAction

    @Serializable
    @SerialName("LeaveChannel")
    data object LeaveChannel : ClanAction

    /** Listen in on [clanName]'s channel as a guest. */
    @Serializable
    @SerialName("JoinGuestChannel")
    data class JoinGuestChannel(val clanName: String) : ClanAction

    @Serializable
    @SerialName("LeaveGuestChannel")
    data object LeaveGuestChannel : ClanAction

    /**
     * Throw [target] out of the channel. [guest] is which list the actor clicked on; the lobby
     * decides from the clan's membership whether the target really is a guest.
     */
    @Serializable
    @SerialName("KickFromChannel")
    data class KickFromChannel(val target: String, val guest: Boolean) : ClanAction

    /** Say [message] in the channel; [guest] means the actor speaks in a guest channel. */
    @Serializable
    @SerialName("Chat")
    data class Chat(val guest: Boolean, val message: ChatMessage) : ClanAction

    @Serializable
    @SerialName("QuickChat")
    data class QuickChat(val guest: Boolean, val message: QuickChatMessage) : ClanAction
}
