package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ParticleDefinition

/**
 * Writes a particle (index 27 archive 1) file.
 *
 * Opcodes 8, 9 and 10 are bare markers; opcode 2's byte is one the client reads and discards, so
 * it is nullable on the definition and its presence is a null check rather than a value test.
 */
class ParticleEncoder : OpcodeEncoder<ParticleDefinition>() {

    override fun opcodes(definition: ParticleDefinition): IntArray {
        val opcodes = ArrayList<Int>(8)
        if (definition.rotation != 0) {
            opcodes.add(1)
        }
        if (definition.unused2 != null) {
            opcodes.add(2)
        }
        if (definition.offsetX != 0 || definition.offsetY != 0 || definition.offsetZ != 0) {
            opcodes.add(3)
        }
        if (definition.type != 0 || definition.sizeMultiplier != 0) {
            opcodes.add(4)
        }
        if (definition.particleHandlingType != 0) {
            opcodes.add(6)
        }
        if (definition.currentOffset) {
            opcodes.add(8)
        }
        if (definition.verticeCalculationType) {
            opcodes.add(9)
        }
        if (definition.unusedOpcode10) {
            opcodes.add(10)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ParticleDefinition, members: ParticleDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(definition.rotation)
            2 -> writeByte(definition.unused2 ?: 0)
            3 -> {
                writeInt(definition.offsetX)
                writeInt(definition.offsetY)
                writeInt(definition.offsetZ)
            }
            4 -> {
                writeByte(definition.type)
                writeInt(definition.sizeMultiplier)
            }
            6 -> writeByte(definition.particleHandlingType)
            8, 9, 10 -> {}
            else -> error("Unhandled particle opcode $opcode for definition ${definition.id}")
        }
    }
}
