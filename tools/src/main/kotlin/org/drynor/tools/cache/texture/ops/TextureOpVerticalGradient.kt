package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation

/**
 * Type 3: Vertical gradient.
 * Fills output row with the vertical coordinate value.
 */
class TextureOpVerticalGradient : TextureOperation(0, true) {
    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val value = verticalTable[y]
            for (x in 0 until currentWidth) {
                output[x] = value
            }
        }
        return output
    }
}
