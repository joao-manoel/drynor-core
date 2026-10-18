package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 21: Interpolates between two textures based on a third texture as blend factor.
 */
class TextureOpInterpolate : TextureOperation(3, false) {

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val input0 = getChildMonochromeOutput(0, y)
            val input1 = getChildMonochromeOutput(1, y)
            val blendFactor = getChildMonochromeOutput(2, y)

            for (x in 0 until currentWidth) {
                val blend = blendFactor[x]
                output[x] = when {
                    blend == 4096 -> input0[x]
                    blend == 0 -> input1[x]
                    else -> ((4096 - blend) * input1[x] + blend * input0[x]) shr 12
                }
            }
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val blendFactor = getChildMonochromeOutput(2, y)
            val input0 = getChildColorOutput(0, y)
            val input1 = getChildColorOutput(1, y)
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            for (x in 0 until currentWidth) {
                val blend = blendFactor[x]
                when {
                    blend == 4096 -> {
                        outputRed[x] = input0[0][x]
                        outputGreen[x] = input0[1][x]
                        outputBlue[x] = input0[2][x]
                    }
                    blend == 0 -> {
                        outputRed[x] = input1[0][x]
                        outputGreen[x] = input1[1][x]
                        outputBlue[x] = input1[2][x]
                    }
                    else -> {
                        val inverseBlend = 4096 - blend
                        outputRed[x] = (blend * input0[0][x] + inverseBlend * input1[0][x]) shr 12
                        outputGreen[x] = (blend * input0[1][x] + inverseBlend * input1[1][x]) shr 12
                        outputBlue[x] = (blend * input0[2][x] + inverseBlend * input1[2][x]) shr 12
                    }
                }
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
