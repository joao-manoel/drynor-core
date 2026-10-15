package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.max
import kotlin.math.min

/**
 * Type 7: Combines two input operations using various blend modes.
 *
 * Blend modes:
 * 1 = Add
 * 2 = Subtract
 * 3 = Multiply
 * 4 = Divide
 * 5 = Screen
 * 6 = Soft Light
 * 7 = Dodge
 * 8 = Burn
 * 9 = Min
 * 10 = Max
 * 11 = Difference
 * 12 = Exclusion
 */
class TextureOpCombine : TextureOperation(2, false) {
    /** Blend mode (1-12) */
    private var blendMode: Int = 6

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)

        if (monochromeImageCache.dirty) {
            val input0 = getChildMonochromeOutput(0, y)
            val input1 = getChildMonochromeOutput(1, y)

            when (blendMode) {
                1 -> { // Add
                    for (x in 0 until currentWidth) {
                        output[x] = input0[x] + input1[x]
                    }
                }
                2 -> { // Subtract
                    for (x in 0 until currentWidth) {
                        output[x] = input0[x] - input1[x]
                    }
                }
                3 -> { // Multiply
                    for (x in 0 until currentWidth) {
                        output[x] = input0[x] * input1[x] shr 12
                    }
                }
                4 -> { // Divide
                    for (x in 0 until currentWidth) {
                        val b = input1[x]
                        output[x] = if (b != 0) (input0[x] shl 12) / b else 4096
                    }
                }
                5 -> { // Screen
                    for (x in 0 until currentWidth) {
                        output[x] = 4096 - ((4096 - input0[x]) * (4096 - input1[x]) shr 12)
                    }
                }
                6 -> { // Soft Light
                    for (x in 0 until currentWidth) {
                        val b = input1[x]
                        output[x] = if (b < 2048) {
                            b * input0[x] shr 11
                        } else {
                            4096 - ((4096 - input0[x]) * (4096 - b) shr 11)
                        }
                    }
                }
                7 -> { // Dodge
                    for (x in 0 until currentWidth) {
                        val a = input0[x]
                        output[x] = if (a == 4096) 4096 else (input1[x] shl 12) / (4096 - a)
                    }
                }
                8 -> { // Burn
                    for (x in 0 until currentWidth) {
                        val a = input0[x]
                        output[x] = if (a == 0) 0 else 4096 - ((4096 - input1[x]) shl 12) / a
                    }
                }
                9 -> { // Min
                    for (x in 0 until currentWidth) {
                        output[x] = min(input0[x], input1[x])
                    }
                }
                10 -> { // Max
                    for (x in 0 until currentWidth) {
                        output[x] = max(input0[x], input1[x])
                    }
                }
                11 -> { // Difference
                    for (x in 0 until currentWidth) {
                        val a = input0[x]
                        val b = input1[x]
                        output[x] = if (a > b) a - b else b - a
                    }
                }
                12 -> { // Exclusion
                    for (x in 0 until currentWidth) {
                        val a = input0[x]
                        val b = input1[x]
                        output[x] = b + a - (a * b shr 11)
                    }
                }
            }
        }

        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty) {
            val input0 = getChildColorOutput(0, y)
            val input1 = getChildColorOutput(1, y)

            val outR = output[0]
            val outG = output[1]
            val outB = output[2]
            val inR0 = input0[0]
            val inG0 = input0[1]
            val inB0 = input0[2]
            val inR1 = input1[0]
            val inG1 = input1[1]
            val inB1 = input1[2]

            when (blendMode) {
                1 -> { // Add
                    for (x in 0 until currentWidth) {
                        outR[x] = inR0[x] + inR1[x]
                        outG[x] = inG0[x] + inG1[x]
                        outB[x] = inB1[x] + inB0[x]
                    }
                }
                2 -> { // Subtract
                    for (x in 0 until currentWidth) {
                        outR[x] = inR0[x] - inR1[x]
                        outG[x] = inG0[x] - inG1[x]
                        outB[x] = inB0[x] - inB1[x]
                    }
                }
                3 -> { // Multiply
                    for (x in 0 until currentWidth) {
                        outR[x] = inR1[x] * inR0[x] shr 12
                        outG[x] = inG0[x] * inG1[x] shr 12
                        outB[x] = inB0[x] * inB1[x] shr 12
                    }
                }
                4 -> { // Divide
                    for (x in 0 until currentWidth) {
                        val r1 = inR1[x]
                        val g1 = inG1[x]
                        val b1 = inB1[x]
                        outR[x] = if (r1 != 0) (inR0[x] shl 12) / r1 else 4096
                        outG[x] = if (g1 != 0) (inG0[x] shl 12) / g1 else 4096
                        outB[x] = if (b1 != 0) (inB0[x] shl 12) / b1 else 4096
                    }
                }
                5 -> { // Screen
                    for (x in 0 until currentWidth) {
                        outR[x] = 4096 - ((4096 - inR1[x]) * (4096 - inR0[x]) shr 12)
                        outG[x] = 4096 - ((4096 - inG0[x]) * (4096 - inG1[x]) shr 12)
                        outB[x] = 4096 - ((4096 - inB0[x]) * (4096 - inB1[x]) shr 12)
                    }
                }
                6 -> { // Soft Light
                    for (x in 0 until currentWidth) {
                        val r1 = inR1[x]
                        val g1 = inG1[x]
                        val b1 = inB1[x]
                        outR[x] = if (r1 < 2048) r1 * inR0[x] shr 11 else 4096 - ((4096 - inR0[x]) * (4096 - r1) shr 11)
                        outG[x] = if (g1 < 2048) g1 * inG0[x] shr 11 else 4096 - ((4096 - inG0[x]) * (4096 - g1) shr 11)
                        outB[x] = if (b1 < 2048) b1 * inB0[x] shr 11 else 4096 - ((4096 - inB0[x]) * (4096 - b1) shr 11)
                    }
                }
                7 -> { // Dodge
                    for (x in 0 until currentWidth) {
                        val r0 = inR0[x]
                        val g0 = inG0[x]
                        val b0 = inB0[x]
                        outR[x] = if (r0 == 4096) 4096 else (inR1[x] shl 12) / (4096 - r0)
                        outG[x] = if (g0 == 4096) 4096 else (inG1[x] shl 12) / (4096 - g0)
                        outB[x] = if (b0 == 4096) 4096 else (inB1[x] shl 12) / (4096 - b0)
                    }
                }
                8 -> { // Burn
                    for (x in 0 until currentWidth) {
                        val r0 = inR0[x]
                        val g0 = inG0[x]
                        val b0 = inB0[x]
                        outR[x] = if (r0 == 0) 0 else 4096 - ((4096 - inR1[x]) shl 12) / r0
                        outG[x] = if (g0 == 0) 0 else 4096 - ((4096 - inG1[x]) shl 12) / g0
                        outB[x] = if (b0 == 0) 0 else 4096 - ((4096 - inB1[x]) shl 12) / b0
                    }
                }
                9 -> { // Min
                    for (x in 0 until currentWidth) {
                        outR[x] = min(inR0[x], inR1[x])
                        outG[x] = min(inG0[x], inG1[x])
                        outB[x] = min(inB0[x], inB1[x])
                    }
                }
                10 -> { // Max
                    for (x in 0 until currentWidth) {
                        outR[x] = max(inR0[x], inR1[x])
                        outG[x] = max(inG0[x], inG1[x])
                        outB[x] = max(inB0[x], inB1[x])
                    }
                }
                11 -> { // Difference
                    for (x in 0 until currentWidth) {
                        val r0 = inR0[x]
                        val g0 = inG0[x]
                        val b0 = inB0[x]
                        val r1 = inR1[x]
                        val g1 = inG1[x]
                        val b1 = inB1[x]
                        outR[x] = if (r0 > r1) r0 - r1 else r1 - r0
                        outG[x] = if (g0 > g1) g0 - g1 else g1 - g0
                        outB[x] = if (b0 > b1) b0 - b1 else b1 - b0
                    }
                }
                12 -> { // Exclusion
                    for (x in 0 until currentWidth) {
                        val r0 = inR0[x]
                        val g0 = inG0[x]
                        val b0 = inB0[x]
                        val r1 = inR1[x]
                        val g1 = inG1[x]
                        val b1 = inB1[x]
                        outR[x] = r0 + r1 - (r1 * r0 shr 11)
                        outG[x] = g1 + g0 - (g1 * g0 shr 11)
                        outB[x] = b1 + b0 - (b1 * b0 shr 11)
                    }
                }
            }
        }

        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> blendMode = buffer.readUnsignedByte()
            1 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
