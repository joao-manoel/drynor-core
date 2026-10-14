package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 27: Generates square waveform patterns.
 */
class TextureOpSquareWaveform : TextureOperation(0, true) {
    private var waveCount: Int = 10
    private var dutyCycle: Int = 2048
    private var direction: Int = 0
    private var thresholds: IntArray? = null
    private var startPositions: IntArray? = null

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalValue = verticalTable[y]

            if (direction != 0) {
                for (x in 0 until currentWidth) {
                    var value = 0
                    var waveOutput: Short = 0
                    val horizontalValue = horizontalTable[x]

                    value = when (direction) {
                        1 -> horizontalValue
                        2 -> (horizontalValue - (4096 - verticalValue) shr 1) + 2048
                        3 -> (horizontalValue - verticalValue shr 1) + 2048
                        else -> 0
                    }

                    for (wave in 0 until waveCount) {
                        if (value >= startPositions!![wave] && value < startPositions!![wave + 1]) {
                            if (value < thresholds!![wave]) {
                                waveOutput = 4096
                            }
                            break
                        }
                    }
                    output[x] = waveOutput.toInt()
                }
            } else {
                var waveOutput: Short = 0
                var waveIndex = 0
                while (waveIndex < waveCount) {
                    if (verticalValue >= startPositions!![waveIndex] && verticalValue < startPositions!![waveIndex + 1]) {
                        if (verticalValue < thresholds!![waveIndex]) {
                            waveOutput = 4096
                        }
                        break
                    }
                    waveIndex++
                }
                for (x in 0 until currentWidth) {
                    output[x] = waveOutput.toInt()
                }
            }
        }
        return output
    }

    override fun postDecode() {
        computeWaveform()
    }

    private fun computeWaveform() {
        var position = 0
        thresholds = IntArray(waveCount + 1)
        startPositions = IntArray(waveCount + 1)
        val waveLength = 4096 / waveCount
        val activeLength = waveLength * dutyCycle shr 12

        for (wave in 0 until waveCount) {
            startPositions!![wave] = position
            thresholds!![wave] = activeLength + position
            position += waveLength
        }

        startPositions!![waveCount] = 4096
        thresholds!![waveCount] = thresholds!![0] + 4096
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> waveCount = buffer.readUnsignedByte()
            1 -> dutyCycle = buffer.readShort() and 0xFFFF
            2 -> direction = buffer.readUnsignedByte()
        }
    }
}
