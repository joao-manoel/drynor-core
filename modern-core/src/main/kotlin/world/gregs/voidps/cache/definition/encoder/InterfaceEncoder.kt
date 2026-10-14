package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting

class InterfaceEncoder : DefinitionEncoder<InterfaceComponentDefinition> {

    override fun Writer.encode(definition: InterfaceComponentDefinition) {
        if (definition.id == -1) {
            return
        }
        writeByte(-1)
        // The decoder only reads the name back when the high bit is set, so an empty (but present)
        // name still has to set it or the field decodes as null instead of "".
        if (definition.unknown != null) {
            writeByte(definition.type or 0x80)
            writeString(definition.unknown)
        } else {
            writeByte(definition.type)
        }
        writeShort(definition.contentType)
        writeShort(definition.basePositionX)
        writeShort(definition.basePositionY)
        writeShort(definition.baseWidth)
        writeShort(definition.baseHeight)
        writeByte(definition.horizontalSizeMode.toInt())
        writeByte(definition.verticalSizeMode.toInt())
        writeByte(definition.horizontalPositionMode.toInt())
        writeByte(definition.verticalPositionMode.toInt())
        writeShort(definition.parent)

        var hiddenFlag = 0
        if (definition.hidden) {
            hiddenFlag = hiddenFlag or 0x1
        }
        writeByte(hiddenFlag)

        when (definition.type) {
            0 -> {
                writeShort(definition.scrollWidth)
                writeShort(definition.scrollHeight)
                writeByte(definition.disableHover)
            }
            3 -> {
                writeInt(definition.colour)
                writeByte(definition.filled)
                writeByte(definition.alpha)
            }
            4 -> {
                writeBigSmart(definition.fontId)
                writeString(definition.text)
                writeByte(definition.lineHeight)
                writeByte(definition.horizontalTextAlign)
                writeByte(definition.verticalTextAlign)
                writeByte(definition.shaded)
                writeInt(definition.colour)
                writeByte(definition.alpha)
            }
            5 -> {
                writeInt(definition.defaultImage)
                writeShort(definition.imageRotation)
                var imageFlag = 0
                if (definition.aBoolean4861) {
                    imageFlag = imageFlag or 0x1
                }
                if (definition.imageRepeat) {
                    imageFlag = imageFlag or 0x2
                }
                writeByte(imageFlag)
                writeByte(definition.alpha)
                writeByte(definition.rotation)
                writeInt(definition.backgroundColour)
                writeByte(definition.flipVertical)
                writeByte(definition.flipHorizontal)
                writeInt(definition.colour)
            }
            6 -> {
                writeBigSmart(definition.defaultMediaId)
                var flag = 0
                if (definition.animated) {
                    flag = flag or 0x4
                }
                if (definition.centreType) {
                    flag = flag or 0x2
                }
                if (definition.ignoreZBuffer) {
                    flag = flag or 0x8
                }
                // Only one of the two viewport blocks is ever present. 0x2 (centreType) carries
                // viewportZ, 0x1 does not, so a component that wants a Z has to use the centre block
                // and viewportZ is dropped when centreType is false - the format has nowhere to put it.
                val transform = !definition.centreType && (definition.viewportX != 0 || definition.viewportY != 0 || definition.graphicPitch != 0 || definition.graphicRoll != 0 || definition.graphicYaw != 0 || definition.graphicScale != 100)
                if (transform) {
                    flag = flag or 0x1
                }
                writeByte(flag)
                if (transform) {
                    writeShort(definition.viewportX)
                    writeShort(definition.viewportY)
                    writeShort(definition.graphicPitch)
                    writeShort(definition.graphicRoll)
                    writeShort(definition.graphicYaw)
                    writeShort(definition.graphicScale)
                } else if (definition.centreType) {
                    writeShort(definition.viewportX)
                    writeShort(definition.viewportY)
                    writeShort(definition.viewportZ)
                    writeShort(definition.graphicPitch)
                    writeShort(definition.graphicRoll)
                    writeShort(definition.graphicYaw)
                    writeShort(definition.graphicScale)
                }
                writeBigSmart(definition.animation)
                if (definition.horizontalSizeMode.toInt() != 0) {
                    writeShort(definition.viewportWidth)
                }
                if (definition.verticalSizeMode.toInt() != 0) {
                    writeShort(definition.viewportHeight)
                }
            }
            9 -> {
                writeByte(definition.lineWidth)
                writeInt(definition.colour)
                writeByte(definition.lineMirrored)
            }
        }

        writeMedium(definition.setting.setting)
        encodeKeyBindings(definition)
        writeString(definition.name)
        var optionFlag = 0
        val options = definition.options
        if (options != null) {
            optionFlag = options.size
        }
        // Only the entries actually written go in the high nibble, not the array length; the decoder
        // takes the array length from the index byte of the first entry instead.
        val icons = definition.mouseIcon?.takeIf { it.isNotEmpty() }
        val extraIcon = if (icons == null) -1 else icons.indices.firstOrNull { it != icons.lastIndex && icons[it] != -1 } ?: -1
        if (icons != null) {
            optionFlag = optionFlag or ((if (extraIcon == -1) 1 else 2) shl 4)
        }
        writeByte(optionFlag)
        options?.forEach {
            writeString(it)
        }
        if (icons != null) {
            // The last entry has to come first so the decoder sizes the array correctly. The format
            // only carries two entries in total, so anything else set past the second is lost.
            writeByte(icons.lastIndex)
            writeShort(icons[icons.lastIndex])
            if (extraIcon != -1) {
                writeByte(extraIcon)
                writeShort(icons[extraIcon])
            }
        }

        writeString(definition.optionOverride)
        writeByte(definition.anInt4708)
        writeByte(definition.anInt4795)
        writeByte(definition.anInt4860)
        writeString(definition.useOption)

        // The presence of these three shorts is decided by the setting bits, never by their own value:
        // components routinely have the bits set and store 65535, which reads back as -1. Gating the
        // write on "not -1" instead drops three shorts the decoder still expects and desyncs the rest
        // of the component. writeShort truncates to 16 bits, so -1 goes out as the 65535 sentinel.
        if (InterfaceComponentSetting.useOptionFlags(definition.setting.setting) != 0) {
            writeShort(definition.setting.anInt7413)
            writeShort(definition.anInt4698)
            writeShort(definition.anInt4839)
        }

        encodeScript(definition.onLoadScript)
        encodeScript(definition.onMouseOver)
        encodeScript(definition.onMouseLeaveScript)
        encodeScript(definition.hookParams)
        encodeScript(definition.onTargetEnter)
        encodeScript(definition.onVarpTransmit)
        encodeScript(definition.mouseLeaveScript)
        encodeScript(definition.onStatTransmit)
        encodeScript(definition.onTimer)
        encodeScript(definition.params)
        // onTargetLeave is skipped, the format written above is the original if3
        encodeScript(definition.popupScript)
        encodeScript(definition.onClick)
        encodeScript(definition.onClickRepeat)
        encodeScript(definition.onRelease)
        encodeScript(definition.onHold)
        encodeScript(definition.onDrag)
        encodeScript(definition.onDragComplete)
        encodeScript(definition.onMouseMove)
        encodeScript(definition.onKey)
        encodeScript(definition.onScrollWheel)
        encodeIntArray(definition.varps)
        encodeIntArray(definition.mouseLeaveArrayParams)
        encodeIntArray(definition.statTransmitFilter)
        encodeIntArray(definition.keyPressArray)
        encodeIntArray(definition.mouseWheelArray)
    }

    /**
     * Writes the key binding list; each entry is four bytes and the list is terminated by a zero byte
     * where the next entry's header would start.
     *
     * Header byte packs the slot as `slot + 1` in the high nibble - which is what makes zero a valid
     * terminator - and the top nibble of the 12 bit repeat delay in the low nibble. The remaining
     * eight bits of the delay follow, then the key and the modifier mask.
     */
    private fun Writer.encodeKeyBindings(definition: InterfaceComponentDefinition) {
        val keyRepeats = definition.keyRepeats
        val keyCodes = definition.keyCodes
        val keyModifiers = definition.keyModifiers
        if (keyRepeats == null || keyCodes == null || keyModifiers == null) {
            writeByte(0)
            return
        }
        val size = minOf(keyRepeats.size, keyCodes.size, keyModifiers.size, SLOTS)
        var written = 0
        for (slot in 0 until size) {
            if (keyRepeats[slot].toInt() == 0 && keyCodes[slot].toInt() == 0 && keyModifiers[slot] == 0) {
                continue
            }
            val delay = keyModifiers[slot] and 0xfff
            writeByte(((slot + 1) shl 4) or (delay shr 8))
            writeByte(delay)
            writeByte(keyRepeats[slot].toInt())
            writeByte(keyCodes[slot].toInt())
            written++
        }
        if (written == 0) {
            // Arrays exist but every slot is empty; an entirely empty list would decode back as null,
            // so keep the arrays alive with a single blank binding in slot 0.
            writeByte(1 shl 4)
            writeByte(0)
            writeByte(0)
            writeByte(0)
        }
        writeByte(0)
    }

    private fun Writer.encodeScript(hook: InterfaceComponentHook?) {
        if (hook == null) {
            writeByte(0)
            return
        }
        writeByte(hook.args.size + 1)
        writeByte(false)
        writeInt(hook.script)
        hook.args.forEach {
            writeByte(it is String)
            if (it is String) {
                writeString(it)
            } else if (it is Int) {
                writeInt(it)
            }
        }
    }

    private fun Writer.encodeIntArray(script: IntArray?) {
        val size = script?.size ?: 0
        writeByte(size)
        script?.forEach {
            writeInt(it)
        }
    }

    private companion object {
        /** Key binding slots the decoder allocates; slot 10 is the "use" binding. */
        private const val SLOTS = 11
    }
}