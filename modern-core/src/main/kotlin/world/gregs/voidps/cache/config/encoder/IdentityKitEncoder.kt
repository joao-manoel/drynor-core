package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.IdentityKitDefinition

/**
 * Writes an identity kit (`idk`, index 2 archive 3) file.
 *
 * Model ids are big smarts, not shorts; see [world.gregs.voidps.cache.config.decoder.IdentityKitDecoder].
 */
class IdentityKitEncoder : OpcodeEncoder<IdentityKitDefinition>() {

    override fun opcodes(definition: IdentityKitDefinition): IntArray {
        val opcodes = ArrayList<Int>(9)
        if (definition.bodyPartId != 0) {
            opcodes.add(1)
        }
        if (definition.modelIds != null) {
            opcodes.add(2)
        }
        if (definition.unknown3 != -1) {
            opcodes.add(3)
        }
        if (definition.originalColours != null) {
            opcodes.add(40)
        }
        if (definition.originalTextureColours != null) {
            opcodes.add(41)
        }
        for (index in definition.headModels.indices) {
            if (definition.headModels[index] != -1) {
                opcodes.add(60 + index)
            }
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: IdentityKitDefinition, members: IdentityKitDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(definition.bodyPartId)
            2 -> {
                val models = definition.modelIds ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            3 -> writeShort(definition.unknown3)
            40 -> writePairs(definition.originalColours, definition.modifiedColours)
            41 -> writePairs(definition.originalTextureColours, definition.modifiedTextureColours)
            in 60..69 -> writeBigSmart(definition.headModels[opcode - 60])
            else -> error("Unhandled identity kit opcode $opcode for definition ${definition.id}")
        }
    }

    private fun Writer.writePairs(original: ShortArray?, modified: ShortArray?) {
        val from = original ?: ShortArray(0)
        val to = modified ?: ShortArray(0)
        writeByte(from.size)
        for (index in from.indices) {
            writeShort(from[index].toInt())
            writeShort(to[index].toInt())
        }
    }
}
