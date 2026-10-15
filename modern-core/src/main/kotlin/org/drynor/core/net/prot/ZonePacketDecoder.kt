package org.drynor.core.net.prot

import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.drynor.core.net.prot.packet.*
import world.gregs.voidps.buffer.readUByte
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass

data class LocCustomisationSizes(val models: Int, val recolors: Int, val retextures: Int)

// CustomizeObject omits its array lengths (the client reads them from its cache), so a decoding client supplies them.
class LocCustomisationSizing(val sizes: (objectId: Int, shape: Int) -> LocCustomisationSizes) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocCustomisationSizing> {
        suspend fun sizes(objectId: Int, shape: Int): LocCustomisationSizes? = coroutineContext[Key]?.sizes(objectId, shape)
    }
}

private class EnclosedZoneStream : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EnclosedZoneStream>
}

internal suspend fun decodingEnclosedZoneStream(): Boolean = coroutineContext[EnclosedZoneStream] != null

object ZonePacketDecoder {

    suspend fun decodeZoneSubPackets(codec: Codec, data: ByteArray): List<ServerProt> = withContext(EnclosedZoneStream()) {
        val source = Buffer().apply { write(data) }
        val packets = mutableListOf<ServerProt>()
        while (!source.exhausted()) {
            packets += decodeSubPacket(codec, source)
        }
        packets
    }

    private suspend fun decodeSubPacket(codec: Codec, source: Source): ServerProt {
        val index = source.readUByte()
        val type = ZonePacketEncoder.SUB_PACKET_TYPES.getOrNull(index) ?: error("Unknown zone sub-packet index $index")
        if (type == SoundAreaTile::class) {
            val sound = decodeAs(codec, SoundEffectTile::class, source) as SoundEffectTile
            return SoundAreaTile(sound.localX, sound.localY, sound.soundId, sound.range, sound.mode, sound.delay, sound.volume, sound.sampleRate, source.readUByte() == 1)
        }
        return decodeAs(codec, type, source)
    }

    private suspend fun decodeAs(codec: Codec, type: KClass<out ServerProt>, source: Source): ServerProt {
        val entry = codec.serverProts[type] ?: error("No codec for zone sub-packet ${type.simpleName}")
        return codec.decode(entry, entry.opcode, source) ?: error("Cannot decode zone sub-packet ${type.simpleName}")
    }
}
