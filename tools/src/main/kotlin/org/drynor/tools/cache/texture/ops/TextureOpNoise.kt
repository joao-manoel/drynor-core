package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation

/**
 * Type 13: Simple hash-based noise generator.
 */
class TextureOpNoise : TextureOperation(0, true) {

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalValue = verticalTable[y]
            for (x in 0 until currentWidth) {
                output[x] = computeNoise(horizontalTable[x], verticalValue) % 4096
            }
        }
        return output
    }

    private fun computeNoise(x: Int, y: Int): Int {
        var value = y * 57 + x
        value = value xor (value shl 1)
        return 4096 - ((value * value * 15731 + 789221) * value + 1376312589 and 0x7fffffff) / 262144
    }
}
