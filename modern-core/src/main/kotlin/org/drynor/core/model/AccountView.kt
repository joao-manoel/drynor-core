package org.drynor.core.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date

@Serializable
data class AccountView @JvmOverloads constructor(
    val username: String,
    var displayName: String,
    var rights: Rights = Rights.PLAYER,
    var banned: Long = 0,
    var muted: Long = 0,
    var social: Social = Social(),
    val bot: Boolean = false,
) {
    fun hasRights(checkRights: Rights) = rights.ordinal >= checkRights.ordinal
    fun checkMuted() = System.currentTimeMillis() < muted
    fun checkBanned() = System.currentTimeMillis() < banned
    fun getUnmuteDate(): String = SimpleDateFormat("dd MMM yyyy HH:mm:ss:SSS Z").format(Date(muted))
    fun muteDays(days: Int) { require(days >= 0); muted = System.currentTimeMillis() + days * 86_400_000L }
    fun mutePerm() { muted = Long.MAX_VALUE }
    fun unmute() { muted = 0 }
    fun banDays(days: Int) { require(days >= 0); banned = System.currentTimeMillis() + days * 86_400_000L }
    fun banPerm() { banned = Long.MAX_VALUE }
    fun unban() { banned = 0 }
}
