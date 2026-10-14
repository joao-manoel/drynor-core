package org.darkan.tools.engine.render

import org.darkan.tools.cache.map.environment.MapEnvironment
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import kotlin.math.sqrt

/**
 * Live, editable copy of a region's [MapEnvironment], expressed in engine units and
 * shaped for direct upload into the `EnvironmentBlock` UBO.
 *
 * Seeded from the cache on every region change, then freely mutated by the Map Editor's
 * Environment panel. Nothing here writes back to the cache.
 *
 * The appearance contract lives in `docs/tools-environment-lighting.md`; the short version:
 *
 * ```
 * sunBrightness = (0.7 + brightnessPref * 0.1 + randomColourOffset) * sunAmbient
 * ambient       = sunBrightness * sunColour
 * sunDir        = normalize(sunPosition * 4)
 * lit           = ambient + max(ndl, 0) * sunLight * sunColour
 *                         - max(-ndl, 0) * sunBacklight * sunColour
 * ```
 *
 * The tools engine has no brightness preference and no per-map random offset, so
 * [SUN_BRIGHTNESS_BASE] is the client's plain 0.7 with both extra terms at zero.
 */
class EnvironmentSettings {

    /** Directional (sun) colour, packed 0xRRGGBB. */
    var sunColour: Int = MapEnvironment.DEFAULT_SUN_COLOUR

    /** Sun position in RS scene space, exactly as the cache stores it (Y is negative-up). */
    var sunPositionX: Float = MapEnvironment.DEFAULT_SUN_POSITION_X
    var sunPositionY: Float = MapEnvironment.DEFAULT_SUN_POSITION_Y
    var sunPositionZ: Float = MapEnvironment.DEFAULT_SUN_POSITION_Z

    /** Global ambient scale. */
    var sunAmbient: Float = MapEnvironment.DEFAULT_SUN_AMBIENT

    /** Key-light (diffuse) strength. */
    var sunLight: Float = MapEnvironment.DEFAULT_SUN_LIGHT

    /** Negative back-light strength: darkens surfaces facing away from the sun. */
    var sunBacklight: Float = MapEnvironment.DEFAULT_SUN_BACKLIGHT

    /** Fog colour, packed 0xRRGGBB. Doubles as the background when there is no skybox. */
    var fogColour: Int = MapEnvironment.DEFAULT_FOG_COLOUR

    /** Raw fog depth from the cache; the density the client uses is `(fogDepth + 256) shl 2`. */
    var fogDepth: Int = 0

    /** Skybox config id (Index.CONFIGS archive 29), or -1 for a flat [fogColour] background. */
    var skyboxId: Int = -1

    /** Skybox yaw offset in RS angle units (16384 per full turn). */
    var skyboxRotation: Int = 0

    /**
     * Master switch for distance fog, and an editor default rather than a cache value.
     *
     * The decoded cache fog is authentic but unusable to edit through: Canifis' fog colour is
     * an olive that reaches full opacity inside the render distance, so from the editor's
     * overhead camera the map washes out and the sky disappears. Nothing about the decoded
     * environment is discarded by this -- [fogColour], [fogDepth] and [fogDistanceMultiplier]
     * are still loaded, still shown and still editable in the Environment panel, and ticking
     * "Fog enabled" there (or launching with `-Ddarkan.fog=true`) puts the authentic
     * atmosphere straight back.
     */
    var fogEnabled: Boolean = System.getProperty("darkan.fog", "false").toBoolean()

    /**
     * Colour for the flat sky background and the clear colour.
     *
     * The fog colour only reads as a sky while fog is actually thickening the scene toward
     * it. With fog off it just paints a flat slab of the fog tint over the horizon, which is
     * what "the sky is gone" looks like, so the editor falls back to a neutral daylight sky.
     */
    val backgroundColour: Int
        get() = if (fogEnabled) fogColour else EDITOR_SKY_COLOUR

    /**
     * When true the scene meshes carry *unlit* colour and the shaders apply the sun.
     * When false the meshes carry lighting baked in at build time and the shaders pass
     * colour through untouched. Flipping this requires a terrain rebuild.
     *
     * `-Ddarkan.dynamicSun=false` starts on the legacy baked path, which is what the
     * before/after comparison screenshots were taken with.
     */
    var dynamicSunLighting: Boolean = System.getProperty("darkan.dynamicSun", "true").toBoolean()

    /**
     * The client's `Loader.FOG_DISTANCE_MULTIPLIER`, which scales the constant term of
     * `MapRegion.calculateViewRange()`. Fog always reaches full opacity at the view range,
     * so this is the knob that decides how much of the scene it eats.
     *
     * `1` is stock Jagex (~34 tiles of visibility, which fogs out everything but the
     * immediate foreground from the editor's overhead camera). The default matches the
     * reference client in `../darkan-bot`, which ships with `FOG_DISTANCE_MULTIPLIER = 10`
     * for ~88 tiles of visibility.
     */
    var fogDistanceMultiplier: Float = DEFAULT_FOG_DISTANCE_MULTIPLIER

    /** Camera near plane in engine units; the client clamps `fogStart` to it. */
    var nearClipDistance: Float = 1.0f

    /** True when this was seeded from a region that actually carries an environment block. */
    var fromCache: Boolean = false
        private set

    /** Copies the decoded cache values in, discarding any edits. */
    fun applyFrom(environment: MapEnvironment, fromCache: Boolean) {
        sunColour = environment.sunColour
        sunPositionX = environment.sunPositionX
        sunPositionY = environment.sunPositionY
        sunPositionZ = environment.sunPositionZ
        sunAmbient = environment.sunAmbient
        sunLight = environment.sunLight
        sunBacklight = environment.sunBacklight
        fogColour = environment.fogColour
        fogDepth = environment.fogDepth
        skyboxId = environment.skybox?.id ?: -1
        skyboxRotation = environment.skybox?.rotation ?: 0
        this.fromCache = fromCache
    }

    /**
     * Unit vector pointing *at* the sun, in engine space.
     *
     * The client uploads `sunPosition shl 2` as a directional light position, so only the
     * direction matters and the shift drops out of the normalisation. RS scene space has Y
     * negative-up and Z running north, engine space has Y up and Z running south, so both
     * are negated (the same conversion [CoordinateUtil.rsToOpenGL] applies to positions).
     */
    fun sunDirection(dest: Vector3f): Vector3f {
        val x = sunPositionX
        val y = -sunPositionY
        val z = -sunPositionZ
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1e-6f) {
            return dest.set(0f, 1f, 0f)
        }
        return dest.set(x / length, y / length, z / length)
    }

    /** Ambient term the client feeds to `GL_LIGHT_MODEL_AMBIENT`, before the sun colour. */
    val sunBrightness: Float
        get() = SUN_BRIGHTNESS_BASE * sunAmbient

    /** `(fogDepth + 256) shl 2`, converted from RS scene units into engine units. */
    val fogDensityUnits: Float
        get() = ((fogDepth + 256) shl 2) * RS_TO_ENGINE

    /** The client's `viewRangeEnd` for the current [fogDistanceMultiplier], in RS units. */
    val viewRangeEndRs: Float
        get() = CLIENT_VIEW_RANGE_BASE + CLIENT_FOG_DISTANCE_STEP * fogDistanceMultiplier

    /** Distance at which fog is fully opaque, in engine units. */
    val fogEndUnits: Float
        get() = viewRangeEndRs * RS_TO_ENGINE

    /** Distance at which fog starts, in engine units. Clamped to the near plane like the client. */
    val fogStartUnits: Float
        get() {
            val start = fogEndUnits - fogDensityUnits * DENSITY_FACTOR
            return if (start < nearClipDistance) nearClipDistance else start
        }

    /**
     * Packs the block into [out] in std140 order. [out] must hold [STD140_FLOATS] floats.
     *
     * Layout (five vec4s, 80 bytes):
     * 0. `sunDirection.xyz`, w unused
     * 1. `sunColour.rgb`, w unused
     * 2. `x` ambient, `y` sunLight, `z` sunBacklight, `w` 1 when the shaders should light
     * 3. `fogColour.rgb`, `w` 1 when fog is on
     * 4. `x` fogStart, `y` fogEnd, `z` 1 / (fogEnd - fogStart), w unused
     */
    fun writeStd140(out: FloatArray, direction: Vector3f) {
        sunDirection(direction)
        out[0] = direction.x
        out[1] = direction.y
        out[2] = direction.z
        out[3] = 0f

        out[4] = red(sunColour)
        out[5] = green(sunColour)
        out[6] = blue(sunColour)
        out[7] = 0f

        out[8] = sunBrightness
        out[9] = sunLight
        out[10] = sunBacklight
        out[11] = if (dynamicSunLighting) 1f else 0f

        out[12] = red(fogColour)
        out[13] = green(fogColour)
        out[14] = blue(fogColour)
        out[15] = if (fogEnabled) 1f else 0f

        val start = fogStartUnits
        val end = fogEndUnits
        val span = end - start
        out[16] = start
        out[17] = end
        out[18] = if (span > 1e-4f) 1f / span else 0f
        out[19] = 0f
    }

    companion object {
        /** Floats in the std140 `EnvironmentBlock` (5 vec4). */
        const val STD140_FLOATS = 20

        /**
         * The client's `(0.7 + brightnessPref * 0.1 + randomColourOffset)`. The tools engine
         * exposes neither the brightness preference nor the per-map random offset, so both
         * extra terms are zero and only the 0.7 base remains.
         */
        const val SUN_BRIGHTNESS_BASE = 0.7f

        /** Scene default `densityFactor`; only the rippling-water effect changes it (to 0.5). */
        const val DENSITY_FACTOR = 1.0f

        /**
         * Neutral daylight sky the editor falls back to while fog is off. Not a cache value:
         * it is the viewer's long-standing background, kept so switching fog off gives a sky
         * rather than a flat slab of fog tint.
         */
        const val EDITOR_SKY_COLOUR = 0x6699CC

        /**
         * `MapRegion.calculateViewRange()`'s scene term with the stock scene size of
         * 104 tiles: `(34.46 * 104).toInt() shl 2` = 14332 RS scene units.
         */
        const val CLIENT_VIEW_RANGE_BASE = 14332f

        /** The `3072 * FOG_DISTANCE_MULTIPLIER` term of `MapRegion.calculateViewRange()`. */
        const val CLIENT_FOG_DISTANCE_STEP = 3072f

        /**
         * `Loader.FOG_DISTANCE_MULTIPLIER` as the reference client in `../darkan-bot` ships
         * it. Jagex stock is 1; the reference build raises it to 10 for ~88 tiles of
         * visibility, and the tools viewer's overhead camera needs at least that much.
         */
        const val DEFAULT_FOG_DISTANCE_MULTIPLIER = 10f

        /**
         * RS scene units to engine world units. One tile is 512 RS units and
         * `RegionManager.TILE_SIZE` engine units, and `TILE_SIZE = 512 * DEFAULT_SCALE`,
         * so the ratio is exactly [CoordinateUtil.DEFAULT_SCALE].
         */
        const val RS_TO_ENGINE = CoordinateUtil.DEFAULT_SCALE

        fun red(packed: Int): Float = ((packed shr 16) and 0xFF) / 255f
        fun green(packed: Int): Float = ((packed shr 8) and 0xFF) / 255f
        fun blue(packed: Int): Float = (packed and 0xFF) / 255f

        fun pack(r: Float, g: Float, b: Float): Int {
            val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (ri shl 16) or (gi shl 8) or bi
        }
    }
}
