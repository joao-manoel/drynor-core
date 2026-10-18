package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.sqrt

/**
 * Type 35: Monochrome edge detection filter.
 * Detects edges by computing gradient magnitude.
 */
class TextureOpMonochromeEdgeDetector : TextureOperation(1, true) {
    private var strength: Int = 4096

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val prevRow = getChildMonochromeOutput(0, (y - 1) and columnEnd)
            val currentRow = getChildMonochromeOutput(0, y)
            val nextRow = getChildMonochromeOutput(0, (y + 1) and columnEnd)

            for (x in 0 until currentWidth) {
                val verticalGradient = (nextRow[x] - prevRow[x]) * strength
                val horizontalGradient = (currentRow[(x + 1) and rowEnd] - currentRow[(x - 1) and rowEnd]) * strength
                val gradientX = horizontalGradient shr 12
                val gradientY = verticalGradient shr 12
                val gradientXSquared = gradientX * gradientX shr 12
                val gradientYSquared = gradientY * gradientY shr 12
                val magnitude = (sqrt((gradientXSquared + gradientYSquared + 4096) / 4096.0) * 4096.0).toInt()
                val normalized = if (magnitude != 0) 16777216 / magnitude else 0
                output[x] = 4096 - normalized
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            strength = buffer.readShort() and 0xFFFF
        }
    }
}
