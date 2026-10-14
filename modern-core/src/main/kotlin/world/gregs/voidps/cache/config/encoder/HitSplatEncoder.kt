package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.HitSplatDefinition

/**
 * Writes a hit mark (`hitmark`, index 2 archive 46) file.
 *
 * The font and the four graphics are big smarts, not shorts; see
 * [world.gregs.voidps.cache.config.decoder.HitSplatDecoder]. Fade is written either as the bare
 * opcode 11 (fade from cycle 0) or as opcode 14 with a cycle, which the recorded opcode order
 * disambiguates.
 */
class HitSplatEncoder : OpcodeEncoder<HitSplatDefinition>() {

    override fun opcodes(definition: HitSplatDefinition): IntArray {
        val opcodes = ArrayList<Int>(13)
        if (definition.font != -1) {
            opcodes.add(1)
        }
        if (definition.textColour != 16777215) {
            opcodes.add(2)
        }
        if (definition.icon != -1) {
            opcodes.add(3)
        }
        if (definition.left != -1) {
            opcodes.add(4)
        }
        if (definition.middle != -1) {
            opcodes.add(5)
        }
        if (definition.right != -1) {
            opcodes.add(6)
        }
        if (definition.offsetX != 0) {
            opcodes.add(7)
        }
        if (definition.amount != "") {
            opcodes.add(8)
        }
        if (definition.duration != 70) {
            opcodes.add(9)
        }
        if (definition.offsetY != 0) {
            opcodes.add(10)
        }
        if (definition.fade == 0) {
            opcodes.add(11)
        }
        if (definition.comparisonType != -1) {
            opcodes.add(12)
        }
        if (definition.anInt3214 != 0) {
            opcodes.add(13)
        }
        if (definition.fade > 0) {
            opcodes.add(14)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: HitSplatDefinition, members: HitSplatDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.font)
            2 -> writeMedium(definition.textColour)
            3 -> writeBigSmart(definition.icon)
            4 -> writeBigSmart(definition.left)
            5 -> writeBigSmart(definition.middle)
            6 -> writeBigSmart(definition.right)
            7 -> writeShort(definition.offsetX)
            8 -> writePrefixedString(definition.amount)
            9 -> writeShort(definition.duration)
            10 -> writeShort(definition.offsetY)
            11 -> {}
            12 -> writeByte(definition.comparisonType)
            13 -> writeShort(definition.anInt3214)
            14 -> writeShort(definition.fade)
            else -> error("Unhandled hit mark opcode $opcode for definition ${definition.id}")
        }
    }
}
