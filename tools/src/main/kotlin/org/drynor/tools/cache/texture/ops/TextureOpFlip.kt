package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 9: Flips texture horizontally and/or vertically.
 */
class TextureOpFlip : TextureOperation(1, false) {
    private var mirrorHorizontally: Boolean = true
    private var mirrorVertically: Boolean = true

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val mirroredRow = getChildMonochromeOutput(
                0,
                if (mirrorVertically) columnEnd - y else y
            )
            if (mirrorHorizontally) {
                for (x in 0 until currentWidth) {
                    output[x] = mirroredRow[rowEnd - x]
                }
            } else {
                System.arraycopy(mirroredRow, 0, output, 0, currentWidth)
            }
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val mirroredRow = getChildColorOutput(
                0,
                if (mirrorVertically) columnEnd - y else y
            )
            val mirroredRed = mirroredRow[0]
            val mirroredGreen = mirroredRow[1]
            val mirroredBlue = mirroredRow[2]
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            if (mirrorHorizontally) {
                for (x in 0 until currentWidth) {
                    val srcX = rowEnd - x
                    outputRed[x] = mirroredRed[srcX]
                    outputGreen[x] = mirroredGreen[srcX]
                    outputBlue[x] = mirroredBlue[srcX]
                }
            } else {
                for (x in 0 until currentWidth) {
                    outputRed[x] = mirroredRed[x]
                    outputGreen[x] = mirroredGreen[x]
                    outputBlue[x] = mirroredBlue[x]
                }
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> mirrorHorizontally = buffer.readUnsignedByte() == 1
            1 -> mirrorVertically = buffer.readUnsignedByte() == 1
            2 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
