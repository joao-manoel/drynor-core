package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 1: Fills the texture with a solid RGB color.
 */
class TextureOpColorFill : TextureOperation(0, false) {
    /** Red component (12-bit fixed-point) */
    private var redComponent: Int = 0

    /** Green component (12-bit fixed-point) */
    private var greenComponent: Int = 0

    /** Blue component (12-bit fixed-point) */
    private var blueComponent: Int = 0

    init {
        setColor(0)
    }

    /**
     * Sets the fill color from a packed RGB value.
     * @param packedColor RGB color value (0xRRGGBB)
     */
    private fun setColor(packedColor: Int) {
        blueComponent = (packedColor and 0xff) shl 4
        greenComponent = (packedColor and 0xff00) shr 4
        redComponent = (packedColor and 0xff0000) shr 12
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> setColor(buffer.readUnsignedMedium())
        }
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val palette = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val reds = palette[0]
            val greens = palette[1]
            val blues = palette[2]

            for (x in 0 until currentWidth) {
                reds[x] = redComponent
                greens[x] = greenComponent
                blues[x] = blueComponent
            }
        }
        return palette
    }
}
