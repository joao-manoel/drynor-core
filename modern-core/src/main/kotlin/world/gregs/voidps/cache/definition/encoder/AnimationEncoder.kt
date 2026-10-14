package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.AnimationDefinition

/**
 * Writes an index 20 seq file.
 *
 * Frame ids are split over two shorts, the low half first for every frame and the high half after, and the
 * decoder adds them as signed values, so the halves are recovered by rounding rather than by masking.
 */
class AnimationEncoder : OpcodeEncoder<AnimationDefinition>() {

    override fun opcodes(definition: AnimationDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.durations != null) {
            opcodes.add(1)
        }
        if (definition.loopOffset != -1) {
            opcodes.add(2)
        }
        if (definition.interleaveOrder != null) {
            opcodes.add(3)
        }
        if (definition.priority != 5) {
            opcodes.add(5)
        }
        if (definition.leftHandItem != -1) {
            opcodes.add(6)
        }
        if (definition.rightHandItem != -1) {
            opcodes.add(7)
        }
        if (definition.maxLoops != 99) {
            opcodes.add(8)
        }
        if (definition.animatingPrecedence != -1) {
            opcodes.add(9)
        }
        if (definition.walkingPrecedence != -1) {
            opcodes.add(10)
        }
        if (definition.replayMode != 2) {
            opcodes.add(11)
        }
        if (definition.expressionFrames != null) {
            opcodes.add(12)
        }
        if (definition.sounds != null) {
            opcodes.add(13)
        }
        if (definition.aBoolean691) {
            opcodes.add(14)
        }
        if (definition.tweened) {
            opcodes.add(15)
        }
        if (definition.unusedFlag16) {
            opcodes.add(16)
        }
        if (definition.useSounds) {
            opcodes.add(18)
        }
        val volumes = definition.soundVolumeRecords
        if (volumes != null) {
            repeat(volumes.size / 2) { opcodes.add(19) }
        }
        val speeds = definition.soundSpeedRecords
        if (speeds != null) {
            repeat(speeds.size / 3) { opcodes.add(20) }
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: AnimationDefinition, members: AnimationDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val durations = definition.durations ?: IntArray(0)
                val frames = definition.frames ?: IntArray(0)
                writeShort(durations.size)
                for (duration in durations) {
                    writeShort(duration)
                }
                for (frame in frames) {
                    writeShort(frame - high(frame))
                }
                for (frame in frames) {
                    writeShort(high(frame) shr 16)
                }
            }
            2 -> writeShort(definition.loopOffset)
            3 -> {
                val records = definition.interleaveRecords
                if (records != null) {
                    writeByte(records.size)
                    for (index in records) {
                        writeByte(index)
                    }
                } else {
                    val order = definition.interleaveOrder ?: BooleanArray(256)
                    writeByte(order.count { it })
                    for (index in order.indices) {
                        if (order[index]) {
                            writeByte(index)
                        }
                    }
                }
            }
            5 -> writeByte(definition.priority)
            6 -> writeShort(definition.leftHandItem)
            7 -> writeShort(definition.rightHandItem)
            8 -> writeByte(definition.maxLoops)
            9 -> writeByte(definition.animatingPrecedence)
            10 -> writeByte(definition.walkingPrecedence)
            11 -> writeByte(definition.replayMode)
            12 -> {
                val frames = definition.expressionFrames ?: IntArray(0)
                writeByte(frames.size)
                for (frame in frames) {
                    writeShort(frame - high(frame))
                }
                for (frame in frames) {
                    writeShort(high(frame) shr 16)
                }
            }
            13 -> {
                val sounds = definition.sounds ?: arrayOfNulls(0)
                writeShort(sounds.size)
                for (sound in sounds) {
                    writeByte(sound?.size ?: 0)
                    if (sound == null || sound.isEmpty()) {
                        continue
                    }
                    writeMedium(sound[0])
                    for (index in 1 until sound.size) {
                        writeShort(sound[index])
                    }
                }
            }
            14, 15, 16, 18 -> Unit
            19 -> {
                val records = definition.soundVolumeRecords ?: error("No sound volume record $occurrence for def ${definition.id}")
                writeByte(records[occurrence * 2])
                writeByte(records[occurrence * 2 + 1])
            }
            20 -> {
                val records = definition.soundSpeedRecords ?: error("No sound speed record $occurrence for def ${definition.id}")
                writeByte(records[occurrence * 3])
                writeShort(records[occurrence * 3 + 1])
                writeShort(records[occurrence * 3 + 2])
            }
            else -> error("Unhandled animation definition opcode: $opcode for def ${definition.id}")
        }
    }

    /**
     * The high half of a frame id, shifted back up. The decoder builds a frame as `(high shl 16) + low` with
     * both halves read as signed shorts, so a low half of 0x8000 or over carried into the high half and the
     * split is recovered by rounding to the nearest multiple of 65536 rather than by masking.
     */
    private fun high(frame: Int): Int = (frame + 32768) and 0xffff0000.toInt()
}
