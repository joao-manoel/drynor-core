package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation

/**
 * Type 24: Converts color input to monochrome by averaging RGB.
 */
class TextureOpMonochrome : TextureOperation(1, true) {

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val childOutput = getChildColorOutput(0, y)
            val red = childOutput[0]
            val green = childOutput[1]
            val blue = childOutput[2]
            for (x in 0 until currentWidth) {
                output[x] = (blue[x] + green[x] + red[x]) / 3
            }
        }
        return output
    }
}
