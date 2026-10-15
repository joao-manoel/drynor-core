package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation

/**
 * Type 2: Horizontal gradient.
 * Returns the horizontal coordinate table directly as grayscale output.
 */
class TextureOpHorizontalGradient : TextureOperation(0, true) {
    override fun getMonochromeOutput(y: Int): IntArray {
        return horizontalTable
    }
}
