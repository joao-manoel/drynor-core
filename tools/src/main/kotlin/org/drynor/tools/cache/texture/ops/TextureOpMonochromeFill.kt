package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 0: Fills the texture with a solid grayscale value.
 */
class TextureOpMonochromeFill : TextureOperation(0, true) {
    /** Fill value (12-bit fixed-point, 0-4096) */
    private var fillValue: Int = 4096

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            for (x in 0 until currentWidth) {
                output[x] = fillValue
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> fillValue = (buffer.readUnsignedByte() shl 12) / 255
        }
    }
}
