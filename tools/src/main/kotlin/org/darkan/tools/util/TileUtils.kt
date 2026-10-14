package org.darkan.tools.util

import kotlin.math.cos

/**
 * Utilities for tile height calculation matching the RS client exactly.
 *
 * The RS client uses procedural Perlin noise to generate terrain heights for tiles
 * that don't have explicit height values in the map data. This ensures consistent
 * terrain generation between the client and our renderer.
 *
 * Reference: darkan-bot/src/main/kotlin/com/jagex/game/runetek5/scene/TileUtils.kt
 */
object TileUtils {

    /**
     * Pre-computed cosine table for efficient interpolation.
     * The RS client uses 16384 entries with values scaled by 16384.
     *
     * Each entry i contains: (16384 * cos(i * step)).toInt()
     * where step = 3.834951969714103E-4 (= 2*PI / 16384)
     */
    private val COSINE = IntArray(16384)

    init {
        val step = 3.834951969714103E-4
        for (i in 0 until 16384) {
            COSINE[i] = (16384.0 * cos(i * step)).toInt()
        }
    }

    /**
     * Calculates tile height using Perlin noise.
     *
     * This is used when a tile has no explicit height value (opcode 0) in the map data.
     * The result is combined with plane offsets to get final height.
     *
     * The RS client adds magic offsets to the coordinates (932731, 556238) before calling
     * this function. Those offsets should be added by the caller if matching client behavior.
     *
     * @param x The absolute X coordinate (with magic offset if matching client)
     * @param y The absolute Y coordinate (with magic offset if matching client)
     * @return The calculated height value (10-60 range, typically multiplied by 32 for final height)
     */
    @JvmStatic
    fun calculateTileheight(x: Int, y: Int): Int {
        // Multi-octave Perlin noise:
        // - Octave 1: frequency 4, amplitude 1 (weight 1.0)
        // - Octave 2: frequency 2, amplitude 0.5 (weight 0.5)
        // - Octave 3: frequency 1, amplitude 0.25 (weight 0.25)
        var height = perlinNoise(45365 + x, y + 91923, 4) - 128 +
                (perlinNoise(x + 10294, 37821 + y, 2) - 128 shr 1) +
                (perlinNoise(x, y, 1) - 128 shr 2)

        // Scale and offset to get reasonable terrain height
        height = (height * 0.3).toInt() + 35

        // Clamp to valid range
        return when {
            height < 10 -> 10
            height > 60 -> 60
            else -> height
        }
    }

    /**
     * Generates Perlin noise at the specified coordinates.
     *
     * Uses bilinear interpolation between four neighboring noise values,
     * with cosine smoothing for natural-looking transitions.
     *
     * @param x The x coordinate
     * @param y The y coordinate
     * @param frequencyReciprocal The inverse of the noise frequency (higher = smoother)
     * @return The noise value (0-255 range)
     */
    @JvmStatic
    fun perlinNoise(x: Int, y: Int, frequencyReciprocal: Int): Int {
        val adjX = x / frequencyReciprocal
        val angleX = x and (frequencyReciprocal - 1)
        val adjY = y / frequencyReciprocal
        val angleY = y and (frequencyReciprocal - 1)

        // Get weighted noise values at the four corners
        val noiseBase = randomNoiseWeighedSum(adjX, adjY)
        val noiseEast = randomNoiseWeighedSum(adjX + 1, adjY)
        val noiseSouth = randomNoiseWeighedSum(adjX, adjY + 1)
        val noiseSouthEast = randomNoiseWeighedSum(adjX + 1, adjY + 1)

        // Bilinear interpolation with cosine smoothing
        val interpolatedNoiseNorth = interpolate(noiseBase, noiseEast, angleX, frequencyReciprocal)
        val interpolatedNoiseSouth = interpolate(noiseSouth, noiseSouthEast, angleX, frequencyReciprocal)
        return interpolate(interpolatedNoiseNorth, interpolatedNoiseSouth, angleY, frequencyReciprocal)
    }

    /**
     * Calculates weighted sum of random noise values from neighboring cells.
     *
     * Uses a 3x3 kernel with weights:
     * - Corners (4 cells): 1/16 each
     * - Sides (4 cells): 1/8 each
     * - Center (1 cell): 1/4
     * Total weight = 4*1/16 + 4*1/8 + 1/4 = 1/4 + 1/2 + 1/4 = 1.0
     *
     * @param x The x coordinate
     * @param y The y coordinate
     * @return The weighted noise sum (0-255 range)
     */
    @JvmStatic
    fun randomNoiseWeighedSum(x: Int, y: Int): Int {
        val corners = randomNoise(x - 1, y - 1) + randomNoise(1 + x, y - 1) +
                randomNoise(x - 1, 1 + y) + randomNoise(x + 1, 1 + y)
        val sides = randomNoise(x - 1, y) + randomNoise(1 + x, y) +
                randomNoise(x, y - 1) + randomNoise(x, y + 1)
        val center = randomNoise(x, y)
        return corners / 16 + sides / 8 + center / 4
    }

    /**
     * Generates pseudo-random noise for the given coordinates.
     *
     * Uses a deterministic hash function to generate consistent noise values
     * for any coordinate pair. The same (x, y) always returns the same value.
     *
     * @param x The x coordinate
     * @param y The y coordinate
     * @return The random noise value (0-255 range)
     */
    @JvmStatic
    fun randomNoise(x: Int, y: Int): Int {
        var n = y * 57 + x
        n = n xor (n shl 13)
        val value = n * (n * n * 15731 + 789221) + 1376312589 and 0x7fffffff
        return value shr 19 and 0xff
    }

    /**
     * Interpolates between two values using cosine interpolation.
     *
     * Cosine interpolation provides smoother transitions than linear interpolation,
     * which is important for natural-looking terrain.
     *
     * @param a The first value
     * @param b The second value
     * @param angle The interpolation position (0 to frequencyReciprocal)
     * @param frequencyReciprocal The frequency reciprocal (interpolation range)
     * @return The interpolated value
     */
    @JvmStatic
    fun interpolate(a: Int, b: Int, angle: Int, frequencyReciprocal: Int): Int {
        // Convert angle to cosine table index (0 to 8192 range)
        val cosineIndex = angle * 8192 / frequencyReciprocal
        // Get cosine value and convert from (-16384, 16384) to (0, 65536) range
        val cosine = (65536 - COSINE[cosineIndex]) shr 1
        // Weighted average using cosine as blend factor
        return ((65536 - cosine) * a shr 16) + (cosine * b shr 16)
    }

    /**
     * Default plane height offset.
     * When a tile on plane > 0 has no explicit height (opcode 0), this offset
     * is subtracted from the previous plane's height.
     *
     * 960 RS units = 30 tile heights (960 / 32)
     */
    const val DEFAULT_PLANE_HEIGHT_OFFSET = 960

    /**
     * Height multiplier for explicit height values.
     * When a tile has an explicit height byte, it's multiplied by this to get RS units.
     *
     * heightByte * 8 shl 2 = heightByte * 32
     */
    const val HEIGHT_MULTIPLIER = 32

    /**
     * Magic X offset added to absolute coordinates for procedural height generation.
     * From MapLoader.decodeTile: calculateTileheight(i_7 + 932731, i_8 + 556238)
     */
    const val MAGIC_X_OFFSET = 932731

    /**
     * Magic Y offset added to absolute coordinates for procedural height generation.
     */
    const val MAGIC_Y_OFFSET = 556238
}
