package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.StructDefinition

/**
 * Writes a struct (`struct`, index 2 archive 26) file: nothing but the param opcode.
 *
 * The params keep the order they were read in, which is what makes the file byte exact -
 * [world.gregs.voidps.cache.definition.Parameterized.readParameters] decodes into an insertion
 * ordered map for exactly this reason, and the 37 structs that list a key twice keep their records.
 */
class StructEncoder : OpcodeEncoder<StructDefinition>() {

    override fun opcodes(definition: StructDefinition): IntArray {
        return if (definition.params == null) IntArray(0) else intArrayOf(249)
    }

    override fun Writer.encodeOpcode(definition: StructDefinition, members: StructDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            249 -> writeParams(definition.id, definition.params, definition.paramRecords)
            else -> error("Unhandled struct opcode $opcode for definition ${definition.id}")
        }
    }
}
