package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.INTERFACES
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import world.gregs.voidps.cache.definition.data.InterfaceDefinition

class InterfaceDecoder : DefinitionDecoder<InterfaceDefinition>(INTERFACES) {

    override fun create(size: Int) = Array(size) { InterfaceDefinition(it) }

    override fun size(cache: Cache): Int {
        return cache.lastArchiveId(index)
    }

    override fun load(definitions: Array<InterfaceDefinition>, reader: Reader) {
        val packed = readId(reader)
        val id = InterfaceDefinition.id(packed)
        val definition = definitions[id]
        if (definition.components == null) {
            definition.components = Array(InterfaceDefinition.componentId(packed) + 1) { InterfaceComponentDefinition(id = it + (id shl 16)) }
        }
        definition.components!![InterfaceDefinition.componentId(packed)].read(reader)
    }

    override fun load(definitions: Array<InterfaceDefinition>, cache: Cache, id: Int) {
        val archiveId = getArchive(id)
        val lastArchive = cache.lastFileId(index, archiveId)
        if (lastArchive == -1) {
            return
        }
        val definition = definitions[id]
        val components = Array(lastArchive + 1) { InterfaceComponentDefinition(id = it + (id shl 16)) }
        for (i in 0..lastArchive) {
            val data = cache.data(index, archiveId, i)
            if (data != null) {
                components[i].read(BufferReader(data))
            }
        }
        definition.components = components
    }

    fun InterfaceComponentDefinition.read(buffer: Reader) {
        // 255 marks the original if3 format, anything else is a later revision of it
        var format = buffer.readUnsignedByte()
        if (format == 255) {
            format = -1
        }
        type = buffer.readUnsignedByte()
        if (type and 0x80 != 0) {
            type = type and 0x7f
            unknown = buffer.readString()
        }
        contentType = buffer.readUnsignedShort()
        // Base positions are signed; components are routinely offset by negative amounts from
        // their anchor. Base sizes are unsigned. Reading these the other way around wraps
        // negative offsets to ~65k and pushes the component off screen.
        basePositionX = buffer.readShort()
        basePositionY = buffer.readShort()
        baseWidth = buffer.readUnsignedShort()
        baseHeight = buffer.readUnsignedShort()
        horizontalSizeMode = buffer.readByte().toByte()
        verticalSizeMode = buffer.readByte().toByte()
        horizontalPositionMode = buffer.readByte().toByte()
        verticalPositionMode = buffer.readByte().toByte()
        parent = buffer.readShort()
        if (parent == 65535) {
            parent = -1
        }
        val flag = buffer.readUnsignedByte()
        hidden = 0x1 and flag != 0
        if (type == 0) {
            scrollWidth = buffer.readShort()
            scrollHeight = buffer.readShort()
            disableHover = buffer.readUnsignedByte() == 1
        } else if (type == 3) {
            colour = buffer.readInt()
            filled = buffer.readUnsignedByte() == 1
            alpha = buffer.readUnsignedByte()
        } else if (type == 4) {
            fontId = buffer.readBigSmart()
            text = buffer.readString()
            lineHeight = buffer.readUnsignedByte()
            horizontalTextAlign = buffer.readUnsignedByte()
            verticalTextAlign = buffer.readUnsignedByte()
            shaded = buffer.readUnsignedByte() == 1
            colour = buffer.readInt()
            alpha = buffer.readUnsignedByte()
        } else if (type == 5) {
            defaultImage = buffer.readInt()
            imageRotation = buffer.readShort()
            val type = buffer.readUnsignedByte()
            aBoolean4861 = type and 0x1 != 0
            imageRepeat = type and 0x2 != 0
            alpha = buffer.readUnsignedByte()
            rotation = buffer.readUnsignedByte()
            backgroundColour = buffer.readInt()
            flipVertical = buffer.readUnsignedByte() == 1
            flipHorizontal = buffer.readUnsignedByte() == 1
            colour = buffer.readInt()
        } else if (type == 6) {
            defaultMediaType = 1
            defaultMediaId = buffer.readBigSmart()
            val modelFlag = buffer.readUnsignedByte()
            animated = 0x4 and modelFlag == 4
            val bool = 0x1 and modelFlag == 1
            centreType = modelFlag and 0x2 == 2
            ignoreZBuffer = 0x8 and modelFlag == 8
            if (bool) {
                viewportX = buffer.readUnsignedShort()
                viewportY = buffer.readUnsignedShort()
                graphicPitch = buffer.readShort()
                graphicRoll = buffer.readShort()
                graphicYaw = buffer.readShort()
                graphicScale = buffer.readShort()
            } else if (centreType) {
                viewportX = buffer.readUnsignedShort()
                viewportY = buffer.readUnsignedShort()
                viewportZ = buffer.readUnsignedShort()
                graphicPitch = buffer.readShort()
                graphicRoll = buffer.readShort()
                graphicYaw = buffer.readShort()
                graphicScale = buffer.readUnsignedShort()
            }
            animation = buffer.readBigSmart()
            if (horizontalSizeMode.toInt() != 0) {
                viewportWidth = buffer.readShort()
            }
            if (verticalSizeMode.toInt() != 0) {
                viewportHeight = buffer.readShort()
            }
        } else if (type == 9) {
            lineWidth = buffer.readUnsignedByte()
            colour = buffer.readInt()
            lineMirrored = buffer.readUnsignedByte() == 1
        }
        val setting = buffer.readUnsignedMedium()
        var mod = buffer.readUnsignedByte()
        if (mod != 0) {
            keyRepeats = ByteArray(11)
            keyCodes = ByteArray(11)
            keyModifiers = IntArray(11)
            // Each binding is four bytes; the first packs the slot into the high nibble (stored as
            // slot + 1, so a zero byte terminates the list) and the top nibble of a 12 bit repeat
            // delay into the low nibble. The second byte holds the rest of the delay.
            while (mod != 0) {
                val index = (mod shr 4) - 1
                mod = ((mod shl 8) or buffer.readUnsignedByte()) and 0xfff
                val repeat = buffer.readByte().toByte()
                if (repeat.toInt() != 0) {
                    clickable = true
                }
                val code = buffer.readByte().toByte()
                keyModifiers!![index] = mod
                keyRepeats!![index] = repeat
                keyCodes!![index] = code
                mod = buffer.readUnsignedByte()
            }
        }
        name = buffer.readString()
        val mouseFlag = buffer.readUnsignedByte()
        val optionCount = mouseFlag and 0xf
        if (optionCount > 0) {
            options = Array(optionCount) { buffer.readString() }
        }
        val iconCount = mouseFlag shr 4
        if (iconCount > 0) {
            val size = buffer.readUnsignedByte()
            mouseIcon = IntArray(size + 1) { if (size + 1 > it) -1 else 0 }
            mouseIcon!![size] = buffer.readShort()
        }
        if (iconCount > 1) {
            mouseIcon!![buffer.readUnsignedByte()] = buffer.readShort()
        }
        optionOverride = buffer.readString()
        if (optionOverride == "") {
            optionOverride = null
        }
        anInt4708 = buffer.readUnsignedByte()
        anInt4795 = buffer.readUnsignedByte()
        anInt4860 = buffer.readUnsignedByte()
        useOption = buffer.readString()
        var settingData = -1
        if (InterfaceComponentSetting.useOptionFlags(setting) != 0) {
            settingData = buffer.readShort()
            if (settingData == 65535) {
                settingData = -1
            }
            anInt4698 = buffer.readShort()
            if (anInt4698 == 65535) {
                anInt4698 = -1
            }
            anInt4839 = buffer.readShort()
            if (anInt4839 == 65535) {
                anInt4839 = -1
            }
        }
        this.setting = InterfaceComponentSetting(setting, settingData)
        onLoadScript = decodeScript(buffer)
        onMouseOver = decodeScript(buffer)
        onMouseLeaveScript = decodeScript(buffer)
        hookParams = decodeScript(buffer)
        onTargetEnter = decodeScript(buffer)
        onVarpTransmit = decodeScript(buffer)
        mouseLeaveScript = decodeScript(buffer)
        onStatTransmit = decodeScript(buffer)
        onTimer = decodeScript(buffer)
        params = decodeScript(buffer)
        if (format >= 0) {
            onTargetLeave = decodeScript(buffer)
        }
        popupScript = decodeScript(buffer)
        onClick = decodeScript(buffer)
        onClickRepeat = decodeScript(buffer)
        onRelease = decodeScript(buffer)
        onHold = decodeScript(buffer)
        onDrag = decodeScript(buffer)
        onDragComplete = decodeScript(buffer)
        onMouseMove = decodeScript(buffer)
        onKey = decodeScript(buffer)
        onScrollWheel = decodeScript(buffer)
        varps = decodeIntArray(buffer)
        mouseLeaveArrayParams = decodeIntArray(buffer)
        statTransmitFilter = decodeIntArray(buffer)
        keyPressArray = decodeIntArray(buffer)
        mouseWheelArray = decodeIntArray(buffer)
    }

    override fun InterfaceDefinition.read(opcode: Int, buffer: Reader) {
        throw IllegalStateException("Shouldn't be used.")
    }

    companion object {
        /**
         * Reads an event hook; the first value is the script to run, the rest are the arguments bound to it.
         */
        private fun InterfaceComponentDefinition.decodeScript(buffer: Reader): InterfaceComponentHook? {
            val length = buffer.readUnsignedByte()
            if (length == 0) {
                return null
            }
            val objects = Array<Any>(length) {
                val string = buffer.readUnsignedBoolean()
                if (string) buffer.readString() else buffer.readInt()
            }
            hasScript = true
            return InterfaceComponentHook(objects[0] as? Int ?: -1, objects.copyOfRange(1, objects.size))
        }

        private fun decodeIntArray(buffer: Reader): IntArray? {
            val length = buffer.readUnsignedByte()
            if (length == 0) {
                return null
            }
            return IntArray(length) { buffer.readInt() }
        }
    }
}