package rs.drynor.core.protocol

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlinx.io.Buffer
import org.drynor.core.net.prot.Codec
import org.drynor.core.net.prot.ProtSize
import org.drynor.core.net.prot.ServerProt

/** A bounded body for transports which own their own framing and ISAAC writer. */
data class PacketBody(val opcode: Int, val size: Int, val bytes: ByteArray) {
    companion object {
        @JvmStatic
        fun decode(codec: Codec, body: PacketBody): ServerProt = runBlocking {
            val source = Buffer().apply { write(body.bytes) }
            val packet = requireNotNull(codec.decodeServerProt(body.opcode, source)) { "Unknown server packet" }
            require(source.exhausted()) { "Packet has trailing bytes" }
            packet
        }

        @JvmStatic
        fun encode(codec: Codec, packet: ServerProt): PacketBody = encodeBody(codec, packet, false)

        /** Handshake responses use a plain response code and length, without ISAAC. */
        @JvmStatic
        fun encodeLogin(codec: Codec, packet: ServerProt): ByteArray {
            val body = encodeBody(codec, packet, true)
            val prefix = when (body.size) {
                -1 -> byteArrayOf(body.opcode.toByte(), body.bytes.size.toByte())
                -2 -> byteArrayOf(body.opcode.toByte(), (body.bytes.size ushr 8).toByte(), body.bytes.size.toByte())
                else -> byteArrayOf(body.opcode.toByte())
            }
            return prefix + body.bytes
        }

        private fun encodeBody(codec: Codec, packet: ServerProt, login: Boolean): PacketBody = runBlocking {
            val registration = requireNotNull(codec.serverProts[packet::class]) { "Unregistered server packet" }
            require((packet::class in codec.loginResponses) == login) {
                if (login) "Expected a login response" else "Login responses need handshake framing"
            }
            val limit = when (val size = registration.size) {
                is ProtSize.Fixed -> size.length
                ProtSize.VarByte -> 255
                ProtSize.VarShort -> 65535
            }
            val channel = ByteChannel(autoFlush = true)
            // Read while encoding: bodies larger than the channel capacity must not deadlock.
            val writer = async {
                try { codec.encodeServerProt(packet, channel) }
                finally { channel.close() }
            }
            try {
                val bytes = channel.readRemaining(limit.toLong() + 1).readByteArray()
                require(bytes.size <= limit) { "Packet body exceeds its protocol size" }
                writer.await()
                if (registration.size is ProtSize.Fixed) require(bytes.size == limit) { "Wrong fixed packet size" }
                PacketBody(registration.opcodeFor(packet), registration.size.toInt(), bytes)
            } finally {
                writer.cancel()
                channel.cancel(null)
            }
        }
    }
}
