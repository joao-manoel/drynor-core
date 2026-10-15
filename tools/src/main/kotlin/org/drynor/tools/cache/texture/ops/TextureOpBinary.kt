package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 26: Binary threshold filter.
 * Outputs 4096 if value is within range, 0 otherwise.
 */
class TextureOpBinary : TextureOperation(1, true) {
    private var minThreshold: Int = 0
    private var maxThreshold: Int = 4096

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val input = getChildMonochromeOutput(0, y)
            for (x in 0 until currentWidth) {
                val value = input[x]
                output[x] = if (value >= minThreshold && value <= maxThreshold) 4096 else 0
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> minThreshold = buffer.readShort() and 0xFFFF
            1 -> maxThreshold = buffer.readShort() and 0xFFFF
        }
    }
}
