package org.drynor.core.net

import io.ktor.utils.io.*
import org.drynor.core.EnvVars
import org.drynor.core.Logger.logTrace
import org.drynor.core.net.prot.ClientProt
import org.drynor.core.net.prot.Codec
import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.packet.FriendStatus
import org.drynor.core.net.prot.packet.FriendlistLoaded
import org.drynor.core.net.prot.packet.UpdateIgnoreList

open class Session(
    write: ByteWriteChannel,
    isaacIn: Isaac,
    isaacOut: Isaac?,
    ip: String,
    codec: Codec,
    inboundIdleTimeoutMs: Long? = EnvVars.sessionIdleTimeoutMillis,
) : ProtSession<ClientProt, ServerProt>(write, isaacIn, isaacOut, ip, codec, EnvVars.packetQueueCapacity, EnvVars.outboundQueueCapacity, inboundIdleTimeoutMs) {

    @JvmName("getIP")
    fun getIPAddress(): String = ip

    fun isClosed(): Boolean = disconnected

    @Suppress("UNUSED_PARAMETER")
    fun setDecoder(decoder: Any?) {}

    override fun inboundCodec(opcode: Int) = codec.clientProtsByOpcode[opcode]

    override fun outboundCodec(prot: ServerProt) = codec.serverProts[prot::class]

    override fun onWrite(prot: ServerProt, opcode: Int) {
        if (prot is FriendStatus || prot is FriendlistLoaded || prot is UpdateIgnoreList)
            logTrace("Session.send SOCIAL: ${prot::class.simpleName} opcode=$opcode ip=$ip")
    }

    companion object {
        fun isExpectedDisconnect(e: Throwable): Boolean = ProtSession.isExpectedDisconnect(e)
    }
}
