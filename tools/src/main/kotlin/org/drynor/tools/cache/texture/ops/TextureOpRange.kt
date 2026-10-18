package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 30: Range mapping and scaling.
 * Maps input values to a configurable output range.
 */
class TextureOpRange : TextureOperation(1, false) {
    private var rangeMultiplier: Int = 2048
    private var rangeOffset: Int = 1024
    private var rangeMax: Int = 3072

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val childOutput = getChildColorOutput(0, y)
            val redInput = childOutput[0]
            val greenInput = childOutput[1]
            val blueInput = childOutput[2]
            val redOutput = output[0]
            val greenOutput = output[1]
            val blueOutput = output[2]

            for (x in 0 until currentWidth) {
                redOutput[x] = (rangeMultiplier * redInput[x] shr 12) + rangeOffset
                greenOutput[x] = (rangeMultiplier * greenInput[x] shr 12) + rangeOffset
                blueOutput[x] = (rangeMultiplier * blueInput[x] shr 12) + rangeOffset
            }
        }
        return output
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val childOutput = getChildMonochromeOutput(0, y)
            for (x in 0 until currentWidth) {
                output[x] = (rangeMultiplier * childOutput[x] shr 12) + rangeOffset
            }
        }
        return output
    }

    override fun postDecode() {
        rangeMultiplier = rangeMax - rangeOffset
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> rangeOffset = buffer.readShort() and 0xFFFF
            1 -> rangeMax = buffer.readShort() and 0xFFFF
            2 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
