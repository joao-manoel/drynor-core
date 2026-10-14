package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.writeInt
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import kotlinx.io.readUShort
import org.darkan.core.clientwatch.MouseTrailStep
import org.darkan.core.clientwatch.MouseTrailStep.Type
import org.darkan.core.model.Preference
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*
import org.darkan.core.fixChatMessage
import world.gregs.voidps.type.Tile

/** The value a 16-bit interface field carries when the client means "none". */
private const val NONE_16 = 0xFFFF

/**
 * Normalises the client's 16-bit "none" sentinel to -1.
 *
 * A component with no slot writes `slotId = -1` and one holding no obj writes
 * `containerItemId = -1` (darkan-bot `Component.slotId` / `Component.containerItemId` and
 * `Client.selectedSlotId` / `Client.selectedItemId`, all of which default to -1), and an interface
 * hash naming a whole interface rather than one of its components carries 0xFFFF in its low half.
 * Read back out of a 16-bit field every one of those arrives as 65535, so they are all mapped here
 * and a handler only ever has to test for -1.
 *
 * The legacy decoders got to the same place unevenly: some read the field signed (IFDragOntoIF,
 * IFOnIF, IFOnObject, IFOnGroundItem), some mapped 65535 in the decoder (IFOnNPC, IFOnPlayer), and
 * some left it to the handler (IFOpHandler, IFOnIFHandler and IFOnGroundItemHandler each compare a
 * component id against 65535). Only 65535 is remapped, so every other value still decodes exactly
 * as the legacy unsigned reads decoded it.
 */
private fun Int.orNone(): Int = if (this == NONE_16) -1 else this

private fun interfaceHash(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) or (componentId and NONE_16)

private const val PREFERENCES_HEADER = 28

private val LAST_PREFERENCE_INDEX = (1..255).last { Preference.forIndex(it) != null }


private suspend fun ByteWriteChannel.writeWalk(walk: Walk) {
    writeBoolean(walk.forceRun)
    writeShort(walk.x)
    writeShortLittle(walk.y)
}

private suspend fun ByteWriteChannel.writeMouseTrailStep(step: MouseTrailStep) {
    when {
        step.type == Type.SET_POSITION -> {
            if (step.frames < 32) writeByte(step.frames + 192)
            else writeShort((step.frames and 0x1FFF) + 57344)
            writeInt((step.y shl 16) or (step.x and 0xFFFF))
        }
        step.frames < 8 && step.x in 0..63 && step.y in 0..63 -> writeShort((step.frames shl 12) + (step.x shl 6) + step.y)
        else -> {
            writeByte(step.frames + 128)
            writeShort((step.x shl 8) + step.y)
        }
    }
}

internal fun Codec.registerRev727ClientProts() {
    //Utility/clientwatch
    clientProt<Ping>(opcode = 0)

    // Client writes: writeShort(pid), writeString(name). The leading byte is the VarByte
    // length prefix, already consumed by the framing layer.
    clientProt(opcode = 39, size = ProtSize.VarByte) {
        UnusedClanOp(
            pid = readUShort().toInt(),
            username = readRSString()
        )
    }.encoder { out ->
        out.writeShort(pid)
        out.writeRSString(username)
    }

    // Client writes: writeInt(checkId), then one result blob per requested operation.
    clientProt(opcode = 36, size = ProtSize.VarByte) {
        ReflectionResponse(readInt(), readByteArray(remaining.toInt()))
    }.encoder { out ->
        out.writeInt(id)
        out.writeBytes(data)
    }

    clientProt(opcode = 62, size = 1) { ClientFocus(readBoolean()) }
        .encoder { out -> out.writeBoolean(focused) }

    clientProt(opcode = 84, size = 6) {
        ClientScreenSize(
            displayMode = readUByte().toInt(),
            width = readUShort().toInt(),
            height = readUShort().toInt(),
            switchDisplayMode = readBoolean()
        )
    }.encoder { out ->
        out.writeByte(displayMode)
        out.writeShort(width)
        out.writeShort(height)
        out.writeBoolean(switchDisplayMode)
    }

    clientProt(opcode = 83, size = 4) {
        MoveCamera(angleX = readUShortAddLittle(), angleY = readUShortAdd())
    }.encoder { out ->
        out.writeShortAddLittle(angleX)
        out.writeShortAdd(angleY)
    }

    clientProt(opcode = 44, size = 9) { //TODO test
        SendFps(
            key1 = readUIntInverseMiddle(),
            key2 = readUIntMiddle(),
            fps = readByteSubtract()
        )
    }.encoder { out ->
        out.writeIntInverseMiddle(key1)
        out.writeIntMiddle(key2)
        out.writeByteSubtract(fps)
    }

    clientProt(opcode = 59, size = 6) {
        val positionHash = readUIntLittle()
        val mouseHash = readUShort().toInt()
        MouseClick(
            mouseButton = mouseHash ushr 15,
            time = mouseHash and 0x7FFF,
            x = positionHash and 0xFFFF,
            y = positionHash ushr 16
        )
    }.encoder { out ->
        out.writeIntLittle((x and 0xFFFF) or (y shl 16))
        out.writeShort((time and 0x7FFF) or (mouseButton shl 15))
    }

    // Opcode 57 is emitted only by the client's native (jaclib/ICMP) mouse hook, so every event
    // that arrives here is a hardware one - the software mouse sends MOUSE_CLICK (59) instead.
    // Bit 0 of the flags byte is not that distinction: the client writes
    // `writeByte128(meta shl 1 or (constant and 0x1))`, where `constant` is an opaque value from
    // the native library that is 0 in practice, so reading it as a hardware flag always said
    // "software".
    clientProt(opcode = 57, size = 7) {
        val positionHash = readUIntLittle()
        val flags = readByteAdd() and 0xFF
        MouseButtonClick(
            mouseButton = flags ushr 1,
            time = readUShortLittle(),
            x = positionHash and 0xFFFF,
            y = positionHash ushr 16,
            hw = true
        )
    }.encoder { out ->
        out.writeIntLittle((x and 0xFFFF) or (y shl 16))
        out.writeByteAdd(mouseButton shl 1)
        out.writeShortLittle(time)
    }

    // Opcode 40 is the native (jaclib/ICMP) mouse trail, opcode 78 the java/AWT one: the client
    // has a recorder per input source and each always sends its own opcode, so the opcode is what
    // says "hardware", not any field in the payload. The native recorder appends one extra byte
    // per step (an opaque native value, 0 in practice) that the java one omits, so it still has to
    // be consumed or every later step decodes from the wrong offset.
    clientProt(opcodes = intArrayOf(40, 78), size = ProtSize.VarByte) { opcode ->
        val hardware = opcode == 40
        val steps = mutableListOf<MouseTrailStep>()
        val frameSteps = readUByte().toInt()
        val frameCount = readUByte().toInt()
        while (remaining > 0) {
            val firstByte = readByte().toUByte().toInt()

            var frames: Int
            var dX: Int
            var dY: Int
            var type = Type.MOVE_OFFSET

            when {
                firstByte >= 224 -> {
                    val secondByte = readByte().toUByte().toInt()
                    frames = ((firstByte shl 8) or secondByte) - 57344
                    val posHash = readInt()
                    dY = posHash shr 16
                    dX = posHash and 0xFFFF
                    type = Type.SET_POSITION
                }
                firstByte >= 192 -> {
                    frames = firstByte - 192
                    val posHash = readInt()
                    dY = posHash shr 16
                    dX = posHash and 0xFFFF
                    type = Type.SET_POSITION
                }
                firstByte >= 128 -> {
                    frames = firstByte - 128
                    val posHash = readUShort().toInt()
                    dX = posHash shr 8
                    dY = posHash and 0xFF
                }
                else -> {
                    val secondByte = readByte().toUByte().toInt()
                    val hash = (firstByte shl 8) or secondByte
                    frames = hash shr 12
                    dX = (hash shr 6) and 0x3F
                    dY = hash and 0x3F
                }
            }

            if (hardware) readByte()
            steps.add(MouseTrailStep(type, frames, dX, dY, hardware))
        }
        MoveMouseData(
            steps = steps.toTypedArray(),
            frameCount = frameCount,
            frameSteps = frameSteps
        )
    }.encoder(opcode = { if (it.steps.all(MouseTrailStep::hardware)) 40 else 78 }) { out ->
        val hardware = steps.all(MouseTrailStep::hardware)
        out.writeByte(frameSteps)
        out.writeByte(frameCount)
        for (step in steps) {
            out.writeMouseTrailStep(step)
            if (hardware) out.writeByte(0)
        }
    }

    clientProt(opcode = 28, size = ProtSize.VarShort) { KeyPress(keyCode = readByte().toInt(), time = readUMedium()) }
        .encoder { out ->
            out.writeByte(keyCode)
            out.writeMedium(time)
        }

    clientProt(opcode = 85, size = ProtSize.VarByte) {
        ClientCheat(
            client = readBoolean(),
            save = readBoolean(),
            command = readRSString()
        )
    }.encoder { out ->
        out.writeBoolean(client)
        out.writeBoolean(save)
        out.writeRSString(command)
    }

    // Entity interactions
    val playerOpOpcodes = intArrayOf(66, 6, 31, 89, 103, 1, 51, 94, 53, 70)
    clientProt(opcodes = playerOpOpcodes, size = 3) { opcode ->
        OpPlayer(
            opNum = playerOpOpcodes.indexOf(opcode) + 1,
            playerIndex = readShort().toInt(),
            forceRun = readBooleanSubtract()
        )
    }.encoder(opcode = { playerOpOpcodes[it.opNum - 1] }) { out ->
        out.writeShort(playerIndex)
        out.writeBooleanSubtract(forceRun)
    }

    val npcOpOpcodes = intArrayOf(65, 16, 50, 77, 95, 3)
    clientProt(opcodes = npcOpOpcodes, size = 3) { opcode ->
        OpNpc(
            opNum = npcOpOpcodes.indexOf(opcode) + 1,
            npcIndex = readUShort().toInt(),
            forceRun = readBoolean()
        )
    }.encoder(opcode = { npcOpOpcodes[it.opNum - 1] }) { out ->
        out.writeShort(npcIndex)
        out.writeBoolean(forceRun)
    }

    val objOpOpcodes = intArrayOf(75, 93, 38, 32, 48, 73)
    clientProt(opcodes = objOpOpcodes, size = 9) { opcode ->
        OpObj(
            opNum = objOpOpcodes.indexOf(opcode) + 1,
            y = readUShort().toInt(),
            x = readUShort().toInt(),
            objectId = readInt(),
            forceRun = readBooleanAdd()
        )
    }.encoder(opcode = { objOpOpcodes[it.opNum - 1] }) { out ->
        out.writeShort(y)
        out.writeShort(x)
        out.writeInt(objectId)
        out.writeBooleanAdd(forceRun)
    }

    val groundItemOpOpcodes = intArrayOf(24, 25, 54, 8, 43, 61)
    clientProt(opcodes = groundItemOpOpcodes, size = 7) { opcode ->
        OpGroundItem(
            opNum = groundItemOpOpcodes.indexOf(opcode) + 1,
            itemId = readUShortAddLittle(),
            forceRun = readBooleanInverse(),
            y = readUShort().toInt(),
            x = readUShortAdd()
        )
    }.encoder(opcode = { groundItemOpOpcodes[it.opNum - 1] }) { out ->
        out.writeShortAddLittle(itemId)
        out.writeBooleanInverse(forceRun)
        out.writeShort(y)
        out.writeShortAdd(x)
    }

    clientProt(opcode = 33, size = 5) {
        val forceRun = readBoolean()
        val x = readUShort().toInt()
        val y = readUShortLittle()
        Walk(x, y, forceRun, false)
    }.encoder { out -> out.writeWalk(this) }

    // A client resolves Walk to this codec by class, so a plain walk goes out as 33 (fixed frames carry no length).
    clientProt(opcode = 42, size = 18) {
        val forceRun = readBoolean()
        val x = readUShort().toInt()
        val y = readUShortLittle()
        readByte()
        readByte() //always -1
        val camAngle = readUShort().toInt()
        readByte() //always 57
        val minimapRotation = readUByte() //TODO
        val minimapZoom = readUByte()
        readByte() //always 89
        val absX = readUShort().toInt()
        val absY = readUShort().toInt()
        readByte() //always 63
        Walk(x, y, forceRun, true)
    }.encoder(opcode = { if (it.minimap) 42 else 33 }) { out ->
        out.writeWalk(this)
        if (!minimap) return@encoder
        out.writeByte(-1)
        out.writeByte(-1)
        out.writeShort(0)
        out.writeByte(57)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(89)
        out.writeShort(0)
        out.writeShort(0)
        out.writeByte(63)
    }

    // Interface interactions
    // Client writes: writeIntLittle(interfaceHash), writeShort(itemId), writeShortAdd(slotId)
    // See darkan-bot PacketEncoder.writeInterfaceComponentData
    val ifButtonOpcodes = intArrayOf(96, 27, 68, 9, 72, 19, 23, 21, 22, 81)
    clientProt(opcodes = ifButtonOpcodes, size = 8) { opcode ->
        val interfaceHash = readUIntLittle()
        IfButton(
            opNum = ifButtonOpcodes.indexOf(opcode) + 1,
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            itemId = readUShort().toInt().orNone(),
            slotId = readUShortAdd().orNone()
        )
    }.encoder(opcode = { ifButtonOpcodes[it.opNum - 1] }) { out ->
        out.writeIntLittle(interfaceHash(interfaceId, componentId))
        out.writeShort(itemId)
        out.writeShortAdd(slotId)
    }

    clientProt(opcode = 4, size = 16) {
        val toSlot = readUShortAddLittle().orNone()
        val fromSlot = readUShortLittle().orNone()
        val toItemId = readUShortAddLittle().orNone()
        val toHash = readUIntLittle()
        val fromHash = readUIntInverseMiddle()
        val fromItemId = readUShortLittle().orNone()
        IfOnIf(
            fromInter = fromHash shr 16,
            toInter = toHash shr 16,
            fromComp = (fromHash and 0xFFFF).orNone(),
            toComp = (toHash and 0xFFFF).orNone(),
            fromSlot = fromSlot,
            toSlot = toSlot,
            fromItemId = fromItemId,
            toItemId = toItemId
        )
    }.encoder { out ->
        out.writeShortAddLittle(toSlot)
        out.writeShortLittle(fromSlot)
        out.writeShortAddLittle(toItemId)
        out.writeIntLittle(interfaceHash(toInter, toComp))
        out.writeIntInverseMiddle(interfaceHash(fromInter, fromComp))
        out.writeShortLittle(fromItemId)
    }

    clientProt(opcode = 98, size = 17) {
        val x = readUShortAddLittle()
        val forceRun = readBooleanSubtract()
        val objectId = readUIntMiddle()
        val interfaceHash = readInt()
        val itemId = readUShortLittle().orNone()
        val slotId = readUShortAdd().orNone()
        val y = readUShortLittle()
        IfOnObject(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId,
            itemId = itemId,
            objectId = objectId,
            x = x,
            y = y,
            forceRun = forceRun
        )
    }.encoder { out ->
        out.writeShortAddLittle(x)
        out.writeBooleanSubtract(forceRun)
        out.writeIntMiddle(objectId)
        out.writeInt(interfaceHash(interfaceId, componentId))
        out.writeShortLittle(itemId)
        out.writeShortAdd(slotId)
        out.writeShortLittle(y)
    }

    clientProt(opcode = 67, size = 15) {
        val itemIdContainer = readUShortAdd().orNone()
        val interfaceHash = readUIntInverseMiddle()
        // The ground item's own id, taken from the clicked menu entry rather than from the dragged
        // component, so it is never the "none" sentinel - legacy IFOnGroundItem reads it signed too.
        val itemId = readShort().toInt()
        val forceRun = readBooleanSubtract()
        val slotId = readUShortAddLittle().orNone()
        val y = readUShortAddLittle()
        val x = readUShortLittle()
        IfOnGroundItem(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId,
            itemIdContainer = itemIdContainer,
            itemId = itemId,
            x = x,
            y = y,
            forceRun = forceRun
        )
    }.encoder { out ->
        out.writeShortAdd(itemIdContainer)
        out.writeIntInverseMiddle(interfaceHash(interfaceId, componentId))
        out.writeShort(itemId)
        out.writeBooleanSubtract(forceRun)
        out.writeShortAddLittle(slotId)
        out.writeShortAddLittle(y)
        out.writeShortLittle(x)
    }

    clientProt(opcode = 41, size = 11) {
        val interfaceHash = readUIntInverseMiddle()
        val npcIndex = readUShortAddLittle()
        val forceRun = readBooleanSubtract()
        val itemId = readUShortAddLittle().orNone()
        val slotId = readUShortAdd().orNone()
        IfOnNpc(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId,
            itemId = itemId,
            npcIndex = npcIndex,
            forceRun = forceRun
        )
    }.encoder { out ->
        out.writeIntInverseMiddle(interfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(npcIndex)
        out.writeBooleanSubtract(forceRun)
        out.writeShortAddLittle(itemId)
        out.writeShortAdd(slotId)
    }

    clientProt(opcode = 13, size = 11) {
        val slotId = readUShort().toInt().orNone()
        val playerIndex = readUShortLittle()
        val forceRun = readBooleanSubtract()
        val interfaceHash = readUIntInverseMiddle()
        val itemId = readUShortLittle().orNone()
        IfOnPlayer(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId,
            itemId = itemId,
            playerIndex = playerIndex,
            forceRun = forceRun
        )
    }.encoder { out ->
        out.writeShort(slotId)
        out.writeShortLittle(playerIndex)
        out.writeBooleanSubtract(forceRun)
        out.writeIntInverseMiddle(interfaceHash(interfaceId, componentId))
        out.writeShortLittle(itemId)
    }

    clientProt(opcode = 46, size = 12) {
        val itemId = readUShortLittle().orNone()
        val y = readUShortLittle()
        val interfaceHash = readUIntMiddle()
        val slotId = readUShortAdd().orNone()
        val x = readUShortLittle()
        IfOnTile(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId,
            itemId = itemId,
            x = x,
            y = y
        )
    }.encoder { out ->
        out.writeShortLittle(itemId)
        out.writeShortLittle(y)
        out.writeIntMiddle(interfaceHash(interfaceId, componentId))
        out.writeShortAdd(slotId)
        out.writeShortLittle(x)
    }

    clientProt(opcode = 49, size = 6) {
        val interfaceHash = readUIntMiddle()
        val slotId = readUShortAddLittle().orNone()
        IfContinue(
            interfaceId = interfaceHash shr 16,
            componentId = (interfaceHash and 0xFFFF).orNone(),
            slotId = slotId
        )
    }.encoder { out ->
        out.writeIntMiddle(interfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(slotId)
    }

    // The drop target of a bank drag can be a tab header, which is a component with no slot: the
    // client writes -1 there (darkan-bot PacketEncoder.sendInterfaceDragOntoInterface writes
    // `target.slotId`, which Component defaults to -1), and legacy IFDragOntoIF read both slots
    // signed. Read unsigned it arrived as 65535 and Bank.switchItem never saw the tab drag at all.
    clientProt(opcode = 74, size = 16) {
        val toSlot = readUShortAddLittle().orNone()
        val fromSlot = readUShortLittle().orNone()
        val toItemId = readUShort().toInt().orNone()
        val fromItemId = readUShortAddLittle().orNone()
        val fromInterfaceHash = readUIntMiddle()
        val toInterfaceHash = readUIntLittle()
        IfDragOntoIf(
            fromInter = fromInterfaceHash shr 16,
            toInter = toInterfaceHash shr 16,
            fromComp = (fromInterfaceHash and 0xFFFF).orNone(),
            toComp = (toInterfaceHash and 0xFFFF).orNone(),
            fromSlot = fromSlot,
            toSlot = toSlot,
            fromItemId = fromItemId,
            toItemId = toItemId
        )
    }.encoder { out ->
        out.writeShortAddLittle(toSlot)
        out.writeShortLittle(fromSlot)
        out.writeShort(toItemId)
        out.writeShortAddLittle(fromItemId)
        out.writeIntMiddle(interfaceHash(fromInter, fromComp))
        out.writeIntLittle(interfaceHash(toInter, toComp))
    }

    clientProt<CloseInterface>(opcode = 60)

    // Dialog interactions
    clientProt(opcode = 87, size = ProtSize.VarByte) { ResumeTextDialog(readRSString()) }
        .encoder { out -> out.writeRSString(text) }
    clientProt(opcode = 80, size = ProtSize.VarByte) { ResumeNameDialog(readRSString()) }
        .encoder { out -> out.writeRSString(name) }
    clientProt(opcode = 69, size = ProtSize.VarByte) { ResumeClanForumQFCDialog(readRSString()) }
        .encoder { out -> out.writeRSString(forumQfc) }
    clientProt(opcode = 11, size = 2) { ResumeHSLDialog(readUShort().toInt()) }
        .encoder { out -> out.writeShort(data) }
    clientProt(opcode = 58, size = 4) { ResumeCountDialog(readInt()) }
        .encoder { out -> out.writeInt(count) }
    // Read signed on purpose: the client writes -1 when the obj dialog is dismissed without a
    // pick, matching legacy GEItemSelect's `readShort()`.
    clientProt(opcode = 17, size = 2) { ResumeItemSelect(readShort().toInt()) }
        .encoder { out -> out.writeShort(itemId) }

    // Communication
    clientProt(opcode = 86, size = ProtSize.VarByte) {
        Chat(
            color = readUByte().toInt().coerceIn(0, 12),
            effect = readUByte().toInt().coerceIn(0, 5),
            message = (chatHuffman().decompress(length = readSmart(), message = readByteArray(remaining.toInt())) ?: "").fixChatMessage()
        )
    }.encoder { out ->
        out.writeByte(color)
        out.writeByte(effect)
        out.writeBytes(chatHuffman().compress(message))
    }

    clientProt(opcode = 15, size = ProtSize.VarShort) {
        PrivateMessage(readRSString(), (chatHuffman().decompress(length = readSmart(), message = readByteArray(remaining.toInt())) ?: "").fixChatMessage())
    }.encoder { out ->
        out.writeRSString(toDisplayName)
        out.writeBytes(chatHuffman().compress(message))
    }

    clientProt(opcode = 64, size = ProtSize.VarByte) {
        QuickChatPublic(
            chatType = readByte().toInt(),
            qcId = readUShort().toInt(),
            messageData = if (remaining > 0) readByteArray(remaining.toInt()) else null
        )
    }.encoder { out ->
        out.writeByte(chatType)
        out.writeShort(qcId)
        messageData?.let { out.writeBytes(it) }
    }

    clientProt(opcode = 14, size = ProtSize.VarByte) {
        QuickChatPrivate(
            toUsername = readRSString(),
            qcId = readUShort().toInt(),
            messageData = if (remaining > 0) readByteArray(remaining.toInt()) else null
        )
    }.encoder { out ->
        out.writeRSString(toUsername)
        out.writeShort(qcId)
        messageData?.let { out.writeBytes(it) }
    }

    clientProt(opcode = 30, size = 1) { ChatType(readUByte().toInt()) }
        .encoder { out -> out.writeByte(type) }
    clientProt(opcode = 20, size = 3) { ChatSetFilter(readUByte().toInt(), readUByte().toInt(), readUByte().toInt()) }
        .encoder { out ->
            out.writeByte(publicFilter)
            out.writeByte(privateFilter)
            out.writeByte(tradeFilter)
        }

    // Friends/Ignore List
    clientProt(opcode = 26, size = ProtSize.VarByte) { AddFriend(readRSString()) }
        .encoder { out -> out.writeRSString(name) }
    clientProt(opcode = 29, size = ProtSize.VarByte) { RemoveFriend(readRSString()) }
        .encoder { out -> out.writeRSString(name) }
    clientProt(opcode = 34, size = ProtSize.VarByte) { AddIgnoreReq(readRSString(), readBoolean()) }
        .encoder { out ->
            out.writeRSString(username)
            out.writeBoolean(temporary)
        }
    clientProt(opcode = 12, size = ProtSize.VarByte) { RemoveIgnore(readRSString()) }
        .encoder { out -> out.writeRSString(name) }

    // Friend Chat
    clientProt(opcode = 71, size = ProtSize.VarByte) { FcJoin(if (remaining > 0) readRSString() else null) }
        .encoder { out -> fcName?.let { out.writeRSString(it) } }
    clientProt(opcode = 91, size = ProtSize.VarByte) { FcKick(readRSString()) }
        .encoder { out -> out.writeRSString(username) }
    clientProt(opcode = 7, size = ProtSize.VarByte) { FcSetRank(rank = readByteSubtract(), username = readRSString()) }
        .encoder { out ->
            out.writeByteSubtract(rank)
            out.writeRSString(username)
        }

    // Clan Chat
    clientProt(opcode = 90, size = ProtSize.VarByte) {
        ClanChannelKickUser(
            guest = !readBoolean(),
            pid = readUShort().toInt(),
            username = readRSString()
        )
    }.encoder { out ->
        out.writeBoolean(!guest)
        out.writeShort(pid)
        out.writeRSString(username)
    }

    // Game State
    clientProt<RegionLoadedConfirm>(opcode = 76)
    clientProt(opcode = 2, size = 4) { SoundEffectMusicEnded(readInt()) }
        .encoder { out -> out.writeInt(musicId) }
    clientProt(opcode = 18, size = 4) { SongLoaded(readInt()) }
        .encoder { out -> out.writeInt(songId) }
    clientProt(opcode = 45, size = 1) { CutsceneFinished(readBoolean()) }
        .encoder { out -> out.writeBoolean(forced) }
    clientProt(opcode = 88, size = 2) { WritePing(readUShort().toInt()) }
        .encoder { out -> out.writeShort(ping) }
    clientProt(opcode = 5, size = 4) { WorldMapClick(Tile(readUIntLittle())) }
        .encoder { out -> out.writeIntLittle(tile.id) }

    clientProt(opcode = 10, size = ProtSize.VarByte) {
        SendPreferences(buildMap {
            var currIdx = 0
            while (remaining > 0) {
                // Index 0 is the client's entry count and index 21 is a reserved filler byte;
                // both map to no Preference and are read purely to advance the cursor.
                val preference = Preference.forIndex(currIdx++)
                val value = readUByte().toInt()
                if (preference != null)
                    put(preference, value)
            }
        })
    }.encoder { out ->
        out.writeByte(PREFERENCES_HEADER)
        for (index in 1..LAST_PREFERENCE_INDEX)
            out.writeByte(Preference.forIndex(index)?.let { preferences[it] } ?: 0)
    }

    clientProt(opcode = 55, size = 4) { TransmitvarVerifyId(readInt()) }
        .encoder { out -> out.writeInt(id) }
    clientProt(opcode = 47, size = 4) { RequestWorldList(readInt()) }
        .encoder { out -> out.writeInt(worldlistVersion) }

    // Reporting/Bug Tracking
    clientProt(opcode = 100, size = ProtSize.VarByte) {
        ReportAbuse(
            username = readRSString(),
            type = readUByte().toInt(),
            mute = readBoolean(),
            reason = readRSString()
        )
    }.encoder { out ->
        out.writeRSString(username)
        out.writeByte(type)
        out.writeBoolean(mute)
        out.writeRSString(reason)
    }

    clientProt(opcode = 35, size = ProtSize.VarShort) {
        BugReport(
            category = readUByte().toInt(),
            body = readJagString(),
            reproSteps = readJagString()
        )
    }.encoder { out ->
        out.writeByte(category)
        out.writeJagString(body)
        out.writeJagString(reproSteps)
    }

    // Account/Login
    clientProt(opcode = 52, size = ProtSize.VarByte) { EmailValidationSubmitCode(readRSString()) }
        .encoder { out -> out.writeRSString(code) }
    clientProt(opcode = 92, size = ProtSize.VarShort) { EmailValidationAddNewAddress(readRSString(), readByte().toInt()) }
        .encoder { out ->
            out.writeRSString(email)
            out.writeByte(flags)
        }
    clientProt(opcode = 99, size = ProtSize.VarShort) { EmailValidationChangeAddress(readRSString(), readRSString()) }
        .encoder { out ->
            out.writeRSString(email)
            out.writeRSString(email2)
        }
    clientProt(opcode = 102, size = ProtSize.VarShort) { CheckEmailValidity(readByteArray(remaining.toInt())) }
        .encoder { out -> out.writeBytes(encryptedData) }
    clientProt(opcode = 101, size = ProtSize.VarShort) { SendSignUpForm(readByteArray(remaining.toInt())) }
        .encoder { out -> out.writeBytes(encryptedData) }
    clientProt(opcode = 79, size = 1) { AccountCreationStage(readUByte().toInt()) }
        .encoder { out -> out.writeByte(stage) }

    clientProt(opcode = 56, size = ProtSize.VarShort) {
        LobbyHyperlink(
            service = readRSString(),
            page = readRSString(),
            query = readRSString(),
            flags = readByte().toInt()
        )
    }.encoder { out ->
        out.writeRSString(service)
        out.writeRSString(page)
        out.writeRSString(query)
        out.writeByte(flags)
    }

    // Unknown/Misc
    clientProt(opcode = 37, size = 2) { PlayVorbis(readShort().toInt()) }
        .encoder { out -> out.writeShort(fileId) }
    clientProt(opcode = 63, size = 4) { AltWalk(x = readUShortAdd(), y = readUShort().toInt()) }
        .encoder { out ->
            out.writeShortAdd(x)
            out.writeShort(y)
        }
    clientProt(opcode = 82, size = 4) { AppletLoadingPleaseWait(readInt() /*Always 1057001181*/) }
        .encoder { out -> out.writeInt(data) }
    clientProt(opcode = 97, size = ProtSize.VarByte) { UnkCs2StringResponse(readRSString()) }
        .encoder { out -> out.writeRSString(str) }
}
