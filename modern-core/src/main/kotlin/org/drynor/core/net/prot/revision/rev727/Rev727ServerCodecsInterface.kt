package org.drynor.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.drynor.core.net.prot.*
import org.drynor.core.net.prot.packet.*
import org.drynor.core.toInterfaceHash
import world.gregs.voidps.buffer.*
import world.gregs.voidps.type.Tile

internal fun Codec.registerRev727IfPackets() {
    serverProt<IfSetPlayerHead>(opcode = 0, size = 4) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val (interfaceId, componentId) = readIntLittle().componentHash()
        IfSetPlayerHead(interfaceId, componentId)
    }

    // Wire order is kit1, kit3, kit2: the client packs fields 1 and 3 into
    // primaryMediaId (primary/secondary head) and field 2 into secondaryMediaId
    // (tertiary head).
    serverProt<IfSetPlayerHeadIgnoreWorn>(opcode = 130, size = 10) { out ->
        out.writeShortLittle(identiKit1)
        out.writeShortAdd(identiKit3)
        out.writeShortAdd(identiKit2)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val identiKit1 = readUShortLittle().shortId()
        val identiKit3 = readUShortAdd().shortId()
        val identiKit2 = readUShortAdd().shortId()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        IfSetPlayerHeadIgnoreWorn(interfaceId, componentId, identiKit1, identiKit2, identiKit3)
    }

    serverProt<IfSetTextFont>(opcode = 7, size = 8) { out ->
        out.writeInt(fontId)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val fontId = readInt()
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        IfSetTextFont(interfaceId, componentId, fontId)
    }

    serverProt<IfMoveSub>(opcode = 13, size = 8) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(fromInterfaceId, fromComponentId))
        out.writeInt(toInterfaceHash(toInterfaceId, toComponentId))
    }.decoder {
        val (fromInterfaceId, fromComponentId) = readIntInverseMiddle().componentHash()
        val (toInterfaceId, toComponentId) = readInt().componentHash()
        IfMoveSub(fromInterfaceId, fromComponentId, toInterfaceId, toComponentId)
    }

    serverProt<IfOpenSubActiveObject>(opcode = 23, size = 32) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(xteas[2])
        out.writeByteAdd(if (overlay) 1 else 0)
        out.writeInt(xteas[1])
        out.writeIntMiddle(xteas[0])
        out.writeShortAdd(subInterfaceId)
        out.writeByteSubtract((type shl 2) or (rotation and 0x3))
        out.writeInt(xteas[3])
        out.writeIntInverseMiddle(tile.id)
        out.writeInt(objectId)
    }.decoder {
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val xtea2 = readIntInverseMiddle()
        val overlay = readBooleanAdd()
        val xtea1 = readInt()
        val xtea0 = readIntMiddle()
        val subInterfaceId = readUShortAdd()
        val shape = readByteSubtract() and 0xff
        val xtea3 = readInt()
        val tile = Tile(readIntInverseMiddle())
        val objectId = readInt()
        IfOpenSubActiveObject(interfaceId, componentId, subInterfaceId, overlay, intArrayOf(xtea0, xtea1, xtea2, xtea3), objectId, tile, shape shr 2, shape and 0x3)
    }

    serverProt<IfSetHide>(opcode = 33, size = 5) { out ->
        out.writeByteAdd(if (hidden) 1 else 0)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val hidden = readBooleanAdd()
        val (interfaceId, componentId) = readInt().componentHash()
        IfSetHide(interfaceId, componentId, hidden)
    }

    serverProt<IfOpenSubActivePlayer>(opcode = 59, size = 25) { out ->
        out.writeIntLittle(xteas[3])
        out.writeByte(if (overlay) 1 else 0)
        out.writeIntMiddle(xteas[0])
        out.writeShort(subInterfaceId)
        out.writeInt(xteas[1])
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(xteas[2])
        out.writeShortLittle(playerIndex)
    }.decoder {
        val xtea3 = readIntLittle()
        val overlay = readBoolean()
        val xtea0 = readIntMiddle()
        val subInterfaceId = readUShort()
        val xtea1 = readInt()
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        val xtea2 = readIntMiddle()
        val playerIndex = readUShortLittle()
        IfOpenSubActivePlayer(interfaceId, componentId, subInterfaceId, overlay, playerIndex, intArrayOf(xtea0, xtea1, xtea2, xtea3))
    }

    serverProt<IfSetGraphic>(opcode = 35, size = 8) { out ->
        out.writeIntInverseMiddle(graphicId)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val graphicId = readIntInverseMiddle()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        IfSetGraphic(interfaceId, componentId, graphicId)
    }

    serverProt<IfSetTextAntiMacro>(opcode = 36, size = 5) { out ->
        out.writeByteSubtract(if (enabled) 1 else 0)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val enabled = readBooleanSubtract()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        IfSetTextAntiMacro(interfaceId, componentId, enabled)
    }

    serverProt<IfOpenTop>(opcode = 37, size = 19) { out ->
        out.writeIntMiddle(xteas[2])
        out.writeIntMiddle(xteas[3])
        out.writeShortAdd(interfaceId)
        out.writeByteSubtract(type)
        // The client reads these two with big-endian readInt(), not readUIntLittle().
        out.writeInt(xteas[1])
        out.writeInt(xteas[0])
    }.decoder {
        val xtea2 = readIntMiddle()
        val xtea3 = readIntMiddle()
        val interfaceId = readUShortAdd()
        val type = readByteSubtract() and 0xff
        val xtea1 = readInt()
        val xtea0 = readInt()
        IfOpenTop(interfaceId, type, intArrayOf(xtea0, xtea1, xtea2, xtea3))
    }

    serverProt<IfOpenSub>(opcode = 38, size = 23) { out ->
        out.writeInt(xteas[0])
        out.writeIntInverseMiddle(xteas[2])
        out.writeIntInverseMiddle(xteas[1])
        out.writeShortAddLittle(subInterfaceId)
        out.writeIntLittle(xteas[3])
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeByteInverse(if (overlay) 1 else 0)
    }.decoder {
        val xtea0 = readInt()
        val xtea2 = readIntInverseMiddle()
        val xtea1 = readIntInverseMiddle()
        val subInterfaceId = readUShortAddLittle()
        val xtea3 = readIntLittle()
        val (interfaceId, componentId) = readInt().componentHash()
        val overlay = readBooleanInverse()
        IfOpenSub(interfaceId, componentId, subInterfaceId, overlay, intArrayOf(xtea0, xtea1, xtea2, xtea3))
    }

    serverProt<IfSetAngle>(opcode = 44, size = 10) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle(pitch)
        out.writeShortLittle(roll)
        out.writeShortAddLittle(scale)
    }.decoder {
        val (interfaceId, componentId) = readInt().componentHash()
        val pitch = readUShortLittle()
        val roll = readUShortLittle()
        val scale = readUShortAddLittle()
        IfSetAngle(interfaceId, componentId, pitch, roll, scale)
    }

    serverProt<IfSetPosition>(opcode = 48, size = 8) { out ->
        out.writeShortAdd(y)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(x)
    }.decoder {
        val y = readShortAdd()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val x = readShortAddLittle().toShort().toInt()
        IfSetPosition(interfaceId, componentId, x, y)
    }

    serverProt<IfSetPlayerModelOther>(opcode = 53, size = 10) { out ->
        out.writeShortAdd(primaryModelId)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(secondaryModelId)
    }.decoder {
        val primaryModelId = readUShortAdd().shortId()
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        val secondaryModelId = readIntMiddle()
        IfSetPlayerModelOther(interfaceId, componentId, primaryModelId, secondaryModelId)
    }

    serverProt<IfSetScrollPos>(opcode = 55, size = 6) { out ->
        out.writeShort(scrollPos)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val scrollPos = readUShort()
        val (interfaceId, componentId) = readInt().componentHash()
        IfSetScrollPos(interfaceId, componentId, scrollPos)
    }

    serverProt<IfOpenSubActiveNpc>(opcode = 34, size = 25) { out ->
        out.writeShortAdd(npcIndex)
        out.writeIntLittle(xteas[1])
        out.writeByteInverse(if (overlay) 1 else 0)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntLittle(xteas[3])
        out.writeShortAddLittle(subInterfaceId)
        out.writeInt(xteas[2])
        out.writeInt(xteas[0])
    }.decoder {
        val npcIndex = readUShortAdd()
        val xtea1 = readIntLittle()
        val overlay = readBooleanInverse()
        val (interfaceId, componentId) = readInt().componentHash()
        val xtea3 = readIntLittle()
        val subInterfaceId = readUShortAddLittle()
        val xtea2 = readInt()
        val xtea0 = readInt()
        IfOpenSubActiveNpc(interfaceId, componentId, subInterfaceId, overlay, npcIndex, intArrayOf(xtea0, xtea1, xtea2, xtea3))
    }

    serverProt<IfSetPlayerModel>(opcode = 72, size = 4) { out ->
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        IfSetPlayerModel(interfaceId, componentId)
    }

    serverProt<IfCloseSub>(opcode = 78, size = 4) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val (interfaceId, componentId) = readInt().componentHash()
        IfCloseSub(interfaceId, componentId)
    }

    serverProt<IfSetAnim>(opcode = 82, size = 8) { out ->
        out.writeIntInverseMiddle(animationId)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val animationId = readIntInverseMiddle()
        val (interfaceId, componentId) = readInt().componentHash()
        IfSetAnim(interfaceId, componentId, animationId)
    }

    serverProt<IfSetColor>(opcode = 105, size = 6) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAdd(color)
    }.decoder {
        val (interfaceId, componentId) = readIntLittle().componentHash()
        val color = readUShortAdd()
        IfSetColor(interfaceId, componentId, color)
    }

    serverProt<IfSetNpcHead>(opcode = 109, size = 8) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(npcId)
    }.decoder {
        val (interfaceId, componentId) = readIntLittle().componentHash()
        val npcId = readIntMiddle()
        IfSetNpcHead(interfaceId, componentId, npcId)
    }

    serverProt<IfSetItem>(opcode = 112, size = 10) { out ->
        out.writeShort(if (itemId == -1) 65535 else itemId)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(amount)
    }.decoder {
        val itemId = readUShort().shortId()
        val (interfaceId, componentId) = readInt().componentHash()
        val amount = readIntInverseMiddle()
        IfSetItem(interfaceId, componentId, itemId, amount)
    }

    serverProt<IfSetEvents>(opcode = 121, size = 12) { out ->
        out.writeShortAddLittle(if (toSlot == -1) 65535 else toSlot)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShort(if (fromSlot == -1) 65535 else fromSlot)
        out.writeIntLittle(settings)
    }.decoder {
        val toSlot = readUShortAddLittle().shortId()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val fromSlot = readUShort().shortId()
        val settings = readIntLittle()
        IfSetEvents(interfaceId, componentId, fromSlot, toSlot, settings)
    }

    serverProt<IfSetText>(opcode = 124, size = ProtSize.VarShort) { out ->
        out.writeRSString(text)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val text = readRSString()
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        IfSetText(interfaceId, componentId, text)
    }

    // The client reads the destination texture first (little endian) and the
    // source texture second (big endian) - the opposite order to IF_SETRECOL.
    serverProt<IfSetReTex>(opcode = 132, size = 9) { out ->
        out.writeShortLittle(toTextureId)
        out.writeShort(fromTextureId)
        out.writeByteSubtract(index)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val toTextureId = readUShortLittle().shortId()
        val fromTextureId = readUShort().shortId()
        val index = readByteSubtract() and 0xff
        val (interfaceId, componentId) = readIntLittle().componentHash()
        IfSetReTex(interfaceId, componentId, index, fromTextureId, toTextureId)
    }

    serverProt<IfSetModel>(opcode = 135, size = 8) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(modelId)
    }.decoder {
        val (interfaceId, componentId) = readInt().componentHash()
        val modelId = readIntInverseMiddle()
        IfSetModel(interfaceId, componentId, modelId)
    }

    serverProt<IfSetReCol>(opcode = 144, size = 9) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle(fromColor)
        out.writeShortLittle(toColor)
        out.writeByte(index)
    }.decoder {
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val fromColor = readUShortLittle()
        val toColor = readUShortLittle()
        val index = readUByte()
        IfSetReCol(interfaceId, componentId, index, fromColor, toColor)
    }

    serverProt<IfSetTargetParam>(opcode = 148, size = 10) { out ->
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(targetInterfaceId)
        out.writeShort(if (toSlot == -1) 65535 else toSlot)
        out.writeShortAdd(if (fromSlot == -1) 65535 else fromSlot)
    }.decoder {
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        val targetInterfaceId = readUShortAddLittle()
        val toSlot = readUShort().shortId()
        val fromSlot = readUShortAdd().shortId()
        IfSetTargetParam(interfaceId, componentId, targetInterfaceId, fromSlot, toSlot)
    }

    serverProt<IfSetClickMask>(opcode = 150, size = 5) { out ->
        out.writeByteSubtract(if (enabled) 1 else 0)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val enabled = readBooleanSubtract()
        val (interfaceId, componentId) = readIntLittle().componentHash()
        IfSetClickMask(interfaceId, componentId, enabled)
    }

    serverProt<IfOpenSubActiveGroundItem>(opcode = 155, size = 29) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntLittle(xteas[2])
        out.writeShortLittle(subInterfaceId)
        out.writeInt(xteas[3])
        out.writeIntMiddle(xteas[0])
        out.writeByteSubtract(if (overlay) 1 else 0)
        out.writeIntMiddle(tile.id)
        out.writeIntMiddle(xteas[1])
        out.writeShortAddLittle(itemId)
    }.decoder {
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val xtea2 = readIntLittle()
        val subInterfaceId = readUShortLittle()
        val xtea3 = readInt()
        val xtea0 = readIntMiddle()
        val overlay = readBooleanSubtract()
        val tile = Tile(readIntMiddle())
        val xtea1 = readIntMiddle()
        val itemId = readUShortAddLittle().shortId()
        IfOpenSubActiveGroundItem(interfaceId, componentId, subInterfaceId, overlay, tile, itemId, intArrayOf(xtea0, xtea1, xtea2, xtea3))
    }

    // OUTDATED_SET_THEORA_STRING_SOMETHING: queues a T17 pulse event on a component.
    serverProt<OutdatedSetTheoraString>(opcode = 20, size = 6) { out ->
        out.writeShortAdd(value)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val value = readUShortAdd()
        val (interfaceId, componentId) = readIntLittle().componentHash()
        OutdatedSetTheoraString(interfaceId, componentId, value)
    }

    // OUTDATED_INTERFACE_PULSE_EVENT: queues a T13 pulse event on a component.
    // The client rebuilds the payload as `low + (high shl 16)`.
    serverProt<OutdatedInterfacePulseEvent>(opcode = 96, size = 8) { out ->
        out.writeShortAdd(value and 0xFFFF)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle((value shr 16) and 0xFFFF)
    }.decoder {
        val low = readUShortAdd()
        val (interfaceId, componentId) = readIntInverseMiddle().componentHash()
        val high = readUShortLittle()
        OutdatedInterfacePulseEvent(interfaceId, componentId, low or (high shl 16))
    }

    serverProt<IfSetPlayerHeadOther>(opcode = 158, size = 10) { out ->
        out.writeInt(key)
        out.writeShortAddLittle(value)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }.decoder {
        val key = readInt()
        val value = readUShortAddLittle()
        val (interfaceId, componentId) = readIntMiddle().componentHash()
        IfSetPlayerHeadOther(interfaceId, componentId, key, value)
    }
}

private fun Int.componentHash() = (this ushr 16) to (this and 0xFFFF)

private fun Int.shortId() = if (this == 0xFFFF) -1 else this
