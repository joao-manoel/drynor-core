package org.darkan.core.net.prot

import world.gregs.voidps.buffer.Cp1252
import java.io.ByteArrayOutputStream
import org.darkan.core.net.prot.packet.*
import kotlin.reflect.KClass

/**
 * Encodes a list of zone [ServerProt] sub-packets into a [ByteArray] suitable for
 * embedding in [UpdateZonePartialEnclosed].
 *
 * Each sub-packet is written as:
 *   1. One byte: the ordinal of the zone packet type (from the UpdateZonePacket enum)
 *   2. The encoded body bytes matching exactly what the Rev727 codec produces
 *
 * Ordinal mapping (must match client's UpdateZonePacket enum):
 * ```
 *  0 = CustomizeObject     (CUSTOMIZE_OBJECT)
 *  1 = RemoveGroundItem    (REMOVE_GROUND_ITEM)
 *  2 = CreateGroundItem    (CREATE_GROUND_ITEM)
 *  3 = DestroyObject       (DESTROY_OBJECT)
 *  4 = CreateObject        (CREATE_OBJECT)
 *  5 = MapProjAnim         (MAP_PROJANIM)
 *  6 = MapProjAnimHalfSq   (MAP_PROJANIM_HALFSQ)
 *  7 = ObjectPrefetch      (OBJECT_PREFETCH)
 *  8 = GroundItemReveal    (GROUND_ITEM_REVEAL)
 *  9 = GroundItemCount     (GROUND_ITEM_COUNT)
 * 10 = SoundEffectTile     (MIDI_SONG_LOCATION, 8 bytes)
 * 11 = TileMessage         (TILE_MESSAGE)
 * 12 = ObjAnim             (OBJ_ANIM)
 * 13 = SoundAreaTile       (SOUND_AREA, 9 bytes)
 * 14 = SpotAnim            (SPOT_ANIM)
 * ```
 */
object ZonePacketEncoder {

    fun encodeZoneSubPackets(packets: List<ServerProt>): ByteArray {
        val out = ByteArrayOutputStream(256)
        for (packet in packets) {
            encodeSubPacket(out, packet)
        }
        return out.toByteArray()
    }

    val SUB_PACKET_TYPES: List<KClass<out ServerProt>> = listOf(
        CustomizeObject::class,
        RemoveGroundItem::class,
        CreateGroundItem::class,
        DestroyObject::class,
        CreateObject::class,
        MapProjAnim::class,
        MapProjAnimHalfSq::class,
        ObjectPrefetch::class,
        GroundItemReveal::class,
        GroundItemCount::class,
        SoundEffectTile::class,
        TileMessage::class,
        ObjAnim::class,
        SoundAreaTile::class,
        SpotAnim::class,
    )

    private fun encodeSubPacket(out: ByteArrayOutputStream, packet: ServerProt) {
        val index = SUB_PACKET_TYPES.indexOf(packet::class)
        if (index == -1) error("Unknown zone packet type: ${packet::class.simpleName}")
        out.write(index)
        when (packet) {
            is CustomizeObject -> encodeCustomizeObject(out, packet)
            is RemoveGroundItem -> encodeRemoveGroundItem(out, packet)
            is CreateGroundItem -> encodeCreateGroundItem(out, packet)
            is DestroyObject -> encodeDestroyObject(out, packet)
            is CreateObject -> encodeCreateObject(out, packet)
            is MapProjAnim -> encodeMapProjAnim(out, packet)
            is MapProjAnimHalfSq -> encodeMapProjAnimHalfSq(out, packet)
            is ObjectPrefetch -> encodeObjectPrefetch(out, packet)
            is GroundItemReveal -> encodeGroundItemReveal(out, packet)
            is GroundItemCount -> encodeGroundItemCount(out, packet)
            is SoundEffectTile -> encodeSoundEffectTile(out, packet)
            is TileMessage -> encodeTileMessage(out, packet)
            is ObjAnim -> encodeObjAnim(out, packet)
            is SoundAreaTile -> encodeSoundAreaTile(out, packet)
            is SpotAnim -> encodeSpotAnim(out, packet)
            else -> error("Unknown zone packet type: ${packet::class.simpleName}")
        }
    }

    // --- Individual encoders matching Rev727ServerCodecsZone.kt exactly ---

    // opcode 84, VarByte
    private fun encodeCustomizeObject(out: ByteArrayOutputStream, p: CustomizeObject) {
        writeInt(out, p.objectId)
        writeByteAdd(out, (p.shape shl 2) or (p.rotation and 0x3))
        var flags = 0
        if (p.clear) {
            flags = flags or 0x1
        } else {
            if (p.modelIds != null) flags = flags or 0x2
            if (p.recolors != null) flags = flags or 0x4
            if (p.retextures != null) flags = flags or 0x8
        }
        writeByte(out, flags)
        writeByteAdd(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        if (!p.clear) {
            p.modelIds?.forEach { writeInt(out, it) }
            p.recolors?.forEach { writeShort(out, it.toInt() and 0xFFFF) }
            p.retextures?.forEach { writeShort(out, it.toInt() and 0xFFFF) }
        }
    }

    // opcode 125, size 3
    private fun encodeRemoveGroundItem(out: ByteArrayOutputStream, p: RemoveGroundItem) {
        writeByteInverse(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShortAddLittle(out, p.itemId)
    }

    // opcode 27, size 5
    private fun encodeCreateGroundItem(out: ByteArrayOutputStream, p: CreateGroundItem) {
        writeShortAddLittle(out, p.amount)
        writeShortAdd(out, p.itemId)
        writeByteInverse(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
    }

    // opcode 69, size 2
    private fun encodeDestroyObject(out: ByteArrayOutputStream, p: DestroyObject) {
        writeByteAdd(out, (p.shape shl 2) or (p.rotation and 0x3))
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
    }

    // opcode 117, size 6
    private fun encodeCreateObject(out: ByteArrayOutputStream, p: CreateObject) {
        writeInt(out, p.objectId)
        writeByteSubtract(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeByteSubtract(out, (p.shape shl 2) or (p.rotation and 0x3))
    }

    // opcode 143, size 16
    private fun encodeMapProjAnim(out: ByteArrayOutputStream, p: MapProjAnim) {
        val flags = (if (p.useFloorHeight) 0x80 else 0) or ((p.localX and 0x7) shl 3) or (p.localY and 0x7)
        writeByte(out, flags)
        writeByte(out, p.xOffset)
        writeByte(out, p.yOffset)
        writeShort(out, p.lockOn)
        writeShort(out, p.spotAnimId)
        writeByte(out, p.startHeight / 4)
        writeByte(out, p.endHeight / 4)
        writeShort(out, p.startTime)
        writeShort(out, p.endTime)
        writeByte(out, if (p.angle == -1) 255 else p.angle)
        writeShort(out, p.slope)
    }

    // opcode 95, size 19
    private fun encodeMapProjAnimHalfSq(out: ByteArrayOutputStream, p: MapProjAnimHalfSq) {
        val coords = ((p.localX and 0xF) shl 4) or (p.localY and 0xF)
        writeByte(out, coords)
        val hasBas = p.basOffIdx != -1
        val flags = (if (p.useFloorHeight) 0x1 else 0) or (if (hasBas) 0x2 else 0) or (if (hasBas) (p.basOffIdx shl 2) else 0)
        writeByte(out, flags)
        writeByte(out, p.xOffset)
        writeByte(out, p.yOffset)
        writeShort(out, p.source)
        writeShort(out, p.lockOn)
        writeShort(out, p.spotAnimId)
        if (hasBas) {
            writeByte(out, p.startHeight)
        } else {
            writeByte(out, p.startHeight / 4)
        }
        writeByte(out, p.endHeight / 4)
        writeShort(out, p.startTime)
        writeShort(out, p.endTime)
        writeByte(out, if (p.angle == -1) 255 else p.angle)
        writeShort(out, p.slope)
    }

    // opcode 88, size 5
    private fun encodeObjectPrefetch(out: ByteArrayOutputStream, p: ObjectPrefetch) {
        writeInt(out, p.objectId)
        writeByte(out, p.typeIndex)
    }

    // opcode 107, size 7
    private fun encodeGroundItemReveal(out: ByteArrayOutputStream, p: GroundItemReveal) {
        writeShort(out, p.playerId)
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShortAddLittle(out, p.amount)
        writeShortAddLittle(out, p.itemId)
    }

    // opcode 3, size 7
    private fun encodeGroundItemCount(out: ByteArrayOutputStream, p: GroundItemCount) {
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShort(out, p.itemId)
        writeShort(out, p.oldAmount)
        writeShort(out, p.newAmount)
    }

    // opcode 128, size 8
    private fun encodeSoundEffectTile(out: ByteArrayOutputStream, p: SoundEffectTile) {
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShort(out, if (p.soundId == -1) 65535 else p.soundId)
        writeByte(out, ((p.range and 0xF) shl 4) or (p.mode and 0x7))
        writeByte(out, p.delay)
        writeByte(out, p.volume)
        writeShort(out, p.sampleRate)
    }

    // ordinal 13 only, 9 bytes
    private fun encodeSoundAreaTile(out: ByteArrayOutputStream, p: SoundAreaTile) {
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShort(out, if (p.soundId == -1) 65535 else p.soundId)
        writeByte(out, ((p.range and 0xF) shl 4) or (p.mode and 0x7))
        writeByte(out, p.delay)
        writeByte(out, p.volume)
        writeShort(out, p.sampleRate)
        writeByte(out, if (p.voice) 1 else 0)
    }

    // opcode 114, VarByte
    private fun encodeTileMessage(out: ByteArrayOutputStream, p: TileMessage) {
        writeByte(out, 0) // Unused
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShort(out, p.priority)
        writeByte(out, p.duration)
        writeMedium(out, p.color)
        writeRSString(out, p.message)
    }

    // opcode 113, size 6
    private fun encodeObjAnim(out: ByteArrayOutputStream, p: ObjAnim) {
        writeByteSubtract(out, (p.shape shl 2) or (p.rotation and 0x3))
        writeByteSubtract(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeIntLittle(out, p.animationId)
    }

    // opcode 139, size 8
    private fun encodeSpotAnim(out: ByteArrayOutputStream, p: SpotAnim) {
        writeByte(out, ((p.localX and 0x7) shl 4) or (p.localY and 0x7))
        writeShort(out, if (p.spotAnimId == -1) 65535 else p.spotAnimId)
        writeShort(out, p.height)
        writeShort(out, p.speed)
        writeByte(out, p.rotation)
    }

    // --- Byte-level write helpers matching JagExtensions.kt transformations ---

    private fun writeByte(out: ByteArrayOutputStream, value: Int) {
        out.write(value)
    }

    private fun writeByteAdd(out: ByteArrayOutputStream, value: Int) {
        out.write(value + 128)
    }

    private fun writeByteInverse(out: ByteArrayOutputStream, value: Int) {
        out.write(-value)
    }

    private fun writeByteSubtract(out: ByteArrayOutputStream, value: Int) {
        out.write(-value + 128)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeShortAdd(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        writeByteAdd(out, value)
    }

    private fun writeShortAddLittle(out: ByteArrayOutputStream, value: Int) {
        writeByteAdd(out, value)
        out.write(value shr 8)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 24)
        out.write(value shr 16)
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeIntLittle(out: ByteArrayOutputStream, value: Int) {
        out.write(value)
        out.write(value shr 8)
        out.write(value shr 16)
        out.write(value shr 24)
    }

    private fun writeIntMiddle(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
        out.write(value shr 24)
        out.write(value shr 16)
    }

    private fun writeMedium(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 16)
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeRSString(out: ByteArrayOutputStream, value: String) {
        // Cp1252, not UTF-8: the protocol is one byte per character, and a wider encoding would
        // both corrupt the text and desync the enclosing sub-packet stream.
        out.write(Cp1252.encode(value))
        out.write(0)
    }
}
