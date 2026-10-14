package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ClanVarDefinition

/**
 * Writes a clan variable (`varclan`, index 2 archive 47) file.
 *
 * Opcode 1 is written from [ClanVarDefinition.typeChar], the descriptor byte itself, because
 * [world.gregs.voidps.cache.definition.cs2.CS2Type.forJagexDesc] is many to one.
 */
class ClanVarEncoder : OpcodeEncoder<ClanVarDefinition>() {

    override fun opcodes(definition: ClanVarDefinition): IntArray {
        val opcodes = ArrayList<Int>(2)
        if (definition.typeChar.code != 0) {
            opcodes.add(1)
        }
        if (definition.baseVar != -1) {
            opcodes.add(3)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ClanVarDefinition, members: ClanVarDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(Cp1252.encode(definition.typeChar).toInt())
            3 -> {
                writeShort(definition.baseVar)
                writeByte(definition.startBit)
                writeByte(definition.endBit)
            }
            else -> error("Unhandled clan variable opcode $opcode for definition ${definition.id}")
        }
    }
}
