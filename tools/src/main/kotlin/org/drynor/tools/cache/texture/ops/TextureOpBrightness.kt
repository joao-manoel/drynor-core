package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 25: Adjusts brightness of pixels matching a target color.
 * Pixels within tolerance of the target color have their RGB multiplied.
 */
class TextureOpBrightness : TextureOperation(1, false) {
    /** Blue multiplier (12-bit fixed-point) */
    private var blueMul: Int = 4096

    /** Green multiplier (12-bit fixed-point) */
    private var greenMul: Int = 4096

    /** Red multiplier (12-bit fixed-point) */
    private var redMul: Int = 4096

    /** Color match tolerance (12-bit fixed-point) */
    private var tolerance: Int = 409

    /** Target color for matching (12-bit R, G, B) */
    private val targetColor: IntArray = IntArray(3)

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty) {
            val childOutput = getChildColorOutput(0, y)
            val childRed = childOutput[0]
            val childGreen = childOutput[1]
            val childBlue = childOutput[2]
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            for (x in 0 until currentWidth) {
                val red = childRed[x]
                var redDelta = red - targetColor[0]
                if (redDelta < 0) redDelta = -redDelta

                if (redDelta > tolerance) {
                    // Red out of tolerance - pass through
                    outputRed[x] = red
                    outputGreen[x] = childGreen[x]
                    outputBlue[x] = childBlue[x]
                } else {
                    val green = childGreen[x]
                    var greenDelta = green - targetColor[1]
                    if (greenDelta < 0) greenDelta = -greenDelta

                    if (greenDelta > tolerance) {
                        // Green out of tolerance - pass through
                        outputRed[x] = red
                        outputGreen[x] = green
                        outputBlue[x] = childBlue[x]
                    } else {
                        val blue = childBlue[x]
                        var blueDelta = blue - targetColor[2]
                        if (blueDelta < 0) blueDelta = -blueDelta

                        if (blueDelta > tolerance) {
                            // Blue out of tolerance - pass through
                            outputRed[x] = red
                            outputGreen[x] = green
                            outputBlue[x] = blue
                        } else {
                            // All within tolerance - apply brightness multipliers
                            outputRed[x] = red * redMul shr 12
                            outputGreen[x] = green * greenMul shr 12
                            outputBlue[x] = blue * blueMul shr 12
                        }
                    }
                }
            }
        }

        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> tolerance = buffer.readShort() and 0xFFFF
            1 -> blueMul = buffer.readShort() and 0xFFFF
            2 -> greenMul = buffer.readShort() and 0xFFFF
            3 -> redMul = buffer.readShort() and 0xFFFF
            4 -> {
                val color = buffer.readUnsignedMedium()
                targetColor[0] = (color and 0xff0000) shl 4
                targetColor[1] = (color and 0xff00) shr 4
                targetColor[2] = (color and 0xff) shr 12
            }
        }
    }
}
