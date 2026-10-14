package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.BodyDefinition

/**
 * The equipment defaults - index 28 archive 6 - byte for byte.
 *
 * The one 727 file lists its records as 1, 3, 5, 4, 6, which is not the order these values derive, so it
 * carries its own; see [world.gregs.voidps.cache.definition.OpcodeOrdered].
 */
class BodyEncoder : OpcodeEncoder<BodyDefinition>() {

    override fun opcodes(definition: BodyDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: BodyDefinition,
        members: BodyDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> slots(definition.disabledSlots)
            3 -> writeByte(definition.anInt4506)
            4 -> writeByte(definition.anInt4504)
            5 -> slots(definition.anIntArray4501)
            6 -> slots(definition.anIntArray4507)
            else -> error("Unhandled equipment defaults opcode $opcode for definition ${definition.id}.")
        }
    }

    private fun Writer.slots(slots: IntArray?) {
        val values = slots ?: IntArray(0)
        writeByte(values.size)
        for (slot in values) {
            writeByte(slot)
        }
    }

    companion object {

        /**
         * The opcodes a file with these values carries when it was packed canonically.
         *
         * Opcode 1 is always written: the client refuses to start without it, so a definition with no
         * customisable slots still has to say so.
         */
        fun opcodesOf(definition: BodyDefinition): IntArray {
            val opcodes = ArrayList<Int>(5)
            opcodes.add(1)
            if (definition.anInt4506 != -1) {
                opcodes.add(3)
            }
            if (definition.anInt4504 != -1) {
                opcodes.add(4)
            }
            if (definition.anIntArray4501 != null) {
                opcodes.add(5)
            }
            if (definition.anIntArray4507 != null) {
                opcodes.add(6)
            }
            return opcodes.toIntArray()
        }
    }
}
