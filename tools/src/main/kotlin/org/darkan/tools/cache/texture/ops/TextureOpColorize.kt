package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 11: Colorizes grayscale textures with RGB values.
 * Applies color multipliers to pixels where R=G=B (grayscale).
 */
class TextureOpColorize : TextureOperation(1, false) {
    private var redMultiplier: Int = 4096
    private var greenMultiplier: Int = 4096
    private var blueMultiplier: Int = 4096

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
                val red = inputRed[x]
                val green = inputGreen[x]
                val blue = inputBlue[x]
                if (red == green && green == blue) {
                    // Grayscale pixel - apply color multipliers
                    outputRed[x] = red * redMultiplier shr 12
                    outputGreen[x] = green * greenMultiplier shr 12
                    outputBlue[x] = blue * blueMultiplier shr 12
                } else {
                    // Non-grayscale - use multipliers directly
                    outputRed[x] = redMultiplier
                    outputGreen[x] = greenMultiplier
                    outputBlue[x] = blueMultiplier
                }
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> redMultiplier = buffer.readShort() and 0xFFFF
            1 -> greenMultiplier = buffer.readShort() and 0xFFFF
            2 -> blueMultiplier = buffer.readShort() and 0xFFFF
        }
    }
}
