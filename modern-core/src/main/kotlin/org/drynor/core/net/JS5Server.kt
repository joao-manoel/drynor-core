package org.drynor.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.drynor.core.EnvVars
import org.drynor.core.Logger.logInfo
import org.drynor.core.Logger.logTrace
import org.drynor.core.Logger.logWarn
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.file.FileProvider

class JS5Server(val provider: FileProvider, val prefetchKeys: IntArray) {
    val limiter = MultilogLimiter()
    private val botIps = IpAllowList(EnvVars.botIpWhitelist)

    suspend fun init(input: ByteReadChannel, output: ByteWriteChannel, ip: String) {
        val limited = !botIps.contains(ip)
        if (limited && !limiter.add(ip)) {
            output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)
            return
        }
        try {
            val size = input.readByte().toInt() //unused because suspend OP

            var major = input.readInt()
            if (major == LEGACY_CLIENT_VERSION) major = input.readInt() // legacy drynor client prepends a 6; skip it
            if (major != EnvVars.majorVersion || input.readInt() != EnvVars.minorVersion) {
                logTrace("Invalid client version")
                output.writeByte(ResponseOpcode.GAME_UPDATE)
                output.flushAndClose()
                return
            }
            val token = input.readRSString()
            if (token != EnvVars.js5ServerToken) {
                logTrace("Invalid JS5 server token $token")
                output.writeByte(ResponseOpcode.BAD_SESSION_ID)
                output.flushAndClose()
                return
            }
            output.writeByte(ResponseOpcode.JS5_SYNC)
            for (key in prefetchKeys)
                output.writeInt(key)
            output.flush()

            val opcode = input.readByte().toInt()
            if (opcode != RequestOpcode.ACKNOWLEDGE) {
                logInfo("Incorrect acknowledgement: $opcode")
                output.writeByte(ResponseOpcode.JS5_REJECTED)
                output.flushAndClose()
                return
            }

            val id = input.readMedium()
            if (id != 3) {
                logInfo("Incorrect session id: $id")
                output.writeByte(ResponseOpcode.BAD_SESSION_ID)
                output.flushAndClose()
                return
            }
            val endAcknowledgement = input.readUShort()
            if (endAcknowledgement != 0) {
                logInfo("Incorrect end of acknowledgement signifier: $id")
                output.writeByte(ResponseOpcode.BAD_SESSION_ID)
                output.flushAndClose()
                return
            }
            coroutineScope {
                while (isActive) {
                    val opcode = input.readByte().toInt()
//                    logTrace("Handling JS5 opcode: $opcode")
                    when (opcode) {
                        RequestOpcode.STATUS_LOGGED_OUT, RequestOpcode.STATUS_LOGGED_IN -> verify(input, output, 0L)
                        RequestOpcode.JS5_FILE_HIGH_PRIORITY, RequestOpcode.JS5_FILE ->
                            provider.serve(output, input.read40BitULong(), opcode == RequestOpcode.JS5_FILE)

                        RequestOpcode.ENCRYPTION_KEY_UPDATE -> input.readUByte()
                        else -> {
                            logWarn("Invalid JS5 opcode: $opcode")
                            input.cancel()
                            output.flushAndClose()
                        }
                    }
                }
            }
        } finally {
            if (limited) limiter.remove(ip)
        }
    }

    suspend fun verify(input: ByteReadChannel, output: ByteWriteChannel, expected: Long): Boolean {
        val id = input.read40BitULong()
        if (id != expected) {
            output.writeByte(ResponseOpcode.BAD_SESSION_ID)
            output.flushAndClose()
            return false
        }
        return true
    }
}

/** The legacy drynor client's extra handshake int. Frozen; remove with the client. */
private const val LEGACY_CLIENT_VERSION = 6
