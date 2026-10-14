package org.darkan.tools.cache.map.environment

import org.joml.Vector3f

/**
 * Immutable environment/lighting data decoded from the per-region effects block of a
 * `m{regionX}_{regionY}` map archive (Index.MAPS).
 *
 * All values mirror the reference client's `Environment` (runetek5 scene/environment),
 * but only the *appearance* is emulated - none of its mutable global state or LRU caches.
 *
 * Colours are packed 0xRRGGBB ints (the cache stores them as plain 24-bit RGB).
 */
data class MapEnvironment(
    /** Directional (sun) colour, packed 0xRRGGBB. */
    val sunColour: Int,
    /** Sun position/direction X. The client multiplies these by 4 before uploading. */
    val sunPositionX: Float,
    val sunPositionY: Float,
    val sunPositionZ: Float,
    /** Ambient term, cache value / 256. */
    val sunAmbient: Float,
    /** Diffuse term, cache value / 256. */
    val sunLight: Float,
    /** Backlight (rim) term, cache value / 256. */
    val sunBacklight: Float,
    /** Fog colour, packed 0xRRGGBB. */
    val fogColour: Int,
    /** Raw fog depth. See [fogDensity] for the value the client actually uses. */
    val fogDepth: Int,
    /** Six material ids forming the sun/reflection cube map, or null when the map uses the scene default. */
    val cubeTexture: CubeTextureRef?,
    /** HDR bloom strength, cache value * 8 / 255. */
    val bloom: Float,
    /** HDR bright-pass threshold, cache value * 8 / 255. */
    val brightpass: Float,
    /** HDR white point, cache value * 8 / 255. */
    val whitePoint: Float,
    /** Skybox reference, or null when the map does not override the skybox. */
    val skybox: SkyboxRef?,
) {

    /** Writes the sun position into [dest] and returns it. */
    fun sunPosition(dest: Vector3f): Vector3f = dest.set(sunPositionX, sunPositionY, sunPositionZ)

    /**
     * Fog density as the client computes it in `EnvironmentManager.calculateFog`:
     * `(fogDepth + 256) shl 2`.
     */
    val fogDensity: Int
        get() = (fogDepth + 256) shl 2

    companion object {
        const val DEFAULT_SUN_COLOUR = 16777215
        const val DEFAULT_FOG_COLOUR = 13156520

        const val DEFAULT_SUN_POSITION_X = -50.0f
        const val DEFAULT_SUN_POSITION_Y = -60.0f
        const val DEFAULT_SUN_POSITION_Z = -50.0f

        const val DEFAULT_SUN_AMBIENT = 1.1523438f
        const val DEFAULT_SUN_LIGHT = 0.69921875f
        const val DEFAULT_SUN_BACKLIGHT = 1.2f

        const val DEFAULT_BLOOM = 1.0f
        const val DEFAULT_BRIGHTPASS = 0.25f
        const val DEFAULT_WHITE_POINT = 1.0f

        /**
         * The client's `Environment.reset()` state - used for chunks with no environment block
         * and as the starting point when an HDR/skybox opcode arrives before opcode 0.
         */
        val DEFAULT = MapEnvironment(
            sunColour = DEFAULT_SUN_COLOUR,
            sunPositionX = DEFAULT_SUN_POSITION_X,
            sunPositionY = DEFAULT_SUN_POSITION_Y,
            sunPositionZ = DEFAULT_SUN_POSITION_Z,
            sunAmbient = DEFAULT_SUN_AMBIENT,
            sunLight = DEFAULT_SUN_LIGHT,
            sunBacklight = DEFAULT_SUN_BACKLIGHT,
            fogColour = DEFAULT_FOG_COLOUR,
            fogDepth = 0,
            cubeTexture = null,
            bloom = DEFAULT_BLOOM,
            brightpass = DEFAULT_BRIGHTPASS,
            whitePoint = DEFAULT_WHITE_POINT,
            skybox = null,
        )
    }
}

/** Six material (texture) ids making up an environment cube map. */
data class CubeTextureRef(
    val positiveX: Int,
    val negativeX: Int,
    val positiveY: Int,
    val negativeY: Int,
    val positiveZ: Int,
    val negativeZ: Int,
)

/**
 * Skybox placement decoded from opcode 128.
 *
 * [id] indexes the skybox config list (Index.CONFIGS archive 29); [x]/[y]/[z] are the
 * signed world-space anchor the client feeds to each [SunType] projection, and [rotation]
 * is an unsigned 16-bit angle.
 */
data class SkyboxRef(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int,
)

/**
 * Animated flicker parameters for a point light.
 *
 * Either derived from the light's packed type (`type` 0..30) or, when the type is 31,
 * loaded from the light config list (Index.CONFIGS archive 31).
 */
data class LightFlicker(
    /** Waveform selector: 0 none, 1 sine, 2 saw, 3 noise, 4 stepped, 5 triangle. */
    val effect: Int,
    /** Cycle length. */
    val duration: Int,
    /** Amplitude scale, 2048 == full. */
    val ticker: Int,
    /** Constant offset added to the modulated amplitude. */
    val surrounding: Int,
) {
    companion object {
        val NONE = LightFlicker(effect = 0, duration = 2048, ticker = 2048, surrounding = 0)

        /**
         * Mirror of `FlickeringEffect.updateConstants()` - maps a packed light type to its
         * flicker constants. Type 31 means "look the parameters up in the light config list".
         */
        fun forType(type: Int): LightFlicker = when (type) {
            2 -> LightFlicker(1, 2048, 2048, 0)
            3 -> LightFlicker(1, 4096, 2048, 0)
            4 -> LightFlicker(4, 2048, 2048, 0)
            5 -> LightFlicker(4, 8192, 2048, 0)
            6 -> LightFlicker(3, 2048, 768, 1280)
            7 -> LightFlicker(3, 4096, 768, 1280)
            8 -> LightFlicker(3, 2048, 1024, 1024)
            9 -> LightFlicker(3, 4096, 1024, 1024)
            10 -> LightFlicker(3, 2048, 512, 1536)
            11 -> LightFlicker(3, 4096, 512, 1536)
            12 -> LightFlicker(2, 2048, 2048, 0)
            13 -> LightFlicker(2, 8192, 2048, 0)
            14 -> LightFlicker(1, 2048, 768, 1280)
            15 -> LightFlicker(1, 4096, 512, 1536)
            16 -> LightFlicker(1, 8192, 256, 1792)
            else -> NONE
        }
    }
}

/**
 * A point light placed by the map's effects block (opcode 1).
 *
 * Positions are region-local and expressed in world units where one tile is
 * [MapPointLight.TILE_UNITS] (512) - the same scale the client's scene graph uses.
 *
 * Height convention: the cache stores [heightOffset] as a *downward* offset because RS
 * heights are negative-up. The tools terrain stores positive-up heights, so the final
 * world height is `terrainHeight(plane, tileX, tileZ) + heightOffset`.
 */
class MapPointLight(
    /** Plane the light sits on (0..3). */
    val plane: Int,
    /** Light expands upward through the plane above. */
    val growsUpwards: Boolean,
    /** Light expands downward through the plane below. */
    val growsDownwards: Boolean,
    /** Region-local X in world units (east). */
    val x: Int,
    /** Height above the terrain in world units, positive-up (see class docs). */
    val heightOffset: Int,
    /** Region-local Z in world units (north). */
    val z: Int,
    /** Influence radius in world units. */
    val radius: Int,
    /**
     * Per-slice vertical extent, `size2d * 2 + 1` entries.
     * High byte is the upper height, low byte the lower height, both clamped to the slice count.
     */
    val ranges: ShortArray,
    /** Raw packed 16-bit RS HSL colour. */
    val colourHsl: Int,
    /** [colourHsl] converted to packed 0xRRGGBB. */
    val colour: Int,
    /** Packed light type (0..31). 31 means [lightTypeId] carries the real parameters. */
    val type: Int,
    /** Phase offset applied to the flicker waveform. */
    val rotationOffset: Int,
    /** Light config id when [type] is 31, otherwise -1. */
    val lightTypeId: Int,
    /** Resolved flicker parameters. */
    val flicker: LightFlicker,
) {
    /** Region-local tile X (0..63). */
    val tileX: Int
        get() = x shr TILE_SHIFT

    /** Region-local tile Z (0..63). */
    val tileZ: Int
        get() = z shr TILE_SHIFT

    override fun toString(): String =
        "MapPointLight(plane=$plane, tile=($tileX,$tileZ), pos=($x,$heightOffset,$z), radius=$radius, " +
            "colour=0x${colour.toString(16).padStart(6, '0')}, hsl=$colourHsl, type=$type, " +
            "lightTypeId=$lightTypeId, flicker=$flicker, up=$growsUpwards, down=$growsDownwards, " +
            "slices=${ranges.size})"

    companion object {
        /** Bit shift converting tile coordinates to world units. */
        const val TILE_SHIFT = 9

        /** World units per tile. */
        const val TILE_UNITS = 1 shl TILE_SHIFT
    }
}

/**
 * Decoded environment payload for a single 64x64 region.
 *
 * The cache stores one environment per map file, which the client then applies to every
 * 8x8 chunk of that region - [chunkEnvironments] preserves that shape so callers can index
 * per chunk without caring about the underlying layout.
 */
class RegionEnvironment(
    val regionX: Int,
    val regionY: Int,
    /** 8x8 chunk grid, indexed `chunkX * 8 + chunkY`. Null where the map defines no environment. */
    val chunkEnvironments: Array<MapEnvironment?>,
    /** Every point light in the region, in file order. */
    val lights: List<MapPointLight>,
    /**
     * Per-plane 64x64 light intensity grid (opcode 129), row-major `x * 64 + y`.
     * Null for planes the map leaves unlit.
     */
    val lightGrid: Array<ByteArray?>,
    /** How many opcode-0 environment blocks the file contained (diagnostic; normally 0 or 1). */
    val environmentBlocks: Int,
    /**
     * Byte-faithful echo of the effects block this was decoded from. Write-back merges edits
     * into this rather than re-encoding the lossy view above; see [EnvironmentLayout].
     */
    val layout: EnvironmentLayout = EnvironmentLayout.EMPTY,
) {

    /** Environment for a chunk, or null when the map defines none. */
    fun environment(chunkX: Int, chunkY: Int): MapEnvironment? {
        if (chunkX !in 0 until CHUNKS || chunkY !in 0 until CHUNKS) return null
        return chunkEnvironments[chunkX * CHUNKS + chunkY]
    }

    /** Environment for a region-local tile, falling back to [MapEnvironment.DEFAULT]. */
    fun environmentAtTile(localX: Int, localY: Int): MapEnvironment =
        environment(localX shr 3, localY shr 3) ?: MapEnvironment.DEFAULT

    /** Signed light intensity for a region-local tile, or 0 when the plane is unlit. */
    fun lightIntensity(plane: Int, localX: Int, localY: Int): Int {
        if (plane !in 0 until PLANES) return 0
        if (localX !in 0 until SIZE || localY !in 0 until SIZE) return 0
        val grid = lightGrid[plane] ?: return 0
        return grid[localX * SIZE + localY].toInt()
    }

    /** True when the region carries no environment, lights or light grid at all. */
    fun isEmpty(): Boolean =
        environmentBlocks == 0 && lights.isEmpty() && lightGrid.all { it == null }

    companion object {
        const val SIZE = 64
        const val CHUNKS = 8
        const val PLANES = 4

        fun empty(regionX: Int, regionY: Int) = RegionEnvironment(
            regionX = regionX,
            regionY = regionY,
            chunkEnvironments = arrayOfNulls(CHUNKS * CHUNKS),
            lights = emptyList(),
            lightGrid = arrayOfNulls(PLANES),
            environmentBlocks = 0,
        )
    }
}
