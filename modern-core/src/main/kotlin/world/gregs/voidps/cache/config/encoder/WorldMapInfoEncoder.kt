package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.WorldMapInfoDefinition
import world.gregs.voidps.cache.definition.encoder.writeExtras

/**
 * Writes a map element (`mapelement`, index 2 archive 36) file.
 *
 * The two graphic ids (opcodes 1, 2) and the label graphic (opcode 18) are big smarts. The min/max
 * offset bounds the decoder derives from opcode 15 are not written; they are recomputed on decode.
 */
class WorldMapInfoEncoder : OpcodeEncoder<WorldMapInfoDefinition>() {

    override fun opcodes(definition: WorldMapInfoDefinition): IntArray {
        val opcodes = ArrayList<Int>(24)
        if (definition.graphicId != -1) {
            opcodes.add(1)
        }
        if (definition.highlightGraphicId != -1) {
            opcodes.add(2)
        }
        if (definition.name != null) {
            opcodes.add(3)
        }
        if (definition.anInt1058 != 0) {
            opcodes.add(4)
        }
        if (definition.anInt1054 != -1) {
            opcodes.add(5)
        }
        if (definition.fontSize != 0) {
            opcodes.add(6)
        }
        if (definition.displayFlags != -1) {
            opcodes.add(7)
        }
        if (definition.randomisedMinimapPosition != -1) {
            opcodes.add(8)
        }
        if (definition.varbit != -1 || definition.varp != -1 || definition.anInt1087 != 0 || definition.anInt1042 != 0) {
            opcodes.add(9)
        }
        for (index in definition.aStringArray1065.indices) {
            if (definition.aStringArray1065[index] != null) {
                opcodes.add(10 + index)
            }
        }
        if (definition.anIntArray1049 != null) {
            opcodes.add(15)
        }
        if (!definition.aBoolean1064) {
            opcodes.add(16)
        }
        if (definition.aString1045 != null) {
            opcodes.add(17)
        }
        if (definition.anInt1093 != -1) {
            opcodes.add(18)
        }
        if (definition.clientScript != -1) {
            opcodes.add(19)
        }
        if (definition.anInt1048 != -1 || definition.anInt1044 != -1 || definition.anInt1078 != 0 || definition.anInt1072 != 0) {
            opcodes.add(20)
        }
        if (definition.anInt1081 != 0) {
            opcodes.add(21)
        }
        if (definition.anInt1077 != 0) {
            opcodes.add(22)
        }
        if (definition.anInt1074 != -1 || definition.anInt1050 != -1 || definition.anInt1080 != -1) {
            opcodes.add(23)
        }
        if (definition.anInt1071 != 0 || definition.anInt1092 != 0) {
            opcodes.add(24)
        }
        if (definition.extras != null) {
            opcodes.add(249)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: WorldMapInfoDefinition, members: WorldMapInfoDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphicId)
            2 -> writeBigSmart(definition.highlightGraphicId)
            3 -> writeString(definition.name)
            4 -> writeMedium(definition.anInt1058)
            5 -> writeMedium(definition.anInt1054)
            6 -> writeByte(definition.fontSize)
            7 -> writeByte(definition.displayFlags)
            8 -> writeByte(definition.randomisedMinimapPosition)
            9 -> {
                writeShort(definition.varbit)
                writeShort(definition.varp)
                writeInt(definition.anInt1087)
                writeInt(definition.anInt1042)
            }
            in 10..14 -> writeString(definition.aStringArray1065[opcode - 10])
            15 -> {
                // The area outline: one x/y pair per vertex, the outline colour, the fill colour
                // table, then one colour table index per vertex.
                val offsets = definition.anIntArray1049 ?: IntArray(0)
                val colours = definition.anIntArray1066 ?: IntArray(0)
                val pointers = definition.aByteArray1057 ?: ByteArray(0)
                writeByte(pointers.size)
                for (offset in offsets) {
                    writeShort(offset)
                }
                writeInt(definition.anInt1084)
                writeByte(colours.size)
                for (colour in colours) {
                    writeInt(colour)
                }
                for (pointer in pointers) {
                    writeByte(pointer.toInt())
                }
            }
            16 -> {}
            17 -> writeString(definition.aString1045)
            18 -> writeBigSmart(definition.anInt1093)
            19 -> writeShort(definition.clientScript)
            20 -> {
                writeShort(definition.anInt1048)
                writeShort(definition.anInt1044)
                writeInt(definition.anInt1078)
                writeInt(definition.anInt1072)
            }
            21 -> writeInt(definition.anInt1081)
            22 -> writeInt(definition.anInt1077)
            23 -> {
                writeByte(definition.anInt1074)
                writeByte(definition.anInt1050)
                writeByte(definition.anInt1080)
            }
            24 -> {
                writeShort(definition.anInt1071)
                writeShort(definition.anInt1092)
            }
            249 -> writeExtras(definition.id, definition.extras)
            else -> error("Unhandled map element opcode $opcode for definition ${definition.id}")
        }
    }
}
