package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.ClientVariableParameterDefinition

/**
 * Writes a client variable (`varc`, index 2 archive 19) file.
 */
class ClientVariableParameterEncoder : OpcodeEncoder<ClientVariableParameterDefinition>() {

    override fun opcodes(definition: ClientVariableParameterDefinition): IntArray {
        val opcodes = ArrayList<Int>(2)
        if (definition.aChar3210.code != 0) {
            opcodes.add(1)
        }
        if (definition.anInt3208 != 1) {
            opcodes.add(2)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ClientVariableParameterDefinition, members: ClientVariableParameterDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(Cp1252.encode(definition.aChar3210).toInt())
            2 -> {}
            else -> error("Unhandled client variable opcode $opcode for definition ${definition.id}")
        }
    }
}
