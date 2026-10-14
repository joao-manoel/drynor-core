package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.PlayerVariableParameterDefinition

/**
 * Writes a player variable (`varp`, index 2 archive 16) file.
 */
class PlayerVariableParameterEncoder : OpcodeEncoder<PlayerVariableParameterDefinition>() {

    override fun opcodes(definition: PlayerVariableParameterDefinition): IntArray {
        val opcodes = ArrayList<Int>(2)
        if (definition.typeChar.code != 0) {
            opcodes.add(1)
        }
        if (definition.type != 0) {
            opcodes.add(5)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: PlayerVariableParameterDefinition, members: PlayerVariableParameterDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(Cp1252.encode(definition.typeChar).toInt())
            5 -> writeShort(definition.type)
            else -> error("Unhandled player variable opcode $opcode for definition ${definition.id}")
        }
    }
}
