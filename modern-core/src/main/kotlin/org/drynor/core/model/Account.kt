package org.drynor.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonIgnore
import org.drynor.core.formatPlayerNameForDisplay
import org.drynor.core.formatPlayerNameForProtocol
import java.text.SimpleDateFormat
import java.util.*

@Serializable
enum class Rights(val crown: Int) {
    PLAYER(0),
    MOD(1),
    ADMIN(2),
    DEVELOPER(2),
    OWNER(2);
}

@Serializable
class Account {
    var username: String
    var email: String
    var recoveryEmail: String
    var displayName: String
    var prevDisplayName: String = ""
    var displayNameChanges: Int = 0
    var nameChangeRequired: Boolean = false
    var rights: Rights = Rights.PLAYER
    @Contextual
    var previousPasswords: MutableSet<String> = mutableSetOf()
    @Contextual
    var password: IntArray? = null
    var passwordHash: String
    var legacyPass: String? = null
    var social: Social = Social()
    var banned: Long = 0
    var muted: Long = 0
    var lastIp: String? = null
    var bot: Boolean = false

    constructor(username: String, email: String, passwordHash: String, displayName: String = username.formatPlayerNameForDisplay()) {
        this.username = username
        this.email = email
        this.recoveryEmail = email
        this.displayName = displayName
        this.passwordHash = passwordHash
    }

    constructor() {
        this.username = ""
        this.email = ""
        this.recoveryEmail = ""
        this.displayName = ""
        this.passwordHash = ""
    }

    fun checkMuted() = System.currentTimeMillis() < muted
    fun muteSpecific(ms: Long) { muted = System.currentTimeMillis() + ms }
    fun muteDays(days: Int) = muteSpecific(days * (24 * 60 * 60 * 1000L))
    fun mutePerm() { muted = Long.MAX_VALUE }
    fun unmute() { muted = 0 }
    @BsonIgnore
    fun getUnmuteDate() = SimpleDateFormat("dd MMM yyyy HH:mm:ss:SSS Z").format(Date(muted))

    fun checkBanned() = System.currentTimeMillis() < banned
    fun banSpecific(ms: Long) { banned = System.currentTimeMillis() + ms }
    fun banDays(days: Int) = banSpecific(days * (24 * 60 * 60 * 1000L))
    fun banPerm() { banned = Long.MAX_VALUE }
    fun unban() { banned = 0 }
    @BsonIgnore
    fun getUnbanDate() = SimpleDateFormat("dd MMM yyyy HH:mm:ss:SSS Z").format(Date(banned))

    fun copyPunishments(account: Account) {
        banned = account.banned
        muted = account.muted
    }

    fun hasRights(checkRights: Rights) = rights.ordinal >= checkRights.ordinal

    /**
     * True until the account holds a name of its own: either it has never picked one - a client
     * sign-up starts with the email as the username - or a moderator has revoked the one it had.
     */
    @BsonIgnore
    fun mustChooseName() = nameChangeRequired || isUnnamedSignup()

    /**
     * A sign-up that has not been through the name screen yet: `Accounts.createLobby` seeds both
     * the username and the email with the address the client signed up with. Only such an account
     * may have its username written, so this is also what tells a first naming from a rename.
     */
    @BsonIgnore
    fun isUnnamedSignup() = username == email

    /**
     * Forced renames leave [prevDisplayName] alone so the old name never surfaces again; a rename
     * the player chose to spend a change on is remembered the usual way.
     */
    fun renameTo(newDisplayName: String, forced: Boolean) {
        if (!forced) {
            prevDisplayName = displayName
            displayNameChanges--
        }
        displayName = newDisplayName
        nameChangeRequired = false
    }

    fun view() = AccountView(username, displayName, rights, banned, muted, social, bot)

    fun onlineTo(other: Account) =
        !(other.social.status.toInt() == 2 || (other.social.status.toInt() == 1 && !other.social.friends.contains(username)))

    /**
     * Replaces the Argon2 hash and drops every legacy credential (the AES [password] and the
     * SHA-1 [legacyPass]), so the login path can never fall back to the old secret. The hash being
     * replaced is remembered in [previousPasswords].
     */
    fun changePassword(newPasswordHash: String) {
        if (passwordHash.isNotEmpty()) previousPasswords.add(passwordHash)
        passwordHash = newPasswordHash
        password = null
        legacyPass = null
    }

    // NEVER CALL THIS EVER. ONLY TO BE USED FOR ACCOUNT CREATION WHEN PLAYER SELECTS A DISPLAY NAME
    @Deprecated("Only for initial account creation")
    fun initializeUsername(newUsername: String) {
        username = newUsername.formatPlayerNameForProtocol()
        displayName = username.formatPlayerNameForDisplay()
    }
}

@Serializable
data class Social(
    var friends: MutableSet<String> = HashSet(),
    var ignores: MutableSet<String> = HashSet(),
    var tillLogoutIgnores: MutableSet<String>? = null,
    var status: Byte = 0,
    var fcStatus: Byte = 0,
    var clanName: String? = null,
    var connectedToClan: Boolean = false,
    /**
     * Display name of the owner of the friends chat this account is in, remembered across logins
     * so the lobby and the worlds can re-join it (legacy `Social.currentFriendsChat`). Null means
     * "in no channel" - explicitly leaving a channel clears it, logging out does not.
     */
    var currentFriendsChat: String? = "help",
    var guestedClanChat: String? = null,
    var friendsChat: FriendsChat = FriendsChat()
) {
    fun addFriend(account: Account) = friends.add(account.username)
    fun addIgnore(account: Account) = ignores.add(account.username)
    fun removeFriend(account: Account) = friends.remove(account.username)
    fun removeIgnore(account: Account) = ignores.remove(account.username)
}

@Serializable
data class FriendsChat(
    var name: String? = null,
    var friendsChatRanks: MutableMap<String, FriendsChatRank> = HashMap(),
    var rankToEnter: FriendsChatRank = FriendsChatRank.UNRANKED,
    var rankToSpeak: FriendsChatRank = FriendsChatRank.UNRANKED,
    var rankToKick: FriendsChatRank = FriendsChatRank.OWNER,
    var rankToLS: FriendsChatRank = FriendsChatRank.UNRANKED,
    var coinshare: Boolean = false
) {
    fun getRank(username: String) = friendsChatRanks[username] ?: FriendsChatRank.UNRANKED

    fun setRank(name: String, rank: FriendsChatRank?) {
        if (rank == null) {
            friendsChatRanks.remove(name)
            return
        }
        friendsChatRanks[name] = rank
    }
}

@Serializable
enum class FriendsChatRank(val id: Int) {
    UNRANKED(-1),
    FRIEND(0),
    RECRUIT(1),
    CORPORAL(2),
    SERGEANT(3),
    LIEUTENANT(4),
    CAPTAIN(5),
    GENERAL(6),
    OWNER(7),
    JMOD(127);

    companion object {
        private val idMap = entries.associateBy { it.id }
        fun fromId(id: Int): FriendsChatRank = idMap[id] ?: UNRANKED
    }
}