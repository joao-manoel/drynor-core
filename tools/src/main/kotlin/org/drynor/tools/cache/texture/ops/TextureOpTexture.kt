package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 36: Samples from another texture/material.
 * Used for nested texture references within the texture operation DAG.
 */
class TextureOpTexture : TextureOperation(0, false) {
    var texturePixels: IntArray? = null
    var textureHeight: Int = 0
    var textureWidth: Int = 0
    private var materialId: Int = -1

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val pixels = texturePixels
            val red = output[0]
            val green = output[1]
            val blue = output[2]

            if (pixels == null || textureWidth == 0 || textureHeight == 0) {
                // Return gray if texture not loaded
                for (x in 0 until currentWidth) {
                    red[x] = 2048
                    green[x] = 2048
                    blue[x] = 2048
                }
                return output
            }

            val adjustedY = (if (textureHeight != currentHeight) {
                textureHeight * y / currentHeight
            } else {
                y
            }) * textureWidth

            if (textureWidth == currentWidth) {
                var offset = adjustedY
                for (x in 0 until currentWidth) {
                    val pixel = pixels[offset++]
                    blue[x] = (pixel and 0xff) shl 4
                    green[x] = (pixel and 0xff00) shr 4
                    red[x] = (pixel and 0xff0000) shr 12
                }
            } else {
                for (x in 0 until currentWidth) {
                    val adjustedX = x * textureWidth / currentWidth
                    val pixel = pixels[adjustedY + adjustedX]
                    blue[x] = (pixel and 0xff) shl 4
                    green[x] = (pixel and 0xff00) shr 4
                    red[x] = (pixel and 0xff0000) shr 12
                }
            }
        }
        return output
    }

    override fun createImageCache(width: Int, height: Int) {
        super.createImageCache(width, height)
        // Note: Actual texture loading happens externally via TextureLoader
        // which sets texturePixels, textureWidth, and textureHeight
    }

    override fun clearImageCache() {
        super.clearImageCache()
        texturePixels = null
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            materialId = buffer.readShort() and 0xFFFF
        }
    }

    override fun getMaterialId(): Int = materialId
}
