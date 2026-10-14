package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.region.RegionLoadProfiler
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.darkan.tools.util.TileUtils
import org.joml.Vector3f
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Loads terrain data from the cache and creates renderable meshes.
 * Uses actual underlay and overlay definitions for accurate colors.
 */
object TerrainLoader {
    /** Thread-safe wrapper for ConcurrentHashMap (which disallows null values). */
    private sealed class CacheResult<out T> {
        data class Found<T>(val value: T) : CacheResult<T>()
        data object NotFound : CacheResult<Nothing>()
    }

    private val regionCache = ConcurrentHashMap<Int, CacheResult<RegionData>>()

    /** Decoded underwater (`um`) maps, keyed the same way as [regionCache]. */
    private val underwaterCache = ConcurrentHashMap<Int, CacheResult<RegionData>>()

    /**
     * Overlay ids that sit over a pool floor in a given region, i.e. the ones that are water
     * here. Derived from the underwater map rather than guessed from the overlay definition:
     * an overlay only behaves as water where the cache actually put a bed under it, and the
     * same id is used for dry decoration elsewhere.
     */
    private val waterOverlayCache = ConcurrentHashMap<Int, Set<Int>>()

    // Lazy-loaded definitions from cache
    private var underlays: Array<UnderlayDefinition>? = null
    private var overlays: Array<OverlayDefinition>? = null
    private var definitionsLoaded = false

    /** Enable/disable terrain color blending. When disabled, uses raw underlay colors. */
    var enableBlending = true

    /**
     * Enable/disable enhanced tile tessellation. When enabled, simple square tiles (shapes 0 and 12)
     * are subdivided into 4 triangles using a center vertex. This allows proper HSL-space color
     * interpolation instead of GPU RGB interpolation, resulting in smoother color gradients
     * across tiles with varying corner colors (e.g., at terrain type boundaries).
     *
     * When disabled, simple tiles use only 2 triangles with 4 corners, and the GPU performs
     * linear RGB interpolation which can produce slightly different results than HSL interpolation.
     */
    var enableEnhancedTessellation = true

    /** Enable/disable overlay rendering. When disabled, only underlay tiles are rendered. */
    var enableOverlays = true


    /**
     * Enable the shallow-water shoreline band baked from the region's underwater height map.
     * `-Ddarkan.shoreline=false` turns it off for before/after comparison.
     */
    var enableShoreline = System.getProperty("darkan.shoreline", "true").toBoolean()

    /**
     * Enable/disable terrain lighting. When enabled, applies per-vertex lighting based on
     * height gradients, matching the RS client's GroundGL lighting algorithm.
     * This creates visible color variation even on uniformly-colored terrain.
     */
    var enableLighting = true

    /**
     * Bake the directional sun into the vertex colours at mesh-build time.
     *
     * When false the mesh carries the *unlit* colour and the scene shaders apply the sun
     * from the environment UBO instead, which is what makes the sun editable at runtime.
     * The flat [GROUND_BASE_LIGHT_STRENGTH] scale is still baked either way, because the
     * reference client bakes it too: `GroundGL.resetLight` writes
     * `lightness = lightStrength * (colour and 0x7f) shr 7` with a base strength of 74
     * before the GL lights ever see the vertex.
     *
     * Changing this requires a terrain rebuild ([clearCache] + reload).
     */
    var bakeSunLighting = true

    /**
     * `GroundGL.resetLight`'s `baseStrength`. The per-tile light grid (map opcode 129) is
     * subtracted from it; with no grid the ground's flat multiplier is `74 / 128`.
     */
    const val GROUND_BASE_LIGHT_STRENGTH = 74

    /** [GROUND_BASE_LIGHT_STRENGTH] as the lightness multiplier the client applies (`shr 7`). */
    const val GROUND_BASE_LIGHT_FACTOR = GROUND_BASE_LIGHT_STRENGTH / 128f

    /**
     * Enable/disable per-vertex color noise. When enabled, adds subtle random brightness
     * variation to terrain colors, matching the RS client's HSL-to-RGB conversion which
     * adds +-1.5% random brightness (d_1 = 0.7 + Math.random() * 0.03 - 0.015).
     *
     * This breaks up the uniformity of large terrain areas. The noise is deterministic
     * based on vertex position to avoid shimmering.
     */
    var enableColorNoise = true

    /**
     * Amount of color noise variation (0.03 = +-1.5%, 0.06 = +-3%).
     * RS client uses approximately 0.03 (+-1.5%).
     */
    var colorNoiseAmount = 0.03f

    // Lighting configuration - matches RS client defaults from EnvironmentManager.kt
    // RS default sun position: (-200, -240, -200) -> normalized: (-0.53, -0.64, -0.53)
    // These can be adjusted via ImGui to match specific RS environments

    /** Light direction X component */
    var LIGHT_DIRECTION_X = 0.163f

    /** Light direction Y component */
    var LIGHT_DIRECTION_Y = 0.070f

    /** Light direction Z component */
    var LIGHT_DIRECTION_Z = 1.00f

    /** Sun brightness - base ambient factor */
    var SUN_BRIGHTNESS = 1.2f

    /** Bright factor - applied when surface faces light */
    var BRIGHT_FACTOR = 0.865f

    /** Dark factor - applied when surface faces away from light */
    var DARK_FACTOR = 0.814f

    // Legacy parameters for backward compatibility
    /** Ambient light factor (minimum brightness) - mapped to SUN_BRIGHTNESS */
    var AMBIENT_LIGHT: Float
        get() = SUN_BRIGHTNESS
        set(value) { SUN_BRIGHTNESS = value }

    /** Diffuse light strength - mapped to BRIGHT_FACTOR */
    var DIFFUSE_STRENGTH: Float
        get() = BRIGHT_FACTOR
        set(value) { BRIGHT_FACTOR = value }

    data class RegionData(
        val regionId: Int,
        val tiles: Array<Array<Array<TileData>>> // [plane][x][y]
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RegionData
            return regionId == other.regionId
        }

        override fun hashCode(): Int = regionId
    }

    /**
     * Holds references to neighboring region data for seamless edge blending.
     * Each neighbor provides tile data needed for height lookups, normal computation,
     * and underlay blending at region boundaries. References are to cached RegionData
     * instances from [regionCache], so no extra memory is allocated for tile arrays.
     *
     * Directions use RS conventions: X increases east, Y increases north.
     */
    class NeighborData {
        var east: RegionData? = null       // Region at (rx+1, ry)
        var north: RegionData? = null      // Region at (rx, ry+1)
        var northeast: RegionData? = null  // Region at (rx+1, ry+1)
        var west: RegionData? = null       // Region at (rx-1, ry)
        var south: RegionData? = null      // Region at (rx, ry-1)
        var northwest: RegionData? = null  // Region at (rx-1, ry+1)
        var southeast: RegionData? = null  // Region at (rx+1, ry-1)
        var southwest: RegionData? = null  // Region at (rx-1, ry-1)
    }

    /**
     * Build [NeighborData] for a region by loading neighboring regions from cache.
     * Uses [loadRegion] which caches results, so repeated calls are cheap.
     *
     * @param cache The game cache
     * @param rx Region X coordinate
     * @param ry Region Y coordinate
     * @return NeighborData with all available neighbors populated
     */
    fun buildNeighborData(cache: Cache, rx: Int, ry: Int): NeighborData {
        val start = if (profileSubPhases) System.nanoTime() else 0L
        val nd = buildNeighborDataInternal(cache, rx, ry)
        if (profileSubPhases) RegionLoadProfiler.record("tm:neighbors", System.nanoTime() - start)
        return nd
    }

    private fun buildNeighborDataInternal(cache: Cache, rx: Int, ry: Int): NeighborData {
        val nd = NeighborData()
        if (rx + 1 <= 255) nd.east = loadRegion(cache, rx + 1, ry)
        if (ry + 1 <= 255) nd.north = loadRegion(cache, rx, ry + 1)
        if (rx + 1 <= 255 && ry + 1 <= 255) nd.northeast = loadRegion(cache, rx + 1, ry + 1)
        if (rx - 1 >= 0) nd.west = loadRegion(cache, rx - 1, ry)
        if (ry - 1 >= 0) nd.south = loadRegion(cache, rx, ry - 1)
        if (rx - 1 >= 0 && ry + 1 <= 255) nd.northwest = loadRegion(cache, rx - 1, ry + 1)
        if (rx + 1 <= 255 && ry - 1 >= 0) nd.southeast = loadRegion(cache, rx + 1, ry - 1)
        if (rx - 1 >= 0 && ry - 1 >= 0) nd.southwest = loadRegion(cache, rx - 1, ry - 1)
        return nd
    }

    /**
     * Get tile height at extended coordinates that may be outside the 0-63 range.
     * For coordinates within 0-63, reads from the current region. For coordinates
     * outside that range, reads from the appropriate neighbor region.
     *
     * @param region The current region data
     * @param neighbors Neighbor data (null falls back to clamped behavior)
     * @param plane The plane level
     * @param x Tile X coordinate (can be -5 to 68 for blending window)
     * @param y Tile Y coordinate (can be -5 to 68 for blending window)
     * @return Scaled height value in OpenGL world units
     */
    private fun getExtendedHeight(region: RegionData, neighbors: NeighborData?, plane: Int, x: Int, y: Int): Float {
        if (x in 0..63 && y in 0..63) {
            return region.tiles[plane][x][y].height * HEIGHT_SCALE
        }
        if (neighbors == null) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return region.tiles[plane][cx][cy].height * HEIGHT_SCALE
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> { return region.tiles[plane][x][y].height * HEIGHT_SCALE }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return region.tiles[plane][cx][cy].height * HEIGHT_SCALE
        }
        return neighborRegion.tiles[plane][nx][ny].height * HEIGHT_SCALE
    }

    /**
     * Get raw (unscaled) tile height at extended coordinates for normal computation.
     */
    private fun getExtendedHeightRaw(tiles: Array<Array<Array<TileData>>>, neighbors: NeighborData?, neighborPlane: Int, x: Int, y: Int): Float {
        if (x in 0..63 && y in 0..63) {
            return tiles[neighborPlane][x][y].height * HEIGHT_SCALE
        }
        if (neighbors == null) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return tiles[neighborPlane][cx][cy].height * HEIGHT_SCALE
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> { return tiles[neighborPlane][x][y].height * HEIGHT_SCALE }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            val cx = x.coerceIn(0, 63)
            val cy = y.coerceIn(0, 63)
            return tiles[neighborPlane][cx][cy].height * HEIGHT_SCALE
        }
        return neighborRegion.tiles[neighborPlane][nx][ny].height * HEIGHT_SCALE
    }

    /**
     * Raw (unscaled) tile height at coordinates that may lie outside this region's
     * 0..63 range, read from the appropriate neighbour region when one is supplied.
     *
     * This is the integer counterpart of [getExtendedHeightRaw], exposed because the
     * RS client's height array covers the *whole loaded scene* plus a guard row and
     * column, not a single region. Anything reproducing client height arithmetic --
     * [org.darkan.tools.cache.map.VertexDisplacer.HeightField] and the average-height
     * corner sampling in [org.darkan.tools.cache.map.ObjectRenderer] -- must be able to
     * read one tile past the region edge, or objects on the last row/column silently
     * sample the wrong corner.
     *
     * Where the required neighbour is absent the region's own edge tile is used, which
     * matches the fallback in [getExtendedHeight] and [getExtendedHeightRaw].
     *
     * @param tiles This region's `[plane][x][y]` tile array
     * @param neighbors Neighbour data; null falls back to clamping into this region
     * @param plane Plane level (clamped to 0..3)
     * @param x Tile X, may be negative or > 63
     * @param y Tile Y, may be negative or > 63
     * @return Height in raw RS units
     */
    fun getExtendedTileHeight(
        tiles: Array<Array<Array<TileData>>>,
        neighbors: NeighborData?,
        plane: Int,
        x: Int,
        y: Int
    ): Int {
        val p = plane.coerceIn(0, 3)
        if (x in 0..63 && y in 0..63) {
            return tiles[p][x][y].height
        }
        if (neighbors == null) {
            return tiles[p][x.coerceIn(0, 63)][y.coerceIn(0, 63)].height
        }
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63           -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0            -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63           -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            else             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) {
            return tiles[p][x.coerceIn(0, 63)][y.coerceIn(0, 63)].height
        }
        return neighborRegion.tiles[p][nx][ny].height
    }

    /** [getExtendedTileHeight] converted to OpenGL world units. */
    fun getExtendedTileHeightScaled(
        tiles: Array<Array<Array<TileData>>>,
        neighbors: NeighborData?,
        plane: Int,
        x: Int,
        y: Int
    ): Float = getExtendedTileHeight(tiles, neighbors, plane, x, y) * HEIGHT_SCALE

    /**
     * Get underlay ID at extended coordinates using neighbor data.
     * Returns 0 (no underlay) if the neighbor region is not available.
     */
    private fun getExtendedUnderlayId(region: RegionData, neighbors: NeighborData?, plane: Int, x: Int, y: Int): Int {
        if (x in 0..63 && y in 0..63) {
            return region.tiles[plane][x][y].underlayId
        }
        if (neighbors == null) return 0
        val neighborRegion: RegionData?
        val nx: Int
        val ny: Int
        when {
            x > 63 && y > 63 -> { neighborRegion = neighbors.northeast; nx = x - 64; ny = y - 64 }
            x > 63 && y < 0  -> { neighborRegion = neighbors.southeast; nx = x - 64; ny = y + 64 }
            x < 0  && y > 63 -> { neighborRegion = neighbors.northwest; nx = x + 64; ny = y - 64 }
            x < 0  && y < 0  -> { neighborRegion = neighbors.southwest; nx = x + 64; ny = y + 64 }
            x > 63            -> { neighborRegion = neighbors.east;      nx = x - 64; ny = y }
            x < 0             -> { neighborRegion = neighbors.west;      nx = x + 64; ny = y }
            y > 63            -> { neighborRegion = neighbors.north;     nx = x;      ny = y - 64 }
            y < 0             -> { neighborRegion = neighbors.south;     nx = x;      ny = y + 64 }
            else              -> return region.tiles[plane][x][y].underlayId
        }
        if (neighborRegion == null || nx !in 0..63 || ny !in 0..63) return 0
        return neighborRegion.tiles[plane][nx][ny].underlayId
    }

    /**
     * Tile function mask flags from the RS client (SettingsBits.kt / MapLoader.kt).
     * These flags control collision, visibility, and height behavior.
     *
     * From SettingsBits.java in darkan-bot client:
     * - isVisible(collisionPlane, z, x, y): returns true if:
     *     (flags[0][x][y] & 0x2) != 0, OR
     *     ((flags[z][x][y] & 0x10) == 0 AND getCollisionPlane(z,x,y) == collisionPlane)
     * - getCollisionPlane(z, x, y): returns:
     *     0 if (flags[z][x][y] & 0x8) != 0
     *     z - 1 if z > 0 AND (flags[1][x][y] & 0x2) != 0
     *     z otherwise
     */
    object TileFlags {
        /** Tile is blocked for walking (adds collision flag) */
        const val BLOCK_WALK = 0x1

        /**
         * This tile is a bridge - affects height calculation and visibility.
         *
         * When set on plane 0: Forces tile to always be visible (overrides INVISIBLE)
         * When set on plane 1:
         *   - Collision plane for planes > 0 is reduced by 1
         *   - Objects use height from plane+1
         *   - Terrain below bridges is linked for proper rendering
         */
        const val BRIDGE = 0x2

        /**
         * Force collision plane to 0 regardless of actual plane.
         * Used for areas that should always use ground-level collision.
         * In getCollisionPlane(): if set, returns 0.
         */
        const val FORCE_COLLISION_PLANE_0 = 0x8

        /**
         * Tile is not visible (used for hidden areas, interiors, etc).
         * When set, the tile should not be rendered UNLESS:
         * - The bridge flag (0x2) is set on plane 0 at this location
         * - The collision plane doesn't match the viewing plane
         */
        const val INVISIBLE = 0x10
    }

    data class TileData(
        val height: Int,
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    ) {
        /** Check if tile has a specific flag set */
        fun hasFlag(flag: Int): Boolean = (flags and flag) == flag

        /** Check if this tile is marked as a bridge */
        val isBridge: Boolean get() = hasFlag(TileFlags.BRIDGE)

        /** Check if this tile should block walking */
        val blocksWalk: Boolean get() = hasFlag(TileFlags.BLOCK_WALK)

        /** Check if this tile is invisible (should not be rendered) */
        val isInvisible: Boolean get() = hasFlag(TileFlags.INVISIBLE)

        /** Check if this tile forces collision to plane 0 */
        val forcesCollisionPlane0: Boolean get() = hasFlag(TileFlags.FORCE_COLLISION_PLANE_0)
    }

    /**
     * Calculate the collision plane for a tile, matching the RS client's SettingsBits.getCollisionPlane().
     *
     * The collision plane determines which plane's collision data is used and affects visibility.
     *
     * @param tiles The 4-plane tile array for the region
     * @param z The current plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return The collision plane (0-3)
     */
    fun getCollisionPlane(tiles: Array<Array<Array<TileData>>>, z: Int, x: Int, y: Int): Int {
        // If FORCE_COLLISION_PLANE_0 flag is set on this tile, always return 0
        if (tiles[z][x][y].forcesCollisionPlane0) {
            return 0
        }
        // If we're on a plane > 0 AND the bridge flag is set on plane 1 at this position,
        // the collision plane is reduced by 1 (allowing walking under bridges)
        if (z > 0 && tiles[1][x][y].isBridge) {
            return z - 1
        }
        return z
    }

    /**
     * Check if a tile at the given position should be visible for a specific viewing plane.
     *
     * This matches the RS client's SettingsBits.isVisible() logic:
     * - If bridge flag (0x2) is set on plane 0, the tile is ALWAYS visible
     * - Otherwise, visible if NOT invisible AND collision plane matches viewing plane
     *
     * @param tiles The 4-plane tile array for the region
     * @param viewingPlane The plane we're viewing (typically the player's plane)
     * @param z The tile's plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if the tile should be rendered
     */
    fun isTileVisible(tiles: Array<Array<Array<TileData>>>, viewingPlane: Int, z: Int, x: Int, y: Int): Boolean {
        // Bridge flag on plane 0 makes tile always visible (used for bridges/overhangs)
        if (tiles[0][x][y].isBridge) {
            return true
        }
        // Otherwise, check: not invisible AND collision plane matches viewing plane
        return !tiles[z][x][y].isInvisible && getCollisionPlane(tiles, z, x, y) == viewingPlane
    }

    /**
     * Check if the bridge flag is set on plane 1 at this position.
     * When set, objects should use height from the plane above their current plane.
     *
     * @param tiles The 4-plane tile array for the region
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if bridge flag is set on plane 1
     */
    fun isBridgeTile(tiles: Array<Array<Array<TileData>>>, x: Int, y: Int): Boolean {
        return tiles[1][x][y].isBridge
    }

    /**
     * Get the height plane to use for an object at the given position.
     * Following the RS client (MapRegion.tileheightForAbsolutePosition):
     * - Check if bridge flag (0x2) is set on level 1 at this position
     * - If so, and current plane < 3, use height from plane + 1
     * - Otherwise use the current plane's height
     *
     * @param tiles The 4-plane tile array for the region
     * @param plane The object's plane level
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return The plane to use for height lookup
     */
    fun getHeightPlane(tiles: Array<Array<Array<TileData>>>, plane: Int, x: Int, y: Int): Int {
        if (plane < 3 && isBridgeTile(tiles, x, y)) {
            return plane + 1
        }
        return plane
    }

    /**
     * Check if terrain from a higher plane should be rendered due to bridge linkBelow.
     *
     * In the RS client, linkBelow() shifts terrain from higher planes down to lower planes
     * at bridge locations. This function determines if terrain from sourcePlane should
     * be visible when rendering targetPlane due to this bridge shifting.
     *
     * The logic mirrors Scene.linkBelow():
     * - When bridge flag (0x2) is set on plane 1, terrain is shifted down
     * - Plane 1 terrain appears at plane 0 (ground under bridge)
     * - Plane 2 terrain appears at plane 1
     * - Plane 3 terrain appears at plane 2
     *
     * @param tiles The 4-plane tile array for the region
     * @param sourcePlane The plane whose terrain data we're considering
     * @param targetPlane The plane we're rendering/viewing
     * @param x The tile X coordinate (0-63)
     * @param y The tile Y coordinate (0-63)
     * @return True if sourcePlane terrain should appear at targetPlane due to bridge
     */
    fun isBridgeLinkedPlane(tiles: Array<Array<Array<TileData>>>, sourcePlane: Int, targetPlane: Int, x: Int, y: Int): Boolean {
        // Bridge linking only occurs when bridge flag is set on plane 1
        if (!isBridgeTile(tiles, x, y)) {
            return false
        }
        // linkBelow shifts terrain down by 1 plane: sourcePlane appears at (sourcePlane - 1)
        // So terrain from plane N appears at plane (N-1) after linkBelow
        return sourcePlane > 0 && sourcePlane - 1 == targetPlane
    }

    /**
     * Get the raw (unscaled) height value at a tile position.
     *
     * The height values are computed during region decode using the RS client's exact algorithm:
     * - Plane 0: procedural noise * 32 or heightByte * 32
     * - Plane 1+: previousHeight + 960 or previousHeight + heightByte * 32
     *
     * Heights are stored as positive values (higher terrain = larger value).
     *
     * @param tiles The 4-plane tile array from RegionData
     * @param localX Local X coordinate (0-63)
     * @param localY Local Y coordinate (0-63)
     * @param plane Plane level (0-3)
     * @return The height in RS units (typically 960-1920+ for ground level)
     */
    fun getTileHeight(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Int {
        val x = localX.coerceIn(0, 63)
        val y = localY.coerceIn(0, 63)
        val p = plane.coerceIn(0, 3)
        return tiles[p][x][y].height
    }

    /**
     * Get the scaled height value at a tile position in OpenGL world units.
     *
     * This applies HEIGHT_SCALE to convert RS height units to OpenGL coordinates.
     *
     * @param tiles The 4-plane tile array from RegionData
     * @param localX Local X coordinate (0-63)
     * @param localY Local Y coordinate (0-63)
     * @param plane Plane level (0-3)
     * @return The height in OpenGL world units
     */
    fun getTileHeightScaled(tiles: Array<Array<Array<TileData>>>, localX: Int, localY: Int, plane: Int): Float {
        return getTileHeight(tiles, localX, localY, plane) * HEIGHT_SCALE
    }

    /**
     * Get the height scale factor used to convert RS heights to OpenGL coordinates.
     * This is exposed so other components (like MapViewer) can use the same scale.
     */
    fun getHeightScale(): Float = HEIGHT_SCALE

    /**
     * A terrain batch for a specific texture.
     */
    data class TerrainBatch(
        val mesh: Mesh,
        val textureId: Int,  // -1 for untextured/color-only
        /**
         * True when this batch's geometry also carries atlas transforms, meaning the
         * merged atlas terrain mesh can draw it. The viewer skips such batches for any
         * region the merged mesh already covers; batches with this false are textures
         * the atlas could not represent and must always take the per-texture path.
         */
        val inAtlas: Boolean = false
    )

    /**
     * A terrain batch for a single texture layer rendered with per-vertex alpha.
     * Used in multi-pass alpha rendering where each non-winning texture is rendered
     * as a separate pass on top of the opaque base layer. The mesh contains a
     * per-vertex alpha attribute (location 4) that the terrain_alpha shader uses
     * to blend this layer over the base via standard alpha blending.
     */
    data class AlphaTerrainBatch(
        val mesh: Mesh,
        val textureId: Int,  // Single texture for this alpha layer
        /** See [TerrainBatch.inAtlas]. */
        val inAtlas: Boolean = false
    )

    /**
     * Container for terrain meshes with separate batches for textured, alpha-blended, and untextured tiles.
     * The rendering order is: colorMesh (opaque) -> texturedBatches (opaque, includes winning textures)
     * -> alphaBatches (rendered with GL alpha blending on top).
     */
    data class TerrainMeshes(
        val colorMesh: Mesh?,
        val texturedBatches: List<TerrainBatch>,
        val alphaBatches: List<AlphaTerrainBatch> = emptyList(),
        /** See [TerrainBatch.inAtlas]; applies to [colorMesh]. */
        val colorInAtlas: Boolean = false
    ) {
        fun cleanup() {
            colorMesh?.cleanup()
            texturedBatches.forEach { it.mesh.cleanup() }
            alphaBatches.forEach { it.mesh.cleanup() }
        }
    }

    // ==================== ATLAS UV LOOKUP ====================
    //
    // When set, terrain mesh data will include per-vertex atlas transform arrays.
    // This allows all terrain textures to be merged into a single draw call per
    // pass (opaque / alpha) by sampling from a texture atlas instead of individual
    // GL textures.

    /**
     * Optional atlas UV lookup function. When non-null, [computeTerrainMeshData]
     * populates per-vertex atlas transform arrays (4 floats per vertex: u0, v0,
     * uScale, vScale) enabling atlas-based terrain rendering.
     *
     * The function takes a texture ID and returns a FloatArray of [u0, v0, uScale,
     * vScale] that maps fract(localUV) into atlas UV space.
     *
     * When null, the legacy per-texture-batch code path is used and no atlas
     * transform arrays are generated.
     *
     * The lookup may also return null for an individual texture, meaning "this
     * texture is not reachable through the bound atlas page". That batch then gets
     * no atlas transforms and stays on the per-texture path, so an atlas that ran
     * out of room degrades to extra draw calls rather than to wrong pixels.
     */
    var atlasLookup: ((textureId: Int) -> FloatArray?)? = null

    /**
     * Sentinel atlas transform used for color-only tiles when atlas mode is active.
     * This should map to a solid white 1x1 pixel region in the atlas so that
     * color-only tiles produce texColor = white, making the final color equal to
     * the vertex color (texColor.rgb * vColor).
     *
     * Default value maps to full atlas (0,0,1,1) which is a reasonable fallback
     * but should be overridden to point at an actual white pixel in the atlas.
     */
    var sentinelAtlasTransform: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)

    // ==================== GPU-FREE MESH DATA CLASSES ====================
    //
    // These hold the intermediate FloatArray/IntArray results of terrain mesh
    // computation, before any OpenGL calls. They enable splitting mesh generation
    // into a pure-CPU phase (computeTerrainMeshData) and a GL-only upload phase
    // (uploadTerrainMeshData), which is required for parallel multi-region loading
    // since GL calls must remain on the main thread.

    /**
     * Pre-computed array data for a color-only (untextured) terrain mesh.
     * Contains vertex positions, RGB colors, normals, and indices.
     *
     * When atlas mode is enabled, also includes texture coordinates (all zeros)
     * and atlas transforms (sentinel white pixel) so color-only tiles can be
     * merged into the same draw call as textured tiles.
     */
    data class ColorMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        /** Texture coordinates for atlas mode (all zeros). Null when atlas is not active. */
        val texCoords: FloatArray? = null,
        /** Atlas transforms for atlas mode (sentinel white pixel). Null when atlas is not active. */
        val atlasTransforms: FloatArray? = null
    )

    /**
     * Pre-computed array data for an opaque textured terrain mesh batch.
     * Contains vertex positions, RGB colors, texture coordinates, normals, indices,
     * and the texture ID for this batch.
     *
     * When atlas mode is enabled, also includes per-vertex atlas transforms
     * (4 floats per vertex: u0, v0, uScale, vScale).
     */
    data class TexturedMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val texCoords: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val textureId: Int,
        /** Per-vertex atlas UV transforms. Null when atlas is not active. */
        val atlasTransforms: FloatArray? = null
    )

    /**
     * Pre-computed array data for an alpha-blended terrain mesh batch.
     * Contains vertex positions, RGB colors, texture coordinates, normals,
     * per-vertex alpha values, indices, and the texture ID for this batch.
     *
     * When atlas mode is enabled, also includes per-vertex atlas transforms
     * (4 floats per vertex: u0, v0, uScale, vScale).
     */
    data class AlphaMeshArrays(
        val positions: FloatArray,
        val colors: FloatArray,
        val texCoords: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val alphas: FloatArray,
        val textureId: Int,
        /** Per-vertex atlas UV transforms. Null when atlas is not active. */
        val atlasTransforms: FloatArray? = null
    )

    /**
     * Container for all pre-computed terrain mesh array data for a single region/plane.
     * This is the output of [computeTerrainMeshData] and the input to [uploadTerrainMeshData].
     * Contains no OpenGL resources -- purely CPU-side data that is safe to compute
     * on any thread.
     */
    data class TerrainMeshData(
        val colorArrays: ColorMeshArrays?,
        val texturedArrays: List<TexturedMeshArrays>,
        val alphaArrays: List<AlphaMeshArrays>
    )

    private val batchedMeshCache = ConcurrentHashMap<Int, CacheResult<TerrainMeshes>>()

    // ==================== SUB-PHASE PROFILING ====================
    //
    // `ctx:terrainMesh` covers region decode, neighbour assembly and the whole
    // tessellation, so the aggregate number cannot say which of those to attack.
    // `-Ddarkan.profileTerrain=true` splits it into `tm:*` sub-phases, which
    // RegionLoadProfiler reports alongside its own labels. Off by default: the
    // per-tile clocks are cheap but not free.

    /** Whether [computeTerrainMeshData] reports its internal `tm:*` sub-phases. */
    internal val profileSubPhases: Boolean = System.getProperty("darkan.profileTerrain") == "true"

    /**
     * Per-thread nanosecond accumulators for the per-tile sub-phases, summed over a
     * region's 4096 tiles and reported once per region so the profiler's atomics stay
     * cold. Slot 0 = blend analysis, 1 = tessellation, 2 = the rest of the per-tile
     * preamble (heights, definitions, corner colours).
     */
    private val tileSubPhaseNanos: ThreadLocal<LongArray> = ThreadLocal.withInitial { LongArray(3) }

    /**
     * Whether tessellation reuses per-vertex work within a tile (vertex colour memo,
     * scratch normals and triangle index buffer, texture-group memo).
     *
     * `-Ddarkan.fastTerrain=false` restores the previous recompute-per-emission
     * behaviour. Both paths must produce identical mesh arrays, so this exists only to
     * A/B the cost of the reuse inside a single build, where machine noise cancels.
     */
    @Volatile
    internal var fastTessellation: Boolean = System.getProperty("darkan.fastTerrain", "true").toBoolean()

    /**
     * Load floor definitions (underlays and overlays) from the cache.
     * Call this once before using getUnderlayColor/getOverlayColor.
     */
    fun loadDefinitions(cache: Cache) {
        if (definitionsLoaded) return

        underlays = UnderlayDecoder().load(cache)
        overlays = OverlayDecoder().load(cache)
        definitionsLoaded = true
    }

    /**
     * Compute blended underlay colors for a region plane using the RS client's EXACT algorithm.
     *
     * The client uses a sliding window approach with 5-tile radius (11x11 area) to blend
     * underlay colors smoothly across tile boundaries. This is the exact algorithm from
     * MapLoader.calculateUnderlayPalette() in the RS client.
     *
     * Key aspects of the RS algorithm:
     * 1. Maintains per-column accumulators (hue, saturation, lightness, divisor, count)
     * 2. Uses sliding window from x = -5 to regionSizeX
     * 3. For each column, accumulates row totals with y sliding window from -5 to regionSizeY
     * 4. Hue is weighted by chroma (divisor): finalHue = hueSum * 256 / divisorSum
     * 5. Saturation and lightness are simple averages: sat/depth, light/depth
     *
     * @param region The region data containing tile information
     * @param plane The plane level to compute colors for
     * @return A 64x64 array of packed HSL color values, one for each tile
     */
    internal fun computeBlendedUnderlayColors(region: RegionData, plane: Int, neighbors: NeighborData? = null): Array<IntArray> {
        // Output is 65x65 so getCornerHslColors can sample at (x+1, y+1) without clamping.
        // Index 64 represents the first tile of the neighbor region on that axis.
        val blendedColors = Array(65) { IntArray(65) }
        val underlayDefs = underlays ?: return blendedColors

        // Helper to get underlay ID at any coordinate using neighbor data
        fun underlayAt(tx: Int, ty: Int): Int = getExtendedUnderlayId(region, neighbors, plane, tx, ty)

        // If blending is disabled, return raw underlay colors
        if (!enableBlending) {
            for (x in 0 until 65) {
                for (y in 0 until 65) {
                    val underlayId = underlayAt(x, y)
                    val defIndex = underlayId - 1
                    if (defIndex >= 0 && defIndex < underlayDefs.size) {
                        val def = underlayDefs[defIndex]
                        if (def.chroma > 0) {
                            val hue = def.hue * 256 / def.chroma
                            blendedColors[x][y] = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                        }
                    }
                }
            }
            return blendedColors
        }

        // Debug: Track how many tiles get blended colors
        var nonZeroCount = 0

        // We need blended colors for output tiles 0..64 on each axis.
        // The sliding window for each output tile (x,y) covers tiles from
        // (x-5, y-5) to (x+5, y+5), an 11x11 area.
        //
        // With neighbor data, the window can extend into neighboring regions.
        // Tile coordinates range from -5 to 69 for the full window coverage.
        //
        // Column accumulators track the X-window sum for each Y row.
        // They need to cover tile Y = -5..69 -> mapped to indices 0..74 via offset +5.
        //
        // X iteration starts at -10 so that forwardX = x+5 = -5 is added first,
        // building up the full window before we start computing output at x=0.
        // Similarly, Y iteration starts at -10 for the same reason.
        val extYSize = 75  // covers Y = -5..69 -> index = tileY + 5
        val underlayHue = IntArray(extYSize)
        val underlaySaturation = IntArray(extYSize)
        val underlayLightness = IntArray(extYSize)
        val underlayDivisor = IntArray(extYSize)
        val underlayCount = IntArray(extYSize)

        for (x in -10..64) {
            // Update column accumulators for each extended Y row.
            // Add forward edge (x+5), subtract back edge (x-5) for the 11-column window.
            for (tileY in -5..69) {
                val accIdx = tileY + 5  // Map to 0..74

                // Add tile at x+5 (forward edge of X window)
                val forwardX = x + 5
                if (forwardX in -5..69) {
                    val fwdUnderlayId = underlayAt(forwardX, tileY)
                    if (fwdUnderlayId > 0) {
                        val def = underlayDefs.getOrNull(fwdUnderlayId - 1)
                        if (def != null) {
                            underlayHue[accIdx] += def.hue
                            underlaySaturation[accIdx] += def.saturation
                            underlayLightness[accIdx] += def.lightness
                            underlayDivisor[accIdx] += def.chroma
                            underlayCount[accIdx]++
                        }
                    }
                }

                // Subtract tile at x-5 (back edge of X window, leaving the window)
                val backwardX = x - 5
                if (backwardX in -5..69) {
                    val bkwdUnderlayId = underlayAt(backwardX, tileY)
                    if (bkwdUnderlayId > 0) {
                        val def = underlayDefs.getOrNull(bkwdUnderlayId - 1)
                        if (def != null) {
                            underlayHue[accIdx] -= def.hue
                            underlaySaturation[accIdx] -= def.saturation
                            underlayLightness[accIdx] -= def.lightness
                            underlayDivisor[accIdx] -= def.chroma
                            underlayCount[accIdx]--
                        }
                    }
                }
            }

            // For x >= 0, compute blended colors by accumulating Y window
            if (x >= 0) {
                var hueSum = 0
                var saturationSum = 0
                var lightnessSum = 0
                var divisorSum = 0
                var depth = 0

                // Y sliding window starts at -10 to build up the full window
                // before producing output at y=0.
                for (y in -10..64) {
                    // Add row at y+5 (forward edge of Y window)
                    val forwardY = y + 5
                    if (forwardY in -5..69) {
                        val accIdx = forwardY + 5
                        hueSum += underlayHue[accIdx]
                        saturationSum += underlaySaturation[accIdx]
                        lightnessSum += underlayLightness[accIdx]
                        divisorSum += underlayDivisor[accIdx]
                        depth += underlayCount[accIdx]
                    }

                    // Subtract row at y-5 (back edge of Y window, leaving the window)
                    val backwardY = y - 5
                    if (backwardY in -5..69) {
                        val accIdx = backwardY + 5
                        hueSum -= underlayHue[accIdx]
                        saturationSum -= underlaySaturation[accIdx]
                        lightnessSum -= underlayLightness[accIdx]
                        divisorSum -= underlayDivisor[accIdx]
                        depth -= underlayCount[accIdx]
                    }

                    // Compute blended color for output tile (x, y)
                    if (y >= 0 && divisorSum > 0 && depth > 0) {
                        val hue = hueSum * 256 / divisorSum
                        val saturation = saturationSum / depth
                        val lightness = lightnessSum / depth
                        blendedColors[x][y] = ColorUtil.packHsl16(hue, saturation, lightness)
                        nonZeroCount++
                    }
                }
            }
        }

        return blendedColors
    }

    /**
     * Get blended underlay color for a specific position from pre-computed blended colors.
     * Returns the packed HSL value for further processing.
     */
    private fun getBlendedUnderlayColorHsl(blendedColors: Array<IntArray>, x: Int, y: Int): Int {
        if (x !in 0..64 || y !in 0..64) return 0
        return blendedColors[x][y]
    }

    /**
     * Get the 4 corner HSL colors for a tile from the blended color array.
     * Returns packed HSL values in order: [SW, SE, NW, NE]
     *
     * RS client uses paletteIndices positions:
     * - SW corner (0,0): paletteIndices[x][y]
     * - NW corner (0,1): paletteIndices[x][y+1]
     * - NE corner (1,1): paletteIndices[x+1][y+1]
     * - SE corner (1,0): paletteIndices[x+1][y]
     *
     * blendedColors is 65x65 (indices 0..64), so (x+1) and (y+1) are always in range
     * for tiles 0..63. No clamping is needed.
     */
    private fun getCornerHslColors(blendedColors: Array<IntArray>, x: Int, y: Int): IntArray {
        val swHsl = getBlendedUnderlayColorHsl(blendedColors, x, y)
        val seHsl = getBlendedUnderlayColorHsl(blendedColors, x + 1, y)
        val nwHsl = getBlendedUnderlayColorHsl(blendedColors, x, y + 1)
        val neHsl = getBlendedUnderlayColorHsl(blendedColors, x + 1, y + 1)

        return intArrayOf(swHsl, seHsl, nwHsl, neHsl)
    }

    /**
     * Convert packed HSL corner colors to RGB Vector3f array.
     * Used for overlay colors that don't need HSL blending.
     * Falls back to black (not green) for invalid HSL = 0 values.
     */
    private fun hslCornersToRgb(hslCorners: IntArray): Array<Vector3f> {
        val black = Vector3f(0f, 0f, 0f)
        return arrayOf(
            if (hslCorners[0] != 0) ColorUtil.hslToRgb(hslCorners[0]) else black,
            if (hslCorners[1] != 0) ColorUtil.hslToRgb(hslCorners[1]) else black,
            if (hslCorners[2] != 0) ColorUtil.hslToRgb(hslCorners[2]) else black,
            if (hslCorners[3] != 0) ColorUtil.hslToRgb(hslCorners[3]) else black
        )
    }

    fun loadRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        // Only cache successful results. Failed decodes (null) are not cached so they
        // can be retried — transient failures from concurrent cache access would otherwise
        // be permanently cached as NotFound, breaking subsequent loads of valid regions.
        val cached = regionCache[regionId]
        if (cached != null) return (cached as? CacheResult.Found)?.value

        val decodeStart = if (profileSubPhases) System.nanoTime() else 0L
        val data = decodeRegion(cache, regionX, regionY) ?: return null
        if (profileSubPhases) RegionLoadProfiler.record("tm:decode", System.nanoTime() - decodeStart)
        regionCache[regionId] = CacheResult.Found(data)
        // Decoded alongside the surface so the mesh builders, which are handed a RegionData
        // and no Cache, can find the bed by region id via [underwaterFor].
        underwaterCache.computeIfAbsent(regionId) {
            val bed = decodeUnderwaterRegion(cache, regionX, regionY, data)
            if (bed == null) CacheResult.NotFound else CacheResult.Found(bed)
        }
        return data
    }

    /**
     * The already-decoded underwater map for a region id, or null when that region has no
     * water. Populated as a side effect of [loadRegion].
     */
    internal fun underwaterFor(regionId: Int): RegionData? =
        (underwaterCache[regionId] as? CacheResult.Found)?.value

    /** Overlay ids that behave as water in this region; see [waterOverlayCache]. */
    internal fun waterOverlayIdsFor(regionId: Int): Set<Int> =
        waterOverlayCache[regionId] ?: emptySet()

    /**
     * Load the region's underwater map -- the river/pool *bed* beneath the water surface.
     *
     * The cache stores this as a second map archive `um<rx>_<ry>` in exactly the tile format
     * of `m<rx>_<ry>`, except that its plane 0 heights are **offsets relative to the surface**
     * rather than absolute (the client's `MapLoader.offsetTileHeights`, applied from
     * `MapRegion` right after the archive is decoded). They are added to the surface heights
     * here so the returned [RegionData] carries absolute bed heights on the same scale as
     * [loadRegion], which makes water depth a plain subtraction.
     *
     * Returns null for regions with no underwater map, which is most of them -- only regions
     * that actually contain water ship one.
     */
    fun loadUnderwaterRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY
        val cached = underwaterCache[regionId]
        if (cached != null) return (cached as? CacheResult.Found)?.value

        val surface = loadRegion(cache, regionX, regionY)
        val data = if (surface == null) null else decodeUnderwaterRegion(cache, regionX, regionY, surface)
        underwaterCache[regionId] = if (data == null) CacheResult.NotFound else CacheResult.Found(data)
        return data
    }

    /**
     * Load terrain meshes with separate batches for textured and untextured tiles.
     * Tiles with texture IDs from underlay/overlay definitions go into textured batches,
     * while tiles with only color information go into the color mesh.
     *
     * Uses the RS client's visibility logic to determine which tiles to render:
     * - Bridge flag on plane 0 makes tiles always visible
     * - Invisible flag (0x10) hides tiles unless overridden by bridge
     * - Collision plane calculation affects visibility determination
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @param plane Plane level (0-3) - this is both the tile plane AND the viewing plane
     * @return TerrainMeshes containing color mesh and textured batches, or null if region not found
     */
    fun loadRegionMeshes(cache: Cache, regionX: Int, regionY: Int, plane: Int = 0, neighbors: NeighborData? = null): TerrainMeshes? {
        // Ensure definitions are loaded
        loadDefinitions(cache)

        val regionId = (regionX shl 8) or regionY
        val cacheKey = regionId * 4 + plane

        // When neighbors are provided, skip the cache because the mesh depends on
        // neighbor data which may differ between calls. Context regions loaded without
        // neighbors can still use the cache.
        if (neighbors == null) {
            val cached = batchedMeshCache[cacheKey]
            if (cached is CacheResult.Found) {
                return cached.value
            }
        }

        // Generate new meshes - log overlay state for debugging
        println("[TerrainLoader] Generating meshes for region ($regionX, $regionY) plane $plane - enableOverlays=$enableOverlays")

        val region = loadRegion(cache, regionX, regionY) ?: return null
        val meshes = createBatchedTerrainMeshes(region, plane, neighbors)
        // Only cache when no neighbors (backward-compatible path)
        if (neighbors == null) {
            batchedMeshCache[cacheKey] = CacheResult.Found(meshes)
        }
        return meshes
    }

    /**
     * Compute per-vertex normals for a 64x64 tile region on a specific plane.
     * Returns Triple of (normalsX, normalsY, normalsZ) arrays, each 65x65 (one per vertex).
     * This matches the RS client's GroundGL.kt normal computation from height gradients.
     *
     * @param tiles The 3D tile array [plane][x][y]
     * @param plane The plane level to compute normals for
     * @return Triple of (normalsX, normalsY, normalsZ) arrays
     */
    internal fun computeVertexNormals(tiles: Array<Array<Array<TileData>>>, plane: Int, neighbors: NeighborData? = null): Triple<Array<FloatArray>, Array<FloatArray>, Array<FloatArray>> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val normalsX = Array(65) { FloatArray(65) }
        val normalsY = Array(65) { FloatArray(65) }
        val normalsZ = Array(65) { FloatArray(65) }

        // Vertex indices go from 0 to 64 (65 vertices per axis for 64 tiles).
        // Vertex at index 0 is the SW corner of tile 0, vertex at index 64 is the
        // NE corner of tile 63 (which is also the SW corner of the neighbor's tile 0).
        // Normal computation samples heights at (vx-1, vy) and (vx+1, vy) etc.
        // At boundaries (vx=0, vx=64, vy=0, vy=64), these samples extend into
        // neighbor regions. With neighbors=null, falls back to clamped behavior.
        for (vx in 0 until 65) {
            for (vy in 0 until 65) {
                val heightWest  = getExtendedHeightRaw(tiles, neighbors, plane, vx - 1, vy)
                val heightEast  = getExtendedHeightRaw(tiles, neighbors, plane, vx + 1, vy)
                val heightSouth = getExtendedHeightRaw(tiles, neighbors, plane, vx, vy - 1)
                val heightNorth = getExtendedHeightRaw(tiles, neighbors, plane, vx, vy + 1)

                val heightDeltaX = heightEast - heightWest
                val heightDeltaY = heightNorth - heightSouth

                val nx = heightDeltaX
                val ny = tileSize * 2f
                val nz = heightDeltaY

                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0.0001f) {
                    normalsX[vx][vy] = nx / len
                    normalsY[vx][vy] = ny / len
                    normalsZ[vx][vy] = nz / len
                } else {
                    normalsX[vx][vy] = 0f
                    normalsY[vx][vy] = 1f
                    normalsZ[vx][vy] = 0f
                }
            }
        }
        return Triple(normalsX, normalsY, normalsZ)
    }

    /**
     * Water depth at a tile's four corners in SW, SE, NW, NE order, in RS height units.
     *
     * Depth is `surface - bed`, and the bed only differs from the surface where the region's
     * underwater map actually carries a pool floor, so this is 0 across all dry land and ramps
     * smoothly down to 0 at the waterline. That smoothness is the whole point: it is a
     * property of the cache's height field, not of how a tile happens to be tessellated, so a
     * shoreline derived from it cannot break up the way a tessellation-derived one does.
     *
     * Corners are clamped inside the region rather than read from neighbours: the bed is only
     * consulted a fraction of a tile in from the waterline and pools do not straddle region
     * borders often enough to justify threading a second neighbour set through.
     */
    private fun cornerWaterDepths(region: RegionData, plane: Int, x: Int, y: Int): FloatArray {
        val bed = underwaterFor(region.regionId) ?: return NO_WATER_DEPTH
        val bedTiles = bed.tiles[plane]
        val surfaceTiles = region.tiles[plane]
        val x1 = if (x < 63) x + 1 else 63
        val y1 = if (y < 63) y + 1 else 63
        // Both sides read the same clamped in-region tile so the subtraction stays consistent;
        // mixing in the neighbour-extended surface height would fabricate depth at the seam.
        val depths = floatArrayOf(
            (surfaceTiles[x][y].height - bedTiles[x][y].height).toFloat(),
            (surfaceTiles[x1][y].height - bedTiles[x1][y].height).toFloat(),
            (surfaceTiles[x][y1].height - bedTiles[x][y1].height).toFloat(),
            (surfaceTiles[x1][y1].height - bedTiles[x1][y1].height).toFloat()
        )
        for (i in 0 until 4) if (depths[i] < 0f) depths[i] = 0f
        return depths
    }

    /** Shared all-zero depth corners for the overwhelming majority of tiles that are dry. */
    private val NO_WATER_DEPTH = FloatArray(4)

    /**
     * How opaque a water surface is over the bed once the water is deep enough to be fully
     * fogged, so just under two fifths of what you see in an open pool is the bed underneath.
     * In the shallows this is scaled down by the depth fog term -- see [generateShapedTile]'s
     * shoreline block.
     *
     * **Tuned by eye, not taken from the client.** The client composites water per vertex
     * (`GroundSM.blendTiles`: `alpha = faceAlphas[corner] * 255 / waterTile.scale`, fed to
     * `ColorUtil.blendColorsWithAlpha`), so there is no constant surface opacity there to
     * copy -- its alpha varies per corner with the map's own water data, which this loader
     * does not reconstruct. 155/255 is simply the value that looked right against the client
     * across the water we compared, and it is written as a fraction of 255 only because that
     * is the range the comparison was made in. Changing it changes how water looks; there is
     * no reference to check it against.
     */
    private const val WATER_SURFACE_OPACITY = 155f / 255f

    /**
     * How much wider than the overlay's own `waterFogDepth` the shoreline ramp is spread.
     *
     * This is the one place the shoreline knowingly departs from the client's numbers, and it
     * is forced by where the two evaluate the fog. The client rasterises the pool floor as its
     * own geometry and evaluates `depth * 255 / waterFogDepth` per fragment, so its ramp can
     * live inside a 24-unit slice of depth. We bake the same expression into vertex colours on
     * the surface mesh, and the only depth samples that exist there are the tile corners, 512
     * units apart. In this cache a shoreline tile steps from 0 to 64 or more units of depth
     * across one such interval, so evaluated at 24 the ramp collapses onto the outermost
     * vertex and the band survives the bake as a scalloped one-pixel fringe -- the thin, uneven
     * rim this replaces.
     *
     * Spreading the ramp to 4x puts the mud where the sampling grid can actually carry it: the
     * whole ring of tiles that touch the waterline reads as wet mud and fades to open water
     * over the next, which is what the fringe looks like at the client's own resolution. It
     * scales with the overlay's own value, so overlays that declare deeper fog still get
     * proportionally more open water.
     */
    private const val SHORE_FOG_SPREAD = 4f

    /**
     * Generate vertices for a single tile, running the full blending analysis pipeline.
     * Returns a list of ShapedVertex objects for all triangles of this tile.
     *
     * @param region The region data
     * @param plane The plane level
     * @param x Tile X coordinate within region
     * @param y Tile Y coordinate within region
     * @param blendedColors Pre-computed blended underlay colors for this plane
     * @param normalsX Pre-computed vertex normals X components
     * @param normalsY Pre-computed vertex normals Y components
     * @param normalsZ Pre-computed vertex normals Z components
     * @return List of ShapedVertex objects for all triangles of this tile
     */
    internal fun generateTileVertices(
        region: RegionData, plane: Int,
        x: Int, y: Int,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: NeighborData? = null
    ): List<ShapedVertex> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val tile = region.tiles[plane][x][y]

        // Skip tiles with no overlay and no underlay
        if (tile.overlayId == 0 && tile.underlayId == 0) return emptyList()

        // Run full blending analysis for this tile
        val profile = profileSubPhases
        val subPhase = if (profile) tileSubPhaseNanos.get() else null
        var mark = if (profile) System.nanoTime() else 0L
        val blendState = analyzeTileBlending(region, plane, x, y, blendedColors)
        if (subPhase != null) {
            val now = System.nanoTime()
            subPhase[0] += now - mark
            mark = now
        }

        // Calculate heights for this tile's corners using extended lookups for edge tiles.
        // At region edges (x=63 or y=63), the +1 coordinates read from neighbor regions
        // instead of clamping to the same tile (which caused height seams).
        // heights array: [SW, SE, NW, NE]
        val heights = floatArrayOf(
            getExtendedHeight(region, neighbors, plane, x, y),         // SW (0,0)
            getExtendedHeight(region, neighbors, plane, x + 1, y),     // SE (1,0)
            getExtendedHeight(region, neighbors, plane, x, y + 1),     // NW (0,1)
            getExtendedHeight(region, neighbors, plane, x + 1, y + 1)  // NE (1,1)
        )

        // Water depth at the same four corners, from the region's underwater map. Zero
        // everywhere the map has no bed, which is everywhere that is not water.
        val cornerDepths = cornerWaterDepths(region, plane, x, y)

        // Get overlay and underlay definitions
        val overlayDef = if (tile.overlayId > 0) getOverlayDefinition(tile.overlayId) else null
        val underlayDef = if (tile.underlayId > 0) getUnderlayDefinition(tile.underlayId) else null

        // Get blended corner HSL colors for underlay (SW, SE, NW, NE order)
        val underlayCornerHsl = getCornerHslColors(blendedColors, x, y)
        val overlayColor = if (tile.overlayId > 0) getOverlayColor(tile.overlayId) else Vector3f(0f, 0f, 0f)

        // Determine texture IDs
        val overlayTextureId = overlayDef?.texture ?: -1
        val underlayTextureId = underlayDef?.texture ?: -1

        // Determine raw texture scales (RS units; 512 == one texture repeat per tile).
        // The UV scale used for vertex generation is derived from these downstream, and the
        // raw value also feeds the client's material-ordering key (scale << 14 | textureId).
        val overlayScaleRaw = if (overlayDef != null && overlayDef.texture >= 0 && overlayDef.scale > 0) {
            overlayDef.scale
        } else {
            512
        }
        val underlayScaleRaw = if (underlayDef != null && underlayDef.texture >= 0 && underlayDef.scale > 0) {
            underlayDef.scale
        } else {
            512
        }

        // Get corner underlay definitions for per-vertex texture selection
        // (matching client's addUnderlayTiles: each corner uses the underlay from its adjacent tile)
        // SW corner = this tile, SE corner = east neighbor, NW corner = north neighbor, NE corner = NE neighbor
        val cornerUnderlayTextures: IntArray
        val cornerUnderlayScalesRaw: IntArray
        if (underlayDef != null) {
            // Corner underlay IDs using extended lookups for edge tiles.
            // At region edges, reads from neighbor regions instead of clamping.
            val swUnderlayId = tile.underlayId  // This tile
            val seUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x + 1, y).let { if (it == 0) swUnderlayId else it }
            val nwUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x, y + 1).let { if (it == 0) swUnderlayId else it }
            val neUnderlayId = getExtendedUnderlayId(region, neighbors, plane, x + 1, y + 1).let { if (it == 0) swUnderlayId else it }

            val swDef = getUnderlayDefinition(swUnderlayId)
            val seDef = getUnderlayDefinition(seUnderlayId)
            val nwDef = getUnderlayDefinition(nwUnderlayId)
            val neDef = getUnderlayDefinition(neUnderlayId)

            cornerUnderlayTextures = intArrayOf(
                swDef?.texture ?: -1,
                seDef?.texture ?: -1,
                nwDef?.texture ?: -1,
                neDef?.texture ?: -1
            )
            cornerUnderlayScalesRaw = intArrayOf(
                if (swDef != null && swDef.texture >= 0 && swDef.scale > 0) swDef.scale else 512,
                if (seDef != null && seDef.texture >= 0 && seDef.scale > 0) seDef.scale else 512,
                if (nwDef != null && nwDef.texture >= 0 && nwDef.scale > 0) nwDef.scale else 512,
                if (neDef != null && neDef.texture >= 0 && neDef.scale > 0) neDef.scale else 512
            )
        } else {
            cornerUnderlayTextures = intArrayOf(-1, -1, -1, -1)
            cornerUnderlayScalesRaw = intArrayOf(512, 512, 512, 512)
        }

        // Adjust colors for textured tiles
        val finalOverlayColor = if (overlayTextureId >= 0) {
            if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
                ColorUtil.hslToRgb(overlayDef.colour)
            } else {
                Vector3f(1f, 1f, 1f)
            }
        } else {
            overlayColor
        }

        // Get overlay's packed HSL color
        val overlayHsl = if (overlayDef != null && overlayDef.colour > 0 && overlayDef.colour != -1) {
            overlayDef.colour
        } else {
            0
        }

        // Determine the effective shape and rotation from blend analysis
        val effectiveShape = blendState?.effectiveShape ?: run {
            var s = tile.overlayShape
            if (s == 0 && tile.overlayId == 0) s = 12
            s
        }
        val effectiveRotation = blendState?.effectiveRotation ?: tile.overlayRotation

        // Get pre-computed normals for this tile's 4 corners.
        // Normals arrays are 65x65, so x+1 and y+1 (up to 64) are always valid indices.
        val cornerNormals = arrayOf(
            Vector3f(normalsX[x][y], normalsY[x][y], normalsZ[x][y]),
            Vector3f(normalsX[x + 1][y], normalsY[x + 1][y], normalsZ[x + 1][y]),
            Vector3f(normalsX[x][y + 1], normalsY[x][y + 1], normalsZ[x][y + 1]),
            Vector3f(normalsX[x + 1][y + 1], normalsY[x + 1][y + 1], normalsZ[x + 1][y + 1])
        )

        // Determine if overlay/underlay exist
        val hasValidOverlay = if (overlayDef != null) {
            overlayDef.texture >= 0 || !(overlayDef.colour == -1 && overlayDef.blendColour == -1)
        } else false
        val hasOverlay = tile.overlayId > 0 && hasValidOverlay
        val hasValidUnderlayColors = underlayCornerHsl.all { it != 0 }
        val hasUnderlay = tile.underlayId > 0 && underlayDef != null && hasValidUnderlayColors

        if (subPhase != null) {
            val now = System.nanoTime()
            subPhase[2] += now - mark
            mark = now
        }

        // Generate shaped vertices for this tile using the blend state
        val shaped = generateShapedTile(
            x, y,
            effectiveShape, effectiveRotation,
            tileSize,
            heights,
            finalOverlayColor,
            underlayCornerHsl,
            overlayTextureId,
            underlayTextureId,
            overlayScaleRaw,
            underlayScaleRaw,
            overlayDef?.underlayOverrides ?: false,
            overlayHsl,
            cornerNormals,
            hasOverlay = hasOverlay,
            hasUnderlay = hasUnderlay,
            regionId = region.regionId,
            blendState = blendState,
            cornerUnderlayTextures = cornerUnderlayTextures,
            cornerUnderlayScalesRaw = cornerUnderlayScalesRaw,
            cornerDepths = cornerDepths,
            waterFogDepth = overlayDef?.waterScale ?: 0,
            isWater = tile.overlayId > 0 &&
                waterOverlayCache[region.regionId]?.contains(tile.overlayId) == true,
            waterColor = packedRgbToVector(overlayDef?.waterColour ?: 0)
        )
        if (subPhase != null) subPhase[1] += System.nanoTime() - mark
        return shaped
    }

    /** 0xRRGGBB to a 0..1 [Vector3f]. */
    private fun packedRgbToVector(packed: Int): Vector3f = Vector3f(
        ((packed shr 16) and 0xFF) / 255f,
        ((packed shr 8) and 0xFF) / 255f,
        (packed and 0xFF) / 255f
    )

    /**
     * Create separate mesh batches for textured and untextured terrain tiles.
     * Delegates to [computeTerrainMeshData] (pure CPU) followed by [uploadTerrainMeshData] (GL-only).
     *
     * Uses proper tile shape tessellation with the RS client's shape lookup tables.
     * Shapes create paths, edges, diagonal cuts, etc. by tessellating tiles into
     * multiple triangles with some belonging to overlay and others to underlay.
     */
    private fun createBatchedTerrainMeshes(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshes {
        val meshData = computeTerrainMeshData(region, plane, neighbors)
        return uploadTerrainMeshData(meshData)
    }

    /**
     * Compute all terrain mesh array data for a region/plane without any OpenGL calls.
     *
     * This function is the pure-CPU phase of terrain mesh generation. It performs:
     * 1. Per-vertex normal computation
     * 2. Blended underlay color computation (11x11 sliding window)
     * 3. Tile vertex generation for all 64x64 tiles
     * 4. Separation of vertices into opaque color, opaque textured, and alpha groups
     * 5. Packing of vertex attributes into FloatArray/IntArray data
     *
     * The returned [TerrainMeshData] contains no GL resources and is safe to compute
     * on any thread. Pass the result to [uploadTerrainMeshData] on the main GL thread
     * to create GPU-resident [Mesh] objects.
     *
     * Thread safety: This function reads only from immutable [RegionData], pre-computed
     * blended colors, and pre-computed normals. All intermediate state is local.
     * Multiple invocations on different regions can run concurrently.
     *
     * @param region The region data containing tile information
     * @param plane The plane level (0-3)
     * @param neighbors Optional neighbor data for seamless edge blending
     * @return Pre-computed mesh array data ready for GPU upload
     */
    fun computeTerrainMeshData(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshData {
        if (!profileSubPhases) return computeTerrainMeshDataInternal(region, plane, neighbors)

        // Wall clocks are useless for this phase on a busy machine: nine regions tessellate
        // against a dispatcher that also runs model decode and batching, so most of the
        // measured span is time the coroutine spent off-CPU. Thread CPU time answers the
        // only question an optimisation of this function can be judged by -- did the work
        // itself get cheaper -- and is immune to how contended the box is.
        val threads = threadMxBean
        val cpuStart = if (threads != null && threads.isCurrentThreadCpuTimeSupported) {
            threads.currentThreadCpuTime
        } else -1L
        val result = computeTerrainMeshDataInternal(region, plane, neighbors)
        if (cpuStart >= 0 && threads != null) {
            RegionLoadProfiler.record("tm:threadCpu", threads.currentThreadCpuTime - cpuStart)
        }
        return result
    }

    /** Used to charge [computeTerrainMeshData] in thread CPU time rather than wall time. */
    private val threadMxBean: ThreadMXBean? = try {
        ManagementFactory.getThreadMXBean()
    } catch (e: Exception) {
        null
    }

    private fun computeTerrainMeshDataInternal(region: RegionData, plane: Int, neighbors: NeighborData? = null): TerrainMeshData {
        val profile = profileSubPhases
        val subPhase = if (profile) tileSubPhaseNanos.get().also { it.fill(0L) } else null
        var mark = if (profile) System.nanoTime() else 0L

        // Pre-compute per-vertex normals for the main plane, using neighbor data
        // for correct normals at region boundaries.
        val (vertexNormalsX, vertexNormalsY, vertexNormalsZ) = computeVertexNormals(region.tiles, plane, neighbors)
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:normals", now - mark)
            mark = now
        }

        // Pre-compute blended underlay colors for the main plane, using neighbor data
        // so the 11x11 blending window extends into neighboring regions at edges.
        val blendedUnderlayColors = computeBlendedUnderlayColors(region, plane, neighbors)
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:blend", now - mark)
            mark = now
        }

        // Collect vertices grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        // Iterate through all 64x64 tiles
        //
        // TERRAIN GENERATION (based on RS client analysis of MapLoader.kt addBlendedTiles):
        // The RS client generates terrain for ALL tiles where overlayId != 0 || underlayId != 0.
        // There is NO special handling for bridge flags in terrain generation.
        //
        // The bridge flag (tileFunctionMasks[1][x][y] & 0x2) affects:
        // - linkBelow(): shifts Scene Tile OBJECTS down one plane (called AFTER terrain generation)
        // - isVisible(): determines Scene Tile visibility for objects, not terrain
        // - getCollisionPlane(): adjusts collision detection
        //
        // None of these affect terrain mesh generation. The generateTileVertices function
        // already skips tiles with no underlay/overlay data.
        //
        // IMPORTANT: The RS client does NOT use visibility checks for terrain generation.
        // addBlendedTiles() in MapLoader.kt simply iterates all tiles and generates terrain
        // for any tile with overlayId != 0 || underlayId != 0.
        //
        // The isVisible() function is used for OBJECTS (entities), not terrain.
        // Terrain is always generated regardless of the INVISIBLE flag or bridge flags.
        //
        // The generateTileVertices function already skips tiles with no overlay/underlay data.
        // The hasOverlay/hasUnderlay checks handle the RS client's null-setting logic for
        // overlays with colour == -1 (matching RS client behavior).
        // Consecutive vertices overwhelmingly share a texture -- a triangle's three vertices
        // always do -- so the last resolved group is kept and reused instead of hashing a
        // boxed Int key for every one of the region's ~100k vertices. Vertices are still
        // appended in exactly the same order, so the grouping is unchanged.
        val groupMemo = fastTessellation
        var lastTexId = Int.MIN_VALUE
        var lastGroup: MutableList<ShapedVertex>? = null
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val vertices = generateTileVertices(
                    region, plane,
                    x, y,
                    blendedUnderlayColors,
                    vertexNormalsX,
                    vertexNormalsY,
                    vertexNormalsZ,
                    neighbors
                )
                for (vertex in vertices) {
                    val texId = vertex.textureId
                    var group = lastGroup
                    if (!groupMemo || group == null || texId != lastTexId) {
                        group = textureGroups.getOrPut(texId) { mutableListOf() }
                        lastGroup = group
                        lastTexId = texId
                    }
                    group.add(vertex)
                }
            }
        }
        if (profile && subPhase != null) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:tilesTotal", now - mark)
            RegionLoadProfiler.record("tm:tileAnalyze", subPhase[0])
            RegionLoadProfiler.record("tm:tileShape", subPhase[1])
            RegionLoadProfiler.record("tm:tilePrep", subPhase[2])
            mark = now
        }

        // Separate vertices into opaque (alpha == 1.0) and alpha (alpha < 1.0) groups.
        // Opaque vertices go into standard textured/color batches.
        // Alpha vertices go into alpha batches rendered with the terrain_alpha shader.
        //
        // Vertices are processed in groups of 3 (triangles). If ALL vertices in a
        // triangle have alpha == 1.0, it goes to the opaque group. Otherwise it goes
        // to an alpha group keyed by texture ID.
        val opaqueGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<ShapedVertex>>()

        for ((texId, verts) in textureGroups) {
            // Process in groups of 3 (triangles)
            var i = 0
            while (i + 2 < verts.size) {
                val v0 = verts[i]
                val v1 = verts[i + 1]
                val v2 = verts[i + 2]

                // Check if any vertex in this triangle has alpha < 1.0
                val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

                if (needsAlpha && v0.textureId >= 0) {
                    // Alpha layer - group by texture ID
                    val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                } else {
                    // Opaque - add to normal group
                    val group = opaqueGroups.getOrPut(texId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                }
                i += 3
            }
            // Handle remaining vertices (should be 0 if all triangles are complete)
            while (i < verts.size) {
                opaqueGroups.getOrPut(texId) { mutableListOf() }.add(verts[i])
                i++
            }
        }
        if (profile) {
            val now = System.nanoTime()
            RegionLoadProfiler.record("tm:split", now - mark)
            mark = now
        }

        // Capture atlas lookup once for this computation (thread-safe snapshot)
        val currentAtlasLookup = atlasLookup
        val currentSentinelTransform = sentinelAtlasTransform

        // Build color mesh arrays (textureId == -1) with RGB colors
        val colorArrays = opaqueGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)
            // Atlas mode: include texCoords (all zeros) and atlas transforms (sentinel)
            val texCoords = if (currentAtlasLookup != null) FloatArray(vertices.size * 2) else null
            val atlasTransforms = if (currentAtlasLookup != null) FloatArray(vertices.size * 4) else null

            vertices.forEachIndexed { i, v ->
                positions[i * 3] = v.x
                positions[i * 3 + 1] = v.y
                positions[i * 3 + 2] = v.z
                colors[i * 3] = v.r
                colors[i * 3 + 1] = v.g
                colors[i * 3 + 2] = v.b
                normals[i * 3] = v.nx
                normals[i * 3 + 1] = v.ny
                normals[i * 3 + 2] = v.nz
                indices[i] = i
                if (texCoords != null) {
                    texCoords[i * 2] = 0f
                    texCoords[i * 2 + 1] = 0f
                }
                if (atlasTransforms != null) {
                    atlasTransforms[i * 4] = currentSentinelTransform[0]
                    atlasTransforms[i * 4 + 1] = currentSentinelTransform[1]
                    atlasTransforms[i * 4 + 2] = currentSentinelTransform[2]
                    atlasTransforms[i * 4 + 3] = currentSentinelTransform[3]
                }
            }

            ColorMeshArrays(positions, colors, normals, indices, texCoords, atlasTransforms)
        }

        // Build opaque textured mesh arrays with RGB colors
        val texturedArrays = mutableListOf<TexturedMeshArrays>()
        for ((texId, vertices) in opaqueGroups) {
            if (texId == -1) continue // Skip color group
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val indices = IntArray(vertices.size)
            // Look up atlas transform once per batch (all vertices share the same texture).
            // A null result means the atlas cannot serve this texture from the page the
            // viewer binds, so the batch gets no transforms and stays on the per-texture path.
            val batchAtlasTransform = currentAtlasLookup?.invoke(texId)
            val atlasTransforms = if (batchAtlasTransform != null) FloatArray(vertices.size * 4) else null

            vertices.forEachIndexed { i, v ->
                positions[i * 3] = v.x
                positions[i * 3 + 1] = v.y
                positions[i * 3 + 2] = v.z
                colors[i * 3] = v.r
                colors[i * 3 + 1] = v.g
                colors[i * 3 + 2] = v.b
                texCoords[i * 2] = v.u
                texCoords[i * 2 + 1] = v.v
                normals[i * 3] = v.nx
                normals[i * 3 + 1] = v.ny
                normals[i * 3 + 2] = v.nz
                indices[i] = i
                if (atlasTransforms != null && batchAtlasTransform != null) {
                    atlasTransforms[i * 4] = batchAtlasTransform[0]
                    atlasTransforms[i * 4 + 1] = batchAtlasTransform[1]
                    atlasTransforms[i * 4 + 2] = batchAtlasTransform[2]
                    atlasTransforms[i * 4 + 3] = batchAtlasTransform[3]
                }
            }

            texturedArrays.add(TexturedMeshArrays(positions, colors, texCoords, normals, indices, texId, atlasTransforms))
        }

        // Build alpha mesh arrays (per-vertex alpha for multi-pass blending)
        val alphaArrays = mutableListOf<AlphaMeshArrays>()
        for ((texId, vertices) in alphaGroups) {
            if (vertices.isEmpty()) continue

            val positions = FloatArray(vertices.size * 3)
            val colors = FloatArray(vertices.size * 3)
            val texCoords = FloatArray(vertices.size * 2)
            val normals = FloatArray(vertices.size * 3)
            val alphas = FloatArray(vertices.size)
            val indices = IntArray(vertices.size)
            val batchAtlasTransform = currentAtlasLookup?.invoke(texId)
            val atlasTransforms = if (batchAtlasTransform != null) FloatArray(vertices.size * 4) else null

            vertices.forEachIndexed { i, v ->
                positions[i * 3] = v.x
                positions[i * 3 + 1] = v.y
                positions[i * 3 + 2] = v.z
                colors[i * 3] = v.r
                colors[i * 3 + 1] = v.g
                colors[i * 3 + 2] = v.b
                texCoords[i * 2] = v.u
                texCoords[i * 2 + 1] = v.v
                normals[i * 3] = v.nx
                normals[i * 3 + 1] = v.ny
                normals[i * 3 + 2] = v.nz
                alphas[i] = v.alpha
                indices[i] = i
                if (atlasTransforms != null && batchAtlasTransform != null) {
                    atlasTransforms[i * 4] = batchAtlasTransform[0]
                    atlasTransforms[i * 4 + 1] = batchAtlasTransform[1]
                    atlasTransforms[i * 4 + 2] = batchAtlasTransform[2]
                    atlasTransforms[i * 4 + 3] = batchAtlasTransform[3]
                }
            }

            alphaArrays.add(AlphaMeshArrays(positions, colors, texCoords, normals, indices, alphas, texId, atlasTransforms))
        }
        if (profile) RegionLoadProfiler.record("tm:pack", System.nanoTime() - mark)

        // Debug: Log which textures are included in this terrain mesh
        if (texturedArrays.isNotEmpty()) {
            val textureList = texturedArrays.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: textured batches with textures [$textureList]")
        }
        if (alphaArrays.isNotEmpty()) {
            val alphaList = alphaArrays.map { it.textureId }.sorted().joinToString(", ")
            println("[TerrainLoader] Region ${region.regionId} plane $plane: alpha batches with textures [$alphaList]")
        }

        return TerrainMeshData(colorArrays, texturedArrays, alphaArrays)
    }

    /**
     * Upload pre-computed terrain mesh data to the GPU, creating [Mesh] objects.
     *
     * This function is the GL-only phase of terrain mesh generation. It takes the
     * [TerrainMeshData] produced by [computeTerrainMeshData] and creates OpenGL
     * VAO/VBO resources from the pre-computed arrays.
     *
     * IMPORTANT: This function MUST be called on the main GL thread only.
     *
     * @param data Pre-computed mesh array data from [computeTerrainMeshData]
     * @return [TerrainMeshes] containing GPU-resident mesh objects ready for rendering
     */
    fun uploadTerrainMeshData(data: TerrainMeshData): TerrainMeshes {
        // Upload color mesh.
        // When atlas transforms are present, use the atlas-aware factory method
        // so color-only tiles can be merged into atlas draw calls.
        val colorMesh = data.colorArrays?.let { arrays ->
            if (arrays.atlasTransforms != null && arrays.texCoords != null) {
                Mesh.createWithAtlasTransform(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh(arrays.positions, arrays.colors, null, arrays.normals, arrays.indices, colorComponents = 3)
            }
        }

        // Upload opaque textured batches.
        // When atlas transforms are present, include them as a vertex attribute.
        val texturedBatches = data.texturedArrays.map { arrays ->
            val mesh = if (arrays.atlasTransforms != null) {
                Mesh.createWithAtlasTransform(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh(arrays.positions, arrays.colors, arrays.texCoords, arrays.normals, arrays.indices, colorComponents = 3)
            }
            TerrainBatch(mesh, arrays.textureId, inAtlas = arrays.atlasTransforms != null)
        }

        // Upload alpha terrain batches.
        // When atlas transforms are present, include both alpha and atlas transform attributes.
        val alphaBatches = data.alphaArrays.map { arrays ->
            val mesh = if (arrays.atlasTransforms != null) {
                Mesh.createWithAtlasAndAlpha(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.alphas, arrays.atlasTransforms, arrays.indices
                )
            } else {
                Mesh.createWithAlpha(
                    arrays.positions, arrays.colors, arrays.texCoords, arrays.normals,
                    arrays.alphas, arrays.indices
                )
            }
            AlphaTerrainBatch(mesh, arrays.textureId, inAtlas = arrays.atlasTransforms != null)
        }

        val colorInAtlas = data.colorArrays?.let { it.atlasTransforms != null && it.texCoords != null } ?: false
        return TerrainMeshes(colorMesh, texturedBatches, alphaBatches, colorInAtlas)
    }

    /**
     * Temporary tile data used during decoding, before height computation.
     * Stores raw height byte and whether explicit height was provided (opcode 1).
     */
    private data class RawTileData(
        val heightByte: Int,        // Raw height byte (0-255), or 0 if opcode 0
        val hasExplicitHeight: Boolean,  // True if opcode 1 was used (explicit height)
        val overlayId: Int,
        val overlayShape: Int,
        val overlayRotation: Int,
        val underlayId: Int,
        val flags: Int
    )

    /**
     * Decode a region from cache with authentic RS client height computation.
     *
     * The RS client computes tile heights cumulatively:
     * - Plane 0: Either procedural noise or explicit height * 32 (negated)
     * - Plane 1+: Previous plane height minus offset (960 default or explicit * 32)
     *
     * Heights in RS are NEGATIVE (higher elevation = more negative value).
     * We store them as positive values for easier use in OpenGL (negate RS convention).
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @return RegionData with computed absolute heights, or null if region doesn't exist
     */
    private fun decodeRegion(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        // First pass: decode raw tile data for all planes
        val rawTiles = Array(4) { Array(64) { Array(64) { RawTileData(0, false, 0, 0, 0, 0, 0) } } }

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    rawTiles[plane][x][y] = decodeRawTile(buffer)
                }
            }
        }

        // Second pass: compute cumulative heights plane by plane
        // Heights are stored as positive values (negated from RS convention)
        // This is the EXACT algorithm from MapLoader.decodeTile in the RS client
        val tiles = Array(4) { Array(64) { Array(64) { TileData(0, 0, 0, 0, 0, 0) } } }

        // Absolute tile coordinates for procedural height generation
        val baseAbsX = regionX * 64
        val baseAbsY = regionY * 64

        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val raw = rawTiles[plane][x][y]
                    val absX = baseAbsX + x
                    val absY = baseAbsY + y

                    // Compute height following RS client logic from MapLoader.decodeTile
                    val height: Int = if (plane == 0) {
                        if (!raw.hasExplicitHeight) {
                            // Opcode 0 on plane 0: use procedural noise
                            // RS: tileHeights[0][x][y] = -TileUtils.calculateTileheight(absX + 932731, absY + 556238) * 8 shl 2
                            // The result is negative in RS (higher = more negative), we store positive
                            TileUtils.calculateTileheight(
                                absX + TileUtils.MAGIC_X_OFFSET,
                                absY + TileUtils.MAGIC_Y_OFFSET
                            ) * TileUtils.HEIGHT_MULTIPLIER
                        } else {
                            // Opcode 1 on plane 0: use explicit height
                            // RS: tileHeights[0][x][y] = -heightByte * 8 shl 2 = -heightByte * 32
                            // We store positive (negate RS convention)
                            raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                        }
                    } else {
                        // Planes 1, 2, 3: cumulative from previous plane
                        val prevHeight = tiles[plane - 1][x][y].height

                        if (!raw.hasExplicitHeight) {
                            // Opcode 0 on plane > 0: use default floor-to-floor offset
                            // RS: tileHeights[plane][x][y] = tileHeights[plane-1][x][y] - 960
                            // In RS, heights are negative, so subtracting makes them more negative (higher)
                            // We store positive, so we ADD the offset to go higher
                            prevHeight + TileUtils.DEFAULT_PLANE_HEIGHT_OFFSET
                        } else {
                            // Opcode 1 on plane > 0: use explicit offset from previous plane
                            // RS: tileHeights[plane][x][y] = tileHeights[plane-1][x][y] - (heightByte * 8 shl 2)
                            // We store positive, so we ADD
                            prevHeight + raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                        }
                    }

                    tiles[plane][x][y] = TileData(
                        height,
                        raw.overlayId,
                        raw.overlayShape,
                        raw.overlayRotation,
                        raw.underlayId,
                        raw.flags
                    )
                }
            }
        }

        return RegionData((regionX shl 8) or regionY, tiles)
    }

    /**
     * Decode the `um<rx>_<ry>` underwater map into absolute bed heights.
     *
     * Same opcode stream as [decodeRegion]; the only difference is the height pass. The
     * underwater archive stores plane 0 as a *delta below the surface*, so a bed tile's
     * absolute height is `surfaceHeight - heightByte * HEIGHT_MULTIPLIER` (heights are stored
     * positive-up here, inverted from RS, so a bed below the surface subtracts). Tiles with
     * no explicit height sit exactly at the surface, i.e. zero depth, which is what the vast
     * majority of an underwater map is -- it only carries real data under the water tiles.
     */
    private fun decodeUnderwaterRegion(
        cache: Cache,
        regionX: Int,
        regionY: Int,
        surface: RegionData
    ): RegionData? {
        val mapArchive = cache.archiveId(Index.MAPS, "um${regionX}_$regionY")
        if (mapArchive == -1) return null

        val data = cache.data(Index.MAPS, mapArchive) ?: return null
        val buffer = BufferReader(data)

        // Seeded with the surface heights so that anything the archive does not describe reads
        // back as zero depth. Underwater maps only carry plane 0, so without this the upper
        // planes would sit at height 0 and every overlay up there would look like deep water.
        val tiles = Array(4) { plane ->
            Array(64) { x ->
                Array(64) { y -> TileData(surface.tiles[plane][x][y].height, 0, 0, 0, 0, 0) }
            }
        }
        // The stream runs out partway through; everything past the end is "no bed here".
        // Breaking out rather than returning matters: the water-overlay set below is still
        // derived from what was decoded.
        decode@ for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val raw = try {
                        decodeRawTile(buffer)
                    } catch (e: Exception) {
                        break@decode
                    }
                    val surfaceHeight = surface.tiles[plane][x][y].height
                    val height = if (raw.hasExplicitHeight) {
                        surfaceHeight - raw.heightByte * TileUtils.HEIGHT_MULTIPLIER
                    } else {
                        surfaceHeight
                    }
                    tiles[plane][x][y] = TileData(
                        height, raw.overlayId, raw.overlayShape,
                        raw.overlayRotation, raw.underlayId, raw.flags
                    )
                }
            }
        }
        val result = RegionData((regionX shl 8) or regionY, tiles)
        waterOverlayCache[result.regionId] = waterOverlayIdsOf(surface, result)
        return result
    }

    /** Overlay ids that have a pool floor beneath them somewhere in this region. */
    private fun waterOverlayIdsOf(surface: RegionData, bed: RegionData): Set<Int> {
        val ids = HashSet<Int>()
        for (plane in 0 until 4) {
            val s = surface.tiles[plane]
            val b = bed.tiles[plane]
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val overlayId = s[x][y].overlayId
                    if (overlayId > 0 && s[x][y].height > b[x][y].height) ids.add(overlayId)
                }
            }
        }
        return ids
    }

    /**
     * Decode raw tile data from the buffer.
     * This extracts the raw bytes without computing cumulative heights.
     */
    private fun decodeRawTile(buffer: BufferReader): RawTileData {
        var heightByte = 0
        var hasExplicitHeight = false
        var overlayId = 0
        var overlayShape = 0
        var overlayRotation = 0
        var underlayId = 0
        var flags = 0

        while (true) {
            val opcode = buffer.readUnsignedByte()
            when {
                opcode == 0 -> {
                    // No explicit height - use procedural noise or default plane offset
                    hasExplicitHeight = false
                    break
                }
                opcode == 1 -> {
                    // Explicit height byte provided
                    heightByte = buffer.readUnsignedByte()
                    hasExplicitHeight = true
                    break
                }
                opcode <= 49 -> {
                    overlayId = buffer.readUnsignedByte()
                    overlayShape = (opcode - 2) / 4
                    overlayRotation = (opcode - 2) and 3
                }
                opcode <= 81 -> {
                    flags = opcode - 49
                }
                else -> {
                    underlayId = opcode - 81
                }
            }
        }

        return RawTileData(heightByte, hasExplicitHeight, overlayId, overlayShape, overlayRotation, underlayId, flags)
    }

    /**
     * Get the color for an underlay ID using actual definition data.
     *
     * Underlay colors in the client use pre-computed HSL components that are
     * designed for blending across tiles. We convert these back to a packed
     * HSL format and then to RGB.
     *
     * Client stores: hue (pre-multiplied by chroma/divisor), saturation (0-255),
     * lightness (0-255), chroma (divisor for averaging).
     */
    fun getUnderlayColor(underlayId: Int): Vector3f {
        if (underlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = underlays
        // RS client uses (underlayId - 1) for definition lookup
        val defIndex = underlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]

            // Use the pre-computed HSL values from UnderlayDecoder
            // The client's calculateHsl16 computes these for blending purposes
            if (def.chroma > 0) {
                // Reconstruct the packed HSL using the same formula as client's hsl16to24
                // hue needs to be divided by chroma to get the actual hue value
                // Then we pack it as: ((hue * 256 / chroma) & 0xff >> 2) << 10 | sat >> 5 << 7 | lightness >> 1
                val hue = def.hue * 256 / def.chroma
                val packedHsl = ColorUtil.packHsl16(hue, def.saturation, def.lightness)
                return ColorUtil.hslToRgb(packedHsl)
            }

            // Fallback to direct RGB conversion if no HSL data
            if (def.colour != 0) {
                return ColorUtil.rgbToVector(def.colour)
            }
        }

        // Fallback colors for when definitions aren't loaded
        return when (underlayId) {
            1 -> Vector3f(0.6f, 0.5f, 0.3f)   // Dirt
            2 -> Vector3f(0.3f, 0.3f, 0.35f)  // Stone
            3 -> Vector3f(0.2f, 0.3f, 0.6f)   // Water
            4 -> Vector3f(0.9f, 0.85f, 0.7f)  // Sand
            else -> DEFAULT_GRASS_COLOR
        }
    }

    /**
     * Get the color for an overlay ID using actual definition data.
     *
     * Overlay colors in the client use `tileColorHsl` for terrain rendering.
     * The color is pre-converted to packed HSL format by OverlayDecoder.shiftRGBColours().
     *
     * Note: `blendColour` (minimapColorHsl) is for minimap rendering, not terrain.
     */
    fun getOverlayColor(overlayId: Int): Vector3f {
        if (overlayId <= 0) return DEFAULT_GRASS_COLOR

        val defs = overlays
        // RS client uses (overlayId - 1) for definition lookup
        val defIndex = overlayId - 1
        if (defs != null && defIndex >= 0 && defIndex < defs.size) {
            val def = defs[defIndex]
            // Use colour (tileColorHsl) for terrain rendering
            // blendColour is for minimap display, not terrain
            val color = def.colour
            if (color != 0 && color != -1) {
                // Overlay colors are stored as packed HSL by shiftRGBColours in OverlayDecoder
                // Format: hue (6 bits @ 10-15) | sat (3 bits @ 7-9) | lightness (7 bits @ 0-6)
                return ColorUtil.hslToRgb(color)
            }
        }

        // Fallback - return default color
        return DEFAULT_GRASS_COLOR
    }

    /**
     * Get underlay definition by ID.
     * Note: RS client uses (id - 1) for definition lookup.
     */
    fun getUnderlayDefinition(underlayId: Int): UnderlayDefinition? {
        val defs = underlays ?: return null
        val defIndex = underlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    /**
     * Get overlay definition by ID.
     * Note: RS client uses (id - 1) for definition lookup.
     */
    fun getOverlayDefinition(overlayId: Int): OverlayDefinition? {
        val defs = overlays ?: return null
        val defIndex = overlayId - 1
        return if (defIndex >= 0 && defIndex < defs.size) defs[defIndex] else null
    }

    /**
     * Collect all unique texture IDs referenced by overlay and underlay definitions.
     * Used to pre-populate a terrain texture atlas with all possible terrain textures.
     *
     * Must be called after [loadDefinitions].
     *
     * @return Set of texture IDs (always >= 0, -1 values filtered out)
     */
    fun getUniqueTextureIds(): Set<Int> {
        val textureIds = mutableSetOf<Int>()
        overlays?.forEach { def ->
            if (def.texture >= 0) textureIds.add(def.texture)
        }
        underlays?.forEach { def ->
            if (def.texture >= 0) textureIds.add(def.texture)
        }
        return textureIds
    }

    fun clearCache() {
        println("[TerrainLoader] clearCache() called - clearing ${batchedMeshCache.size} cached meshes")
        batchedMeshCache.values.forEach { (it as? CacheResult.Found)?.value?.cleanup() }
        batchedMeshCache.clear()
        regionCache.clear()
    }

    /**
     * Clear the batched mesh cache only (GPU resources for terrain meshes).
     * Does NOT clear the region data cache, so terrain tile data can be reused.
     * Call this when context region meshes need to be freed between region loads.
     */
    fun clearBatchedMeshCache() {
        batchedMeshCache.values.forEach { (it as? CacheResult.Found)?.value?.cleanup() }
        batchedMeshCache.clear()
    }

    /**
     * Clear all caches including definitions.
     */
    fun clearAll() {
        clearCache()
        underlays = null
        overlays = null
        definitionsLoaded = false
    }

    /**
     * Get a list of valid region IDs in the cache.
     */
    fun getAvailableRegions(cache: Cache): List<Pair<Int, Int>> {
        val regions = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                val archiveId = cache.archiveId(Index.MAPS, "m${x}_$y")
                if (archiveId != -1) {
                    regions.add(Pair(x, y))
                }
            }
        }
        return regions
    }

    private val DEFAULT_GRASS_COLOR = Vector3f(0.3f, 0.55f, 0.25f)

    /**
     * Height scaling factor for converting RS heights to OpenGL world coordinates.
     *
     * Heights in TileData are now stored as absolute computed values in RS units,
     * matching exactly what the RS client computes:
     * - Plane 0: procedural noise * 32 or heightByte * 32
     * - Plane 1+: previousHeight + offset (960 default or heightByte * 32)
     *
     * We store heights as POSITIVE values (opposite of RS convention where heights are negative).
     * This means higher terrain has larger height values.
     *
     * To convert to OpenGL world coordinates, we just apply the standard scale factor.
     * DEFAULT_SCALE = 1/128, so HEIGHT_SCALE = 1/128.
     *
     * Example: A tile at ground level might have height = 1920 (60 * 32)
     * OpenGL Y = 1920 * (1/128) = 15.0 units
     */
    private const val HEIGHT_SCALE = CoordinateUtil.DEFAULT_SCALE  // = 1/128

    // ==================== TILE SHAPE TESSELLATION SYSTEM ====================
    //
    // RS tiles use a sophisticated tessellation system where each tile can have
    // different shapes (not just squares). The shape determines how the tile is
    // divided into triangles, with some triangles belonging to the overlay and
    // others to the underlay.
    //
    // There are 13 predefined vertex positions within each tile:
    // Index 0: SW corner (0, 0)
    // Index 1: South midpoint (256, 0)
    // Index 2: SE corner (512, 0)
    // Index 3: East midpoint (512, 256)
    // Index 4: NE corner (512, 512)
    // Index 5: North midpoint (256, 512)
    // Index 6: NW corner (0, 512)
    // Index 7: West midpoint (0, 256)
    // Index 8: Interior (128, 256)
    // Index 9: Interior (256, 384)
    // Index 10: Interior (128, 128)
    // Index 11: Interior (384, 128)
    // Index 12: Center (256, 256)
    //
    // Tile unit is 512, so these define positions as fractions of the tile.

    /**
     * X offsets for the 13 vertex positions within a tile (0-512 scale).
     * Indexed by vertex index 0-12.
     */
    private val TILE_VERTEX_X = intArrayOf(0, 256, 512, 512, 512, 256, 0, 0, 128, 256, 128, 384, 256)

    /**
     * Y offsets for the 13 vertex positions within a tile (0-512 scale).
     * Indexed by vertex index 0-12.
     */
    private val TILE_VERTEX_Y = intArrayOf(0, 0, 0, 256, 512, 512, 512, 256, 256, 384, 128, 128, 256)

    /**
     * Number of triangles belonging to the overlay for each shape (0-14).
     * Shape 12 has 0 overlay faces (empty/special), shape 13 has 4 (full tile).
     */
    private val OVERLAY_FACE_COUNT = intArrayOf(2, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    /**
     * Number of triangles belonging to the underlay for each shape (0-14).
     */
    private val UNDERLAY_FACE_COUNT = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 2, 0, 4)

    /**
     * Enhanced overlay face count when using enhanced tessellation.
     * Shapes 0 and 13 get 4 triangles instead of 2 (using center vertex).
     */
    private val OVERLAY_FACE_COUNT_ENHANCED = intArrayOf(4, 1, 1, 1, 2, 2, 2, 1, 3, 3, 3, 2, 0, 4, 0)

    /**
     * Enhanced underlay face count when using enhanced tessellation.
     * Shape 12 and 14 get 4 triangles instead of 2 (using center vertex).
     */
    private val UNDERLAY_FACE_COUNT_ENHANCED = intArrayOf(0, 1, 2, 2, 1, 1, 2, 3, 1, 3, 3, 4, 4, 0, 4)

    /**
     * Triangle vertex A indices for each shape.
     * First OVERLAY_FACE_COUNT[shape] entries are overlay triangles,
     * remaining entries are underlay triangles.
     */
    private val TRIANGLE_A = arrayOf(
        intArrayOf(0, 2),           // Shape 0: 2 overlay faces
        intArrayOf(0, 2),           // Shape 1: 1 overlay, 1 underlay
        intArrayOf(0, 0, 2),        // Shape 2: 1 overlay, 2 underlay
        intArrayOf(2, 0, 0),        // Shape 3: 1 overlay, 2 underlay
        intArrayOf(0, 2, 0),        // Shape 4: 2 overlay, 1 underlay
        intArrayOf(0, 0, 2),        // Shape 5: 2 overlay, 1 underlay
        intArrayOf(0, 5, 1, 4),     // Shape 6: 2 overlay, 2 underlay
        intArrayOf(0, 4, 4, 4),     // Shape 7: 1 overlay, 3 underlay
        intArrayOf(4, 4, 4, 0),     // Shape 8: 3 overlay, 1 underlay
        intArrayOf(6, 6, 6, 2, 2, 2), // Shape 9: 3 overlay, 3 underlay
        intArrayOf(2, 2, 2, 6, 6, 6), // Shape 10: 3 overlay, 3 underlay
        intArrayOf(0, 11, 6, 6, 6, 4), // Shape 11: 2 overlay, 4 underlay
        intArrayOf(0, 2),           // Shape 12: 0 overlay, 2 underlay
        intArrayOf(0, 4, 4, 4),     // Shape 13: 4 overlay, 0 underlay
        intArrayOf(0, 4, 4, 4)      // Shape 14: 0 overlay, 4 underlay
    )

    /**
     * Triangle vertex B indices for each shape.
     */
    private val TRIANGLE_B = arrayOf(
        intArrayOf(2, 4),
        intArrayOf(2, 4),
        intArrayOf(5, 2, 4),
        intArrayOf(4, 5, 2),
        intArrayOf(2, 4, 5),
        intArrayOf(5, 2, 4),
        intArrayOf(1, 6, 2, 5),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(6, 7, 1, 1),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(2, 10, 0, 10, 11, 11),
        intArrayOf(2, 4),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(1, 6, 7, 1)
    )

    /**
     * Triangle vertex C indices for each shape.
     */
    private val TRIANGLE_C = arrayOf(
        intArrayOf(6, 6),
        intArrayOf(6, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 6, 5),
        intArrayOf(5, 5, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 0, 4, 1),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 1, 2, 7),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(11, 0, 10, 11, 4, 2),
        intArrayOf(6, 6),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 7, 1, 2)
    )

    /**
     * Enhanced triangle vertex A indices for shapes 0, 12, 13, 14.
     * These use the center vertex (12) to create 4 triangles instead of 2,
     * which allows proper HSL-space color interpolation at the center.
     *
     * For shape 0 (full overlay): 4 triangles from center to each edge
     *   SW-SE-center, SE-NE-center, NE-NW-center, NW-SW-center
     *
     * For shape 12 (full underlay): same as shape 0 but all underlay
     */
    private val TRIANGLE_A_ENHANCED = arrayOf(
        intArrayOf(0, 2, 4, 6),     // Shape 0: 4 overlay faces (SW, SE, NE, NW corners)
        intArrayOf(0, 2),           // Shape 1: unchanged
        intArrayOf(0, 0, 2),        // Shape 2: unchanged
        intArrayOf(2, 0, 0),        // Shape 3: unchanged
        intArrayOf(0, 2, 0),        // Shape 4: unchanged
        intArrayOf(0, 0, 2),        // Shape 5: unchanged
        intArrayOf(0, 5, 1, 4),     // Shape 6: unchanged
        intArrayOf(0, 4, 4, 4),     // Shape 7: unchanged
        intArrayOf(4, 4, 4, 0),     // Shape 8: unchanged
        intArrayOf(6, 6, 6, 2, 2, 2), // Shape 9: unchanged
        intArrayOf(2, 2, 2, 6, 6, 6), // Shape 10: unchanged
        intArrayOf(0, 11, 6, 6, 6, 4), // Shape 11: unchanged
        intArrayOf(0, 2, 4, 6),     // Shape 12: 4 underlay faces (using center)
        intArrayOf(0, 4, 4, 4),     // Shape 13: unchanged
        intArrayOf(0, 4, 4, 4)      // Shape 14: unchanged
    )

    /**
     * Enhanced triangle vertex B indices.
     */
    private val TRIANGLE_B_ENHANCED = arrayOf(
        intArrayOf(2, 4, 6, 0),     // Shape 0: SW->SE, SE->NE, NE->NW, NW->SW
        intArrayOf(2, 4),
        intArrayOf(5, 2, 4),
        intArrayOf(4, 5, 2),
        intArrayOf(2, 4, 5),
        intArrayOf(5, 2, 4),
        intArrayOf(1, 6, 2, 5),
        intArrayOf(1, 6, 7, 1),
        intArrayOf(6, 7, 1, 1),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(2, 10, 0, 10, 11, 11),
        intArrayOf(2, 4, 6, 0),     // Shape 12: same pattern
        intArrayOf(1, 6, 7, 1),
        intArrayOf(1, 6, 7, 1)
    )

    /**
     * Enhanced triangle vertex C indices.
     * All use center vertex (12) for shapes 0 and 12.
     */
    private val TRIANGLE_C_ENHANCED = arrayOf(
        intArrayOf(12, 12, 12, 12), // Shape 0: all triangles meet at center
        intArrayOf(6, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 6, 5),
        intArrayOf(5, 5, 6),
        intArrayOf(6, 5, 5),
        intArrayOf(5, 0, 4, 1),
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 1, 2, 7),
        intArrayOf(8, 9, 4, 0, 8, 9),
        intArrayOf(0, 8, 9, 8, 9, 4),
        intArrayOf(11, 0, 10, 11, 4, 2),
        intArrayOf(12, 12, 12, 12), // Shape 12: all triangles meet at center
        intArrayOf(7, 7, 1, 2),
        intArrayOf(7, 7, 1, 2)
    )

    // ==================== NEIGHBOR-DRIVEN BLENDING MODE TABLES ====================
    //
    // Used when !overlaySupportsBlending && any hasFacesOn[0..3] is true.
    // This mode creates blended transitions between neighboring overlay tiles
    // by inserting additional edge triangles at tile boundaries.

    /** Neighbor-driven mode: vertex A indices (client: anIntArrayArray3774) */
    private val NEIGHBOR_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 4), intArrayOf(6, 0, 2),
        intArrayOf(2, 6, 0), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 2, 4, 4), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 2, 2), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 4, 6, 6), intArrayOf(0, 2, 4, 6)
    )

    /** Neighbor-driven mode: vertex B indices (client: anIntArrayArray3830) */
    private val NEIGHBOR_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 4, 6), intArrayOf(0, 2, 4),
        intArrayOf(4, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 4, 7, 6), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 4, 6, 10, 0), intArrayOf(2, 4, 6, 0)
    )

    /** Neighbor-driven mode: vertex C indices (client: anIntArrayArray3831) */
    private val NEIGHBOR_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12), intArrayOf(5, 5, 5),
        intArrayOf(5, 5, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 1, 1, 7), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 10), intArrayOf(12, 12, 12, 12)
    )

    /** Neighbor-driven mode: overlay face counts per shape (client: anIntArray3826) */
    private val NEIGHBOR_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    /** Neighbor-driven mode: underlay face counts per shape (client: anIntArray3847) */
    private val NEIGHBOR_BLEND_UNDERLAY_COUNT = intArrayOf(0, 2, 2, 2, 1, 1, 3, 3, 1, 3, 3, 4, 4)

    /** Neighbor-driven mode: face counts for edge insertion (client: anIntArrayArray3828) */
    private val NEIGHBOR_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, 2, 3, 0), intArrayOf(1, 2, -1, 0),
        intArrayOf(2, 0, -1, 1), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 1, 3, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(3, 5, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, 2, 3, 5),
        intArrayOf(0, 1, 2, 3)
    )

    // ==================== OVERLAY-SUPPORTS-BLENDING MODE TABLES ====================
    //
    // Used when the overlay has blendsWithUnderlay=true AND there is an underlay.
    // This mode creates smooth blending between overlay and underlay colors at tile
    // boundaries, with additional vertex positions for smoother gradients.

    /** Overlay-supports-blending mode: vertex A indices (client: anIntArrayArray3775) */
    private val OVERLAY_BLEND_A = arrayOf(
        intArrayOf(0, 2, 4, 6), intArrayOf(6, 0, 2, 3, 5, 3), intArrayOf(6, 0, 2, 4),
        intArrayOf(2, 5, 6, 1), intArrayOf(0, 2, 6), intArrayOf(6, 0, 2),
        intArrayOf(5, 6, 0, 1, 2, 4), intArrayOf(7, 7, 1, 2, 4, 6), intArrayOf(2, 4, 4, 7),
        intArrayOf(6, 6, 4, 0, 1, 1, 3, 3), intArrayOf(0, 2, 2, 6, 6, 4),
        intArrayOf(0, 2, 2, 3, 7, 0, 4, 3), intArrayOf(0, 2, 4, 6)
    )

    /** Overlay-supports-blending mode: vertex B indices (client: anIntArrayArray3821) */
    private val OVERLAY_BLEND_B = arrayOf(
        intArrayOf(2, 4, 6, 0), intArrayOf(0, 2, 3, 5, 6, 4), intArrayOf(0, 1, 4, 5),
        intArrayOf(4, 6, 0, 2), intArrayOf(2, 4, 0), intArrayOf(0, 2, 4),
        intArrayOf(6, 0, 1, 2, 4, 5), intArrayOf(0, 1, 2, 4, 6, 7), intArrayOf(4, 7, 6, 0),
        intArrayOf(0, 8, 6, 1, 9, 2, 9, 4), intArrayOf(2, 9, 4, 0, 8, 6),
        intArrayOf(2, 11, 3, 7, 10, 10, 6, 6), intArrayOf(2, 4, 6, 0)
    )

    /** Overlay-supports-blending mode: vertex C indices (client: anIntArrayArray3836) */
    private val OVERLAY_BLEND_C = arrayOf(
        intArrayOf(12, 12, 12, 12), intArrayOf(12, 12, 12, 12, 12, 5), intArrayOf(5, 5, 1, 1),
        intArrayOf(5, 1, 1, 5), intArrayOf(5, 5, 5), intArrayOf(5, 5, 5),
        intArrayOf(12, 12, 12, 12, 12, 12), intArrayOf(1, 12, 12, 12, 12, 12), intArrayOf(1, 1, 7, 1),
        intArrayOf(8, 9, 9, 8, 8, 3, 1, 9), intArrayOf(8, 8, 9, 8, 9, 9),
        intArrayOf(10, 10, 11, 11, 11, 7, 3, 7), intArrayOf(12, 12, 12, 12)
    )

    /** Overlay-supports-blending mode: overlay face counts (client: OVERLAY_FACE_COUNT_BLENDED) */
    private val OVERLAY_BLEND_OVERLAY_COUNT = intArrayOf(4, 2, 1, 1, 2, 2, 3, 1, 3, 3, 3, 2, 0)

    /** Overlay-supports-blending mode: underlay face counts (client: UNDERLAY_FACE_COUNT_BLENDED) */
    private val OVERLAY_BLEND_UNDERLAY_COUNT = intArrayOf(0, 4, 3, 3, 1, 1, 3, 5, 1, 5, 3, 6, 4)

    /** Overlay-supports-blending mode: face counts for edge insertion (client: anIntArrayArray3833) */
    private val OVERLAY_BLEND_FACE_COUNTS = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, -1, -1, 0), intArrayOf(-1, 2, -1, 0),
        intArrayOf(-1, 0, -1, 2), intArrayOf(0, 1, -1, 2), intArrayOf(1, 2, -1, 0),
        intArrayOf(-1, 4, -1, 1), intArrayOf(-1, 3, 4, -1), intArrayOf(-1, 0, 2, -1),
        intArrayOf(-1, -1, 2, 0), intArrayOf(0, 2, 5, 3), intArrayOf(0, -1, 6, -1),
        intArrayOf(0, 1, 2, 3)
    )

    // ==================== SHAPE SUPPORTS HEIGHT TABLE ====================
    //
    // For each shape (0-14), for each vertex (0-12), whether that vertex
    // belongs to the overlay (true) or underlay (false). Used to determine
    // which vertices get overlay colors vs underlay colors.
    // (Already exists in the file as OVERLAY_SHAPE_SUPPORTS_HEIGHT in TRIANGLE_A comments,
    // but used directly from the client's static arrays in the blending system.)

    /** pathSquares: edge collision check for non-blending overlays (client: pathSquares) */
    private val PATH_SQUARES = arrayOf(
        BooleanArray(4), BooleanArray(4),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), BooleanArray(4), BooleanArray(4),
        BooleanArray(4), BooleanArray(4)
    )

    /** pathBlendings: edge collision check for blending overlays (client: pathBlendings) */
    private val PATH_BLENDINGS = arrayOf(
        BooleanArray(4), booleanArrayOf(false, true, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, true, false),
        booleanArrayOf(false, false, true, false), booleanArrayOf(false, false, true, false),
        booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, false, true),
        booleanArrayOf(true, false, false, true), booleanArrayOf(true, true, false, false),
        BooleanArray(4), booleanArrayOf(false, true, false, true), BooleanArray(4)
    )

    /**
     * OVERLAY_SHAPE_SUPPORTS_HEIGHT table from the client.
     * For each shape (0-14) and each vertex index (0-12), indicates whether that
     * vertex position belongs to the overlay region of the tile shape.
     * Used for neighbor blending analysis and vertex color assignment.
     */
    private val SHAPE_VERTEX_IS_OVERLAY = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true, true),
        booleanArrayOf(true, true, true, false, false, false, true, true, false, false, false, false, true),
        booleanArrayOf(true, false, false, false, false, true, true, true, false, false, false, false, false),
        booleanArrayOf(false, false, true, true, true, true, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, true, true, true, false, false, false, false, false, false, false),
        booleanArrayOf(true, true, true, false, false, true, true, true, false, false, false, false, false),
        booleanArrayOf(true, true, false, false, false, true, true, true, false, false, false, false, true),
        booleanArrayOf(true, true, false, false, false, false, false, true, false, false, false, false, false),
        booleanArrayOf(false, true, true, true, true, true, true, true, false, false, false, false, false),
        booleanArrayOf(true, false, false, false, true, true, true, true, true, true, false, false, false),
        booleanArrayOf(true, true, true, true, true, false, false, false, true, true, false, false, false),
        booleanArrayOf(true, true, true, false, false, false, false, false, false, false, true, true, false),
        BooleanArray(13),
        booleanArrayOf(true, true, true, true, true, true, true, true, true, true, true, true, true),
        BooleanArray(13)
    )

    // ==================== BLENDING STATE ====================
    //
    // Per-tile blending state computed by the analysis methods.
    // This captures the client's mutable state (pathShape, tileRotation, hasFacesOn,
    // waterSlots, overlayFunctionMask, etc.) in an immutable data class so tiles
    // can be generated independently after analysis.

    /**
     * Per-tile blending analysis result from the client's addBlendedTiles() pipeline.
     * Captures all the state needed to select face tables and generate vertices.
     */
    internal data class TileBlendState(
        /** The effective overlay shape after potential promotion to 13/14 */
        val effectiveShape: Int,
        /** The effective tile rotation after diagonal selection */
        val effectiveRotation: Int,
        /** Whether overlay supports blending (blendsWithUnderlay AND has underlay AND shape != 0) */
        val overlaySupportsBlending: Boolean,
        /** Whether the tile is unblendable (no neighbor blending and no overlay blending) */
        val unblendable: Boolean,
        /** Edge blending flags: [south, east, north, west] */
        val hasFacesOn: BooleanArray,
        /** Per-vertex slot priority (-1 = no neighbor overlay) for vertices 0-12 */
        val waterSlots: IntArray,
        /** Per-vertex overlay HSL colors from neighbors (valid when waterSlots[i] >= 0) */
        val overlayTileColors: IntArray,
        /** Per-vertex overlay texture IDs from neighbors */
        val waterTextureIds: IntArray,
        /** Per-vertex overlay texture scales from neighbors */
        val waterTextureScaling: IntArray,
        /** The overlay's own HSL color */
        val overlayHslColor: Int,
        /**
         * The overlay's own blending priority (client `FloType.slot`). A neighbouring overlay
         * only overrides this tile's overlay on a shared vertex when its slot is strictly
         * greater than this value.
         */
        val overlaySlot: Int,
        /** The overlay's own texture ID */
        val overlayTextureIdBlend: Int,
        /** The overlay's own texture scale */
        val overlayTextureScale: Int,
        /** Whether the overlay casts shadows */
        val hasShadows: Boolean,
        /** Selected face table A indices for this tile */
        val triA: IntArray,
        /** Selected face table B indices for this tile */
        val triB: IntArray,
        /** Selected face table C indices for this tile */
        val triC: IntArray,
        /** Overlay face count for this tile from the selected table */
        val overlayFaceCount: Int,
        /** Underlay face count for this tile from the selected table */
        val underlayFaceCount: Int,
        /** Face counts for edge insertion (null for unblendable mode) */
        val edgeFaceCounts: IntArray?,
        /** Total overlay faces processed (for face index tracking) */
        val overlayTotalFaces: Int,
        /** Per-corner underlay texture IDs: [SW, SE, NW, NE] for per-vertex texture selection */
        val cornerUnderlayTextures: IntArray = intArrayOf(-1, -1, -1, -1),
        /** Per-corner underlay UV scales: [SW, SE, NW, NE] for per-vertex texture selection */
        val cornerUnderlayScales: FloatArray = floatArrayOf(1f/512f, 1f/512f, 1f/512f, 1f/512f)
    )

    /**
     * Analyze blending for a single tile, implementing the client's full
     * calculateUnderlayRotation -> calculateOverlayDisplay -> calculateOverlayRotation
     * -> calculateFaceCounts pipeline.
     *
     * @param region The region data
     * @param plane The plane level
     * @param x Tile X coordinate
     * @param y Tile Y coordinate
     * @return TileBlendState with all blending decisions, or null if tile has no overlay/underlay
     */
    internal fun analyzeTileBlending(
        region: RegionData,
        plane: Int,
        x: Int, y: Int,
        blendedColors: Array<IntArray>
    ): TileBlendState? {
        val tile = region.tiles[plane][x][y]
        val overlayId = tile.overlayId
        val underlayId = tile.underlayId
        if (overlayId == 0 && underlayId == 0) return null

        val overlayDefs = overlays ?: return null
        val underlayDefs = underlays ?: return null

        // Get definitions
        var overlayDef = if (overlayId > 0 && overlayId - 1 < overlayDefs.size) overlayDefs[overlayId - 1] else null
        val underlayDef = if (underlayId > 0 && underlayId - 1 < underlayDefs.size) underlayDefs[underlayId - 1] else null

        // Determine initial shape
        var pathShape = tile.overlayShape
        if (pathShape == 0 && overlayDef == null) {
            pathShape = 12
        }

        // Check if overlay is valid (matching client's null-setting logic)
        val originalOverlay = overlayDef
        var hasShadows = false
        var overlayBlends = false
        val hasFacesOn = BooleanArray(4)

        if (overlayDef != null) {
            if (overlayDef.colour == -1 && overlayDef.blendColour == -1) {
                overlayDef = null
            } else if (underlayDef != null && pathShape != 0) {
                overlayBlends = overlayDef.underlayOverrides
            }
        }

        // Calculate underlay rotation (diagonal selection)
        val nextX = if (x < 63) x + 1 else x
        val nextY = if (y < 63) y + 1 else y
        var tileRotation = tile.overlayRotation

        tileRotation = calculateUnderlayRotationLocal(
            underlayId.toByte(), x, y, nextX, nextY, region, plane, pathShape, tileRotation
        )

        // Initialize per-vertex blending state
        val waterSlots = IntArray(13) { -1 }
        val overlayFunctionMask = IntArray(13) { 1 }
        val overlayTileColors = IntArray(13)
        val waterTextureIds = IntArray(13)
        val waterTextureScaling = IntArray(13)

        // Run neighbor overlay analysis and rotation promotion
        calculateOverlayDisplayLocal(
            overlayDef, x, y, region, plane, pathShape, tileRotation, overlayBlends,
            hasFacesOn, waterSlots, overlayFunctionMask, overlayTileColors,
            waterTextureIds, waterTextureScaling
        )

        // Determine multi-color state (not critical for our rendering, but affects data flow)
        val blendings = if (overlayDef != null && overlayDef.underlayOverrides) {
            PATH_BLENDINGS.getOrNull(pathShape) ?: BooleanArray(4)
        } else {
            PATH_SQUARES.getOrNull(pathShape) ?: BooleanArray(4)
        }

        // Check for additional edge promotion based on overlayFunctionMask.
        // `blendings` is already the row for this shape (4 entries, one per edge), so the
        // index below is bounds-safe on its own; guarding this block on the SHAPE being
        // smaller than the row length silently disabled edge promotion for every shape from
        // 4 upwards, which is most of the diagonal and corner overlay shapes.
        if (!blendings[(tileRotation + 1) and 0x3]) {
            hasFacesOn[1] = hasFacesOn[1] || ((overlayFunctionMask[2] and overlayFunctionMask[4]) == 0)
        }
        if (!blendings[(tileRotation + 3) and 0x3]) {
            hasFacesOn[3] = hasFacesOn[3] || ((overlayFunctionMask[6] and overlayFunctionMask[0]) == 0)
        }
        if (!blendings[tileRotation and 0x3]) {
            hasFacesOn[0] = hasFacesOn[0] || ((overlayFunctionMask[0] and overlayFunctionMask[2]) == 0)
        }
        if (!blendings[(tileRotation + 2) and 0x3]) {
            hasFacesOn[2] = hasFacesOn[2] || ((overlayFunctionMask[4] and overlayFunctionMask[6]) == 0)
        }

        // Diagonal shape promotion (shape 0/12 -> 13/14)
        if (!overlayBlends && (pathShape == 0 || pathShape == 12)) {
            if (hasFacesOn[0] && !hasFacesOn[1] && !hasFacesOn[2] && hasFacesOn[3]) {
                hasFacesOn[3] = false; hasFacesOn[0] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 0
            } else if (hasFacesOn[0] && hasFacesOn[1] && !hasFacesOn[2] && !hasFacesOn[3]) {
                hasFacesOn[1] = false; hasFacesOn[0] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 3
            } else if (!hasFacesOn[0] && hasFacesOn[1] && hasFacesOn[2] && !hasFacesOn[3]) {
                hasFacesOn[2] = false; hasFacesOn[1] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 2
            } else if (!hasFacesOn[0] && !hasFacesOn[1] && hasFacesOn[2] && hasFacesOn[3]) {
                hasFacesOn[3] = false; hasFacesOn[2] = false
                pathShape = if (pathShape == 0) 13 else 14
                tileRotation = 1
            }
        }

        // Determine blending mode
        val unblendable = !overlayBlends && !hasFacesOn[0] && !hasFacesOn[1] && !hasFacesOn[2] && !hasFacesOn[3]

        // Select face tables based on blending mode
        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int
        val edgeFaceCounts: IntArray?

        if (unblendable) {
            // Use standard unblendable tables (shapes 0-14)
            triA = TRIANGLE_A.getOrNull(pathShape) ?: intArrayOf()
            triB = TRIANGLE_B.getOrNull(pathShape) ?: intArrayOf()
            triC = TRIANGLE_C.getOrNull(pathShape) ?: intArrayOf()
            overlayFaces = if (overlayDef != null) OVERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) UNDERLAY_FACE_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = null
        } else if (overlayBlends) {
            // Use overlay-supports-blending tables (shapes 0-12)
            checkBlendableShape(pathShape, OVERLAY_BLEND_A.size)
            triA = OVERLAY_BLEND_A[pathShape]
            triB = OVERLAY_BLEND_B[pathShape]
            triC = OVERLAY_BLEND_C[pathShape]
            overlayFaces = if (overlayDef != null) OVERLAY_BLEND_OVERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) OVERLAY_BLEND_UNDERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = OVERLAY_BLEND_FACE_COUNTS.getOrNull(pathShape)
        } else {
            // Use neighbor-driven blending tables (shapes 0-12)
            checkBlendableShape(pathShape, NEIGHBOR_BLEND_A.size)
            triA = NEIGHBOR_BLEND_A[pathShape]
            triB = NEIGHBOR_BLEND_B[pathShape]
            triC = NEIGHBOR_BLEND_C[pathShape]
            overlayFaces = if (overlayDef != null) NEIGHBOR_BLEND_OVERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            underlayFaces = if (underlayDef != null) NEIGHBOR_BLEND_UNDERLAY_COUNT.getOrElse(pathShape) { 0 } else 0
            edgeFaceCounts = NEIGHBOR_BLEND_FACE_COUNTS.getOrNull(pathShape)
        }

        // Overlay color and texture
        val overlayHsl = if (overlayDef != null && overlayDef.colour != -1) overlayDef.colour else -1
        val overlayTexId = overlayDef?.texture ?: -1
        val overlayTexScale = overlayDef?.scale ?: 256

        // Shadow check
        if (overlayDef != null && pathShape != 12 && overlayDef.colour != -1 && overlayDef.blockShadow) {
            hasShadows = true
        }

        return TileBlendState(
            effectiveShape = pathShape,
            effectiveRotation = tileRotation,
            overlaySupportsBlending = overlayBlends,
            unblendable = unblendable,
            hasFacesOn = hasFacesOn,
            waterSlots = waterSlots,
            overlayTileColors = overlayTileColors,
            waterTextureIds = waterTextureIds,
            waterTextureScaling = waterTextureScaling,
            overlayHslColor = overlayHsl,
            // 8 is the client's own default for a missing overlay: `FloType.slot` is
            // initialised to 8 and only overwritten if the config carries the opcode.
            overlaySlot = overlayDef?.anInt3633 ?: 8,
            overlayTextureIdBlend = overlayTexId,
            overlayTextureScale = overlayTexScale,
            hasShadows = hasShadows,
            triA = triA,
            triB = triB,
            triC = triC,
            overlayFaceCount = overlayFaces,
            underlayFaceCount = underlayFaces,
            edgeFaceCounts = edgeFaceCounts,
            overlayTotalFaces = 0
        )
    }

    /**
     * Asserts the invariant that a blended tile's shape has a row in the blend tables.
     *
     * The two blending modes only have rows for shapes 0..12. Shapes 13 and 14 exist
     * solely in the 15-entry `TRIANGLE_*` tables, and reaching a blend table with one
     * is impossible by construction: 13/14 are produced only by the diagonal promotion
     * in [analyzeTileBlending], which runs only when `!overlaySupportsBlending`, and
     * each of its four branches requires exactly two `hasFacesOn` flags to be set and
     * clears both - leaving all four false. `unblendable` is therefore necessarily
     * true for such a tile and it takes the `TRIANGLE_*` branch instead.
     *
     * This used to be a `coerceIn` clamp, which would have silently re-tessellated a
     * shape 13/14 tile as shape 12 - a completely different tile, and the same
     * silent-wrong-geometry failure mode as the earlier bug that skipped shapes 4-11.
     * The reference client relies on the identical invariant but indexes directly and
     * would throw, so it fails loud where the clamp failed silent. Fail loud here too,
     * so that any future change to the promotion or blending-mode order surfaces
     * immediately rather than quietly emitting the wrong geometry.
     */
    private fun checkBlendableShape(pathShape: Int, tableSize: Int) {
        check(pathShape in 0 until tableSize) {
            "Tile shape $pathShape has no row in the blend tables (0..${tableSize - 1}). " +
                "Shapes 13/14 must reach the unblendable TRIANGLE_* tables; the promotion " +
                "in analyzeTileBlending clears every hasFacesOn flag it sets, so a blended " +
                "tile can never carry them. Blending mode selection has diverged."
        }
    }

    /**
     * Local implementation of calculateUnderlayRotation matching the client.
     * Determines which diagonal to use for plain tiles (shape 0/12) based on
     * underlay continuity with neighbors.
     */
    private fun calculateUnderlayRotationLocal(
        underlayId: Byte, x: Int, y: Int, nextX: Int, nextY: Int,
        region: RegionData, plane: Int, pathShape: Int, currentRotation: Int
    ): Int {
        if ((pathShape == 0 || pathShape == 12) && x > 0 && y > 0 && x < 64 && y < 64) {
            val tiles = region.tiles[plane]
            val uid = underlayId.toInt()
            var swScore = if (tiles[x - 1][y - 1].underlayId.toByte().toInt() == uid) 1 else -1
            var seScore = if (tiles[nextX][y - 1].underlayId.toByte().toInt() == uid) 1 else -1
            var neScore = if (tiles[nextX][nextY].underlayId.toByte().toInt() == uid) 1 else -1
            var nwScore = if (tiles[x - 1][nextY].underlayId.toByte().toInt() == uid) 1 else -1

            if (tiles[x][y - 1].underlayId.toByte().toInt() == uid) { swScore++; seScore++ } else { swScore--; seScore-- }
            if (tiles[nextX][y].underlayId.toByte().toInt() == uid) { seScore++; neScore++ } else { seScore--; neScore-- }
            if (tiles[x][nextY].underlayId.toByte().toInt() == uid) { neScore++; nwScore++ } else { neScore--; nwScore-- }
            if (tiles[x - 1][y].underlayId.toByte().toInt() == uid) { nwScore++; swScore++ } else { nwScore--; swScore-- }

            var swNeDelta = Math.abs(swScore - neScore)
            var seNwDelta = Math.abs(seScore - nwScore)

            if (swNeDelta == seNwDelta) {
                // Tie-break with height difference
                swNeDelta = Math.abs(tiles[x][y].height - tiles[nextX][nextY].height)
                seNwDelta = Math.abs(tiles[nextX][y].height - tiles[x][nextY].height)
            }

            return if (swNeDelta < seNwDelta) 1 else 0
        }
        return currentRotation
    }

    /**
     * Local implementation of calculateOverlayDisplay matching the client.
     * Examines all 8 neighboring tiles to find overlays with blendsWithUnderlay=true
     * that should blend their colors into this tile's vertices.
     */
    private fun calculateOverlayDisplayLocal(
        overlayType: OverlayDefinition?,
        x: Int, y: Int,
        region: RegionData, plane: Int,
        pathShape: Int, tileRotation: Int,
        overlayBlends: Boolean,
        hasFacesOn: BooleanArray,
        waterSlots: IntArray,
        overlayFunctionMask: IntArray,
        overlayTileColors: IntArray,
        waterTextureIds: IntArray,
        waterTextureScaling: IntArray
    ) {
        val overlayDefs = overlays ?: return
        val tiles = region.tiles[plane]

        val blendingAttributes = if (overlayType != null && overlayType.underlayOverrides) {
            PATH_BLENDINGS.getOrNull(pathShape) ?: BooleanArray(4)
        } else {
            PATH_SQUARES.getOrNull(pathShape) ?: BooleanArray(4)
        }

        // Check diagonal neighbors (corners)
        // SW neighbor: tiles[x-1][y-1], affects vertex slot 0
        if (y > 0 && x > 0) {
            val nOverlayId = tiles[x - 1][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x - 1][y - 1].overlayShape
                    val rotation = (tiles[x - 1][y - 1].overlayRotation * 2 + 4) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[0] = overlay.colour
                        waterTextureIds[0] = overlay.texture
                        waterTextureScaling[0] = overlay.scale
                        waterSlots[0] = overlay.anInt3633
                        overlayFunctionMask[0] = 256
                    }
                }
            }
        }

        // SE neighbor: tiles[x+1][y-1], affects vertex slot 2
        if (y > 0 && x < 63) {
            val nOverlayId = tiles[x + 1][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x + 1][y - 1].overlayShape
                    val rotation = (tiles[x + 1][y - 1].overlayRotation * 2 + 6) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[2] = overlay.colour
                        waterTextureIds[2] = overlay.texture
                        waterTextureScaling[2] = overlay.scale
                        waterSlots[2] = overlay.anInt3633
                        overlayFunctionMask[2] = 512
                    }
                }
            }
        }

        // NW neighbor: tiles[x-1][y+1], affects vertex slot 6
        if (y < 63 && x > 0) {
            val nOverlayId = tiles[x - 1][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x - 1][y + 1].overlayShape
                    val rotation = (tiles[x - 1][y + 1].overlayRotation * 2 + 2) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[6] = overlay.colour
                        waterTextureIds[6] = overlay.texture
                        waterTextureScaling[6] = overlay.scale
                        waterSlots[6] = overlay.anInt3633
                        overlayFunctionMask[6] = 64
                    }
                }
            }
        }

        // NE neighbor: tiles[x+1][y+1], affects vertex slot 4
        if (y < 63 && x < 63) {
            val nOverlayId = tiles[x + 1][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1 && overlay.underlayOverrides) {
                    val shape = tiles[x + 1][y + 1].overlayShape
                    val rotation = (tiles[x + 1][y + 1].overlayRotation * 2) and 0x7
                    if (shape < SHAPE_VERTEX_IS_OVERLAY.size && rotation < SHAPE_VERTEX_IS_OVERLAY[shape].size
                        && SHAPE_VERTEX_IS_OVERLAY[shape][rotation]) {
                        overlayTileColors[4] = overlay.colour
                        waterTextureIds[4] = overlay.texture
                        waterTextureScaling[4] = overlay.scale
                        waterSlots[4] = overlay.anInt3633
                        overlayFunctionMask[4] = 128
                    }
                }
            }
        }

        // Cardinal neighbors: South (y-1), North (y+1), West (x-1), East (x+1)
        // These affect 3 vertex slots each and set hasFacesOn flags

        // South neighbor: tiles[x][y-1]
        if (y > 0) {
            val nOverlayId = tiles[x][y - 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x][y - 1].overlayShape
                    val rotation = tiles[x][y - 1].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 2
                        var slotB = (rotation * 2 + 4)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x20
                                } else {
                                    overlayFunctionMask[slotA] = 32
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB++
                            slotA--
                        }
                        if (!blendingAttributes[tileRotation and 0x3]) {
                            hasFacesOn[0] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 2) and 0x3] else false
                        }
                    } else if (!blendingAttributes[tileRotation and 0x3]) {
                        hasFacesOn[0] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 2) and 0x3] else false
                    }
                }
            }
        }

        // North neighbor: tiles[x][y+1]
        if (y < 63) {
            val nOverlayId = tiles[x][y + 1].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x][y + 1].overlayShape
                    val rotation = tiles[x][y + 1].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 4
                        var slotB = (rotation * 2 + 2)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x10
                                } else {
                                    overlayFunctionMask[slotA] = 16
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB--
                            slotA++
                        }
                        if (!blendingAttributes[(tileRotation + 2) and 0x3]) {
                            hasFacesOn[2] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][rotation and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 2) and 0x3]) {
                        hasFacesOn[2] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][rotation and 0x3] else false
                    }
                }
            }
        }

        // West neighbor: tiles[x-1][y]
        if (x > 0) {
            val nOverlayId = tiles[x - 1][y].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x - 1][y].overlayShape
                    val rotation = tiles[x - 1][y].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotA = 6
                        var slotB = (rotation * 2 + 4)
                        for (i in 0..2) {
                            slotB = slotB and 0x7
                            slotA = slotA and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotB < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotB] && waterSlots[slotA] <= overlay.anInt3633) {
                                overlayTileColors[slotA] = overlay.colour
                                waterTextureIds[slotA] = overlay.texture
                                waterTextureScaling[slotA] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotA]) {
                                    overlayFunctionMask[slotA] = overlayFunctionMask[slotA] or 0x8
                                } else {
                                    overlayFunctionMask[slotA] = 8
                                }
                                waterSlots[slotA] = overlay.anInt3633
                            }
                            slotB--
                            slotA++
                        }
                        if (!blendingAttributes[(tileRotation + 3) and 0x3]) {
                            hasFacesOn[3] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 1) and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 3) and 0x3]) {
                        hasFacesOn[3] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 1) and 0x3] else false
                    }
                }
            }
        }

        // East neighbor: tiles[x+1][y]
        if (x < 63) {
            val nOverlayId = tiles[x + 1][y].overlayId
            if (nOverlayId > 0 && nOverlayId - 1 < overlayDefs.size) {
                val overlay = overlayDefs[nOverlayId - 1]
                if (overlay.colour != -1) {
                    val shape = tiles[x + 1][y].overlayShape
                    val rotation = tiles[x + 1][y].overlayRotation
                    if (overlay.underlayOverrides) {
                        var slotB = 4
                        var slotA = (rotation * 2 + 6)
                        for (i in 0..2) {
                            slotA = slotA and 0x7
                            slotB = slotB and 0x7
                            if (shape < SHAPE_VERTEX_IS_OVERLAY.size && slotA < SHAPE_VERTEX_IS_OVERLAY[shape].size
                                && SHAPE_VERTEX_IS_OVERLAY[shape][slotA] && waterSlots[slotB] <= overlay.anInt3633) {
                                overlayTileColors[slotB] = overlay.colour
                                waterTextureIds[slotB] = overlay.texture
                                waterTextureScaling[slotB] = overlay.scale
                                if (overlay.anInt3633 == waterSlots[slotB]) {
                                    overlayFunctionMask[slotB] = overlayFunctionMask[slotB] or 0x4
                                } else {
                                    overlayFunctionMask[slotB] = 4
                                }
                                waterSlots[slotB] = overlay.anInt3633
                            }
                            slotA++
                            slotB--
                        }
                        if (!blendingAttributes[(tileRotation + 1) and 0x3]) {
                            hasFacesOn[1] = if (shape < PATH_BLENDINGS.size) PATH_BLENDINGS[shape][(rotation + 3) and 0x3] else false
                        }
                    } else if (!blendingAttributes[(tileRotation + 1) and 0x3]) {
                        hasFacesOn[1] = if (shape < PATH_SQUARES.size) PATH_SQUARES[shape][(rotation + 3) and 0x3] else false
                    }
                }
            }
        }

        // Self-overlay blending: if this tile's overlay blends with underlay,
        // set its own color into vertex slots where SHAPE_VERTEX_IS_OVERLAY is true
        if (overlayType != null && overlayType.underlayOverrides) {
            for (index in 0..7) {
                val signedRotation = (index - tileRotation * 2) and 0x7
                if (pathShape < SHAPE_VERTEX_IS_OVERLAY.size && index < SHAPE_VERTEX_IS_OVERLAY[pathShape].size
                    && SHAPE_VERTEX_IS_OVERLAY[pathShape][index] && waterSlots[signedRotation] <= overlayType.anInt3633) {
                    overlayTileColors[signedRotation] = overlayType.colour
                    waterTextureIds[signedRotation] = overlayType.texture
                    waterTextureScaling[signedRotation] = overlayType.scale
                    if (overlayType.anInt3633 == waterSlots[signedRotation]) {
                        overlayFunctionMask[signedRotation] = overlayFunctionMask[signedRotation] or 0x2
                    } else {
                        overlayFunctionMask[signedRotation] = 2
                    }
                    waterSlots[signedRotation] = overlayType.anInt3633
                }
            }
        }
    }

    /**
     * Data class to hold vertex data for shaped tile generation.
     * Supports multi-pass alpha rendering for smooth underlay transitions.
     *
     * The alpha field controls per-vertex opacity for multi-pass blending.
     * Opaque vertices (alpha=1.0) go into standard textured batches.
     * Translucent vertices (alpha<1.0) go into alpha batches that are rendered
     * on top of the opaque base layer with GL alpha blending. The GPU interpolates
     * alpha across the triangle for smooth per-fragment transitions between textures.
     */
    internal data class ShapedVertex(
        val x: Float, val y: Float, val z: Float,
        val r: Float, val g: Float, val b: Float,
        val u: Float, val v: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val textureId: Int,
        /** Per-vertex alpha for multi-pass blending (1.0 = fully opaque, 0.0 = fully transparent) */
        val alpha: Float = 1f
    )

    /**
     * Generates shaped triangles for a tile, returning separate lists for overlay and underlay triangles.
     *
     * @param x Tile X coordinate within region
     * @param y Tile Y coordinate within region
     * @param shape The tile shape (0-11 typically used)
     * @param rotation The shape rotation (0-3)
     * @param tileSize The size of each tile in world units
     * @param heights Array of corner heights [SW, SE, NW, NE]
     * @param overlayColor Color for overlay triangles (RGB) - used for interior vertices
     * @param underlayCornerHsl Array of 4 corner packed HSL colors for underlay [SW, SE, NW, NE]
     *                          Vertex colors are bilinearly interpolated in HSL space
     * @param overlayTextureId Texture ID for overlay (-1 for color only)
     * @param underlayTextureId Texture ID for underlay (-1 for color only)
     * @param overlayScaleRaw Raw overlay texture scale in RS units (512 = one texture per tile)
     * @param underlayScaleRaw Raw underlay texture scale in RS units
     * @param overlayBlendsWithUnderlay If true, overlay edge vertices use blended underlay colors
     * @param overlayHsl The overlay's packed HSL color for blending calculations
     * @param cornerNormals Pre-computed normals for 4 corners [SW, SE, NW, NE] from neighboring heights
     * @return List of ShapedVertex objects for all triangles
     */
    internal fun generateShapedTile(
        x: Int, y: Int,
        shape: Int, rotation: Int,
        tileSize: Float,
        heights: FloatArray, // [h00, h10, h01, h11] = [SW, SE, NW, NE]
        overlayColor: Vector3f,
        underlayCornerHsl: IntArray, // Packed HSL: [SW, SE, NW, NE]
        overlayTextureId: Int,
        underlayTextureId: Int,
        overlayScaleRaw: Int,
        underlayScaleRaw: Int,
        overlayBlendsWithUnderlay: Boolean = false,
        overlayHsl: Int = 0,
        cornerNormals: Array<Vector3f> = arrayOf(
            Vector3f(0f, 1f, 0f), Vector3f(0f, 1f, 0f),
            Vector3f(0f, 1f, 0f), Vector3f(0f, 1f, 0f)
        ),
        hasOverlay: Boolean = true,
        hasUnderlay: Boolean = true,
        regionId: Int = 0,
        blendState: TileBlendState? = null,
        cornerUnderlayTextures: IntArray = intArrayOf(-1, -1, -1, -1),
        cornerUnderlayScalesRaw: IntArray = intArrayOf(512, 512, 512, 512),
        cornerDepths: FloatArray = NO_WATER_DEPTH,
        waterFogDepth: Int = 0,
        isWater: Boolean = false,
        waterColor: Vector3f = Vector3f()
    ): List<ShapedVertex> {
        val safeShape = shape.coerceIn(0, TRIANGLE_A.size - 1)

        // Determine face tables and counts based on whether we have blend state
        val triA: IntArray
        val triB: IntArray
        val triC: IntArray
        val overlayFaces: Int
        val underlayFaces: Int

        if (blendState != null) {
            // For unblendable tiles with shape 0/12, prefer enhanced tessellation for smoother gradients
            val useEnhanced = blendState.unblendable && enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)
            if (useEnhanced) {
                triA = TRIANGLE_A_ENHANCED[safeShape]
                triB = TRIANGLE_B_ENHANCED[safeShape]
                triC = TRIANGLE_C_ENHANCED[safeShape]
                overlayFaces = if (hasOverlay) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else 0
                underlayFaces = if (hasUnderlay) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else 0
            } else {
                triA = blendState.triA
                triB = blendState.triB
                triC = blendState.triC
                overlayFaces = if (hasOverlay) blendState.overlayFaceCount else 0
                underlayFaces = if (hasUnderlay) blendState.underlayFaceCount else 0
            }
        } else {
            // Fallback: use enhanced tessellation for shapes 0 and 12 if enabled
            val useEnhanced = enableEnhancedTessellation && (safeShape == 0 || safeShape == 12)
            triA = if (useEnhanced) TRIANGLE_A_ENHANCED[safeShape] else TRIANGLE_A[safeShape]
            triB = if (useEnhanced) TRIANGLE_B_ENHANCED[safeShape] else TRIANGLE_B[safeShape]
            triC = if (useEnhanced) TRIANGLE_C_ENHANCED[safeShape] else TRIANGLE_C[safeShape]
            overlayFaces = if (hasOverlay) {
                if (useEnhanced) OVERLAY_FACE_COUNT_ENHANCED[safeShape] else OVERLAY_FACE_COUNT[safeShape]
            } else 0
            underlayFaces = if (hasUnderlay) {
                if (useEnhanced) UNDERLAY_FACE_COUNT_ENHANCED[safeShape] else UNDERLAY_FACE_COUNT[safeShape]
            } else 0
        }

        val totalFaces = overlayFaces + underlayFaces
        if (totalFaces == 0) return emptyList()

        val fast = fastTessellation

        // Sized for the common case of one material layer per face; extra layers grow it.
        val vertices = if (fast) ArrayList<ShapedVertex>(totalFaces * 3) else mutableListOf()

        // Base position for this tile
        val baseX = x * tileSize
        val baseZ = -y * tileSize  // Flip Z for OpenGL

        // Generate rotated vertex positions for this tile
        val vertexX = FloatArray(13)
        val vertexZ = FloatArray(13)
        val vertexY = FloatArray(13)
        val vertexDepth = FloatArray(13)
        val vertexLocalX = IntArray(13)
        val vertexLocalY = IntArray(13)

        for (i in 0 until 13) {
            var vx = TILE_VERTEX_X[i]
            var vy = TILE_VERTEX_Y[i]

            when (rotation) {
                1 -> { val temp = vx; vx = vy; vy = 512 - temp }
                2 -> { vx = 512 - vx; vy = 512 - vy }
                3 -> { val temp = vx; vx = 512 - vy; vy = temp }
            }

            vertexLocalX[i] = vx
            vertexLocalY[i] = vy
            vertexX[i] = baseX + (vx.toFloat() / 512f) * tileSize
            vertexZ[i] = baseZ - (vy.toFloat() / 512f) * tileSize

            val fx = vx.toFloat() / 512f
            val fy = vy.toFloat() / 512f
            vertexY[i] = (1 - fx) * (1 - fy) * heights[0] +
                         fx * (1 - fy) * heights[1] +
                         (1 - fx) * fy * heights[2] +
                         fx * fy * heights[3]

            vertexDepth[i] = (1 - fx) * (1 - fy) * cornerDepths[0] +
                             fx * (1 - fy) * cornerDepths[1] +
                             (1 - fx) * fy * cornerDepths[2] +
                             fx * fy * cornerDepths[3]
        }

        // Compute per-vertex quadrant for underlay texture selection
        // (matching client's addUnderlayTiles: SW=0, SE=1, NW=2, NE=3)
        fun getVertexQuadrant(vIdx: Int): Int {
            val lx = vertexLocalX[vIdx]
            val ly = vertexLocalY[vIdx]
            return if (lx < 256) {
                if (ly < 256) 0 else 2  // SW or NW
            } else {
                if (ly < 256) 1 else 3  // SE or NE
            }
        }

        // Corner attributes are indexed by the tile's WORLD corners and must NOT be rotated.
        //
        // `vertexLocalX/Y` above are already post-rotation, i.e. they are the vertex's real
        // offset inside the tile in world space -- which is why the height bilinear a few
        // lines up feeds them straight into the unrotated `heights[SW,SE,NW,NE]`, and why the
        // per-vertex underlay texture lookup indexes the unrotated `cornerUnderlayTextures`.
        // The client does the same: `addUnderlayTiles` derives `sizeX/sizeY` by rotating the
        // canonical vertex delta, then uses those rotated sizes to pick `overlayColors[x][y]`
        // / `underlayA..D` / `lightingX[x][y]`, all of which are world-corner indexed.
        //
        // Rotating the corner array as well applied the rotation TWICE, so on every tile with
        // rotation != 0 the underlay colours and normals were permuted relative to the tile's
        // own geometry, heights and textures. Shaped overlay tiles (shorelines, paths, floor
        // trims) are precisely the tiles that carry a non-zero rotation, which is why the
        // damage showed up as broken bands and mismatched quads rather than as uniform error.
        val nSW = cornerNormals[0]
        val nSE = cornerNormals[1]
        val nNW = cornerNormals[2]
        val nNE = cornerNormals[3]

        // Interpolated vertex normals, kept as three flat arrays rather than 13 Vector3f
        // objects. The arithmetic still runs through JOML's lerp/length/div on three
        // scratch vectors, so the results are bit-identical to the per-vertex allocation
        // this replaces; only the 39 short-lived vectors per tile are gone.
        val vertexNormalX = FloatArray(13)
        val vertexNormalY = FloatArray(13)
        val vertexNormalZ = FloatArray(13)
        val southScratch = if (fast) Vector3f() else null
        val northScratch = if (fast) Vector3f() else null
        val normalScratch = if (fast) Vector3f() else null
        for (i in 0 until 13) {
            val fx = vertexLocalX[i].toFloat() / 512f
            val fy = vertexLocalY[i].toFloat() / 512f
            val interpolated: Vector3f
            if (southScratch != null && northScratch != null && normalScratch != null) {
                southScratch.set(nSW).lerp(nSE, fx)
                northScratch.set(nNW).lerp(nNE, fx)
                interpolated = normalScratch.set(southScratch).lerp(northScratch, fy)
            } else {
                val southNormal = Vector3f(nSW).lerp(nSE, fx)
                val northNormal = Vector3f(nNW).lerp(nNE, fx)
                interpolated = Vector3f(southNormal).lerp(northNormal, fy)
            }
            val len = interpolated.length()
            if (len > 0.0001f) {
                interpolated.div(len)
            } else {
                interpolated.set(0f, 1f, 0f)
            }
            vertexNormalX[i] = interpolated.x
            vertexNormalY[i] = interpolated.y
            vertexNormalZ[i] = interpolated.z
        }

        // Underlay corner colours, world-corner indexed and deliberately unrotated -- see the
        // note on the corner normals above.
        val hslSW = underlayCornerHsl[0]
        val hslSE = underlayCornerHsl[1]
        val hslNW = underlayCornerHsl[2]
        val hslNE = underlayCornerHsl[3]

        // Helper: apply lighting and noise to an HSL color and convert to RGB
        fun applyEffectsAndConvert(hsl: Int, vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            var result = hsl
            if (result == 0) return Vector3f(0f, 0f, 0f)
            if (enableColorNoise) {
                result = ColorUtil.applyBrightnessNoise(result, ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount))
            }
            if (enableLighting) {
                val lightFactor = if (bakeSunLighting) {
                    ColorUtil.calculateTerrainLightFactor(
                        vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                        LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                        SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                    )
                } else {
                    GROUND_BASE_LIGHT_FACTOR
                }
                result = ColorUtil.applyLightToHsl(result, lightFactor)
            }
            return if (fast) ColorUtil.hslToRgb(result) else ColorUtil.hslToRgbUncached(result)
        }

        // ---- Per-vertex material selection (client MapLoader.addUnderlayTiles / addOverlayTiles)
        //
        // In the client a ground vertex's colour, texture id and texture scale are all picked
        // by the same three-way branch, so the choice is made once here and consumed both by
        // the colour helpers below and by the triangle emitter. Deciding colour and texture
        // independently is what left water-edge vertices wearing the shoreline overlay's
        // colour on top of the mud underlay's texture.
        //
        //   0 = a neighbouring overlay (water, path, ...) claims this vertex
        //   1 = this tile's own overlay claims it (overlay blends down into the underlay)
        //   2 = plain underlay
        fun underlayVertexSource(vIdx: Int): Int {
            if (blendState != null && vIdx < 8) {
                val slot = (vIdx - rotation * 2) and 0x7
                if (blendState.waterSlots[slot] >= 0 && blendState.overlayTileColors[slot] != 0) {
                    return 0
                }
            }
            if (blendState != null && blendState.overlaySupportsBlending
                && blendState.effectiveShape < SHAPE_VERTEX_IS_OVERLAY.size
                && vIdx < SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape].size
                && SHAPE_VERTEX_IS_OVERLAY[blendState.effectiveShape][vIdx]
                && blendState.overlayHslColor > 0
            ) {
                return 1
            }
            return 2
        }

        /** True when a neighbouring overlay outranks this tile's own overlay at [vIdx]. */
        fun overlayVertexOverridden(vIdx: Int): Boolean {
            if (blendState == null || vIdx >= 8) return false
            val slot = (vIdx - rotation * 2) and 0x7
            return blendState.waterSlots[slot] > blendState.overlaySlot &&
                blendState.overlayTileColors[slot] != 0
        }

        fun uvScaleOf(rawScale: Int): Float = if (rawScale > 0) 1f / rawScale.toFloat() else 1f / 512f

        // Texture id + raw texture scale for each of the 13 tile vertices, resolved through the
        // same branch as the colour. Underlay and overlay faces resolve differently, so both
        // tables are built up front and indexed by the face's own kind at emit time.
        val underlayVertexTexture = IntArray(13)
        val underlayVertexScale = IntArray(13)
        val overlayVertexTexture = IntArray(13)
        val overlayVertexScale = IntArray(13)
        for (i in 0 until 13) {
            val slot = (i - rotation * 2) and 0x7
            when (underlayVertexSource(i)) {
                0 -> {
                    underlayVertexTexture[i] = blendState!!.waterTextureIds[slot]
                    underlayVertexScale[i] = blendState.waterTextureScaling[slot]
                }
                1 -> {
                    underlayVertexTexture[i] = blendState!!.overlayTextureIdBlend
                    underlayVertexScale[i] = blendState.overlayTextureScale
                }
                else -> {
                    val q = getVertexQuadrant(i)
                    val cornerTex = cornerUnderlayTextures[q]
                    underlayVertexTexture[i] = if (cornerTex >= 0) cornerTex else underlayTextureId
                    underlayVertexScale[i] = if (cornerTex >= 0) cornerUnderlayScalesRaw[q] else underlayScaleRaw
                }
            }
            if (overlayVertexOverridden(i)) {
                overlayVertexTexture[i] = blendState!!.waterTextureIds[slot]
                overlayVertexScale[i] = blendState.waterTextureScaling[slot]
            } else {
                overlayVertexTexture[i] = overlayTextureId
                overlayVertexScale[i] = overlayScaleRaw
            }
        }

        // The tile's own ground colour at a vertex, with no neighbour-overlay bleed applied.
        // This is what a shoreline exposes when the water above it thins out.
        fun plainUnderlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            // Standard underlay bilinear interpolation in HSL space
            val localX = vertexLocalX[vIdx]
            val localY = vertexLocalY[vIdx]
            val fx = localX.toFloat() / 512f
            val fy = localY.toFloat() / 512f

            // Bilinear blend corner colors
            var blendedHsl: Int
            if (localX == 0 && localY == 0) {
                blendedHsl = hslSW
            } else if (localX == 0 && localY == 512) {
                blendedHsl = hslNW
            } else if (localX == 512 && localY == 512) {
                blendedHsl = hslNE
            } else if (localX == 512 && localY == 0) {
                blendedHsl = hslSE
            } else {
                // Interior point: bilinear interpolation using RS blend()
                // Blend factor is (localPos << 7) >> 9 = localPos / 4
                // But the client uses: sizeX shl 7 shr 9 which is localX / 4 (0-128 range for 0-512)
                val factorX = (localX shl 7) shr 9  // = localX / 4, range 0-128
                val factorY = (localY shl 7) shr 9  // = localY / 4, range 0-128
                val south = ColorUtil.blendHsl(hslSW, hslSE, factorX)
                val north = ColorUtil.blendHsl(hslNW, hslNE, factorX)
                blendedHsl = ColorUtil.blendHsl(south, north, factorY)
            }

            if (blendedHsl == 0) return Vector3f(0f, 0f, 0f)
            return applyEffectsAndConvert(blendedHsl, vIdx, worldPosX, worldPosZ)
        }

        // Helper: get underlay color for a vertex, considering neighbor overlay blending
        fun getUnderlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            when (underlayVertexSource(vIdx)) {
                0 -> {
                    val slot = (vIdx - rotation * 2) and 0x7
                    return applyEffectsAndConvert(
                        blendState!!.overlayTileColors[slot], vIdx, worldPosX, worldPosZ
                    )
                }
                1 -> return applyEffectsAndConvert(
                    blendState!!.overlayHslColor, vIdx, worldPosX, worldPosZ
                )
            }
            return plainUnderlayVertexColor(vIdx, worldPosX, worldPosZ)
        }

        // ---- Shoreline ------------------------------------------------------------------
        //
        // A pool's mud ring is the bed seen through shallow water, and the client builds it
        // out of two things we did not have: the region's underwater height map (the `um`
        // archive, giving a per-vertex depth) and the overlay's `waterColour`/`waterFogDepth`.
        // The bed is drawn as its own ground, tinted toward `waterColour` in proportion to
        // depth (`alpha = depth * 255 / waterFogDepth`), and the water surface is laid over it
        // at partial opacity. At the waterline the depth is zero, so the bed keeps its plain
        // ground colour and shows through as wet mud; further out the tint takes over and the
        // bed reads as open water.
        //
        // With a single ground mesh there is nowhere to put the bed, so that composite is
        // folded into the water surface's own vertex colour instead. The result is the same
        // ramp without a second mesh or a second draw call, and because depth is a continuous
        // field sampled from the cache the ring follows the true waterline -- it cannot break
        // up between tiles the way an edge derived from tile tessellation does, which is what
        // left the old shoreline present on some boundary tiles and missing on others.
        //
        // The one thing that does not survive the fold is the *coverage* of the surface. In
        // the client the bed and the surface are separate grounds that coincide exactly where
        // the depth is zero, so at the waterline the bed wins and you see bare wet mud, not a
        // 155/255 mix of mud and open water. Folding both into one colour has to reproduce
        // that, otherwise the mud never rises above two fifths of the blend and the ring shows
        // up as a faint tan tinge instead of a band. So the surface's own opacity is ramped by
        // the same depth term: no water above the bed means no water surface to look through.
        //
        //   fog     = clamp(depth * 255 / (waterFogDepth * SHORE_FOG_SPREAD), 0, 255) / 255
        //   bed     = groundColour  ..lerp(waterColour, fog)
        //   colour  = bed ..lerp(surfaceColour, 155/255 * fog)
        //
        // At fog = 1 that collapses to the client's deep-water mix (155/255 surface over a bed
        // already fogged out to waterColour); at fog = 0 it collapses to the bare ground. See
        // [SHORE_FOG_SPREAD] for why the fog depth itself is widened -- it is the only constant
        // here that is not the client's own.
        val shoreDepth = waterFogDepth.toFloat() * SHORE_FOG_SPREAD
        val isWaterOverlay = enableShoreline && shoreDepth > 0f && hasUnderlay && isWater

        /**
         * How much water is stacked over the bed at [vIdx], 0 at the waterline and 1 once the
         * fog term saturates. This is the client's `depth * 255 / waterFogDepth` clamp,
         * normalised to 0..1, over the spread ramp.
         */
        fun waterFogAt(vIdx: Int): Float =
            (vertexDepth[vIdx] * 255f / shoreDepth).coerceIn(0f, 255f) / 255f

        // Helper: get overlay color for a vertex, checking neighbor overrides
        fun overlayVertexColorNoShore(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            // Check if this vertex has a higher-priority neighbor overlay
            if (overlayVertexOverridden(vIdx)) {
                val slot = (vIdx - rotation * 2) and 0x7
                return applyEffectsAndConvert(
                    blendState!!.overlayTileColors[slot], vIdx, worldPosX, worldPosZ
                )
            }

            // Use the tile's own overlay color
            if (overlayHsl != 0) {
                return applyEffectsAndConvert(overlayHsl, vIdx, worldPosX, worldPosZ)
            }

            // For textured overlays without color, apply lighting in RGB space
            val lightFactor = if (!enableLighting) {
                1.0f
            } else if (bakeSunLighting) {
                ColorUtil.calculateTerrainLightFactor(
                    vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                    LIGHT_DIRECTION_X, LIGHT_DIRECTION_Y, LIGHT_DIRECTION_Z,
                    SUN_BRIGHTNESS, BRIGHT_FACTOR, DARK_FACTOR
                )
            } else {
                GROUND_BASE_LIGHT_FACTOR
            }
            val noiseFactor = if (enableColorNoise) {
                ColorUtil.getVertexNoise(worldPosX, worldPosZ, colorNoiseAmount)
            } else 1.0f
            val combinedFactor = lightFactor * noiseFactor
            return Vector3f(
                (overlayColor.x * combinedFactor).coerceIn(0f, 1f),
                (overlayColor.y * combinedFactor).coerceIn(0f, 1f),
                (overlayColor.z * combinedFactor).coerceIn(0f, 1f)
            )
        }

        /** Overlay colour, with the bed composited under it for water overlays. */
        fun getOverlayVertexColor(vIdx: Int, worldPosX: Float, worldPosZ: Float): Vector3f {
            val surface = overlayVertexColorNoShore(vIdx, worldPosX, worldPosZ)
            if (!isWaterOverlay) return surface
            val ground = plainUnderlayVertexColor(vIdx, worldPosX, worldPosZ)
            // A vertex whose blended underlay resolves to nothing has no bed to expose; letting
            // it through would paint the waterline black rather than muddy.
            if (ground.x == 0f && ground.y == 0f && ground.z == 0f) return surface
            val fog = waterFogAt(vIdx)
            return ground.lerp(waterColor, fog).lerp(surface, WATER_SURFACE_OPACITY * fog)
        }

        // ---- Per-vertex colour memo ------------------------------------------------------
        //
        // A tile emits each of its 13 vertex slots many times over: once per face the slot
        // belongs to, and once more per extra material layer that face carries. The colour
        // of a slot depends only on the slot -- the world position handed to the noise term
        // is that slot's own -- so it was being recomputed three to five times over.
        //
        // That recomputation is the most expensive thing in this function: each call runs an
        // HSL bilinear blend, a sin()-based noise hash, the light dot product, an HSL->RGB
        // conversion, and for water overlays the whole underlay colour a second time to
        // composite the bed. Resolving each slot once and reading the result back as floats
        // gives bit-identical vertices for a fraction of the work.
        //
        // Colours are cached as floats, not as the Vector3f the helpers return, because the
        // shoreline composite lerps its ground vector in place; handing out a shared vector
        // would let one vertex mutate another's colour.
        val underlayColorCache = FloatArray(39)
        val underlayColorReady = BooleanArray(13)
        val overlayColorCache = FloatArray(39)
        val overlayColorReady = BooleanArray(13)

        // Process the face tables using the client's addOverlayTiles + addUnderlayTiles pattern.
        // The client iterates overlay faces first, then underlay faces, using a shared index
        // (overlayTotalFaces) that tracks progress through the combined face table.
        // When hasFacesOn flags are set and the current face matches an edgeFaceCounts entry,
        // edge triangles are inserted that bridge between the main tile and the neighbor.

        var overlayTotalFaces = 0

        // Material ordering key, mirroring the uid the client hashes its ground materials by
        // (`scale << 14 | textureId`). An untextured material ORs in -1 there and collapses to
        // -1, so it sorts ahead of every textured material; reproduce that exactly.
        fun materialKey(texId: Int, rawScale: Int): Long =
            if (texId < 0) -1L else ((rawScale.toLong() shl 14) or texId.toLong())

        /** Reused index triple; [emitTriangle] never runs inside itself, so one buffer does. */
        val triangleScratch = IntArray(3)

        // Emit one triangle as the client's GroundGL does: the lowest-keyed of the three
        // vertex materials draws the face opaquely, and every other distinct vertex material
        // draws the same face again with alpha 1 only at the vertices that own it. The base
        // material is chosen per FACE from a globally ordered key, not per tile by majority
        // vote, so neighbouring tiles along a material boundary always resolve the same way.
        fun emitTriangle(ia: Int, ib: Int, ic: Int, isOverlay: Boolean) {
            val vertexIndicesArr = if (fast) triangleScratch else IntArray(3)
            vertexIndicesArr[0] = ia
            vertexIndicesArr[1] = ib
            vertexIndicesArr[2] = ic
            val vertexTexture = if (isOverlay) overlayVertexTexture else underlayVertexTexture
            val vertexScale = if (isOverlay) overlayVertexScale else underlayVertexScale
            val colorCache = if (isOverlay) overlayColorCache else underlayColorCache
            val colorReady = if (isOverlay) overlayColorReady else underlayColorReady

            // Helper to create a vertex with a given texture, UV scale, and alpha
            fun createVertex(vIdx: Int, texId: Int, vUvScale: Float, vAlpha: Float): ShapedVertex {
                val px = vertexX[vIdx]
                val py = vertexY[vIdx]
                val pz = vertexZ[vIdx]
                val colorBase = vIdx * 3
                if (!fast || !colorReady[vIdx]) {
                    val color = if (isOverlay) getOverlayVertexColor(vIdx, px, pz)
                                else getUnderlayVertexColor(vIdx, px, pz)
                    colorCache[colorBase] = color.x
                    colorCache[colorBase + 1] = color.y
                    colorCache[colorBase + 2] = color.z
                    colorReady[vIdx] = true
                }

                val localXVal = vertexLocalX[vIdx]
                val localYVal = vertexLocalY[vIdx]
                val sceneX = (x * 512 + localXVal).toFloat()
                val sceneY = (y * 512 + localYVal).toFloat()

                val u = sceneX * vUvScale
                val v = sceneY * vUvScale

                return ShapedVertex(
                    px, py, pz,
                    colorCache[colorBase], colorCache[colorBase + 1], colorCache[colorBase + 2],
                    u, v,
                    vertexNormalX[vIdx], vertexNormalY[vIdx], vertexNormalZ[vIdx],
                    texId,
                    alpha = vAlpha
                )
            }

            // Pick the base material: the lowest-keyed of the three vertex materials. In the
            // client this is the material the face itself is appended to, with all three of
            // its vertex alphas forced opaque.
            var baseVertex = vertexIndicesArr[0]
            for (vi in 1..2) {
                val candidate = vertexIndicesArr[vi]
                if (materialKey(vertexTexture[candidate], vertexScale[candidate]) <
                    materialKey(vertexTexture[baseVertex], vertexScale[baseVertex])
                ) {
                    baseVertex = candidate
                }
            }
            val baseKey = materialKey(vertexTexture[baseVertex], vertexScale[baseVertex])
            val baseUvScale = uvScaleOf(vertexScale[baseVertex])
            for (vIdx in vertexIndicesArr) {
                vertices.add(createVertex(vIdx, vertexTexture[baseVertex], baseUvScale, 1f))
            }

            // One additional blended pass per remaining distinct vertex material, opaque only
            // at the vertices that own it. Materials are compared by their full key so two
            // underlays sharing a texture at different scales stay distinct, as they do in the
            // client's material table.
            for (vi in 0..2) {
                val layerVertex = vertexIndicesArr[vi]
                val layerKey = materialKey(vertexTexture[layerVertex], vertexScale[layerVertex])
                if (layerKey == baseKey) continue
                var alreadyEmitted = false
                for (prev in 0 until vi) {
                    val prevVertex = vertexIndicesArr[prev]
                    if (materialKey(vertexTexture[prevVertex], vertexScale[prevVertex]) == layerKey) {
                        alreadyEmitted = true
                    }
                }
                if (alreadyEmitted) continue

                val layerUvScale = uvScaleOf(vertexScale[layerVertex])
                for (vIdx in vertexIndicesArr) {
                    val ownsLayer = materialKey(vertexTexture[vIdx], vertexScale[vIdx]) == layerKey
                    vertices.add(
                        createVertex(vIdx, vertexTexture[layerVertex], layerUvScale, if (ownsLayer) 1f else 0f)
                    )
                }
            }
        }

        // Helper to emit a triangle from face table indices with edge triangle expansion
        fun emitFace(isOverlay: Boolean, faceIndex: Int) {
            if (isOverlay && !enableOverlays) {
                // The shared index still has to advance past the face the client would
                // have consumed here, or the underlay pass reads the wrong entries.
                overlayTotalFaces++
                return
            }

            // Textures are resolved per vertex inside emitTriangle, so nothing about the
            // material is decided at face granularity here.

            // Determine if this face triggers edge triangle insertion
            // The client checks: hasFacesOn[edge] && overlayTotalFaces == edgeFaceCounts[edge]
            // Edge mapping: 0=south (vertex 1), 1=east (vertex 3), 2=north (vertex 5), 3=west (vertex 7)
            val edgeFaceCounts = blendState?.edgeFaceCounts
            val hasFacesOn = blendState?.hasFacesOn

            // Check each edge direction for insertion
            // Client checks edges in order S(0), N(2), E(1), W(3) - order matters for if-else-if
            val edgeCheckOrder = intArrayOf(0, 2, 1, 3)  // S, N, E, W (matching client)
            val edgeVertices = intArrayOf(1, 3, 5, 7)    // S, E, N, W edge midpoint vertices
            var edgeInserted = false

            if (edgeFaceCounts != null && hasFacesOn != null) {
                for (checkIdx in 0..3) {
                    val edge = edgeCheckOrder[checkIdx]
                    val rotatedEdge = (edge - rotation) and 0x3
                    if (hasFacesOn[rotatedEdge] && edgeFaceCounts[edge] == overlayTotalFaces) {
                        edgeInserted = true
                        val edgeVertex = edgeVertices[edge]

                        // Emit 2 triangles: original triangle split by the edge midpoint
                        // Triangle 1: triA[faceIndex], edgeVertex, triC[faceIndex]
                        // Triangle 2: triA[faceIndex], triB[faceIndex], edgeVertex
                        // (matching client's 6-vertex expansion)
                        val ia1 = triA[overlayTotalFaces]
                        val ic1 = triC[overlayTotalFaces]
                        val ib2 = triB[overlayTotalFaces]

                        // Triangle 1: A -> edgeVertex -> C
                        emitTriangle(ia1, edgeVertex, ic1, isOverlay)
                        // Triangle 2: edgeVertex -> B -> C
                        emitTriangle(edgeVertex, ib2, ic1, isOverlay)
                        break
                    }
                }
            }

            if (!edgeInserted) {
                // Standard triangle: A, B, C from face tables
                if (overlayTotalFaces < triA.size) {
                    val ia = triA[overlayTotalFaces]
                    val ib = triB[overlayTotalFaces]
                    val ic = triC[overlayTotalFaces]
                    emitTriangle(ia, ib, ic, isOverlay)
                }
            }

            overlayTotalFaces++
        }

        // Emit overlay faces first, then underlay faces
        for (face in 0 until overlayFaces) {
            emitFace(true, face)
        }

        // If overlay faces were skipped (no visible overlay), advance the counter
        // to match the client's behavior where overlayTotalFaces is always advanced
        // past overlay entries before underlay faces begin
        if (overlayFaces == 0 && blendState != null) {
            overlayTotalFaces = blendState.overlayFaceCount
        }

        for (face in 0 until underlayFaces) {
            emitFace(false, face)
        }

        return vertices
    }
}
