package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 22: Inverts color/grayscale values.
 */
class TextureOpInvert : TextureOperation(1, false) {

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val childOutput = getChildColorOutput(0, y)
            val inputRed = childOutput[0]
            val inputGreen = childOutput[1]
            val inputBlue = childOutput[2]
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            for (x in 0 until currentWidth) {
                outputRed[x] = 4096 - inputRed[x]
                outputGreen[x] = 4096 - inputGreen[x]
                outputBlue[x] = 4096 - inputBlue[x]
            }
        }
        return output
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val input = getChildMonochromeOutput(0, y)
            for (x in 0 until currentWidth) {
                output[x] = 4096 - input[x]
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
