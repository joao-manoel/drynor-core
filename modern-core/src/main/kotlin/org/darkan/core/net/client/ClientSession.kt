package org.darkan.core.net.client

import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlin.coroutines.CoroutineContext
import org.darkan.core.net.Isaac
import org.darkan.core.net.ProtSession
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ProtDecodeContext
import org.darkan.core.net.prot.ServerProt

class ClientSession(
    write: ByteWriteChannel,
    isaacIn: Isaac,
    isaacOut: Isaac,
    host: String,
    codec: Codec,
) : ProtSession<ServerProt, ClientProt>(write, isaacIn, isaacOut, host, codec, INBOUND_CAPACITY, OUTBOUND_CAPACITY) {

    @Volatile
    var localPlayerIndex: Int = -1

    var decodeContext: CoroutineContext = ClientDefinitions.locCustomisation

    override suspend fun decodeInbound(prot: Codec.ProtCodec<ServerProt>, opcode: Int, packet: Source): ServerProt? =
        withContext(ProtDecodeContext(localPlayerIndex) + decodeContext) { codec.decode(prot, opcode, packet) }

    override fun inboundCodec(opcode: Int) = codec.serverProtsByOpcode[opcode]

    override fun outboundCodec(prot: ClientProt) = codec.clientProts[prot::class]

    private companion object {
        const val INBOUND_CAPACITY = 4096
        const val OUTBOUND_CAPACITY = 1024
    }
}
