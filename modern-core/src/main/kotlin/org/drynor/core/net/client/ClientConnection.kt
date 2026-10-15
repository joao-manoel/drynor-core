package org.drynor.core.net.client

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.Source
import org.drynor.core.net.Isaac
import org.drynor.core.net.RequestOpcode
import org.drynor.core.net.ResponseOpcode
import org.drynor.core.net.login.LobbyLoginRequest
import org.drynor.core.net.login.LoginRsaKey
import org.drynor.core.net.login.WorldLoginRequest
import org.drynor.core.net.prot.Codec
import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.packet.LobbyLoginDetails
import org.drynor.core.net.prot.packet.WorldLoginDetails
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.buffer.writeShort
import java.io.Closeable

sealed interface LoginResult<out T : ServerProt> {
    data class Connected<T : ServerProt>(val connection: ClientConnection, val details: T) : LoginResult<T>
    data class Refused(val response: Int) : LoginResult<Nothing>
}

class ClientConnection private constructor(
    private val selector: SelectorManager,
    private val socket: Socket,
    private val input: ByteReadChannel,
    val session: ClientSession,
) : Closeable {

    fun start(scope: CoroutineScope): Job = scope.launch { session.readPackets(input) }

    override fun close() {
        session.close()
        socket.close()
        selector.close()
    }

    companion object {
        private const val SERVER_CIPHER_OFFSET = 50

        suspend fun lobby(host: String, port: Int, codec: Codec, request: LobbyLoginRequest, key: LoginRsaKey): LoginResult<LobbyLoginDetails> =
            login(host, port, codec, RequestOpcode.LOBBY, request.encode(key), request.isaacSeed) { codec.decodeServerProt<LobbyLoginDetails>(it) }

        suspend fun world(host: String, port: Int, codec: Codec, request: WorldLoginRequest, key: LoginRsaKey): LoginResult<WorldLoginDetails> {
            val opcode = if (request.reconnecting) RequestOpcode.RECONNECT else RequestOpcode.LOGIN
            return login(host, port, codec, opcode, request.encode(key), request.isaacSeed) { codec.decodeServerProt<WorldLoginDetails>(it) }
        }

        private suspend fun <T : ServerProt> login(
            host: String,
            port: Int,
            codec: Codec,
            opcode: Int,
            block: ByteArray,
            isaacSeed: IntArray,
            decodeDetails: suspend (Source) -> T,
        ): LoginResult<T> {
            val selector = SelectorManager(Dispatchers.IO)
            val socket = try {
                aSocket(selector).tcp().connect(host, port) { noDelay = true }
            } catch (e: Exception) {
                selector.close()
                throw e
            }
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            try {
                output.writeByte(RequestOpcode.CONNECT_LOGIN)
                output.flush()
                val sync = input.readUByte()
                if (sync != ResponseOpcode.JS5_SYNC) return refuse(socket, selector, sync)
                output.writeByte(opcode)
                output.writeShort(block.size)
                output.writeFully(block)
                output.flush()
                val response = input.readUByte()
                if (response != ResponseOpcode.SUCCESS) return refuse(socket, selector, response)
                val details = decodeDetails(input.readPacket(input.readUByte()))
                val serverSeed = IntArray(isaacSeed.size) { isaacSeed[it] + SERVER_CIPHER_OFFSET }
                val session = ClientSession(output, Isaac(serverSeed), Isaac(isaacSeed.copyOf()), host, codec)
                return LoginResult.Connected(ClientConnection(selector, socket, input, session), details)
            } catch (e: Exception) {
                socket.close()
                selector.close()
                throw e
            }
        }

        private fun refuse(socket: Socket, selector: SelectorManager, response: Int): LoginResult.Refused {
            socket.close()
            selector.close()
            return LoginResult.Refused(response)
        }
    }
}
