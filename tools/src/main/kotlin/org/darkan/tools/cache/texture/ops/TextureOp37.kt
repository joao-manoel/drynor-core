package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import org.darkan.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 37: Pattern generator with dual cosine wave checks.
 * Creates a pattern based on diagonal wave interference.
 */
class TextureOp37 : TextureOperation(0, true) {
    private var offsetX1: Int = 2048
    private var offsetY1: Int = 0
    private var offsetX2: Int = 0
    private var offsetY2: Int = 2048
    private var frequency: Int = 12288
    private var amplitude: Int = 4096
    private var scale: Int = 8192

    override fun postDecode() {
        TextureRaster.calculateTable()
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalPos = verticalTable[y] - 2048

            for (x in 0 until currentWidth) {
                val horizontalPos = horizontalTable[x] - 2048

                // First position with offset
                var pos1X = horizontalPos + offsetX1
                pos1X = if (pos1X < -2048) pos1X + 4096 else pos1X
                pos1X = if (pos1X > 2048) pos1X - 4096 else pos1X
                var pos1Y = verticalPos + offsetY1
                pos1Y = if (pos1Y < -2048) pos1Y + 4096 else pos1Y
                pos1Y = if (pos1Y > 2048) pos1Y - 4096 else pos1Y

                // Second position with offset
                var pos2X = horizontalPos + offsetX2
                pos2X = if (pos2X < -2048) pos2X + 4096 else pos2X
                pos2X = if (pos2X > 2048) pos2X - 4096 else pos2X
                var pos2Y = verticalPos + offsetY2
                pos2Y = if (pos2Y < -2048) pos2Y + 4096 else pos2Y
                pos2Y = if (pos2Y > 2048) pos2Y - 4096 else pos2Y

                output[x] = if (!checkWave1(pos1X, pos1Y) && !checkWave2(pos2X, pos2Y)) 0 else 4096
            }
        }
        return output
    }

    private fun checkWave1(x: Int, y: Int): Boolean {
        val diagonalSum = (y - x) * frequency shr 12
        var threshold = TextureRaster.PALETTE_COS[((diagonalSum * 255) shr 12) and 0xff]
        threshold = (threshold shl 12) / frequency
        threshold = (threshold shl 12) / scale
        threshold = threshold * amplitude shr 12
        return y + x < threshold && y + x > -threshold
    }

    private fun checkWave2(x: Int, y: Int): Boolean {
        val diagonalDiff = (y + x) * frequency shr 12
        var threshold = TextureRaster.PALETTE_COS[((diagonalDiff * 255) shr 12) and 0xff]
        threshold = (threshold shl 12) / frequency
        threshold = (threshold shl 12) / scale
        threshold = threshold * amplitude shr 12
        return y - x < threshold && y - x > -threshold
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> offsetX1 = buffer.readShort() and 0xFFFF
            1 -> offsetY1 = buffer.readShort() and 0xFFFF
            2 -> offsetX2 = buffer.readShort() and 0xFFFF
            3 -> offsetY2 = buffer.readShort() and 0xFFFF
            4 -> frequency = buffer.readShort() and 0xFFFF
            5 -> amplitude = buffer.readShort() and 0xFFFF
            6 -> scale = buffer.readShort() and 0xFFFF
        }
    }
}
