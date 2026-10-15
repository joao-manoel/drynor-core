package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.sqrt

/**
 * Type 33: Color edge detection filter.
 * Outputs edge normals as RGB colors.
 */
class TextureOpColorEdgeDetector : TextureOperation(1, false) {
    private var normalized: Boolean = true
    private var strength: Int = 4096

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val prevRow = getChildMonochromeOutput(0, (y - 1) and columnEnd)
            val currentRow = getChildMonochromeOutput(0, y)
            val nextRow = getChildMonochromeOutput(0, (y + 1) and columnEnd)
            val redChannel = output[0]
            val greenChannel = output[1]
            val blueChannel = output[2]

            for (x in 0 until currentWidth) {
                val verticalGradient = (nextRow[x] - prevRow[x]) * strength
                val horizontalGradient = (currentRow[(x + 1) and rowEnd] - currentRow[(x - 1) and rowEnd]) * strength
                val gradientX = horizontalGradient shr 12
                val gradientY = verticalGradient shr 12
                val gradientXSquared = gradientX * gradientX shr 12
                val gradientYSquared = gradientY * gradientY shr 12
                val magnitude = (sqrt(((gradientXSquared + gradientYSquared + 4096) / 4096.0)) * 4096.0).toInt()

                val red: Int
                val green: Int
                val blue: Int
                if (magnitude != 0) {
                    red = horizontalGradient / magnitude
                    green = verticalGradient / magnitude
                    blue = 16777216 / magnitude
                } else {
                    red = 0
                    green = 0
                    blue = 0
                }

                if (normalized) {
                    redChannel[x] = (red shr 1) + 2048
                    greenChannel[x] = (green shr 1) + 2048
                    blueChannel[x] = (blue shr 1) + 2048
                } else {
                    redChannel[x] = red
                    greenChannel[x] = green
                    blueChannel[x] = blue
                }
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> strength = buffer.readShort() and 0xFFFF
            1 -> normalized = buffer.readUnsignedByte() == 1
        }
    }
}
