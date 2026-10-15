package org.drynor.core.net

object ResponseOpcode {
    const val JS5_SYNC = 0
    const val VIDEO_AD = 1
    const val SUCCESS = 2
    const val INVALID_CREDENTIALS = 3
    const val ACCOUNT_DISABLED = 4
    const val ACCOUNT_ONLINE = 5
    const val GAME_UPDATE = 6
    const val WORLD_FULL = 7
    const val LOGIN_SERVER_OFFLINE = 8
    const val LOGIN_LIMIT_EXCEEDED = 9
    const val BAD_SESSION_ID = 10
    const val PASSWORD_INSECURE = 11
    const val MEMBERS_ACCOUNT_REQUIRED = 12
    const val COULD_NOT_COMPLETE_LOGIN = 13
    const val SERVER_BEING_UPDATED = 14
    const val RECONNECTING = 15
    const val LOGIN_ATTEMPTS_EXCEEDED = 16
    const val MEMBERS_ONLY_AREA = 17
    const val INVALID_LOGIN_SERVER = 20
    const val TRANSFERRING_PROFILE = 21
    const val NO_REPLY_FROM_LOGIN_SERVER = 23
    const val DISPLAY_NAME_CHANGE_REQUIRED = 31

    // The JS5 handshake has its own reply table: 0 is accepted, anything else is an error screen.
    const val JS5_REJECTED = 11
}