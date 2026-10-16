package org.drynor.core.social.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.drynor.core.EnvVars
import org.drynor.core.clan.ClanAction
import org.drynor.core.clan.ClanChannelState
import org.drynor.core.model.AccountView
import org.drynor.core.model.Clan
import org.drynor.core.model.Rights
import org.drynor.core.model.FriendsChat
import org.drynor.core.social.FriendsChatState
import org.drynor.core.social.IgnoredPlayer
import org.drynor.core.social.ChatMessage
import org.drynor.core.social.QuickChatMessage
import org.drynor.core.social.SocialAction
import org.drynor.core.social.SocialEvent
import org.drynor.core.social.SocialJson
import org.drynor.core.worldlist.Country
import org.drynor.core.worldlist.WorldMetadata

const val GATEWAY_MAX_MESSAGE_BYTES = 1_048_576L

/**
 * SocialGateway WebSocket wire protocol: the one multiplexed socket between a world and the lobby.
 * Nothing on it is revision-specific - social traffic is a [SocialAction] or [SocialEvent] with the
 * player it concerns, and each side renders its own client packets from the events.
 */
object SocialGatewayWireJson {
    val serializersModule: SerializersModule = SerializersModule {
        include(SocialJson.serializersModule)
    }

    val json: Json = Json {
        prettyPrint = false
        coerceInputValues = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "_type"
        serializersModule = SocialGatewayWireJson.serializersModule
    }
}

@Serializable
sealed interface SocialGatewayWireMessage

@Serializable
enum class AnnouncementChannel {
    GAME,
    FRIENDS_CHAT,
}

@Serializable
sealed interface AnnouncementTarget {
    @Serializable
    @SerialName("AllWorlds")
    data object AllWorlds : AnnouncementTarget

    @Serializable
    @SerialName("World")
    data class World(val worldId: Int) : AnnouncementTarget

    @Serializable
    @SerialName("User")
    data class User(val username: String) : AnnouncementTarget

    @Serializable
    @SerialName("FriendsChat")
    data class FriendsChat(val ownerUsername: String) : AnnouncementTarget
}

@Serializable
@SerialName("AnnouncementBroadcast")
data class AnnouncementBroadcast(
    val channel: AnnouncementChannel,
    val message: String,
    val target: AnnouncementTarget = AnnouncementTarget.AllWorlds,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldInfo")
data class GatewayWorldInfo(
    val worldId: Int,
    val worldName: String,
    val host: String,
    val publicHost: String,
    val port: Int,
    val activity: String = "",
    val members: Boolean = true,
    val quickChat: Boolean = false,
    val botsAllowed: Boolean = false,
    val lootShare: Boolean = true,
    val country: String = "USA",
    val highlighted: Boolean = false,
    val cacheCrcs: List<Int> = emptyList(),
) {
    fun toWorldMetadata(): WorldMetadata = WorldMetadata(
        number = worldId,
        ipAddress = publicHost,
        port = port,
        activity = activity.ifEmpty { worldName },
        country = Country.valueOf(country),
        quickchat = quickChat,
        lootShare = lootShare,
        members = members,
        botsAllowed = botsAllowed,
        highlighted = highlighted,
    )

    companion object {
        fun fromEnv(cacheCrcs: List<Int> = emptyList()): GatewayWorldInfo = GatewayWorldInfo(
            worldId = EnvVars.worldId,
            worldName = EnvVars.worldName,
            host = EnvVars.worldHost,
            publicHost = EnvVars.worldPublicHost,
            port = EnvVars.worldPort,
            activity = EnvVars.worldActivity,
            members = EnvVars.worldMembers,
            quickChat = EnvVars.worldQuickChat,
            botsAllowed = EnvVars.worldBotsAllowed,
            lootShare = EnvVars.worldLootShare,
            country = EnvVars.worldCountry,
            highlighted = EnvVars.worldHighlighted,
            cacheCrcs = cacheCrcs,
        )
    }
}

@Serializable
@SerialName("WorldHello")
data class WorldHello(
    val world: GatewayWorldInfo,
    val token: String,
) : SocialGatewayWireMessage

/** One player a world currently has in game, as the world sees them. */
@Serializable
data class GatewayPlayerPresence(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
)

/** Why a world is sending its online list; it decides how much the lobby pushes back. */
@Serializable
enum class PresenceSyncReason {
    /**
     * The socket has just come up. Either side may have restarted, so the lobby rebuilds its
     * presence for this world from scratch and re-sends every listed player the social state
     * their client needs - friend list, ignores, chat filter, friends chat and clan channels -
     * without anybody having to relog.
     */
    CONNECT,

    /**
     * A periodic drift check. The lobby only applies the difference: players it did not know
     * about come online, players it still thought were here go offline, and nobody else is
     * disturbed.
     */
    PERIODIC,
}

/**
 * Everyone a world currently has online, which is the authority for that world's presence. Sent
 * immediately after the [WorldHello] handshake and again on a timer, so a lobby restart, a world
 * restart or a dropped socket can never leave the two sides disagreeing about who is where.
 */
@Serializable
@SerialName("WorldPresenceSnapshot")
data class WorldPresenceSnapshot(
    val players: List<GatewayPlayerPresence>,
    val reason: PresenceSyncReason,
    val requestId: Long = 0,
) : SocialGatewayWireMessage

@Serializable @SerialName("PresenceSnapshotRequest")
data class PresenceSnapshotRequest(val requestId: Long, val reason: PresenceSyncReason) : SocialGatewayWireMessage

@Serializable @SerialName("PresenceSnapshotAck")
data class PresenceSnapshotAck(val requestId: Long, val success: Boolean) : SocialGatewayWireMessage

@Serializable @SerialName("SocialStateUpdate")
data class SocialStateUpdate(
    val account: AccountView,
    val snapshot: SocialEvent.Snapshot,
    val temporaryIgnores: List<IgnoredPlayer> = emptyList(),
) : SocialGatewayWireMessage

@Serializable @SerialName("FriendsChatReplica")
data class FriendsChatReplica(
    val account: AccountView,
    val ownerUsername: String,
    val ownerDisplayName: String,
    val settings: FriendsChat,
    val usernames: Set<String>,
    val channel: FriendsChatState,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldHelloAck")
data class WorldHelloAck(
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

@Serializable
@SerialName("VerifyLoginToken")
data class VerifyLoginToken(
    val requestId: Int,
    val username: String,
    val token: String,
) : SocialGatewayWireMessage

/**
 * Why the lobby would not admit a login token. The world renders each of these as its own response
 * code, so a refusal the player can act on never reaches them as "your password has been updated".
 */
@Serializable
enum class LoginRefusal {
    TOKEN,
    NO_ACCOUNT,
    BANNED,
    NAME_REQUIRED,
}

@Serializable
@SerialName("LoginTokenVerdict")
data class LoginTokenVerdict(
    val requestId: Int,
    val username: String? = null,
    val displayName: String? = null,
    val account: AccountView? = null,
    val refusal: LoginRefusal? = null,
) : SocialGatewayWireMessage

/**
 * World → lobby: [username] wants [action] applied to an account. Worlds never read or write the
 * accounts collection; the lobby judges the actor's rights, persists the change and answers every
 * world the target is on with an [AccountUpdate].
 */
@Serializable
@SerialName("AccountCommand")
data class AccountCommand(
    val username: String,
    val action: AccountAction,
) : SocialGatewayWireMessage

@Serializable
sealed interface AccountAction {
    @Serializable
    @SerialName("SetRights")
    data class SetRights(val target: String, val rights: Rights) : AccountAction

    @Serializable
    @SerialName("Ban")
    data class Ban(val target: String, val until: Long) : AccountAction

    @Serializable
    @SerialName("Mute")
    data class Mute(val target: String, val until: Long) : AccountAction

    @Serializable
    @SerialName("SetFcStatus")
    data class SetFcStatus(val fcStatus: Int) : AccountAction

    companion object {
        const val PERMANENT = Long.MAX_VALUE
        const val LIFTED = 0L
        @JvmStatic
        fun days(days: Int): Long = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000
    }
}

@Serializable
@SerialName("AccountUpdate")
data class AccountUpdate(val account: AccountView) : SocialGatewayWireMessage

@Serializable
@SerialName("DisplayNameLookup")
data class DisplayNameLookup(val requestId: Int, val username: String) : SocialGatewayWireMessage

@Serializable
@SerialName("DisplayNameResult")
data class DisplayNameResult(val requestId: Int, val displayName: String? = null) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOnline")
data class PlayerOnline(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
    val requestId: Long = 0,
) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOffline")
data class PlayerOffline(
    val username: String,
    val requestId: Long = 0,
) : SocialGatewayWireMessage

/** World → lobby: the player [username] asks for [action]. */
@Serializable
@SerialName("SocialCommand")
data class SocialCommand(
    val username: String,
    val action: SocialAction,
    val requestId: Long = 0,
) : SocialGatewayWireMessage

/** Result of a world-local request; failed connections must never replay mutations. */
@Serializable
@SerialName("SocialCommandResult")
data class SocialCommandResult(
    val requestId: Long,
    val success: Boolean,
    val reason: String? = null,
) : SocialGatewayWireMessage

/** Lobby → world: [event] happened to each of [usernames]; the world renders it for each of them. */
@Serializable
@SerialName("SocialNotification")
data class SocialNotification(
    val usernames: List<String>,
    val event: SocialEvent,
) : SocialGatewayWireMessage

// ─── Clans (docs/clans.md) ───────────────────────────────────────────

/**
 * World → lobby: the player [username] asks for [action]. [requestId] is world-local and is
 * echoed in the [ClanCommandResult] so the world can run the callback it kept for it.
 */
@Serializable
@SerialName("ClanCommand")
data class ClanCommand(
    val username: String,
    val requestId: Int,
    val action: ClanAction,
) : SocialGatewayWireMessage

/** World → lobby: send me [clanName] as a [ClanSnapshot], or [ClanDeleted] if it does not exist. */
@Serializable
@SerialName("ClanFetch")
data class ClanFetch(val clanName: String) : SocialGatewayWireMessage

/**
 * Lobby → world: the outcome of one [ClanCommand]. [action] is the [ClanAction.serialName] of
 * the request so a world can act without keeping it; [message] is shown to the player verbatim
 * when present.
 */
@Serializable
@SerialName("ClanCommandResult")
data class ClanCommandResult(
    val username: String,
    val requestId: Int,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

/**
 * Lobby → world: the whole clan document. Broadcast to every world on every change and sent in
 * answer to a [ClanFetch]; worlds replace their copy wholesale.
 */
@Serializable
@SerialName("ClanSnapshot")
data class ClanSnapshot(val clan: Clan) : SocialGatewayWireMessage

/** Lobby → world: the clan no longer exists. */
@Serializable
@SerialName("ClanDeleted")
data class ClanDeleted(val clanName: String) : SocialGatewayWireMessage

/** Lobby → world: a channel's chatter list. Broadcast to every world on every change. */
@Serializable
@SerialName("ClanChannelUpdate")
data class ClanChannelUpdate(val state: ClanChannelState) : SocialGatewayWireMessage

/**
 * Lobby → world: the authoritative clan fields of one player's `Social`, sent to their world
 * whenever they change and on world connect, so the world's own account saves never clobber
 * them. [inChannel] is `connectedToClan`, [guestChannel] is `guestedClanChat`.
 */
@Serializable
@SerialName("ClanMembership")
data class ClanMembership(
    val username: String,
    val clanName: String? = null,
    val inChannel: Boolean,
    val guestChannel: String? = null,
) : SocialGatewayWireMessage

/**
 * Lobby → world: one line said in [clanName]'s channel by [sender] (a display name) whose
 * rights crown is [rights]. Sent to every world holding a chatter of that channel; the world
 * delivers it to its local chatters with `guest = false` for members and `guest = true` for
 * guests of that clan.
 */
@Serializable
@SerialName("ClanChatMessage")
data class ClanChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: ChatMessage,
) : SocialGatewayWireMessage

/** Lobby → world: the quick-chat form of [ClanChatMessage]. */
@Serializable
@SerialName("ClanQuickChatMessage")
data class ClanQuickChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: QuickChatMessage,
) : SocialGatewayWireMessage
