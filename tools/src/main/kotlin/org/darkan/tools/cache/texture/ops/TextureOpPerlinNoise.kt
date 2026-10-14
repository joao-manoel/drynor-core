package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import java.util.Random
import kotlin.math.pow

/**
 * Type 34: Generates multi-octave Perlin noise.
 */
class TextureOpPerlinNoise : TextureOperation(0, true) {
    /** Permutation array for noise generation */
    private var noiseArray: ByteArray = ByteArray(512)

    /** Number of noise octaves */
    private var numNoiseSteps: Int = 4

    /** Noise amplitude */
    private var noiseAmplitude: Int = 1638

    /** Y-axis texture coordinate scale */
    private var texCoordScaleY: Int = 4

    /** X-axis texture coordinate scale */
    private var texCoordScaleX: Int = 4

    /** Random seed */
    private var randomSeed: Int = 0

    /** Per-octave step multipliers */
    private var stepMultipliers: ShortArray? = null

    /** Per-octave amplitude multipliers */
    private var amplitudeMultipliers: ShortArray? = null

    /** Whether to apply fog/bias effect */
    private var useFogEffect: Boolean = true

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            computeNoiseRow(y, output)
        }
        return output
    }

    private fun computeNoiseRow(y: Int, output: IntArray) {
        val coordY = verticalTable[y] * texCoordScaleY

        if (numNoiseSteps == 1) {
            val stepMul = stepMultipliers!![0]
            val ampMul = amplitudeMultipliers!![0].toInt() shl 12
            val scaleY = ampMul * texCoordScaleY shr 12
            val scaleX = ampMul * texCoordScaleX shr 12
            var posY = ampMul * coordY shr 12
            var rowY = posY shr 12
            var nextRowY = rowY + 1
            if (nextRowY >= scaleY) nextRowY = 0
            posY = posY and 0xfff
            val sinY = SINE_WAVE[posY]
            val permY = noiseArray[rowY and 0xff].toInt() and 0xff
            val permNextY = noiseArray[nextRowY and 0xff].toInt() and 0xff

            if (useFogEffect) {
                for (x in 0 until currentWidth) {
                    val coordX = horizontalTable[x] * texCoordScaleX
                    var noise = computeNoise(ampMul * coordX shr 12, posY, permY, permNextY, sinY, scaleX)
                    noise = stepMul * noise shr 12
                    output[x] = (noise shr 1) + 2048
                }
            } else {
                for (x in 0 until currentWidth) {
                    val coordX = horizontalTable[x] * texCoordScaleX
                    val noise = computeNoise(ampMul * coordX shr 12, posY, permY, permNextY, sinY, scaleX)
                    output[x] = stepMul * noise shr 12
                }
            }
        } else {
            // First octave
            var stepMul = stepMultipliers!![0]
            if (stepMul > 8 || stepMul < -8) {
                val ampMul = amplitudeMultipliers!![0].toInt() shl 12
                val scaleY = ampMul * texCoordScaleY shr 12
                val scaleX = ampMul * texCoordScaleX shr 12
                var posY = coordY * ampMul shr 12
                var rowY = posY shr 12
                var nextRowY = rowY + 1
                if (nextRowY >= scaleY) nextRowY = 0
                posY = posY and 0xfff
                val sinY = SINE_WAVE[posY]
                val permY = noiseArray[rowY and 0xff].toInt() and 0xff
                val permNextY = noiseArray[nextRowY and 0xff].toInt() and 0xff

                for (x in 0 until currentWidth) {
                    val coordX = horizontalTable[x] * texCoordScaleX
                    val noise = computeNoise(ampMul * coordX shr 12, posY, permY, permNextY, sinY, scaleX)
                    output[x] = noise * stepMul shr 12
                }
            } else {
                for (x in 0 until currentWidth) {
                    output[x] = 0
                }
            }

            // Remaining octaves
            for (octave in 1 until numNoiseSteps) {
                stepMul = stepMultipliers!![octave]
                if (stepMul > 8 || stepMul < -8) {
                    val ampMul = amplitudeMultipliers!![octave].toInt() shl 12
                    val scaleY = ampMul * texCoordScaleY shr 12
                    val scaleX = ampMul * texCoordScaleX shr 12
                    var posY = coordY * ampMul shr 12
                    var rowY = posY shr 12
                    var nextRowY = rowY + 1
                    if (nextRowY >= scaleY) nextRowY = 0
                    posY = posY and 0xfff
                    val sinY = SINE_WAVE[posY]
                    val permY = noiseArray[rowY and 0xff].toInt() and 0xff
                    val permNextY = noiseArray[nextRowY and 0xff].toInt() and 0xff

                    if (useFogEffect && octave == numNoiseSteps - 1) {
                        for (x in 0 until currentWidth) {
                            val coordX = horizontalTable[x] * texCoordScaleX
                            var noise = computeNoise(coordX * ampMul shr 12, posY, permY, permNextY, sinY, scaleX)
                            noise = output[x] + (stepMul * noise shr 12)
                            output[x] = (noise shr 1) + 2048
                        }
                    } else {
                        for (x in 0 until currentWidth) {
                            val coordX = horizontalTable[x] * texCoordScaleX
                            val noise = computeNoise(ampMul * coordX shr 12, posY, permY, permNextY, sinY, scaleX)
                            output[x] += noise * stepMul shr 12
                        }
                    }
                }
            }
        }
    }

    private fun computeNoise(
        coordX: Int,
        coordY: Int,
        permY: Int,
        permNextY: Int,
        sinY: Int,
        scaleX: Int
    ): Int {
        var posX = coordX shr 12
        var nextPosX = posX + 1
        if (nextPosX >= scaleX) nextPosX = 0
        val fracX = coordX and 0xfff
        posX = posX and 0xff
        nextPosX = nextPosX and 0xff
        val negFracX = fracX - 4096
        val negFracY = coordY - 4096
        val sinX = SINE_WAVE[fracX]

        var gradType = noiseArray[permY + posX].toInt() and 0x3
        var grad1 = when (gradType) {
            0 -> coordY + fracX
            1 -> coordY - fracX
            2 -> fracX - coordY
            else -> -fracX - coordY
        }

        gradType = noiseArray[permY + nextPosX].toInt() and 0x3
        var grad2 = when (gradType) {
            0 -> negFracX + coordY
            1 -> coordY - negFracX
            2 -> negFracX - coordY
            else -> -negFracX - coordY
        }

        val lerp1 = grad1 + (sinX * (grad2 - grad1) shr 12)

        gradType = noiseArray[posX + permNextY].toInt() and 0x3
        grad1 = when (gradType) {
            0 -> negFracY + fracX
            1 -> negFracY - fracX
            2 -> fracX - negFracY
            else -> -fracX - negFracY
        }

        gradType = noiseArray[nextPosX + permNextY].toInt() and 0x3
        grad2 = when (gradType) {
            0 -> negFracX + negFracY
            1 -> negFracY - negFracX
            2 -> negFracX - negFracY
            else -> -negFracX - negFracY
        }

        val lerp2 = grad1 + (sinX * (grad2 - grad1) shr 12)
        return lerp1 + (sinY * (lerp2 - lerp1) shr 12)
    }

    override fun postDecode() {
        noiseArray = generatePermutationArray(randomSeed)
        computeMultipliers()

        // Trim unused octaves
        var lastOctave = numNoiseSteps - 1
        while (lastOctave >= 1) {
            val stepMul = stepMultipliers!![lastOctave]
            if (stepMul > 8 || stepMul < -8) break
            numNoiseSteps--
            lastOctave--
        }
    }

    private fun computeMultipliers() {
        if (noiseAmplitude > 0) {
            stepMultipliers = ShortArray(numNoiseSteps)
            amplitudeMultipliers = ShortArray(numNoiseSteps)
            for (i in 0 until numNoiseSteps) {
                stepMultipliers!![i] = ((noiseAmplitude / 4096.0f).pow(i) * 4096.0).toInt().toShort()
                amplitudeMultipliers!![i] = 2.0.pow(i).toInt().toShort()
            }
        } else if (stepMultipliers != null && stepMultipliers!!.size == numNoiseSteps) {
            amplitudeMultipliers = ShortArray(numNoiseSteps)
            for (i in 0 until numNoiseSteps) {
                amplitudeMultipliers!![i] = 2.0.pow(i).toInt().toShort()
            }
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> useFogEffect = buffer.readUnsignedByte() == 1
            1 -> numNoiseSteps = buffer.readUnsignedByte()
            2 -> {
                noiseAmplitude = buffer.readShort()
                if (noiseAmplitude < 0) {
                    stepMultipliers = ShortArray(numNoiseSteps)
                    for (i in 0 until numNoiseSteps) {
                        stepMultipliers!![i] = buffer.readShort().toShort()
                    }
                }
            }
            3 -> {
                texCoordScaleY = buffer.readUnsignedByte()
                texCoordScaleX = texCoordScaleY
            }
            4 -> randomSeed = buffer.readUnsignedByte()
            5 -> texCoordScaleX = buffer.readUnsignedByte()
            6 -> texCoordScaleY = buffer.readUnsignedByte()
        }
    }

    companion object {
        /** Smoothstep function lookup table */
        val SINE_WAVE: IntArray = IntArray(4096)

        init {
            for (i in 0 until 4096) {
                // Smoothstep: 3t^2 - 2t^3
                val t3 = i * (i * i shr 12) shr 12
                val term1 = i * 6 - 61440
                val term2 = (i * term1 shr 12) + 40960
                SINE_WAVE[i] = term2 * t3 shr 12
            }
        }

        /**
         * Generate a permutation array for the given seed.
         */
        fun generatePermutationArray(seed: Int): ByteArray {
            val array = ByteArray(512)
            val random = Random(seed.toLong())

            for (i in 0..254) {
                array[i] = i.toByte()
            }

            for (i in 0..254) {
                val remaining = 255 - i
                val swapIdx = random.nextInt(remaining + 1)
                val temp = array[swapIdx]
                array[swapIdx] = array[remaining]
                array[remaining] = temp
                array[511 - i] = temp
            }

            return array
        }
    }
}
