package org.darkan.core.net

object RequestOpcode {
    const val JS5_FILE = 0
    const val JS5_FILE_HIGH_PRIORITY = 1
    const val STATUS_LOGGED_IN = 2
    const val STATUS_LOGGED_OUT = 3
    const val ENCRYPTION_KEY_UPDATE = 4
    const val ACKNOWLEDGE = 6
    const val SESSION = 10
    const val CONNECT_LOGIN = 14
    const val JS5_INIT = 15
    const val LOGIN = 16
    const val RECONNECT = 18
    const val LOBBY = 19
    const val ACCOUNT_CREATION = 28
}