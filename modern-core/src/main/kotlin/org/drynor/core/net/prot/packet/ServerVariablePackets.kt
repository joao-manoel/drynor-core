/**
 * Var packets: player vars, client vars, varbits and clan vars.
 */
package org.drynor.core.net.prot.packet

import kotlinx.serialization.Serializable
import org.drynor.core.net.prot.ServerProt

data class VarpSmall(val id: Int, val value: Int) : ServerProt

data class VarpLarge(val id: Int, val value: Int) : ServerProt

fun setVarpPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) VarpLarge(id, value) else VarpSmall(id, value)

data class ClientSetVarcSmall(val id: Int, val value: Int) : ServerProt

data class ClientSetVarcLarge(val id: Int, val value: Int) : ServerProt

fun setVarcPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) ClientSetVarcLarge(id, value) else ClientSetVarcSmall(id, value)

data class VarbitSmall(val id: Int, val value: Int) : ServerProt

data class VarbitLarge(val id: Int, val value: Int) : ServerProt

fun setVarbitPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) VarbitLarge(id, value) else VarbitSmall(id, value)

data class ClientSetVarcStrSmall(val id: Int, val value: String) : ServerProt

data class ClientSetVarcStrLarge(val id: Int, val value: String) : ServerProt

fun setVarcStrPacket(id: Int, value: String) = if (value.length+2 > Byte.MAX_VALUE) ClientSetVarcStrLarge(id, value) else ClientSetVarcStrSmall(id, value)

data class VarclanSetLong(val id: Int, val value: Long) : ServerProt

data class VarclanSetByte(val id: Int, val value: Int) : ServerProt

data class VarclanSetInt(val id: Int, val value: Int) : ServerProt

data class VarclanSetString(val id: Int, val value: String) : ServerProt

@Serializable data class VarclanEnable(val dummy: Int = 0) : ServerProt

@Serializable data class VarclanDisable(val dummy: Int = 0) : ServerProt

@JvmInline value class ClearVarps(val dummy: Int = 0) : ServerProt
