package org.darkan.tools.util

import org.joml.Vector3f
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

object ColorUtil {
    /** Brightness/gamma value used by RS (approximately 0.7, with slight randomness in original) */
    private const val BRIGHTNESS = 0.7

    /**
     * Generate deterministic noise based on world position for terrain color variation.
     * This matches the RS client's HSL-to-RGB conversion which adds +-1.5% random brightness:
     *   val d_1 = 0.7 + (Math.random() * 0.03 - 0.015)
     *
     * We use deterministic hash-based noise so the terrain doesn't shimmer when reloaded
     * or when the camera moves.
     *
     * @param worldX World X position (can be tile index or world coordinate)
     * @param worldZ World Z position (can be tile index or world coordinate)
     * @param variation Amount of variation (0.03 = +-1.5%, 0.06 = +-3%)
     * @return Noise factor centered at 1.0, e.g. 0.97-1.03 for +-3%
     */
    fun getVertexNoise(worldX: Float, worldZ: Float, variation: Float = 0.03f): Float {
        // Use a simple hash combining both coordinates for pseudo-random but deterministic noise
        // The magic numbers create good distribution without obvious patterns
        val seed = (worldX * 12.9898 + worldZ * 78.233)
        val hash = sin(seed) * 43758.5453
        // Get fractional part for 0-1 range
        val noise = (hash - floor(hash)).toFloat()
        // Convert to centered variation: [0,1] -> [1-variation/2, 1+variation/2]
        return 1.0f - variation * 0.5f + noise * variation
    }

    /**
     * Apply brightness noise to a packed HSL color.
     * Modifies the lightness component while preserving hue and saturation.
     *
     * @param packedHsl Packed HSL color (16-bit RS format)
     * @param noiseFactor Brightness multiplier (e.g., 0.97 to 1.03)
     * @return Modified packed HSL color
     */
    fun applyBrightnessNoise(packedHsl: Int, noiseFactor: Float): Int {
        if (packedHsl == 0) return 0

        // Extract HSL components
        val hue = packedHsl and 0xfc00        // Bits 10-15 (masked to keep position)
        val saturation = packedHsl and 0x380  // Bits 7-9 (masked to keep position)
        val lightness = packedHsl and 0x7f    // Bits 0-6 (0-127 range)

        // Apply noise to lightness
        var newLightness = (lightness * noiseFactor).toInt()

        // Clamp to valid range (2-126 to avoid pure black/white)
        if (newLightness < 2) {
            newLightness = 2
        } else if (newLightness > 126) {
            newLightness = 126
        }

        // Repack HSL with modified lightness
        return hue or saturation or newLightness
    }

    /**
     * Convert RuneScape packed HSL color (16-bit) to RGB.
     * RS HSL format: 6 bits hue (0-63) at bits 10-15, 3 bits saturation (0-7) at bits 7-9, 7 bits lightness (0-127) at bits 0-6
     *
     * Uses the EXACT algorithm from the RS client's calculateHslToRgbTable() function.
     * This is TRUE HSL (Hue-Saturation-Lightness), NOT HSV!
     *
     * The RS client code (from ColorUtil.kt lines 140-206):
     * - Hue: (hsl >> 10 & 0x3f) / 64.0 + 0.0078125
     * - Saturation: 0.0625 + (hsl >> 7 & 0x7) / 8.0
     * - Lightness: (hsl & 0x7f) / 128.0
     * - Standard HSL-to-RGB conversion with q = l*(1+s) if l<0.5, else q = l+s-l*s
     * - Applies gamma correction with brightness ~0.7
     */
    fun hslToRgb(hsl: Int): Vector3f {
        // A packed HSL is 16 bits, so the conversion has only 65536 possible answers and
        // is a pure function of its argument -- which is exactly why the client bakes it
        // into a table (calculateHslToRgbTable) instead of converting per vertex. Terrain
        // tessellation calls this once per ground vertex and the conversion ends in three
        // Math.pow gamma corrections, so memoising it removes the single most expensive
        // thing on that path while returning bit-identical colours.
        //
        // Entries are published through an atomic array: two threads racing on the same
        // colour compute the same floats, and the CAS makes sure a reader never sees a
        // half-written entry. Callers mutate the vector they get back (the shoreline
        // composite lerps in place), so each call still hands out its own instance.
        if (hsl in 0 until HSL_TABLE_SIZE) {
            var entry = hslRgbTable.get(hsl)
            if (entry == null) {
                entry = computeHslToRgb(hsl)
                hslRgbTable.compareAndSet(hsl, null, entry)
            }
            return Vector3f(entry[0], entry[1], entry[2])
        }
        val rgb = computeHslToRgb(hsl)
        return Vector3f(rgb[0], rgb[1], rgb[2])
    }

    /** Number of distinct packed HSL values, i.e. the size of the memo table. */
    private const val HSL_TABLE_SIZE = 1 shl 16

    /** Lazily filled memo for [hslToRgb]; see the note there for the publication rules. */
    private val hslRgbTable = AtomicReferenceArray<FloatArray>(HSL_TABLE_SIZE)

    /**
     * [hslToRgb] with the memo bypassed. Only exists so a caller can A/B the memo's
     * contribution inside one build; it returns exactly the same colour.
     */
    fun hslToRgbUncached(hsl: Int): Vector3f {
        val rgb = computeHslToRgb(hsl)
        return Vector3f(rgb[0], rgb[1], rgb[2])
    }

    /** The actual conversion behind [hslToRgb], as [r, g, b]. */
    private fun computeHslToRgb(hsl: Int): FloatArray {
        // Extract components matching client's bit layout (EXACTLY as in calculateHslToRgbTable)
        val hue = (hsl shr 10 and 0x3f) / 64.0 + 0.0078125  // d_4 in client
        val saturation = 0.0625 + (hsl shr 7 and 0x7) / 8.0  // d_6 in client
        val lightness = (hsl and 0x7f) / 128.0               // d_8 in client

        // Initialize RGB to lightness (achromatic case)
        var r = lightness  // d_10 in client
        var g = lightness  // d_12 in client
        var b = lightness  // d_14 in client

        // Only apply hue/saturation if saturation > 0
        if (saturation != 0.0) {
            // Calculate q (d_16 in client): the "chroma" factor
            val q: Double = if (lightness < 0.5) {
                lightness * (saturation + 1.0)
            } else {
                saturation + lightness - lightness * saturation
            }

            // Calculate p (d_18 in client): the other bound
            val p = lightness * 2.0 - q

            // Calculate red (hue + 1/3)
            var hueR = 0.3333333333333333 + hue
            if (hueR > 1.0) hueR -= 1.0

            // Calculate blue (hue - 1/3)
            var hueB = hue - 0.3333333333333333
            if (hueB < 0.0) hueB += 1.0

            // Convert hue to RGB using standard formula (EXACT RS client algorithm)
            // Red channel
            r = if (hueR * 6.0 < 1.0) {
                p + (q - p) * 6.0 * hueR
            } else if (2.0 * hueR < 1.0) {
                q
            } else if (3.0 * hueR < 2.0) {
                p + 6.0 * (0.6666666666666666 - hueR) * (q - p)
            } else {
                p
            }

            // Green channel (uses raw hue)
            g = if (6.0 * hue < 1.0) {
                p + 6.0 * (q - p) * hue
            } else if (hue * 2.0 < 1.0) {
                q
            } else if (3.0 * hue < 2.0) {
                6.0 * (0.6666666666666666 - hue) * (q - p) + p
            } else {
                p
            }

            // Blue channel
            b = if (hueB * 6.0 < 1.0) {
                hueB * 6.0 * (q - p) + p
            } else if (hueB * 2.0 < 1.0) {
                q
            } else if (hueB * 3.0 < 2.0) {
                6.0 * (0.6666666666666666 - hueB) * (q - p) + p
            } else {
                p
            }
        }

        // Apply gamma correction (brightness ~0.7, with small random variation in RS client)
        // RS client: d_1 = 0.7 + (Math.random() * 0.03 - 0.015)
        // We use fixed 0.7 for consistency
        val rGamma = r.pow(BRIGHTNESS).toFloat()
        val gGamma = g.pow(BRIGHTNESS).toFloat()
        val bGamma = b.pow(BRIGHTNESS).toFloat()

        return floatArrayOf(rGamma, gGamma, bGamma)
    }

    /**
     * OLD HSV-based conversion (WRONG for terrain, but kept for comparison/debugging).
     * This was what we were using before - terrain uses TRUE HSL, not HSV!
     */
    fun hsvToRgb(hsl: Int): Vector3f {
        // Extract components matching client's bit layout
        val hueRaw = (hsl shr 10) and 0x3f    // 6 bits at position 10 (0-63)
        val satRaw = (hsl shr 7) and 0x7      // 3 bits at position 7 (0-7)
        val valueRaw = hsl and 0x7f           // 7 bits at position 0 (0-127)

        // HSV conversion parameters
        val hueDegrees = 360.0f * (hueRaw / 64.0f + 0.0078125f)
        val saturation = satRaw / 8.0f + 0.0625f
        val value = valueRaw / 128.0f

        // HSV to RGB conversion
        var r = 0.0f
        var g = 0.0f
        var b = 0.0f

        val hueNorm = hueDegrees / 60.0f
        val hueSector = hueNorm.toInt()
        val hueFraction = hueNorm - hueSector
        val p = value * (1.0f - saturation)
        val q = value * (1.0f - saturation * hueFraction)
        val t = (1.0f - saturation * (1.0f - hueFraction)) * value

        when (hueSector % 6) {
            0 -> { r = value; g = t; b = p }
            1 -> { r = q; g = value; b = p }
            2 -> { r = p; g = value; b = t }
            3 -> { r = p; g = q; b = value }
            4 -> { r = t; g = p; b = value }
            5 -> { r = value; g = p; b = q }
        }

        // Apply gamma correction
        val rGamma = r.toDouble().pow(BRIGHTNESS).toFloat()
        val gGamma = g.toDouble().pow(BRIGHTNESS).toFloat()
        val bGamma = b.toDouble().pow(BRIGHTNESS).toFloat()

        return Vector3f(rGamma, gGamma, bGamma)
    }

    /**
     * Pack raw HSL values (0-255 range) into RS 16-bit HSL format.
     * This matches the client's hsl16to24() function used for underlay blending.
     *
     * @param hue Hue value (0-255, typically hueSum * 256 / divisorSum)
     * @param saturation Saturation value (0-255)
     * @param lightness Lightness value (0-255)
     */
    fun packHsl16(hue: Int, saturation: Int, lightness: Int): Int {
        var sat = saturation
        // Apply saturation reduction at high lightness (same as client)
        when {
            lightness > 243 -> sat = sat shr 4
            lightness > 217 -> sat = sat shr 3
            lightness > 192 -> sat = sat shr 2
            lightness > 179 -> sat = sat shr 1
        }
        // Pack into RS HSL format: hue(6 bits) << 10 | sat(3 bits) << 7 | lightness(7 bits)
        return ((hue and 0xff) shr 2 shl 10) + (lightness shr 1) + (sat shr 5 shl 7)
    }

    /**
     * Convert HSL to RGB (standard, without gamma).
     * @param h Hue [0-1]
     * @param s Saturation [0-1]
     * @param l Lightness [0-1]
     */
    fun hslToRgb(h: Float, s: Float, l: Float): Vector3f {
        val r: Float
        val g: Float
        val b: Float

        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hueToRgb(p, q, h + 1f / 3f)
            g = hueToRgb(p, q, h)
            b = hueToRgb(p, q, h - 1f / 3f)
        }

        return Vector3f(r, g, b)
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var hue = t
        if (hue < 0f) hue += 1f
        if (hue > 1f) hue -= 1f
        return when {
            hue < 1f / 6f -> p + (q - p) * 6f * hue
            hue < 1f / 2f -> q
            hue < 2f / 3f -> p + (q - p) * (2f / 3f - hue) * 6f
            else -> p
        }
    }

    /**
     * Convert RS packed RGB (24-bit) to Vector3f.
     */
    fun rgbToVector(rgb: Int): Vector3f {
        val r = (rgb shr 16 and 0xff) / 255f
        val g = (rgb shr 8 and 0xff) / 255f
        val b = (rgb and 0xff) / 255f
        return Vector3f(r, g, b)
    }

    /**
     * Convert Vector3f RGB to packed RGB (24-bit).
     */
    fun vectorToRgb(color: Vector3f): Int {
        val r = (color.x * 255).toInt().coerceIn(0, 255)
        val g = (color.y * 255).toInt().coerceIn(0, 255)
        val b = (color.z * 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Blend (modulate) the lightness component of a packed RS HSL color by a factor.
     * This matches the RS client's ModelGL.blendColorLightness() exactly.
     *
     * In the RS client, every model face color is passed through this function with the
     * model's ambient value (default 64, adjusted by ObjectDefinition.ambient). The formula
     * multiplies the 7-bit lightness by the factor and right-shifts by 7, effectively
     * scaling lightness by factor/128.
     *
     * For example, with the default ambient of 64:
     *   lightness 63 -> 63 * 64 >> 7 = 31 (halved)
     *
     * With object ambient=25 (adjustedAmbient = 64+25 = 89):
     *   lightness 63 -> 63 * 89 >> 7 = 43
     *
     * @param hslColor Packed 16-bit RS HSL color
     * @param lightnessFactor Ambient factor (typically 64 + objectDef.ambient)
     * @return Modified packed HSL color with blended lightness
     */
    fun blendColorLightness(hslColor: Int, lightnessFactor: Int): Int {
        var blendedLightness = (hslColor and 0x7f) * lightnessFactor shr 7
        if (blendedLightness < 2) {
            blendedLightness = 2
        } else if (blendedLightness > 126) {
            blendedLightness = 126
        }
        return (hslColor and 0xff80) + blendedLightness
    }

    /**
     * Adjust brightness of a color.
     */
    fun adjustBrightness(color: Vector3f, brightness: Int): Vector3f {
        val factor = 1f + brightness / 512f
        return Vector3f(
            (color.x * factor).coerceIn(0f, 1f),
            (color.y * factor).coerceIn(0f, 1f),
            (color.z * factor).coerceIn(0f, 1f)
        )
    }

    /**
     * Lerp between two colors.
     */
    fun lerp(a: Vector3f, b: Vector3f, t: Float): Vector3f {
        return Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }

    /**
     * Blend two packed HSL colors in HSL space.
     * Matches the RS client's ColorUtil.blend() function exactly.
     *
     * @param colorA First packed HSL color
     * @param colorB Second packed HSL color
     * @param factor Blend factor (0-128, where 0=colorA, 128=colorB)
     * @return Blended packed HSL color
     */
    fun blendHsl(colorA: Int, colorB: Int, factor: Int): Int {
        if (colorA == colorB) return colorA

        val invFactor = 128 - factor
        // Blend each component separately in packed format
        val lightness = (invFactor * (colorA and 0x7f) + (colorB and 0x7f) * factor) shr 7
        val saturation = (invFactor * (colorA and 0x380) + factor * (colorB and 0x380)) shr 7
        val hue = ((colorB and 0xfc00) * factor + invFactor * (colorA and 0xfc00)) shr 7
        return (hue and 0xfc00) or (saturation and 0x380) or (lightness and 0x7f)
    }

    /**
     * Bilinearly interpolate 4 packed HSL corner colors.
     * Used for terrain vertex color blending.
     *
     * @param sw Southwest corner packed HSL
     * @param se Southeast corner packed HSL
     * @param nw Northwest corner packed HSL
     * @param ne Northeast corner packed HSL
     * @param fx X interpolation factor (0-1)
     * @param fy Y interpolation factor (0-1)
     * @return Interpolated packed HSL color
     */
    fun bilinearBlendHsl(sw: Int, se: Int, nw: Int, ne: Int, fx: Float, fy: Float): Int {
        // Convert to 0-128 range for blend function
        val factorX = (fx * 128).toInt().coerceIn(0, 128)
        val factorY = (fy * 128).toInt().coerceIn(0, 128)

        // Blend along X axis first
        val south = blendHsl(sw, se, factorX)
        val north = blendHsl(nw, ne, factorX)

        // Then blend along Y axis
        return blendHsl(south, north, factorY)
    }

    /**
     * Apply terrain lighting to a packed HSL color.
     * This matches the RS client's lighting algorithm from GroundGL.resetLight().
     *
     * The RS client calculates a light strength value (0-74 typically) based on:
     * 1. Base strength of 74
     * 2. Subtracted by shadow intensity at this vertex
     * 3. Then: lightness = lightStrength * (originalLightness) >> 7
     *
     * For HD mode, it also applies directional lighting:
     * - Computes dot(normal, sunDirection)
     * - Multiplies final RGB by this factor
     *
     * This function provides a simplified version that modifies lightness based
     * on the dot product between the surface normal and light direction.
     *
     * @param packedHsl The packed HSL color (16-bit RS format)
     * @param lightFactor Light intensity factor (0.0 = dark, 1.0 = full, >1.0 = brighter)
     * @return Modified packed HSL color with adjusted lightness
     */
    fun applyLightToHsl(packedHsl: Int, lightFactor: Float): Int {
        if (packedHsl == 0) return 0

        // Extract HSL components
        val hue = packedHsl and 0xfc00        // Bits 10-15 (masked to keep position)
        val saturation = packedHsl and 0x380  // Bits 7-9 (masked to keep position)
        val lightness = packedHsl and 0x7f    // Bits 0-6 (0-127 range)

        // Apply light factor to lightness
        // RS client uses: lightness = lightStrength * (color.lightness) >> 7
        // where lightStrength is typically 74 (base) minus shadow intensity
        // So lightStrength ranges roughly from 0 to 74
        // This means the multiplier is roughly 0.0 to 0.58
        //
        // We scale lightFactor to match: 0.5 = ambient, 1.0 = full light
        // newLightness = lightness * lightFactor, clamped to [2, 126]
        var newLightness = (lightness * lightFactor).toInt()

        // Clamp to valid range (RS client uses 2-126 to avoid pure black/white)
        if (newLightness < 2) {
            newLightness = 2
        } else if (newLightness > 126) {
            newLightness = 126
        }

        // Repack HSL with modified lightness
        return hue or saturation or newLightness
    }

    /**
     * Calculate terrain lighting factor based on surface normal and light direction.
     * This matches the RS client's per-vertex lighting calculation EXACTLY.
     *
     * The RS client (GroundGL.kt lines 98-107) computes normals from height gradients:
     * - lightingX = heightDeltaHorizontal * intensity
     * - lightingY = (-512 * 2) * intensity (points downward in RS coords)
     * - lightingZ = heightDeltaVertical * intensity
     *
     * Then applies directional lighting (lines 453-456):
     * - dot = sunDirection.x * normalX + sunDirection.y * normalY + sunDirection.z * normalZ
     * - factor = sunBrightness + dot * (dot > 0 ? brightFactor : darkFactor)
     *
     * RS default lighting parameters (from EnvironmentManager.kt line 160):
     * - Sun position: (-200, -240, -200) -> normalized: (-0.53, -0.64, -0.53)
     * - sunBrightness: ~0.9 (from 0.7 + brightness*0.1 + randomOffset)
     * - brightFactor (aFloat8433): 0.69921875 (light-facing surfaces)
     * - darkFactor (aFloat8434): 1.2 (shadow-facing surfaces)
     *
     * @param normalX Surface normal X component
     * @param normalY Surface normal Y component (positive = up in OpenGL)
     * @param normalZ Surface normal Z component
     * @param lightDirX Light direction X (RS default: -0.53)
     * @param lightDirY Light direction Y (RS default: -0.64, pointing down toward ground)
     * @param lightDirZ Light direction Z (RS default: -0.53)
     * @param sunBrightness Base ambient brightness (RS default: ~0.9)
     * @param brightFactor Factor for lit surfaces (RS default: 0.7)
     * @param darkFactor Factor for shadowed surfaces (RS default: 1.2)
     * @return Light factor to apply to lightness
     */
    fun calculateTerrainLightFactor(
        normalX: Float, normalY: Float, normalZ: Float,
        lightDirX: Float = -0.53f, lightDirY: Float = -0.64f, lightDirZ: Float = -0.53f,
        sunBrightness: Float = 0.9f,
        brightFactor: Float = 0.7f,
        darkFactor: Float = 1.2f
    ): Float {
        // Dot product between normal and light direction
        // In OpenGL: normal.Y is up, light direction should point toward the surface
        // RS uses negative Y for the sun direction (sun above, shining down)
        val dot = normalX * lightDirX + normalY * lightDirY + normalZ * lightDirZ

        // RS client formula: factor = sunBrightness + dot * (dot > 0 ? brightFactor : darkFactor)
        // When dot > 0: surface faces toward light -> brighter
        // When dot < 0: surface faces away from light -> darker
        // The asymmetric factors create more contrast: shadows are darker than highlights are bright
        val factor = if (dot > 0f) {
            sunBrightness + dot * brightFactor
        } else {
            sunBrightness + dot * darkFactor
        }

        // Clamp to reasonable range to avoid over-bright or too-dark
        return factor.coerceIn(0.4f, 1.3f)
    }

    /**
     * Calculate terrain lighting with separate parameters for more control.
     * This version matches the older API signature for backward compatibility.
     *
     * @param normalX Surface normal X component
     * @param normalY Surface normal Y component
     * @param normalZ Surface normal Z component
     * @param lightDirX Light direction X (default: RS-style -0.53)
     * @param lightDirY Light direction Y (default: RS-style -0.64)
     * @param lightDirZ Light direction Z (default: RS-style -0.53)
     * @param ambientFactor Minimum light factor (maps to sunBrightness)
     * @param diffuseStrength Diffuse strength (maps to brightFactor)
     * @return Light factor to apply to lightness
     */
    fun calculateTerrainLightFactorSimple(
        normalX: Float, normalY: Float, normalZ: Float,
        lightDirX: Float, lightDirY: Float, lightDirZ: Float,
        ambientFactor: Float,
        diffuseStrength: Float
    ): Float {
        // This version provides a simpler ambient+diffuse model
        // while still using the correct RS-style light direction
        val dot = normalX * lightDirX + normalY * lightDirY + normalZ * lightDirZ

        // For the simple model: ambient + max(0, dot) * diffuse
        // This gives a gentler transition than the RS formula
        val diffuse = if (dot > 0) dot * diffuseStrength else dot * diffuseStrength * 0.5f

        return (ambientFactor + diffuse).coerceIn(0.4f, 1.2f)
    }
}
