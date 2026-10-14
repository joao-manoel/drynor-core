package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.BASDefinition

/**
 * Writes a base animation set (`bas` / render animations, index 2 archive 32) file.
 *
 * Every animation id is a big smart. Opcodes 26 and 54 are stored scaled (`* 4`, `shl 6`) and are
 * written back down; the per slot opcodes 27, 55 and 56 write a slot index and appear once per
 * populated slot, ascending.
 */
class BASEncoder : OpcodeEncoder<BASDefinition>() {

    override fun opcodes(definition: BASDefinition): IntArray {
        val opcodes = ArrayList<Int>(24)
        if (definition.standAnimation != -1 || definition.walkAnimation != -1) {
            opcodes.add(1)
        }
        if (definition.teleportAnimation != -1) {
            opcodes.add(2)
        }
        if (definition.teleDir3 != -1) {
            opcodes.add(3)
        }
        if (definition.teleDir2 != -1) {
            opcodes.add(4)
        }
        if (definition.teleDir1 != -1) {
            opcodes.add(5)
        }
        if (definition.runAnimation != -1) {
            opcodes.add(6)
        }
        if (definition.runDir3 != -1) {
            opcodes.add(7)
        }
        if (definition.runDir2 != -1) {
            opcodes.add(8)
        }
        if (definition.runDir1 != -1) {
            opcodes.add(9)
        }
        if (definition.modelWidth != 0 || definition.modelLength != 0) {
            opcodes.add(26)
        }
        val transformations = definition.objVerticeTransformations
        if (transformations != null) {
            for (slot in transformations.indices) {
                if (transformations[slot] != null) {
                    opcodes.add(27)
                }
            }
        }
        if (definition.objVisibility != null) {
            opcodes.add(28)
        }
        if (definition.yawAcceleration != 0) {
            opcodes.add(29)
        }
        if (definition.yawMaxVelocity != 0) {
            opcodes.add(30)
        }
        if (definition.rollAcceleration != 0) {
            opcodes.add(31)
        }
        if (definition.rollMaxVelocity != 0) {
            opcodes.add(32)
        }
        if (definition.rollTargetAngle != 0) {
            opcodes.add(33)
        }
        if (definition.pitchAcceleration != 0) {
            opcodes.add(34)
        }
        if (definition.pitchMaxVelocity != 0) {
            opcodes.add(35)
        }
        if (definition.pitchTargetAngle != 0) {
            opcodes.add(36)
        }
        if (definition.unused != -1) {
            opcodes.add(37)
        }
        if (definition.standTurn1 != -1) {
            opcodes.add(38)
        }
        if (definition.standTurn2 != -1) {
            opcodes.add(39)
        }
        if (definition.walkDir3 != -1) {
            opcodes.add(40)
        }
        if (definition.walkDir2 != -1) {
            opcodes.add(41)
        }
        if (definition.walkDir1 != -1) {
            opcodes.add(42)
        }
        if (definition.unused43 != -1) {
            opcodes.add(43)
        }
        if (definition.unused44 != -1) {
            opcodes.add(44)
        }
        if (definition.iconHeightOverride != -1) {
            opcodes.add(45)
        }
        if (definition.teleTurn1 != -1) {
            opcodes.add(46)
        }
        if (definition.teleTurn2 != -1) {
            opcodes.add(47)
        }
        if (definition.runTurn1 != -1) {
            opcodes.add(48)
        }
        if (definition.runTurn2 != -1) {
            opcodes.add(49)
        }
        if (definition.walkTurn1 != -1) {
            opcodes.add(50)
        }
        if (definition.walkTurn2 != -1) {
            opcodes.add(51)
        }
        if (definition.randomStandSequences != null) {
            opcodes.add(52)
        }
        if (!definition.rendersShadow) {
            opcodes.add(53)
        }
        if (definition.hillRotateX != 0 || definition.hillRotateZ != 0) {
            opcodes.add(54)
        }
        val adjustments = definition.turnAngleAdjustment
        if (adjustments != null) {
            for (slot in adjustments.indices) {
                if (adjustments[slot] != 0) {
                    opcodes.add(55)
                }
            }
        }
        val offsets = definition.projectionOffset
        if (offsets != null) {
            for (slot in offsets.indices) {
                if (offsets[slot] != null) {
                    opcodes.add(56)
                }
            }
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: BASDefinition, members: BASDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeBigSmart(definition.standAnimation)
                writeBigSmart(definition.walkAnimation)
            }
            2 -> writeBigSmart(definition.teleportAnimation)
            3 -> writeBigSmart(definition.teleDir3)
            4 -> writeBigSmart(definition.teleDir2)
            5 -> writeBigSmart(definition.teleDir1)
            6 -> writeBigSmart(definition.runAnimation)
            7 -> writeBigSmart(definition.runDir3)
            8 -> writeBigSmart(definition.runDir2)
            9 -> writeBigSmart(definition.runDir1)
            26 -> {
                writeByte(definition.modelWidth / 4)
                writeByte(definition.modelLength / 4)
            }
            27 -> {
                val transformations = definition.objVerticeTransformations ?: error("No obj transformations to write for definition ${definition.id}")
                val slot = slot(occurrence) { transformations[it] != null }
                writeByte(slot)
                for (value in transformations[slot]!!) {
                    writeShort(value)
                }
            }
            28 -> {
                val visibility = definition.objVisibility ?: IntArray(0)
                writeByte(visibility.size)
                for (value in visibility) {
                    writeByte(if (value == -1) 255 else value)
                }
            }
            29 -> writeByte(definition.yawAcceleration)
            30 -> writeShort(definition.yawMaxVelocity)
            31 -> writeByte(definition.rollAcceleration)
            32 -> writeShort(definition.rollMaxVelocity)
            33 -> writeShort(definition.rollTargetAngle)
            34 -> writeByte(definition.pitchAcceleration)
            35 -> writeShort(definition.pitchMaxVelocity)
            36 -> writeShort(definition.pitchTargetAngle)
            37 -> writeByte(definition.unused)
            38 -> writeBigSmart(definition.standTurn1)
            39 -> writeBigSmart(definition.standTurn2)
            40 -> writeBigSmart(definition.walkDir3)
            41 -> writeBigSmart(definition.walkDir2)
            42 -> writeBigSmart(definition.walkDir1)
            43 -> writeShort(definition.unused43)
            44 -> writeShort(definition.unused44)
            45 -> writeShort(definition.iconHeightOverride)
            46 -> writeBigSmart(definition.teleTurn1)
            47 -> writeBigSmart(definition.teleTurn2)
            48 -> writeBigSmart(definition.runTurn1)
            49 -> writeBigSmart(definition.runTurn2)
            50 -> writeBigSmart(definition.walkTurn1)
            51 -> writeBigSmart(definition.walkTurn2)
            52 -> {
                val sequences = definition.randomStandSequences ?: IntArray(0)
                val chances = definition.randomStandSequenceChances ?: IntArray(0)
                writeByte(sequences.size)
                for (index in sequences.indices) {
                    writeBigSmart(sequences[index])
                    writeByte(chances[index])
                }
            }
            53 -> {}
            54 -> {
                writeByte(definition.hillRotateX shr 6)
                writeByte(definition.hillRotateZ shr 6)
            }
            55 -> {
                val adjustments = definition.turnAngleAdjustment ?: error("No turn angles to write for definition ${definition.id}")
                val slot = slot(occurrence) { adjustments[it] != 0 }
                writeByte(slot)
                writeShort(adjustments[slot])
            }
            56 -> {
                val offsets = definition.projectionOffset ?: error("No projection offsets to write for definition ${definition.id}")
                val slot = slot(occurrence) { offsets[it] != null }
                writeByte(slot)
                for (value in offsets[slot]!!) {
                    writeShort(value)
                }
            }
            else -> error("Unhandled base animation set opcode $opcode for definition ${definition.id}")
        }
    }

    /**
     * The slot the [occurrence]th record of a per slot opcode (27, 55, 56) writes: the occurrence'th
     * populated slot, ascending, which is the order [opcodes] lists them in and the order every shipped
     * file carries them in. Those opcodes are [BASDefinition.repeats] records, so every occurrence is
     * written from the fields and none is ever shadowed.
     */
    private inline fun slot(occurrence: Int, populated: (Int) -> Boolean): Int {
        var remaining = occurrence
        for (slot in 0 until BASDefinition.SLOTS) {
            if (!populated(slot)) {
                continue
            }
            if (remaining == 0) {
                return slot
            }
            remaining--
        }
        error("No populated slot $occurrence to write")
    }
}
