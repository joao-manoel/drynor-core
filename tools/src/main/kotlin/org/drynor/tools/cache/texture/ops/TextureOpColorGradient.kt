package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 10: Maps grayscale input to a color gradient.
 * Takes a monochrome input and maps it through a configurable color gradient.
 */
class TextureOpColorGradient : TextureOperation(1, false) {
    /** Gradient stops: [stopIndex][position, r, g, b] */
    private var gradientStops: Array<IntArray>? = null

    /** Precomputed gradient lookup table (257 entries of packed RGB) */
    private val gradientLut = IntArray(257)

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty) {
            val input = getChildMonochromeOutput(0, y)
            val outR = output[0]
            val outG = output[1]
            val outB = output[2]

            for (x in 0 until currentWidth) {
                var idx = input[x] shr 4
                idx = idx.coerceIn(0, 256)
                val color = gradientLut[idx]
                outR[x] = (color and 0xff0000) shr 12
                outG[x] = (color and 0xff00) shr 4
                outB[x] = (color and 0xff) shl 4
            }
        }

        return output
    }

    /**
     * Compute the gradient lookup table from gradient stops.
     */
    private fun computeGradientLut() {
        val stops = gradientStops
        if (stops == null || stops.isEmpty()) return

        for (i in 0..256) {
            val pos = i shl 4  // Position in 12-bit fixed-point

            // Find the gradient segment containing this position
            var stopIndex = 0
            for (s in stops.indices) {
                if (pos >= stops[s][0]) {
                    stopIndex = s + 1
                }
            }

            val r: Int
            val g: Int
            val b: Int

            if (stopIndex < stops.size) {
                val stop = stops[stopIndex]
                if (stopIndex > 0) {
                    // Interpolate between two stops
                    val prevStop = stops[stopIndex - 1]
                    val t = ((pos - prevStop[0]) shl 12) / (stop[0] - prevStop[0])
                    val invT = 4096 - t
                    r = (t * stop[1] + invT * prevStop[1]) shr 12
                    g = (t * stop[2] + invT * prevStop[2]) shr 12
                    b = (t * stop[3] + invT * prevStop[3]) shr 12
                } else {
                    // Before first stop
                    r = stop[1]
                    g = stop[2]
                    b = stop[3]
                }
            } else {
                // After last stop
                val lastStop = stops[stops.size - 1]
                r = lastStop[1]
                g = lastStop[2]
                b = lastStop[3]
            }

            // Pack into RGB (clamped)
            val rClamped = (r shr 4).coerceIn(0, 255)
            val gClamped = (g shr 4).coerceIn(0, 255)
            val bClamped = (b shr 4).coerceIn(0, 255)
            gradientLut[i] = (rClamped shl 16) or (gClamped shl 8) or bClamped
        }
    }

    /**
     * Initialize a preset gradient.
     */
    private fun initPresetGradient(preset: Int) {
        gradientStops = when (preset) {
            1 -> {
                // Black to white
                arrayOf(
                    intArrayOf(0, 0, 0, 0),
                    intArrayOf(4096, 4096, 4096, 4096)
                )
            }
            2 -> {
                // Wood grain
                arrayOf(
                    intArrayOf(0, 2650, 2602, 2361),
                    intArrayOf(2867, 2313, 1799, 1558),
                    intArrayOf(3072, 2618, 1734, 1413),
                    intArrayOf(3276, 2296, 1220, 947),
                    intArrayOf(3481, 2072, 963, 722),
                    intArrayOf(3686, 2730, 2152, 1766),
                    intArrayOf(3891, 2232, 1060, 915),
                    intArrayOf(4096, 1686, 1413, 1140)
                )
            }
            3 -> {
                // Rainbow
                arrayOf(
                    intArrayOf(0, 0, 0, 4096),
                    intArrayOf(663, 0, 4096, 4096),
                    intArrayOf(1363, 0, 4096, 0),
                    intArrayOf(2048, 4096, 4096, 0),
                    intArrayOf(2727, 4096, 0, 0),
                    intArrayOf(3411, 4096, 0, 4096),
                    intArrayOf(4096, 0, 0, 4096)
                )
            }
            4 -> {
                // Cold blue
                arrayOf(
                    intArrayOf(0, 0, 0, 0),
                    intArrayOf(1843, 0, 0, 1493),
                    intArrayOf(2457, 0, 0, 2939),
                    intArrayOf(2781, 0, 1124, 3565),
                    intArrayOf(3481, 546, 3084, 4031),
                    intArrayOf(4096, 4096, 4096, 4096)
                )
            }
            5 -> {
                // Terrain
                arrayOf(
                    intArrayOf(0, 80, 192, 321),
                    intArrayOf(155, 321, 449, 562),
                    intArrayOf(389, 578, 690, 803),
                    intArrayOf(671, 947, 995, 1140),
                    intArrayOf(897, 1285, 1397, 1509),
                    intArrayOf(1175, 1525, 1429, 1413),
                    intArrayOf(1368, 1734, 1461, 1333),
                    intArrayOf(1507, 1413, 1525, 1702),
                    intArrayOf(1736, 1108, 1590, 2056),
                    intArrayOf(2088, 1766, 2056, 2666),
                    intArrayOf(2355, 2409, 2586, 3276),
                    intArrayOf(2691, 3116, 3148, 3228),
                    intArrayOf(3031, 3806, 3710, 3196),
                    intArrayOf(3522, 3437, 3421, 3019),
                    intArrayOf(3727, 3116, 3148, 3228),
                    intArrayOf(4096, 2377, 2505, 2746)
                )
            }
            6 -> {
                // Fire
                arrayOf(
                    intArrayOf(2048, 0, 4096, 0),
                    intArrayOf(2867, 4096, 4096, 0),
                    intArrayOf(3276, 4096, 4096, 0),
                    intArrayOf(4096, 4096, 0, 0)
                )
            }
            else -> throw RuntimeException("Unknown gradient preset: $preset")
        }
    }

    override fun postDecode() {
        if (gradientStops == null) {
            initPresetGradient(1)
        }
        computeGradientLut()
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            val presetOrCount = buffer.readUnsignedByte()
            if (presetOrCount == 0) {
                // Custom gradient
                val stopCount = buffer.readUnsignedByte()
                gradientStops = Array(stopCount) { IntArray(4) }
                for (i in 0 until stopCount) {
                    gradientStops!![i][0] = buffer.readShort() and 0xFFFF
                    gradientStops!![i][1] = buffer.readUnsignedByte() shl 4
                    gradientStops!![i][2] = buffer.readUnsignedByte() shl 4
                    gradientStops!![i][3] = buffer.readUnsignedByte() shl 4
                }
            } else {
                // Preset gradient
                initPresetGradient(presetOrCount)
            }
        }
    }
}
