package org.drynor.tools.cache.texture.ops


/**
 * Type 18: Tiled graphic rendering.
 * Similar to TextureOpGraphic but tiles the graphic across the texture.
 */
class TextureOpTiledGraphic : TextureOpGraphic() {

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty && graphicPixels != null) {
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]
            val tileY = y % graphicHeight * graphicHeight

            for (x in 0 until currentWidth) {
                val pixel = graphicPixels!![x % graphicWidth + tileY]
                outputBlue[x] = (pixel and 0xff) shl 4
                outputGreen[x] = (pixel and 0xff00) shr 4
                outputRed[x] = (pixel and 0xff0000) shr 12
            }
        }
        return output
    }
}
