package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.InventoryDefinition

/**
 * Writes an inventory (`inv`, index 2 archive 5) file.
 */
class InventoryEncoder : OpcodeEncoder<InventoryDefinition>() {

    override fun opcodes(definition: InventoryDefinition): IntArray {
        val opcodes = ArrayList<Int>(2)
        if (definition.length != 0) {
            opcodes.add(2)
        }
        if (definition.ids != null) {
            opcodes.add(4)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: InventoryDefinition, members: InventoryDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            2 -> writeShort(definition.length)
            4 -> {
                val ids = definition.ids ?: IntArray(0)
                val amounts = definition.amounts ?: IntArray(0)
                writeByte(ids.size)
                for (index in ids.indices) {
                    writeShort(ids[index])
                    writeShort(amounts[index])
                }
            }
            else -> error("Unhandled inventory opcode $opcode for definition ${definition.id}")
        }
    }
}
