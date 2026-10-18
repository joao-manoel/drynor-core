package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import org.drynor.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader
import java.util.Random

/**
 * Type 38: Line noise generator.
 * Creates random line patterns across the texture.
 */
class TextureOpLineNoise : TextureOperation(0, true) {
    private var randomSeed: Int = 0
    private var lineCount: Int = 2000
    private var lineLength: Int = 16
    private var baseAngle: Int = 0
    private var angleVariation: Int = 4096

    override fun postDecode() {
        TextureRaster.calculateTable()
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val halfVariation = angleVariation shr 1
            val allRows = monochromeImageCache.getAllPaletteData()
            val random = Random(randomSeed.toLong())

            for (lineIdx in 0 until lineCount) {
                var angle = if (angleVariation > 0) {
                    baseAngle + (nextUnbiasedRandom(random, angleVariation) - halfVariation)
                } else {
                    baseAngle
                }
                angle = (angle shr 4) and 0xff

                val startX = nextUnbiasedRandom(random, currentWidth)
                val startY = nextUnbiasedRandom(random, currentHeight)
                val endX = startX + (TextureRaster.PALETTE_COS[angle] * lineLength shr 12)
                val endY = startY + (TextureRaster.PALETTE_SIN[angle] * lineLength shr 12)

                val deltaX = endX - startX
                val deltaY = endY - startY

                if (deltaX != 0 || deltaY != 0) {
                    var absDeltaX = if (deltaX < 0) -deltaX else deltaX
                    var absDeltaY = if (deltaY < 0) -deltaY else deltaY

                    val steep = absDeltaY > absDeltaX
                    var x0 = startX
                    var y0 = startY
                    var x1 = endX
                    var y1 = endY

                    if (steep) {
                        // Swap x and y
                        x0 = startY
                        y0 = startX
                        x1 = endY
                        y1 = endX
                    }

                    if (x0 > x1) {
                        // Swap start and end
                        val tempX = x0
                        val tempY = y0
                        x0 = x1
                        x1 = tempX
                        y0 = y1
                        y1 = tempY
                    }

                    var currentY = y0
                    val xDiff = x1 - x0
                    var yDiff = y1 - y0
                    var error = -xDiff / 2
                    val brightness = 2048 / xDiff
                    val baseBrightness = 1024 - (nextUnbiasedRandom(random, 4096) shr 2)
                    val yStep = if (y0 < y1) 1 else -1

                    if (yDiff < 0) {
                        yDiff = -yDiff
                    }

                    for (currentX in x0 until x1) {
                        val lineProgress = baseBrightness + brightness * (currentX - x0) + 1024
                        val pixelX = currentX and rowEnd
                        val pixelY = currentY and columnEnd

                        if (steep) {
                            allRows[pixelY]!![pixelX] = lineProgress
                        } else {
                            allRows[pixelX]!![pixelY] = lineProgress
                        }

                        error += yDiff
                        if (error > 0) {
                            error -= xDiff
                            currentY += yStep
                        }
                    }
                }
            }
        }
        return output
    }

    private fun nextUnbiasedRandom(random: Random, range: Int): Int {
        if (range <= 0) return 0
        var value: Int
        var limit: Int
        do {
            value = random.nextInt() and 0x7fffffff
            limit = value % range
        } while (value - limit + (range - 1) < 0)
        return limit
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> randomSeed = buffer.readUnsignedByte()
            1 -> lineCount = buffer.readShort() and 0xFFFF
            2 -> lineLength = buffer.readUnsignedByte()
            3 -> baseAngle = buffer.readShort() and 0xFFFF
            4 -> angleVariation = buffer.readShort() and 0xFFFF
        }
    }
}
