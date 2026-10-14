package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.decoder.BillboardDecoder

/**
 * Writes a billboard (index 29 archive 0) file.
 *
 * Opcodes 6 and 7 are bare markers, so a billboard's flags are written by their presence alone.
 * The two records that are not the value they set are inverted here: opcode 1 writes the `0xffff`
 * sentinel for a billboard with no material, and opcode 2's shorts are one less than the sizes.
 */
class BillboardEncoder : OpcodeEncoder<BillboardType>() {

    override fun opcodes(definition: BillboardType): IntArray {
        val opcodes = ArrayList<Int>(7)
        if (definition.materialId != -1) {
            opcodes.add(1)
        }
        if (definition.size2d != DEFAULT_SIZE || definition.size3d != DEFAULT_SIZE) {
            opcodes.add(2)
        }
        if (definition.unknown3 != -1) {
            opcodes.add(3)
        }
        if (definition.shape != DEFAULT_SHAPE) {
            opcodes.add(4)
        }
        if (definition.blendType != DEFAULT_BLEND) {
            opcodes.add(5)
        }
        if (definition.stationary) {
            opcodes.add(6)
        }
        if (definition.hasUid) {
            opcodes.add(7)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: BillboardType, members: BillboardType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(if (definition.materialId == -1) BillboardDecoder.NO_MATERIAL else definition.materialId)
            2 -> {
                writeShort(definition.size2d - 1)
                writeShort(definition.size3d - 1)
            }
            3 -> writeByte(definition.unknown3)
            4 -> writeByte(definition.shape)
            5 -> writeByte(definition.blendType)
            6, 7 -> {}
            else -> error("Unhandled billboard opcode $opcode for definition ${definition.id}")
        }
    }

    private companion object {
        private const val DEFAULT_SIZE = 64
        private const val DEFAULT_SHAPE = 2
        private const val DEFAULT_BLEND = 1
    }
}
