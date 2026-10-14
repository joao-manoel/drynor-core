package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import io.ktor.utils.io.core.remaining
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.type.Tile

internal fun Codec.registerRev727ZonePackets() {
    serverProt<UpdateZoneFullFollows>(opcode = 15, size = 3) { out ->
        out.writeByteSubtract(chunkX)
        out.writeByte(chunkY)
        out.writeByte(plane)
    }.decoder {
        val chunkX = readByteSubtract()
        val chunkY = readByte().toInt()
        UpdateZoneFullFollows(chunkX, chunkY, readUByte())
    }

    serverProt<UpdateZonePartialFollows>(opcode = 41, size = 3) { out ->
        out.writeByteAdd(chunkY)
        out.writeByteAdd(plane)
        out.writeByteAdd(chunkX)
    }.decoder {
        val chunkY = readByteAdd()
        val plane = readByteAdd() and 0xff
        UpdateZonePartialFollows(readByteAdd(), chunkY, plane)
    }

    serverProt<UpdateZonePartialEnclosed>(opcode = 65, size = ProtSize.VarShort) { out ->
        out.writeByteSubtract(chunkY)
        out.writeByteAdd(plane)
        out.writeByte(chunkX)
        out.writeBytes(data)
    }.decoder {
        val chunkY = readByteSubtract()
        val plane = readByteAdd() and 0xff
        val chunkX = readByte().toInt()
        UpdateZonePartialEnclosed(chunkX, chunkY, plane, readByteArray())
    }

    serverProt<TileMessage>(opcode = 114, size = ProtSize.VarByte) { out ->
        out.writeByte(0) // Unused
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(priority)
        out.writeByte(duration)
        out.writeMedium(color)
        out.writeRSString(message)
    }.decoder {
        readByte()
        val coords = readUByte()
        val priority = readUShort()
        val duration = readUByte()
        val color = readUMedium()
        TileMessage(coords shr 4 and 0x7, coords and 0x7, priority, duration, color, readRSString())
    }

    // Ground item protocols
    serverProt<GroundItemCount>(opcode = 3, size = 7) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(itemId)
        out.writeShort(oldAmount)
        out.writeShort(newAmount)
    }.decoder {
        val coords = readUByte()
        GroundItemCount(coords shr 4 and 0x7, coords and 0x7, readUShort(), readUShort(), readUShort())
    }

    serverProt<CreateGroundItem>(opcode = 27, size = 5) { out ->
        out.writeShortAddLittle(amount)
        out.writeShortAdd(itemId)
        out.writeByteInverse(((localX and 0x7) shl 4) or (localY and 0x7))
    }.decoder {
        val amount = readUShortAddLittle()
        val itemId = readUShortAdd()
        val coords = readByteInverse() and 0xff
        CreateGroundItem(coords shr 4 and 0x7, coords and 0x7, itemId, amount)
    }

    serverProt<GroundItemReveal>(opcode = 107, size = 7) { out ->
        out.writeShort(playerId)
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShortAddLittle(amount)
        out.writeShortAddLittle(itemId)
    }.decoder {
        val playerId = readUShort()
        val coords = readUByte()
        val amount = readUShortAddLittle()
        GroundItemReveal(playerId, coords shr 4 and 0x7, coords and 0x7, readUShortAddLittle(), amount)
    }

    serverProt<RemoveGroundItem>(opcode = 125, size = 3) { out ->
        out.writeByteInverse(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShortAddLittle(itemId)
    }.decoder {
        val coords = readByteInverse() and 0xff
        RemoveGroundItem(coords shr 4 and 0x7, coords and 0x7, readUShortAddLittle())
    }

    // Object protocols
    serverProt<DestroyObject>(opcode = 69, size = 2) { out ->
        out.writeByteAdd((shape shl 2) or (rotation and 0x3))
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
    }.decoder {
        val display = readByteAdd() and 0xff
        val coords = readUByte()
        DestroyObject(coords shr 4 and 0x7, coords and 0x7, display shr 2, display and 0x3)
    }

    serverProt<CreateObject>(opcode = 117, size = 6) { out ->
        out.writeInt(objectId)
        out.writeByteSubtract(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
    }.decoder {
        val objectId = readInt()
        val coords = readByteSubtract() and 0xff
        val display = readByteSubtract() and 0xff
        CreateObject(coords shr 4 and 0x7, coords and 0x7, objectId, display shr 2, display and 0x3)
    }

    serverProt<CustomizeObject>(opcode = 84, size = ProtSize.VarByte) { out ->
        out.writeInt(objectId)
        out.writeByteAdd((shape shl 2) or (rotation and 0x3))
        var flags = 0
        if (clear) {
            flags = flags or 0x1
        } else {
            if (modelIds != null) flags = flags or 0x2
            if (recolors != null) flags = flags or 0x4
            if (retextures != null) flags = flags or 0x8
        }
        out.writeByte(flags)
        out.writeByteAdd(((localX and 0x7) shl 4) or (localY and 0x7))
        if (!clear) {
            modelIds?.forEach { out.writeInt(it) }
            recolors?.forEach { out.writeShort(it.toInt() and 0xFFFF) }
            retextures?.forEach { out.writeShort(it.toInt() and 0xFFFF) }
        }
    }.decoder {
        val objectId = readInt()
        val display = readByteAdd() and 0xff
        val flags = readUByte()
        val coords = readByteAdd() and 0xff
        val localX = coords shr 4 and 0x7
        val localY = coords and 0x7
        val shape = display shr 2
        val rotation = display and 0x3
        if (flags and 0x1 != 0) {
            CustomizeObject(localX, localY, objectId, shape, rotation, clear = true)
        } else {
            val sizes = customisationSizes(objectId, shape, flags)
            CustomizeObject(
                localX, localY, objectId, shape, rotation,
                modelIds = if (flags and 0x2 != 0) IntArray(sizes.models) { readInt() } else null,
                recolors = if (flags and 0x4 != 0) ShortArray(sizes.recolors) { readShort() } else null,
                retextures = if (flags and 0x8 != 0) ShortArray(sizes.retextures) { readShort() } else null,
            )
        }
    }

    serverProt<ObjectPrefetch>(opcode = 88, size = 5) { out ->
        out.writeInt(objectId)
        out.writeByte(typeIndex)
    }.decoder {
        ObjectPrefetch(readInt(), readUByte())
    }

    serverProt<ObjAnim>(opcode = 113, size = 6) { out ->
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
        out.writeByteSubtract(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeIntLittle(animationId)
    }.decoder {
        val display = readByteSubtract() and 0xff
        val coords = readByteSubtract() and 0xff
        ObjAnim(coords shr 4 and 0x7, coords and 0x7, display shr 2, display and 0x3, readIntLittle())
    }

    serverProt<ObjAnimSpecific>(opcode = 80, size = 9) { out ->
        out.writeIntInverseMiddle(tile.id)
        out.writeIntLittle(animationId)
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
    }.decoder {
        val tile = Tile(readIntInverseMiddle())
        val animationId = readIntLittle()
        val display = readByteSubtract() and 0xff
        ObjAnimSpecific(tile, display shr 2, display and 0x3, animationId)
    }

    // Spot anim and projectile protocols
    serverProt<SpotAnim>(opcode = 139, size = 8) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(if (spotAnimId == -1) 65535 else spotAnimId)
        out.writeShort(height)
        out.writeShort(speed)
        out.writeByte(rotation)
    }.decoder {
        val coords = readUByte()
        val spotAnimId = readUShort().let { if (it == 65535) -1 else it }
        SpotAnim(coords shr 4 and 0x7, coords and 0x7, spotAnimId, readUShort(), readUShort(), readUByte())
    }

    serverProt<SpotAnimSpecific>(opcode = 126, size = 12) { out ->
        out.writeByteInverse(slot)
        out.writeShortAdd(if (spotAnimId == -1) 65535 else spotAnimId)
        out.writeByteInverse(settings)
        out.writeShortAdd(speed)
        out.writeIntLittle(targetHash)
        out.writeShortLittle(height)
    }.decoder {
        val slot = readByteInverse() and 0xff
        val spotAnimId = readUShortAdd().let { if (it == 65535) -1 else it }
        val settings = readByteInverse() and 0xff
        val speed = readUShortAdd()
        val targetHash = readIntLittle()
        SpotAnimSpecific(slot, spotAnimId, settings, speed, targetHash, readUShortLittle())
    }

    serverProt<MapProjAnim>(opcode = 143, size = 16) { out ->
        val flags = (if (useFloorHeight) 0x80 else 0) or ((localX and 0x7) shl 3) or (localY and 0x7)
        out.writeByte(flags)
        out.writeByte(xOffset)
        out.writeByte(yOffset)
        out.writeShort(lockOn)
        out.writeShort(spotAnimId)
        out.writeByte(startHeight / 4)
        out.writeByte(endHeight / 4)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(if (angle == -1) 255 else angle)
        out.writeShort(slope)
    }.decoder {
        val flags = readUByte()
        val xOffset = readByte().toInt()
        val yOffset = readByte().toInt()
        val lockOn = readShort().toInt()
        val spotAnimId = readUShort()
        val startHeight = readUByte() * 4
        val endHeight = readUByte() * 4
        val startTime = readUShort()
        val endTime = readUShort()
        val angle = readUByte().let { if (it == 255) -1 else it }
        MapProjAnim(
            flags shr 3 and 0x7, flags and 0x7, xOffset, yOffset, lockOn, spotAnimId,
            startHeight, endHeight, startTime, endTime, angle, readUShort(), flags and 0x80 != 0,
        )
    }

    // The client frames opcode 95 with ServerProt size 17 but decodes 19 bytes
    // (its two size tables contradict each other), so a standalone send always
    // desyncs the stream. Send this enclosed in UpdateZonePartialEnclosed, where
    // the sub-packet stream is self-delimiting and 19 is correct.
    serverProt<MapProjAnimHalfSq>(opcode = 95, size = 19) { out ->
        val coords = ((localX and 0xF) shl 4) or (localY and 0xF)
        out.writeByte(coords)
        val hasBas = basOffIdx != -1
        val flags = (if (useFloorHeight) 0x1 else 0) or (if (hasBas) 0x2 else 0) or (if (hasBas) (basOffIdx shl 2) else 0)
        out.writeByte(flags)
        out.writeByte(xOffset)
        out.writeByte(yOffset)
        out.writeShort(source)
        out.writeShort(lockOn)
        out.writeShort(spotAnimId)
        if (hasBas) {
            out.writeByte(startHeight)
        } else {
            out.writeByte(startHeight / 4)
        }
        out.writeByte(endHeight / 4)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(if (angle == -1) 255 else angle)
        out.writeShort(slope)
    }.decoder {
        val coords = readUByte()
        val flags = readUByte()
        val basOffIdx = if (flags and 0x2 != 0) flags shr 2 else -1
        val xOffset = readByte().toInt()
        val yOffset = readByte().toInt()
        val source = readShort().toInt()
        val lockOn = readShort().toInt()
        val spotAnimId = readUShort()
        val startHeight = if (basOffIdx != -1) readByte().toInt() else readUByte() * 4
        val endHeight = readUByte() * 4
        val startTime = readUShort()
        val endTime = readUShort()
        val angle = readUByte().let { if (it == 255) -1 else it }
        MapProjAnimHalfSq(
            coords shr 4 and 0xF, coords and 0xF, xOffset, yOffset, source, lockOn, spotAnimId,
            startHeight, endHeight, startTime, endTime, angle, readUShort(), basOffIdx, flags and 0x1 != 0,
        )
    }

    serverProt<ProjAnimSpecific>(opcode = 56, size = 22) { out ->
        out.writeByteAdd(endHeight / 4)
        out.writeShortAddLittle(source)
        out.writeShortLittle(endTime)
        out.writeShort(lockOn)
        out.writeByteAdd(flags)
        out.writeShortAdd(y)
        out.writeShort(slope)
        out.writeShortAddLittle(startTime)
        out.writeShortAddLittle(spotAnimId)
        out.writeByteAdd(if (angle == -1) 255 else angle)
        out.writeByteInverse(xDist)
        out.writeByteInverse(yDist)
        out.writeShortAdd(x)
        // The client scales startHeight by 4 unless the BAS-offset flag (0x2) is
        // set, in which case it reinterprets the byte as signed - same rule as
        // MapProjAnimHalfSq, and it must match the `endHeight / 4` above.
        out.writeByteInverse(if ((flags and 0x2) != 0) startHeight else startHeight / 4)
    }.decoder {
        val endHeight = (readByteAdd() and 0xff) * 4
        val source = readShortAddLittle().toShort().toInt()
        val endTime = readUShortLittle()
        val lockOn = readShort().toInt()
        val flags = readByteAdd() and 0xff
        val y = readUShortAdd()
        val slope = readUShort()
        val startTime = readUShortAddLittle()
        val spotAnimId = readUShortAddLittle()
        val angle = (readByteAdd() and 0xff).let { if (it == 255) -1 else it }
        val xDist = readByteInverse()
        val yDist = readByteInverse()
        val x = readUShortAdd()
        val startHeight = readByteInverse().let { if (flags and 0x2 != 0) it else (it and 0xff) * 4 }
        ProjAnimSpecific(endHeight, source, endTime, lockOn, flags, y, slope, startTime, spotAnimId, angle, xDist, yDist, x, startHeight)
    }

    // Sound protocols (zone-specific)
    // The client frames opcode 128 with ServerProt size 6 but decodes 8 bytes -
    // same self-contradiction as opcode 95. Send this enclosed in
    // UpdateZonePartialEnclosed rather than standalone.
    serverProt<SoundEffectTile>(opcode = 128, size = 8) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(((range and 0xF) shl 4) or (mode and 0x7))
        out.writeByte(delay)
        out.writeByte(volume)
        out.writeShort(sampleRate)
    }.decoder {
        val coords = readUByte()
        val soundId = readUShort().let { if (it == 65535) -1 else it }
        val settings = readUByte()
        SoundEffectTile(coords shr 4 and 0x7, coords and 0x7, soundId, settings shr 4 and 0xF, settings and 0x7, readUByte(), readUByte(), readUShort())
    }

    serverProt<MusicTrackTile>(opcode = 93, size = 11) { out ->
        out.writeByteSubtract(near)
        out.writeByte(volume)
        out.writeByteInverse(far)
        out.writeInt(musicArchive)
        out.writeIntMiddle(((plane and 0x3) shl 28) or ((x and 0x3FFF) shl 14) or (y and 0x3FFF))
    }.decoder {
        val near = readByteSubtract() and 0xff
        val volume = readUByte()
        val far = readByteInverse() and 0xff
        val musicArchive = readInt()
        val position = readIntMiddle()
        MusicTrackTile(position ushr 28 and 0x3, position shr 14 and 0x3FFF, position and 0x3FFF, near, far, volume, musicArchive)
    }

    serverProt<AnimateNpc>(opcode = 145, size = 19) { out ->
        out.writeShortAddLittle(npcIndex)
        for (id in animationIds) {
            out.writeIntLittle(id)
        }
        out.writeByte(speed)
    }.decoder {
        val npcIndex = readUShortAddLittle()
        val animationIds = IntArray(4) { readIntLittle() }
        AnimateNpc(npcIndex, animationIds, readUByte())
    }
}

private suspend fun Source.customisationSizes(objectId: Int, shape: Int, flags: Int): LocCustomisationSizes {
    LocCustomisationSizing.sizes(objectId, shape)?.let { return it }
    val models = flags and 0x2 != 0
    val recolors = flags and 0x4 != 0
    val retextures = flags and 0x8 != 0
    check(!decodingEnclosedZoneStream() || !(models || recolors || retextures)) { "CustomizeObject $objectId in a zone stream needs LocCustomisationSizing" }
    check(listOf(models, recolors, retextures).count { it } <= 1) { "CustomizeObject $objectId carries several arrays and needs LocCustomisationSizing" }
    val bytes = remaining.toInt()
    return LocCustomisationSizes(if (models) bytes / 4 else 0, if (recolors) bytes / 2 else 0, if (retextures) bytes / 2 else 0)
}
