package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition

/**
 * Writes an index 21 spotanim file.
 *
 * Opcodes 9 and 11 to 16 all set the same brightness/fade pair, so which of them a definition came from is
 * recovered from the pair's value; none of them appear in 727, where only 1, 2, 4 to 8, 10 and 40 are used.
 */
class SpotAnimEncoder : OpcodeEncoder<SpotAnimDefinition>() {

    override fun opcodes(definition: SpotAnimDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelId != 0) {
            opcodes.add(1)
        }
        if (definition.animationId != -1) {
            opcodes.add(2)
        }
        if (definition.sizeXY != 128) {
            opcodes.add(4)
        }
        if (definition.sizeZ != 128) {
            opcodes.add(5)
        }
        if (definition.rotation != 0) {
            opcodes.add(6)
        }
        if (definition.ambience != 0) {
            opcodes.add(7)
        }
        if (definition.contrast != 0) {
            opcodes.add(8)
        }
        when (definition.aByte2381.toInt()) {
            1 -> opcodes.add(11)
            2 -> opcodes.add(14)
            3 -> opcodes.add(if (definition.anInt2385 == 8224) 9 else if (definition.anInt2385 in 0..65535) 15 else 16)
            4 -> opcodes.add(12)
            5 -> opcodes.add(13)
        }
        if (definition.aBoolean2402) {
            opcodes.add(10)
        }
        if (definition.originalColours != null && definition.modifiedColours != null) {
            opcodes.add(40)
        }
        if (definition.originalTextureColours != null && definition.modifiedTextureColours != null) {
            opcodes.add(41)
        }
        val array = opcodes.toIntArray()
        array.sort()
        return array
    }

    override fun Writer.encodeOpcode(definition: SpotAnimDefinition, members: SpotAnimDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.modelId)
            2 -> writeBigSmart(definition.animationId)
            4 -> writeShort(definition.sizeXY)
            5 -> writeShort(definition.sizeZ)
            6 -> writeShort(definition.rotation)
            7 -> writeByte(definition.ambience)
            8 -> writeByte(definition.contrast)
            9, 10, 11, 12, 13 -> Unit
            14 -> writeByte(definition.anInt2385 / 256)
            15 -> writeShort(definition.anInt2385)
            16 -> writeInt(definition.anInt2385)
            40 -> writeColours(definition.originalColours, definition.modifiedColours)
            41 -> writeColours(definition.originalTextureColours, definition.modifiedTextureColours)
            else -> error("Unhandled spotanim definition opcode: $opcode for def ${definition.id}")
        }
    }
}
