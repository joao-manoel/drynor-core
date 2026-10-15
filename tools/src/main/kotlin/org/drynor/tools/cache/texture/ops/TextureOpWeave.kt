package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.abs

/**
 * Type 14: Generates a weave/fabric pattern.
 */
class TextureOpWeave : TextureOperation(0, true) {
    private var threshold: Int = 585

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalValue = verticalTable[y]
            for (x in 0 until currentWidth) {
                val horizontalValue = horizontalTable[x]

                output[x] = when {
                    horizontalValue > threshold && horizontalValue < 4096 - threshold &&
                    verticalValue > 2048 - threshold && verticalValue < threshold + 2048 -> {
                        val result = abs(2048 - horizontalValue)
                        val scaled = result shl 12
                        4096 - scaled / (2048 - threshold)
                    }
                    horizontalValue > 2048 - threshold && horizontalValue < threshold + 2048 -> {
                        val result = abs(verticalValue - 2048) - threshold
                        val scaled = result shl 12
                        scaled / (2048 - threshold)
                    }
                    verticalValue >= threshold && verticalValue <= 4096 - threshold -> {
                        if (horizontalValue >= threshold && horizontalValue <= 4096 - threshold) {
                            0
                        } else {
                            val result = abs(2048 - verticalValue)
                            val scaled = result shl 12
                            4096 - scaled / (2048 - threshold)
                        }
                    }
                    else -> {
                        val result = abs(horizontalValue - 2048) - threshold
                        val scaled = result shl 12
                        scaled / (2048 - threshold)
                    }
                }
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> threshold = buffer.readShort() and 0xFFFF
        }
    }
}
