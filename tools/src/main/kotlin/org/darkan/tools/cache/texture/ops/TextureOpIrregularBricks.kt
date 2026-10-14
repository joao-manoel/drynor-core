package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import java.util.Random
import kotlin.math.min

/**
 * Type 28: Irregular brick pattern generator.
 * Creates a brick pattern with random positioning and size variations.
 */
class TextureOpIrregularBricks : TextureOperation(0, true) {
    private var randomSeed: Int = 0
    private var minBrickWidth: Int = 1024
    private var maxBrickWidth: Int = 2048
    private var widthVariation: Int = 409
    private var minBrickHeight: Int = 819
    private var maxBrickHeight: Int = 1024
    private var cornerStyle: Int = 0
    private var colorVariation: Int = 1024
    private var brightnessVariation: Int = 1024
    private var cornerRadius: Int = 0

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val allRows = monochromeImageCache.getAllPaletteData()
            generateBrickPattern(allRows)
        }
        return output
    }

    private fun generateBrickPattern(allRows: Array<IntArray?>) {
        var currentRowStart = 0
        var previousRowEnd = 0
        var rowOffset = 0
        var previousOffset = 0
        var brickInfo = Array(0) { IntArray(3) }
        var previousBrickInfo = Array(currentWidth / ((minBrickWidth * currentWidth) shr 12) + 1) { IntArray(3) }
        val random = Random(randomSeed.toLong())
        var isFirstRow = true
        var isSecondRow = true
        var brickCount = 0
        var previousBrickCount = 0
        var previousBrickIndex = 0

        val scaledMinWidth = minBrickWidth * currentWidth shr 12
        val scaledMaxWidth = maxBrickWidth * currentWidth shr 12
        val scaledMinHeight = widthVariation * currentHeight shr 12
        val scaledMaxHeight = minBrickHeight * currentHeight shr 12

        if (scaledMaxHeight <= 1) {
            return
        }

        cornerRadius = currentWidth / 8 * maxBrickHeight shr 12

        while (true) {
            if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                return
            }
            var brickWidth = scaledMinWidth + nextUnbiasedRandom(random, scaledMaxWidth - scaledMinWidth)
            var brickHeight = scaledMinHeight + nextUnbiasedRandom(random, scaledMaxHeight - scaledMinHeight)
            var brickEnd = brickWidth + currentRowStart

            if (brickEnd > currentWidth) {
                brickWidth = currentWidth - currentRowStart
                brickEnd = currentWidth
            }

            // Find vertical position based on previous row
            var verticalPos: Int
            if (isSecondRow) {
                verticalPos = 0
            } else {
                var searchIndex = previousBrickIndex
                val searchBrickInfo = previousBrickInfo[previousBrickIndex]
                var overlapCount = 0
                var searchEnd = brickEnd + previousOffset

                if (searchEnd < 0) searchEnd += currentWidth
                if (searchEnd > currentWidth) searchEnd -= currentWidth

                while (true) {
                    val currentBrickInfo = previousBrickInfo[searchIndex]
                    if (searchEnd >= currentBrickInfo[0] && searchEnd <= currentBrickInfo[1]) {
                        verticalPos = searchBrickInfo[2]
                        if (searchIndex != previousBrickIndex) {
                            var searchStart = currentRowStart + previousOffset
                            if (searchStart < 0) searchStart += currentWidth
                            if (searchStart > currentWidth) searchStart -= currentWidth

                            for (i in 1..overlapCount) {
                                val overlapBrick = previousBrickInfo[(i + previousBrickIndex) % previousBrickCount]
                                verticalPos = verticalPos.coerceAtLeast(overlapBrick[2])
                            }

                            for (i in 0..overlapCount) {
                                val overlapBrick = previousBrickInfo[(i + previousBrickIndex) % previousBrickCount]
                                val overlap2 = overlapBrick[2]
                                if (overlap2 != verticalPos) {
                                    val left = overlapBrick[0]
                                    val right = overlapBrick[1]
                                    val adjLeft: Int
                                    val adjRight: Int
                                    if (searchStart < searchEnd) {
                                        adjLeft = searchStart.coerceAtLeast(left)
                                        adjRight = searchEnd.coerceAtMost(right)
                                    } else if (left == 0) {
                                        adjLeft = 0
                                        adjRight = searchEnd.coerceAtMost(right)
                                    } else {
                                        adjLeft = searchStart.coerceAtLeast(left)
                                        adjRight = currentWidth
                                    }
                                    drawBrick(random, allRows, adjLeft + rowOffset, overlap2, adjRight - adjLeft, verticalPos - overlap2)
                                }
                            }
                        }
                        previousBrickIndex = searchIndex
                        break
                    }
                    searchIndex++
                    if (searchIndex >= previousBrickCount) searchIndex = 0
                    overlapCount++
                    if (overlapCount > previousBrickInfo.size) return // safety bound
                }
            }

            if (verticalPos + brickHeight > currentHeight) {
                brickHeight = currentHeight - verticalPos
            } else {
                isFirstRow = false
            }

            if (brickEnd == currentWidth) {
                drawBrick(random, allRows, previousRowEnd + currentRowStart, verticalPos, brickWidth, brickHeight)

                if (isFirstRow) {
                    return
                }

                isFirstRow = true
                val newBrickInfo = Array(brickCount + 1) { IntArray(3) }
                System.arraycopy(brickInfo, 0, newBrickInfo, 0, brickCount)
                newBrickInfo[brickCount][0] = currentRowStart
                newBrickInfo[brickCount][1] = brickEnd
                newBrickInfo[brickCount][2] = brickHeight + verticalPos

                previousBrickInfo = newBrickInfo
                brickInfo = Array(newBrickInfo.size) { IntArray(3) }
                previousBrickCount = brickCount + 1
                brickCount = 0
                rowOffset = previousRowEnd
                previousRowEnd = nextUnbiasedRandom(random, currentWidth)
                previousOffset = previousRowEnd - rowOffset

                var normalizedOffset = previousOffset
                if (normalizedOffset < 0) normalizedOffset += currentWidth
                if (normalizedOffset > currentWidth) normalizedOffset -= currentWidth

                previousBrickIndex = 0
                var searchIterations = 0
                while (true) {
                    val brick = previousBrickInfo[previousBrickIndex]
                    if (normalizedOffset >= brick[0] && normalizedOffset <= brick[1]) {
                        isSecondRow = false
                        break
                    }
                    previousBrickIndex++
                    if (previousBrickIndex >= previousBrickCount) previousBrickIndex = 0
                    searchIterations++
                    if (searchIterations > previousBrickInfo.size) return // safety bound
                }

                currentRowStart = 0
            } else {
                if (brickCount >= brickInfo.size) {
                    brickInfo = Array(brickCount + 10) { i ->
                        if (i < brickCount) brickInfo[i] else IntArray(3)
                    }
                }
                brickInfo[brickCount][0] = currentRowStart
                brickInfo[brickCount][1] = brickEnd
                brickInfo[brickCount][2] = brickHeight + verticalPos
                brickCount++
                drawBrick(random, allRows, previousRowEnd + currentRowStart, verticalPos, brickWidth, brickHeight)
                currentRowStart = brickEnd
            }
        }
    }

    private fun drawBrick(random: Random, allRows: Array<IntArray?>, startX: Int, startY: Int, width: Int, height: Int) {
        val brightness = if (brightnessVariation > 0) 4096 - nextUnbiasedRandom(random, brightnessVariation) else 4096
        val cornerVariation = colorVariation * cornerRadius shr 12
        val actualCornerRadius = cornerRadius - if (cornerVariation > 0) nextUnbiasedRandom(random, cornerVariation) else 0

        var adjustedStartX = startX
        if (adjustedStartX >= currentWidth) {
            adjustedStartX -= currentWidth
        }

        if (actualCornerRadius > 0) {
            if (height > 0 && width > 0) {
                val halfWidth = width / 2
                val halfHeight = height / 2
                val xRadius = min(halfWidth, actualCornerRadius)
                val yRadius = min(halfHeight, actualCornerRadius)
                val innerStartX = adjustedStartX + xRadius
                val innerWidth = width - xRadius * 2

                for (y in 0 until height) {
                    val row = allRows[(y + startY)]!!
                    val yProgress: Int
                    val yFade: Int

                    if (y < yRadius) {
                        yProgress = brightness * y / yRadius
                        if (cornerStyle == 0) {
                            for (x in 0 until xRadius) {
                                val xProgress = brightness * x / xRadius
                                row[(adjustedStartX + x) and rowEnd] = (xProgress * yProgress) shr 12
                                row[(adjustedStartX + width - x - 1) and rowEnd] = (xProgress * yProgress) shr 12
                            }
                        } else {
                            for (x in 0 until xRadius) {
                                val xProgress = x * brightness / xRadius
                                row[(adjustedStartX + x) and rowEnd] = min(xProgress, yProgress)
                                row[(adjustedStartX + width - x - 1) and rowEnd] = min(xProgress, yProgress)
                            }
                        }
                        fillRow(row, innerStartX, innerWidth, yProgress)
                    } else {
                        yFade = height - y - 1
                        if (yFade < yRadius) {
                            yProgress = brightness * yFade / yRadius
                            if (cornerStyle == 0) {
                                for (x in 0 until xRadius) {
                                    val xProgress = x * brightness / xRadius
                                    row[(adjustedStartX + x) and rowEnd] = (yProgress * xProgress) shr 12
                                    row[(adjustedStartX + width - x - 1) and rowEnd] = (yProgress * xProgress) shr 12
                                }
                            } else {
                                for (x in 0 until xRadius) {
                                    val xProgress = x * brightness / xRadius
                                    row[(adjustedStartX + x) and rowEnd] = min(xProgress, yProgress)
                                    row[(adjustedStartX + width - x - 1) and rowEnd] = min(xProgress, yProgress)
                                }
                            }
                            fillRow(row, innerStartX, innerWidth, yProgress)
                        } else {
                            for (x in 0 until xRadius) {
                                row[(adjustedStartX + x) and rowEnd] = x * brightness / xRadius
                                row[(adjustedStartX + width - x - 1) and rowEnd] = x * brightness / xRadius
                            }
                            fillRow(row, innerStartX, innerWidth, brightness)
                        }
                    }
                }
            }
        } else if (adjustedStartX + width > currentWidth) {
            val overflow = currentWidth - adjustedStartX
            for (y in 0 until height) {
                val row = allRows[(y + startY)]!!
                fillRow(row, adjustedStartX, overflow, brightness)
                fillRow(row, 0, width - overflow, brightness)
            }
        } else {
            for (y in 0 until height) {
                fillRow(allRows[(y + startY)]!!, adjustedStartX, width, brightness)
            }
        }
    }

    private fun fillRow(row: IntArray, start: Int, length: Int, value: Int) {
        val endX = (start + length).coerceAtMost(row.size)
        val wrappedEnd = if (start + length > currentWidth) {
            currentWidth
        } else {
            endX
        }

        for (x in start until wrappedEnd) {
            row[x and rowEnd] = value
        }

        if (start + length > currentWidth) {
            val overflow = start + length - currentWidth
            for (x in 0 until overflow) {
                row[x] = value
            }
        }
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
            1 -> minBrickWidth = buffer.readShort() and 0xFFFF
            2 -> maxBrickWidth = buffer.readShort() and 0xFFFF
            3 -> widthVariation = buffer.readShort() and 0xFFFF
            4 -> minBrickHeight = buffer.readShort() and 0xFFFF
            5 -> maxBrickHeight = buffer.readShort() and 0xFFFF
            6 -> cornerStyle = buffer.readUnsignedByte()
            7 -> colorVariation = buffer.readShort() and 0xFFFF
            8 -> brightnessVariation = buffer.readShort() and 0xFFFF
        }
    }
}
