package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.EnumDefinition

/**
 * Writes an index 17 enum file.
 *
 * The entries come in one of four shapes and the opcode says which: 5 and 6 are a flat list of int key to
 * string or int value, 7 and 8 the same but keyed by a short index into an array whose capacity
 * ([EnumDefinition.arraySize]) the file also carries.
 */
class EnumEncoder : OpcodeEncoder<EnumDefinition>() {

    override fun opcodes(definition: EnumDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.keyType != 0.toChar()) {
            opcodes.add(1)
        }
        if (definition.valueType != 0.toChar()) {
            opcodes.add(2)
        }
        if (definition.defaultString != "null") {
            opcodes.add(3)
        }
        if (definition.defaultInt != 0) {
            opcodes.add(4)
        }
        val map = definition.map
        if (map != null) {
            val strings = map.values.firstOrNull()?.let { it is String } ?: (definition.valueType == 's')
            opcodes.add(if (definition.arraySize != -1) if (strings) 7 else 8 else if (strings) 5 else 6)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: EnumDefinition, members: EnumDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(Cp1252.encode(definition.keyType).toInt())
            2 -> writeByte(Cp1252.encode(definition.valueType).toInt())
            3 -> writeString(definition.defaultString)
            4 -> writeInt(definition.defaultInt)
            5, 6, 7, 8 -> {
                val entries = definition.entries ?: (definition.map ?: emptyMap()).map { KeyedValue(it.key, it.value) }
                if (opcode >= 7) {
                    writeShort(definition.arraySize)
                }
                writeShort(entries.size)
                for (entry in entries) {
                    if (opcode >= 7) {
                        writeShort(entry.key)
                    } else {
                        writeInt(entry.key)
                    }
                    if (opcode == 5 || opcode == 7) {
                        writeString(entry.value as String)
                    } else {
                        writeInt(entry.value as Int)
                    }
                }
            }
            else -> error("Unhandled enum definition opcode: $opcode for def ${definition.id}")
        }
    }
}
