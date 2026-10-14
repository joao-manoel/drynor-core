package org.darkan.core.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date

@Serializable
data class AccountView(
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
}
