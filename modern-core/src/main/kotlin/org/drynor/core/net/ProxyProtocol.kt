package org.drynor.core.net

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import kotlinx.coroutines.withTimeoutOrNull
import org.drynor.core.EnvVars
import java.net.InetAddress

object ProxyProtocol {
    private const val HEADER_TIMEOUT_MS = 1_000L
    private const val V1_MAX_LENGTH = 107
    private val V2_SIGNATURE = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A)

    private val trustedProxies = IpAllowList(EnvVars.trustedProxies)

    suspend fun clientIp(peerIp: String, input: ByteReadChannel): String? {
        if (!trustedProxies.contains(peerIp)) return peerIp
        return withTimeoutOrNull(HEADER_TIMEOUT_MS) { readSource(input) }
    }

    suspend fun readSource(input: ByteReadChannel): String? = when (input.readByte()) {
        'P'.code.toByte() -> readV1(input)
        V2_SIGNATURE[0] -> readV2(input)
        else -> null
    }

    private suspend fun readV1(input: ByteReadChannel): String? {
        val line = StringBuilder("P")
        while (line.length < V1_MAX_LENGTH) {
            val c = input.readByte().toInt().toChar()
            if (c == '\n') break
            line.append(c)
        }
        if (!line.endsWith('\r')) return null
        val parts = line.trimEnd('\r').split(' ')
        if (parts.size != 6 || parts[0] != "PROXY" || parts[1] !in setOf("TCP4", "TCP6")) return null
        return IpAllowList.literal(parts[2])?.let { InetAddress.getByAddress(it).hostAddress }
    }

    private suspend fun readV2(input: ByteReadChannel): String? {
        val signature = input.readByteArray(V2_SIGNATURE.size - 1)
        for (i in signature.indices)
            if (signature[i] != V2_SIGNATURE[i + 1]) return null
        val versionCommand = input.readByte().toInt() and 0xFF
        val family = input.readByte().toInt() and 0xFF
        val length = ((input.readByte().toInt() and 0xFF) shl 8) or (input.readByte().toInt() and 0xFF)
        val body = input.readByteArray(length)
        if (versionCommand shr 4 != 2 || versionCommand and 0xF != 1) return null
        val addressSize = when (family shr 4) {
            1 -> 4
            2 -> 16
            else -> return null
        }
        if (body.size < addressSize * 2 + 4) return null
        return InetAddress.getByAddress(body.copyOfRange(0, addressSize)).hostAddress
    }
}
