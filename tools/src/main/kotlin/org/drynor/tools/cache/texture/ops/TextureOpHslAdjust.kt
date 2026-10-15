package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.max
import kotlin.math.min

/**
 * Type 17: Adjusts HSL (Hue, Saturation, Lightness) values.
 */
class TextureOpHslAdjust : TextureOperation(1, false) {
    private var hueAdjust: Int = 0
    private var saturationAdjust: Int = 0
    private var lightnessAdjust: Int = 0

    // Working variables for HSL conversion
    private var hue: Int = 0
    private var saturation: Int = 0
    private var lightness: Int = 0
    private var outputRed: Int = 0
    private var outputGreen: Int = 0
    private var outputBlue: Int = 0

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val childOutput = getChildColorOutput(0, y)
            val inputRed = childOutput[0]
            val inputGreen = childOutput[1]
            val inputBlue = childOutput[2]
            val outR = output[0]
            val outG = output[1]
            val outB = output[2]

            for (x in 0 until currentWidth) {
                rgbToHsl(inputRed[x], inputGreen[x], inputBlue[x])

                hue += hueAdjust
                saturation += saturationAdjust
                lightness += lightnessAdjust

                while (hue < 0) hue += 4096
                while (hue > 4096) hue -= 4096
                if (saturation < 0) saturation = 0
                if (saturation > 4096) saturation = 4096
                if (lightness < 0) lightness = 0
                if (lightness > 4096) lightness = 4096

                hslToRgb(hue, saturation, lightness)
                outR[x] = outputRed
                outG[x] = outputGreen
                outB[x] = outputBlue
            }
        }
        return output
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int) {
        var maxVal = max(r, g)
        maxVal = max(b, maxVal)
        var minVal = min(r, g)
        minVal = min(b, minVal)
        val delta = maxVal - minVal
        lightness = (minVal + maxVal) / 2

        if (lightness > 0 && lightness < 4096) {
            saturation = (delta shl 12) / (if (lightness <= 2048) lightness * 2 else 8192 - lightness * 2)
        } else {
            saturation = 0
        }

        if (delta > 0) {
            val diffR = (maxVal - r shl 12) / delta
            val diffG = (maxVal - g shl 12) / delta
            val diffB = (maxVal - b shl 12) / delta

            hue = when {
                maxVal == r -> if (minVal == g) diffB + 20480 else 4096 - diffG
                maxVal == g -> if (b == minVal) diffR + 4096 else 12288 - diffB
                else -> if (minVal == r) diffG + 12288 else 20480 - diffR
            }

            hue /= 6
        } else {
            hue = 0
        }
    }

    private fun hslToRgb(h: Int, s: Int, l: Int) {
        val maxComponent = if (l <= 2048) l * (s + 4096) shr 12 else l + s - (l * s shr 12)

        if (maxComponent > 0) {
            val adjustedHue = h * 6
            val minComponent = l + l - maxComponent
            val range = (maxComponent - minComponent shl 12) / maxComponent
            val segment = adjustedHue shr 12
            val fractional = adjustedHue - (segment shl 12)
            var delta = range * maxComponent shr 12
            delta = fractional * delta shr 12
            val rising = minComponent + delta
            val falling = maxComponent - delta

            when (segment) {
                0 -> {
                    outputRed = maxComponent
                    outputGreen = rising
                    outputBlue = minComponent
                }
                1 -> {
                    outputRed = falling
                    outputGreen = maxComponent
                    outputBlue = minComponent
                }
                2 -> {
                    outputRed = minComponent
                    outputGreen = maxComponent
                    outputBlue = rising
                }
                3 -> {
                    outputRed = minComponent
                    outputGreen = falling
                    outputBlue = maxComponent
                }
                4 -> {
                    outputRed = rising
                    outputGreen = minComponent
                    outputBlue = maxComponent
                }
                5 -> {
                    outputRed = maxComponent
                    outputGreen = minComponent
                    outputBlue = falling
                }
            }
        } else {
            outputBlue = l
            outputRed = l
            outputGreen = l
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> hueAdjust = buffer.readShort()
            1 -> saturationAdjust = (buffer.readByte().toInt() shl 12) / 100
            2 -> lightnessAdjust = (buffer.readByte().toInt() shl 12) / 100
        }
    }
}
