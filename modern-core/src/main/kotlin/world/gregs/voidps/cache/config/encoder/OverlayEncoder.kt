package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.OverlayDefinition

/**
 * Writes an overlay (`flo`, index 2 archive 4) file.
 *
 * Opcodes 1 and 7 carry rgb that the decoder converts to packed hsl and cannot convert back, so
 * they are written from [OverlayDefinition.colourRgb] / [OverlayDefinition.blendColourRgb].
 * Opcode 11's byte is [OverlayDefinition.slot], the raw byte, not the `slot shl 8 or id` the decoder
 * packs it into afterwards (client `FloType.postDecode`).
 */
class OverlayEncoder : OpcodeEncoder<OverlayDefinition>() {

    override fun opcodes(definition: OverlayDefinition): IntArray {
        val opcodes = ArrayList<Int>(11)
        if (definition.colourRgb != -1) {
            opcodes.add(1)
        }
        if (definition.texture != -1) {
            opcodes.add(3)
        }
        if (!definition.hideUnderlay) {
            opcodes.add(5)
        }
        if (definition.blendColourRgb != -1) {
            opcodes.add(7)
        }
        if (definition.anInt961 != 0) {
            opcodes.add(8)
        }
        if (definition.scale != 512) {
            opcodes.add(9)
        }
        if (!definition.blockShadow) {
            opcodes.add(10)
        }
        if (definition.slot != OverlayDefinition.DEFAULT_SLOT) {
            opcodes.add(11)
        }
        if (definition.underlayOverrides) {
            opcodes.add(12)
        }
        if (definition.waterColour != 1190717) {
            opcodes.add(13)
        }
        if (definition.waterScale != 64) {
            opcodes.add(14)
        }
        if (definition.waterIntensity != 127) {
            opcodes.add(16)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: OverlayDefinition, members: OverlayDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeMedium(definition.colourRgb)
            2 -> writeByte(definition.texture)
            3 -> writeShort(definition.texture)
            5 -> {}
            7 -> writeMedium(definition.blendColourRgb)
            8 -> {}
            9 -> writeShort(definition.scale shr 2)
            10 -> {}
            11 -> writeByte(definition.slot)
            12 -> {}
            13 -> writeMedium(definition.waterColour)
            14 -> writeByte(definition.waterScale shr 2)
            16 -> writeByte(definition.waterIntensity)
            else -> error("Unhandled overlay opcode $opcode for definition ${definition.id}")
        }
    }
}
