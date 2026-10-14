package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 5: Box blur filter.
 * Applies a box blur with configurable horizontal and vertical radius.
 */
class TextureOpBoxBlur : TextureOperation(1, false) {
    /** Horizontal blur radius */
    private var radiusX: Int = 1

    /** Vertical blur radius */
    private var radiusY: Int = 1

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)

        if (monochromeImageCache.dirty) {
            val kernelHeight = radiusY + radiusY + 1
            val divisorY = 65536 / kernelHeight
            val kernelWidth = radiusX + radiusX + 1
            val divisorX = 65536 / kernelWidth

            // Collect blurred rows
            val rowCache = arrayOfNulls<IntArray>(kernelHeight)
            for (ky in y - radiusY..y + radiusY) {
                val childRow = getChildMonochromeOutput(0, ky and columnEnd)
                val blurredRow = IntArray(currentWidth)

                // Horizontal blur
                var sum = 0
                for (kx in -radiusX..radiusX) {
                    sum += childRow[kx and rowEnd]
                }

                for (x in 0 until currentWidth) {
                    blurredRow[x] = sum * divisorX shr 16
                    sum -= childRow[(x - radiusX) and rowEnd]
                    sum += childRow[(x + radiusX) and rowEnd]
                }

                rowCache[ky + radiusY - y] = blurredRow
            }

            // Vertical blur
            for (x in 0 until currentWidth) {
                var sum = 0
                for (ky in 0 until kernelHeight) {
                    sum += rowCache[ky]!![x]
                }
                output[x] = sum * divisorY shr 16
            }
        }

        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty) {
            val kernelHeight = radiusY + radiusY + 1
            val divisorY = 65536 / kernelHeight
            val kernelWidth = radiusX + radiusX + 1
            val divisorX = 65536 / kernelWidth

            // Collect blurred rows
            val rowCache = arrayOfNulls<Array<IntArray>>(kernelHeight)

            for (ky in y - radiusY..y + radiusY) {
                val childRow = getChildColorOutput(0, ky and columnEnd)
                val blurredRow = Array(3) { IntArray(currentWidth) }

                val childR = childRow[0]
                val childG = childRow[1]
                val childB = childRow[2]

                // Horizontal blur for each channel
                var sumR = 0
                var sumG = 0
                var sumB = 0

                for (kx in -radiusX..radiusX) {
                    val idx = kx and rowEnd
                    sumR += childR[idx]
                    sumG += childG[idx]
                    sumB += childB[idx]
                }

                for (x in 0 until currentWidth) {
                    blurredRow[0][x] = divisorX * sumR shr 16
                    blurredRow[1][x] = divisorX * sumG shr 16
                    blurredRow[2][x] = sumB * divisorX shr 16

                    val removeIdx = (x - radiusX) and rowEnd
                    sumR -= childR[removeIdx]
                    sumG -= childG[removeIdx]
                    sumB -= childB[removeIdx]

                    val addIdx = (x + radiusX) and rowEnd
                    sumR += childR[addIdx]
                    sumG += childG[addIdx]
                    sumB += childB[addIdx]
                }

                rowCache[ky + radiusY - y] = blurredRow
            }

            // Vertical blur
            val outR = output[0]
            val outG = output[1]
            val outB = output[2]

            for (x in 0 until currentWidth) {
                var sumR = 0
                var sumG = 0
                var sumB = 0

                for (ky in 0 until kernelHeight) {
                    val cached = rowCache[ky]!!
                    sumR += cached[0][x]
                    sumG += cached[1][x]
                    sumB += cached[2][x]
                }

                outR[x] = sumR * divisorY shr 16
                outG[x] = divisorY * sumG shr 16
                outB[x] = divisorY * sumB shr 16
            }
        }

        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> radiusX = buffer.readUnsignedByte()
            1 -> radiusY = buffer.readUnsignedByte()
            2 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
