package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.min

/**
 * Type 6: Clamps pixel values to a min/max range.
 */
class TextureOpClamp : TextureOperation(1, false) {
    private var minValue: Int = 0
    private var maxValue: Int = 4096

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val input = getChildColorOutput(0, y)
            val inputRed = input[0]
            val inputGreen = input[1]
            val inputBlue = input[2]
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            for (x in 0 until currentWidth) {
                outputRed[x] = if (inputRed[x] < minValue) minValue else min(inputRed[x], maxValue)
                outputGreen[x] = if (inputGreen[x] < minValue) minValue else min(inputGreen[x], maxValue)
                outputBlue[x] = if (inputBlue[x] < minValue) minValue else min(inputBlue[x], maxValue)
            }
        }
        return output
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val input = getChildMonochromeOutput(0, y)
            for (x in 0 until currentWidth) {
                output[x] = if (input[x] < minValue) minValue else min(input[x], maxValue)
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> minValue = buffer.readShort() and 0xFFFF
            1 -> maxValue = buffer.readShort() and 0xFFFF
            2 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
