package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import org.drynor.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 19: Applies polar coordinate distortion.
 * Uses angle and radius from child operations to sample from input texture.
 */
class TextureOpPolarDistortion : TextureOperation(3, false) {
    private var distortionStrength: Int = 32768

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val angleData = getChildMonochromeOutput(1, y)
            val radiusData = getChildMonochromeOutput(2, y)

            for (x in 0 until currentWidth) {
                val angle = angleData[x] shr 4 and 0xff
                val radius = distortionStrength * radiusData[x] shr 12
                val offsetX = TextureRaster.PALETTE_COS[angle] * radius shr 12
                val offsetY = TextureRaster.PALETTE_SIN[angle] * radius shr 12
                val sampledX = (x + (offsetX shr 12)) and rowEnd
                val sampledY = ((offsetY shr 12) + y) and columnEnd
                val sourceRow = getChildMonochromeOutput(0, sampledY)
                output[x] = sourceRow[sampledX]
            }
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val angleData = getChildMonochromeOutput(1, y)
            val radiusData = getChildMonochromeOutput(2, y)
            val redOutput = output[0]
            val greenOutput = output[1]
            val blueOutput = output[2]

            for (x in 0 until currentWidth) {
                val angle = angleData[x] * 255 shr 12 and 0xff
                val radius = distortionStrength * radiusData[x] shr 12
                val offsetX = TextureRaster.PALETTE_COS[angle] * radius shr 12
                val offsetY = TextureRaster.PALETTE_SIN[angle] * radius shr 12
                val sampledX = (x + (offsetX shr 12)) and rowEnd
                val sampledY = ((offsetY shr 12) + y) and columnEnd
                val sourceRow = getChildColorOutput(0, sampledY)
                redOutput[x] = sourceRow[0][sampledX]
                greenOutput[x] = sourceRow[1][sampledX]
                blueOutput[x] = sourceRow[2][sampledX]
            }
        }
        return output
    }

    override fun postDecode() {
        TextureRaster.calculateTable()
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> distortionStrength = buffer.readShort() and 0xFFFF shl 4
            1 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
