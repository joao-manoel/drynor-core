package org.darkan.core.net.login

import io.ktor.utils.io.core.*
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.MachineInformation
import org.darkan.core.net.ResponseOpcode
import world.gregs.voidps.buffer.readBoolean
import world.gregs.voidps.buffer.readRSString
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.toRSString
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.Xtea
import world.gregs.voidps.cache.secure.decryptXtea
import java.math.BigInteger

sealed interface LoginDecode<out T> {
    data class Decoded<T>(val request: T) : LoginDecode<T>
    data class Rejected(val response: Int) : LoginDecode<Nothing>
}

class LoginRsaKey(val modulus: BigInteger, val exponent: BigInteger)

const val LOGIN_CRC_COUNT = 36
private const val RSA_MAGIC = 10
private const val FINGERPRINT_SIZE = 24

class LobbyLoginRequest(
    val revision: Int,
    val subRevision: Int,
    val isaacSeed: IntArray,
    val username: String,
    val password: String,
    val loginServerToken: String,
    val clientKey: String,
    val js5ServerToken: String,
    val indexCrcs: IntArray,
    val gameType: Int = 0,
    val language: Int = 0,
    val fingerprint: ByteArray = ByteArray(FINGERPRINT_SIZE),
    val preferences: IntArray = IntArray(0),
) {
    fun encode(key: LoginRsaKey): ByteArray {
        val secure = BufferWriter(256)
        secure.writeByte(RSA_MAGIC)
        isaacSeed.forEach(secure::writeInt)
        secure.writeLong(0)
        secure.writeString(password)
        secure.writeLong(0)
        secure.writeLong(0)

        val xtea = BufferWriter(1024)
        xtea.writeByte(1)
        xtea.writeString(username)
        xtea.writeByte(gameType)
        xtea.writeByte(language)
        xtea.writeBytes(fingerprint)
        xtea.writeString(loginServerToken)
        xtea.writeByte(preferences.size)
        preferences.forEach(xtea::writeByte)
        xtea.writeString(clientKey)
        xtea.writeInt(0)
        xtea.writeInt(0)
        xtea.writeString(js5ServerToken)
        repeat(LOGIN_CRC_COUNT) { xtea.writeInt(indexCrcs.getOrElse(it) { 0 }) }

        val out = BufferWriter(2048)
        out.writeInt(revision)
        out.writeInt(subRevision)
        writeSecure(out, secure.toArray(), key)
        out.writeBytes(encipher(xtea.toArray(), isaacSeed))
        return out.toArray()
    }

    companion object {
        fun decode(revision: Int, subRevision: Int, packet: Source, key: LoginRsaKey, crcCount: Int): LoginDecode<LobbyLoginRequest> {
            val secure = readSecure(packet, key) ?: return LoginDecode.Rejected(ResponseOpcode.BAD_SESSION_ID)
            val isaacSeed = IntArray(4) { secure.readInt() }
            if (secure.readLong().toInt() != 0) return LoginDecode.Rejected(ResponseOpcode.BAD_SESSION_ID)
            val password = secure.readRSString()
            val xtea = packet.decryptXtea(isaacSeed)
            val username = readUsername(xtea)
            val gameType = xtea.readUByte()
            val language = xtea.readUByte()
            val fingerprint = xtea.readByteArray(FINGERPRINT_SIZE)
            val loginServerToken = xtea.readRSString()
            val preferences = IntArray(xtea.readUByte()) { xtea.readUByte() }
            val clientKey = xtea.readRSString()
            xtea.readInt()
            xtea.readInt()
            val js5ServerToken = xtea.readRSString()
            val crcs = IntArray(crcCount) { xtea.readInt() }
            return LoginDecode.Decoded(
                LobbyLoginRequest(revision, subRevision, isaacSeed, username, password, loginServerToken, clientKey, js5ServerToken, crcs, gameType, language, fingerprint, preferences)
            )
        }
    }
}

class WorldLoginRequest(
    val revision: Int,
    val subRevision: Int,
    val isaacSeed: IntArray,
    val username: String,
    val authToken: String,
    val worldToken: String,
    val worldId: Int,
    val indexCrcs: IntArray,
    val machine: MachineInformation = MachineInformation(version = MACHINE_INFO_VERSION),
    val displayMode: Int = 0,
    val screenWidth: Int = 765,
    val screenHeight: Int = 503,
    val settings: String = "",
    val affiliate: Int = 0,
    val fingerprint: ByteArray = ByteArray(FINGERPRINT_SIZE),
    val preferences: IntArray = IntArray(0),
    val reconnecting: Boolean = false,
) {
    fun encode(key: LoginRsaKey): ByteArray {
        val secure = BufferWriter(256)
        secure.writeByte(RSA_MAGIC)
        isaacSeed.forEach(secure::writeInt)
        secure.writeLong(0)
        secure.writeString(authToken)
        secure.writeLong(0)
        secure.writeLong(0)

        val xtea = BufferWriter(2048)
        xtea.writeByte(1)
        xtea.writeString(username)
        xtea.writeByte(displayMode)
        xtea.writeShort(screenWidth)
        xtea.writeShort(screenHeight)
        xtea.writeByte(0)
        xtea.writeBytes(fingerprint)
        xtea.writeString(settings)
        xtea.writeInt(affiliate)
        xtea.writeByte(preferences.size)
        preferences.forEach(xtea::writeByte)
        machine.write(xtea)
        xtea.writeInt(0)
        xtea.writeLong(0)
        xtea.writeString("")
        xtea.writeByte(0)
        xtea.writeByte(0)
        xtea.writeByte(0)
        xtea.writeByte(0)
        xtea.writeByte(0)
        xtea.writeInt(0)
        xtea.writeString(worldToken)
        xtea.writeByte(0)
        xtea.writeInt(worldId)
        repeat(LOGIN_CRC_COUNT) { xtea.writeInt(indexCrcs.getOrElse(it) { 0 }) }

        val out = BufferWriter(4096)
        out.writeInt(revision)
        out.writeInt(subRevision)
        out.writeByte(if (reconnecting) 1 else 0)
        writeSecure(out, secure.toArray(), key)
        out.writeBytes(encipher(xtea.toArray(), isaacSeed))
        return out.toArray()
    }

    companion object {
        const val MACHINE_INFO_VERSION = 6

        fun decode(revision: Int, subRevision: Int, reconnectOpcode: Boolean, packet: Source, key: LoginRsaKey): LoginDecode<WorldLoginRequest> {
            val reconnecting = packet.readUByte() == 1 || reconnectOpcode
            val secure = readSecure(packet, key) ?: return LoginDecode.Rejected(ResponseOpcode.BAD_SESSION_ID)
            val isaacSeed = IntArray(4) { secure.readInt() }
            if (secure.readLong().toInt() != 0) return LoginDecode.Rejected(ResponseOpcode.BAD_SESSION_ID)
            val authToken = secure.readRSString()
            val xtea = packet.decryptXtea(isaacSeed)
            val username = readUsername(xtea)
            val displayMode = xtea.readUByte()
            val screenWidth = xtea.readUShort()
            val screenHeight = xtea.readUShort()
            xtea.readUByte()
            val fingerprint = xtea.readByteArray(FINGERPRINT_SIZE)
            val settings = xtea.readRSString()
            val affiliate = xtea.readInt()
            val preferences = IntArray(xtea.readUByte()) { xtea.readUByte() }
            val machine = MachineInformation.parse(xtea)
            xtea.readInt()
            xtea.readLong()
            xtea.readRSString()
            if (xtea.readUByte() == 1) xtea.readRSString()
            xtea.readUByte()
            xtea.readUByte()
            xtea.readUByte()
            xtea.readByte()
            xtea.readInt()
            val worldToken = xtea.readRSString()
            xtea.readUByte()
            val worldId = xtea.readInt()
            val crcs = IntArray(LOGIN_CRC_COUNT) { xtea.readInt() }
            return LoginDecode.Decoded(
                WorldLoginRequest(revision, subRevision, isaacSeed, username, authToken, worldToken, worldId, crcs, machine, displayMode, screenWidth, screenHeight, settings, affiliate, fingerprint, preferences, reconnecting)
            )
        }
    }
}

private fun readUsername(xtea: Source): String =
    (if (xtea.readBoolean()) xtea.readRSString() else xtea.readLong().toRSString()).formatPlayerNameForProtocol()

private fun readSecure(packet: Source, key: LoginRsaKey): Source? {
    val size = packet.readUShort()
    val secure = ByteReadPacket(RSA.crypt(packet.readByteArray(size), key.modulus, key.exponent))
    return if (secure.readUByte() == RSA_MAGIC) secure else null
}

private fun writeSecure(out: BufferWriter, block: ByteArray, key: LoginRsaKey) {
    val encrypted = RSA.crypt(block, key.modulus, key.exponent)
    out.writeShort(encrypted.size)
    out.writeBytes(encrypted)
}

private fun encipher(block: ByteArray, isaacSeed: IntArray): ByteArray {
    Xtea.encipher(block, 0, block.size, isaacSeed)
    return block
}
