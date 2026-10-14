package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import java.util.Random

/**
 * Type 4: Brick pattern generator.
 * Creates a brick wall pattern with configurable brick size, offset, and mortar.
 */
class TextureOpBricks : TextureOperation(0, true) {
    private var bricksPerRow: Int = 4
    private var rowCount: Int = 8
    private var verticalOffset: Int = 0
    private var horizontalOffset: Int = 1024
    private var colorVariation: Int = 1024
    private var widthVariation: Int = 409
    private var heightVariation: Int = 204
    private var mortarSize: Int = 81
    private var halfMortarSize: Int = 0
    private var brickWidth: Int = 0
    private var brickHeight: Int = 0
    private var rowHeights: IntArray? = null
    private var brickWidths: Array<IntArray>? = null
    private var brickColors: Array<IntArray>? = null

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            var rowIndex = 0

            // Calculate vertical position with offset
            var verticalPos = verticalTable[y] + verticalOffset
            while (verticalPos < 0) verticalPos += 4096
            while (verticalPos > 4096) verticalPos -= 4096

            // Find which row we're in
            while (rowIndex < rowCount && verticalPos >= rowHeights!![rowIndex]) {
                rowIndex++
            }

            val currentRow = rowIndex - 1
            val isEvenRow = (rowIndex and 0x1) == 0
            val rowTop = rowHeights!![rowIndex]
            val rowBottom = rowHeights!![rowIndex - 1]

            // Check if we're in the mortar between rows
            if (verticalPos > rowBottom + halfMortarSize && verticalPos < rowTop - halfMortarSize) {
                for (x in 0 until currentWidth) {
                    var brickIndex = 0
                    val rowHorizontalOffset = if (isEvenRow) horizontalOffset else -horizontalOffset

                    // Calculate horizontal position with offset
                    var horizontalPos = horizontalTable[x] + (rowHorizontalOffset * brickWidth shr 12)
                    while (horizontalPos < 0) horizontalPos += 4096
                    while (horizontalPos > 4096) horizontalPos -= 4096

                    // Find which brick we're in
                    while (brickIndex < bricksPerRow && horizontalPos >= brickWidths!![currentRow][brickIndex]) {
                        brickIndex++
                    }

                    val thisBrick = brickIndex - 1
                    val brickLeft = brickWidths!![currentRow][thisBrick]
                    val brickRight = brickWidths!![currentRow][brickIndex]

                    // Check if we're in the mortar between bricks
                    if (horizontalPos > brickLeft + halfMortarSize && horizontalPos < brickRight - halfMortarSize) {
                        output[x] = brickColors!![currentRow][thisBrick]
                    } else {
                        output[x] = 0
                    }
                }
            } else {
                // In the horizontal mortar - fill with 0
                for (x in 0 until currentWidth) {
                    output[x] = 0
                }
            }
        }
        return output
    }

    override fun postDecode() {
        computeBrickPattern()
    }

    private fun computeBrickPattern() {
        val random = Random(rowCount.toLong())
        halfMortarSize = mortarSize / 2
        brickWidth = 4096 / bricksPerRow
        brickHeight = 4096 / rowCount
        val halfBrickWidth = brickWidth / 2
        val halfBrickHeight = brickHeight / 2

        rowHeights = IntArray(rowCount + 1)
        brickWidths = Array(rowCount) { IntArray(bricksPerRow + 1) }
        brickColors = Array(rowCount) { IntArray(bricksPerRow) }
        rowHeights!![0] = 0

        for (row in 0 until rowCount) {
            if (row > 0) {
                var height = brickHeight
                val heightVariance = (nextUnbiasedRandom(random, 4096) - 2048) * heightVariation shr 12
                val adjustedHeight = height + (heightVariance * halfBrickHeight shr 12)
                rowHeights!![row] = adjustedHeight + rowHeights!![row - 1]
            }

            brickWidths!![row][0] = 0

            for (brick in 0 until bricksPerRow) {
                if (brick > 0) {
                    var width = brickWidth
                    val widthVariance = (nextUnbiasedRandom(random, 4096) - 2048) * widthVariation shr 12
                    val adjustedWidth = width + (widthVariance * halfBrickWidth shr 12)
                    brickWidths!![row][brick] = brickWidths!![row][brick - 1] + adjustedWidth
                }

                brickColors!![row][brick] = if (colorVariation > 0) {
                    4096 - nextUnbiasedRandom(random, colorVariation)
                } else {
                    4096
                }
            }

            brickWidths!![row][bricksPerRow] = 4096
        }

        rowHeights!![rowCount] = 4096
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
            0 -> bricksPerRow = buffer.readUnsignedByte()
            1 -> rowCount = buffer.readUnsignedByte()
            2 -> widthVariation = buffer.readShort() and 0xFFFF
            3 -> heightVariation = buffer.readShort() and 0xFFFF
            4 -> horizontalOffset = buffer.readShort() and 0xFFFF
            5 -> verticalOffset = buffer.readShort() and 0xFFFF
            6 -> mortarSize = buffer.readShort() and 0xFFFF
            7 -> colorVariation = buffer.readShort() and 0xFFFF
        }
    }
}
