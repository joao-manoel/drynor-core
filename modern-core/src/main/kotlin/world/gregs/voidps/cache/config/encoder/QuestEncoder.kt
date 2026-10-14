package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.QuestDefinition
import world.gregs.voidps.cache.definition.encoder.writeExtras

/**
 * Writes a quest (`quest`, index 2 archive 35) file.
 *
 * Names are `gjstr2` - a 0 version byte then the cp1252 bytes - and the sort name (opcode 2) is
 * only present when it differs from the name, because the decoder falls back to the name.
 */
class QuestEncoder : OpcodeEncoder<QuestDefinition>() {

    override fun opcodes(definition: QuestDefinition): IntArray {
        val opcodes = ArrayList<Int>(15)
        if (definition.name != null) {
            opcodes.add(1)
        }
        if (definition.listName != null && definition.listName != definition.name) {
            opcodes.add(2)
        }
        if (definition.varps != null) {
            opcodes.add(3)
        }
        if (definition.varbits != null) {
            opcodes.add(4)
        }
        if (definition.subQuest != -1) {
            opcodes.add(5)
        }
        if (definition.type != 0) {
            opcodes.add(6)
        }
        if (definition.difficulty != -1) {
            opcodes.add(7)
        }
        if (definition.members) {
            opcodes.add(8)
        }
        if (definition.questPoints != -1) {
            opcodes.add(9)
        }
        if (definition.pathStart != null) {
            opcodes.add(10)
        }
        if (definition.otherPathStart != -1) {
            opcodes.add(12)
        }
        if (definition.questRequirements != null) {
            opcodes.add(13)
        }
        if (definition.skillRequirements != null) {
            opcodes.add(14)
        }
        if (definition.questPointRequirement != 0) {
            opcodes.add(15)
        }
        if (definition.itemGraphic != -1) {
            opcodes.add(17)
        }
        if (definition.extras != null) {
            opcodes.add(249)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: QuestDefinition, members: QuestDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writePrefixedString(definition.name ?: "")
            2 -> writePrefixedString(definition.listName ?: "")
            3 -> writeVariables(definition.varps)
            4 -> writeVariables(definition.varbits)
            5 -> writeShort(definition.subQuest)
            6 -> writeByte(definition.type)
            7 -> writeByte(definition.difficulty)
            8 -> {}
            9 -> writeByte(definition.questPoints)
            10 -> {
                val starts = definition.pathStart ?: IntArray(0)
                writeByte(starts.size)
                for (start in starts) {
                    writeInt(start)
                }
            }
            12 -> writeInt(definition.otherPathStart)
            13 -> {
                val requirements = definition.questRequirements ?: IntArray(0)
                writeByte(requirements.size)
                for (requirement in requirements) {
                    writeShort(requirement)
                }
            }
            14 -> {
                val requirements = definition.skillRequirements ?: emptyArray()
                writeByte(requirements.size)
                for (requirement in requirements) {
                    writeByte(requirement[0])
                    writeByte(requirement[1])
                }
            }
            15 -> writeShort(definition.questPointRequirement)
            17 -> writeBigSmart(definition.itemGraphic)
            249 -> writeExtras(definition.id, definition.extras)
            else -> error("Unhandled quest opcode $opcode for definition ${definition.id}")
        }
    }

    /**
     * One `var id / start value / complete value` record per variable.
     */
    private fun Writer.writeVariables(variables: Array<IntArray>?) {
        val values = variables ?: emptyArray()
        writeByte(values.size)
        for (value in values) {
            writeShort(value[0])
            writeInt(value[1])
            writeInt(value[2])
        }
    }
}
