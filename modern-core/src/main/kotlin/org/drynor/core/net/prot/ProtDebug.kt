package org.drynor.core.net.prot

import io.ktor.utils.io.*

object ProtDebug {
    suspend fun encodeServerBody(revision: Int, prot: ServerProt): ByteArray {
        val codec = Codec.get(revision) ?: error("No codec registered for revision $revision")
        val entry = codec.serverProts[prot::class] ?: error("No ServerProt codec for ${prot::class} at revision $revision")
        val encoder = entry.encoder ?: return ByteArray(0)

        val channel = ByteChannel()
        encoder.invoke(prot, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead).also { channel.readFully(it) }
        channel.close()
        return bytes
    }

    fun ByteArray.toHexString(): String = joinToString(separator = "") { b -> "%02x".format(b) }
}


