package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ParamDefinition

/**
 * Writes a param (`param`, index 2 archive 11) file.
 */
class ParamEncoder : OpcodeEncoder<ParamDefinition>() {

    override fun opcodes(definition: ParamDefinition): IntArray {
        val opcodes = ArrayList<Int>(4)
        if (definition.type.code != 0) {
            opcodes.add(1)
        }
        if (definition.defaultInt != 0) {
            opcodes.add(2)
        }
        if (!definition.autoDisable) {
            opcodes.add(4)
        }
        if (definition.defaultString != null) {
            opcodes.add(5)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ParamDefinition, members: ParamDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(Cp1252.encode(definition.type).toInt())
            2 -> writeInt(definition.defaultInt)
            4 -> {}
            5 -> writeString(definition.defaultString)
            else -> error("Unhandled param opcode $opcode for definition ${definition.id}")
        }
    }
}
