package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition

/**
 * Writes a particle producer (index 27 archive 0) file.
 *
 * Every record is written from the field it read into, which is only possible because the decoder
 * kept the file's values rather than the client's derived ones. The four opcodes whose absence a
 * default cannot be told apart from - 2, 5, 16, 27, 29 and 31 all set values a file could
 * legitimately carry as zero - are nullable on the definition instead, so presence is a null check
 * and not a guess.
 */
class ParticleProducerEncoder : OpcodeEncoder<ParticleProducerDefinition>() {

    override fun opcodes(definition: ParticleProducerDefinition): IntArray {
        val opcodes = ArrayList<Int>(16)
        if (definition.minAngleHorizontal != 0 || definition.maxAngleHorizontal != 0 ||
            definition.minAngleVertical != 0 || definition.maxAngleVertical != 0
        ) {
            opcodes.add(1)
        }
        if (definition.unused2 != null) {
            opcodes.add(2)
        }
        if (definition.minSpeed != 0 || definition.maxSpeed != 0) {
            opcodes.add(3)
        }
        if (definition.speedUpdateType != 0 || definition.speedFallOffStep != 0) {
            opcodes.add(4)
        }
        if (definition.size != null) {
            opcodes.add(5)
        }
        if (definition.minStartColour != 0 || definition.maxStartColour != 0) {
            opcodes.add(6)
        }
        if (definition.minLifetime != 0 || definition.maxLifetime != 0) {
            opcodes.add(7)
        }
        if (definition.minParticleRate != 0 || definition.maxParticleRate != 0) {
            opcodes.add(8)
        }
        if (definition.secondaryParticles != null) {
            opcodes.add(9)
        }
        if (definition.particles != null) {
            opcodes.add(10)
        }
        if (definition.lowestDisplayPlane != DEFAULT_PLANE) {
            opcodes.add(12)
        }
        if (definition.highestDisplayPlane != DEFAULT_PLANE) {
            opcodes.add(13)
        }
        if (definition.updatesPerCycle != 0) {
            opcodes.add(14)
        }
        if (definition.materialId != -1) {
            opcodes.add(15)
        }
        if (definition.activeFirst != null) {
            opcodes.add(16)
        }
        if (definition.nonTexturedProducerId != -1) {
            opcodes.add(17)
        }
        if (definition.faceColourRgb != 0) {
            opcodes.add(18)
        }
        if (definition.minimumSetting != 0) {
            opcodes.add(19)
        }
        if (definition.colourFading != DEFAULT_PERCENT) {
            opcodes.add(20)
        }
        if (definition.alphaFading != DEFAULT_PERCENT) {
            opcodes.add(21)
        }
        if (definition.endSpeed != -1) {
            opcodes.add(22)
        }
        if (definition.speedChange != DEFAULT_PERCENT) {
            opcodes.add(23)
        }
        if (!definition.uniformColourVariance) {
            opcodes.add(24)
        }
        if (definition.effectiveVertexUids != null) {
            opcodes.add(25)
        }
        if (!definition.unusedOpcode26) {
            opcodes.add(26)
        }
        if (definition.endSize != null) {
            opcodes.add(27)
        }
        if (definition.sizeChange != DEFAULT_PERCENT) {
            opcodes.add(28)
        }
        if (definition.unused29 != null) {
            opcodes.add(29)
        }
        if (definition.textured) {
            opcodes.add(30)
        }
        if (definition.minSize != null || definition.maxSize != null) {
            opcodes.add(31)
        }
        if (!definition.adjustsLightIntensity) {
            opcodes.add(32)
        }
        if (definition.killOverlapping) {
            opcodes.add(33)
        }
        if (!definition.killAboveSurface) {
            opcodes.add(34)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(
        definition: ParticleProducerDefinition,
        members: ParticleProducerDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> {
                writeShort(definition.minAngleHorizontal)
                writeShort(definition.maxAngleHorizontal)
                writeShort(definition.minAngleVertical)
                writeShort(definition.maxAngleVertical)
            }
            2 -> writeByte(definition.unused2 ?: 0)
            3 -> {
                writeInt(definition.minSpeed)
                writeInt(definition.maxSpeed)
            }
            4 -> {
                writeByte(definition.speedUpdateType)
                writeByte(definition.speedFallOffStep)
            }
            5 -> writeShort(definition.size ?: 0)
            6 -> {
                writeInt(definition.minStartColour)
                writeInt(definition.maxStartColour)
            }
            7 -> {
                writeShort(definition.minLifetime)
                writeShort(definition.maxLifetime)
            }
            8 -> {
                writeShort(definition.minParticleRate)
                writeShort(definition.maxParticleRate)
            }
            9 -> writeIds(definition.secondaryParticles)
            10 -> writeIds(definition.particles)
            12 -> writeByte(definition.lowestDisplayPlane)
            13 -> writeByte(definition.highestDisplayPlane)
            14 -> writeShort(definition.updatesPerCycle)
            15 -> writeShort(definition.materialId)
            16 -> {
                writeByte(definition.activeFirst ?: 0)
                writeShort(definition.emissionEndTime ?: 0)
                writeShort(definition.lifetime ?: 0)
                writeByte(definition.periodic ?: 0)
            }
            17 -> writeShort(definition.nonTexturedProducerId)
            18 -> writeInt(definition.faceColourRgb)
            19 -> writeByte(definition.minimumSetting)
            20 -> writeByte(definition.colourFading)
            21 -> writeByte(definition.alphaFading)
            22 -> writeInt(definition.endSpeed)
            23 -> writeByte(definition.speedChange)
            24, 26, 30, 32, 33, 34 -> {}
            25 -> writeIds(definition.effectiveVertexUids)
            27 -> writeShort(definition.endSize ?: 0)
            28 -> writeByte(definition.sizeChange)
            29 -> writeShort(definition.unused29 ?: 0)
            31 -> {
                writeShort(definition.minSize ?: 0)
                writeShort(definition.maxSize ?: 0)
            }
            else -> error("Unhandled particle producer opcode $opcode for definition ${definition.id}")
        }
    }

    private fun Writer.writeIds(ids: IntArray?) {
        val values = ids ?: IntArray(0)
        writeByte(values.size)
        for (value in values) {
            writeShort(value)
        }
    }

    private companion object {
        /** What a display plane is when the file says nothing, and what makes opcodes 12 and 13 absent. */
        private const val DEFAULT_PLANE = -2

        /** The fade and change percentages all default to a whole life. */
        private const val DEFAULT_PERCENT = 100
    }
}
