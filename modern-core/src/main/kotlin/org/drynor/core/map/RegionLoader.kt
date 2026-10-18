package org.drynor.core.map

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Complete region data including terrain tiles and objects.
 */
data class RegionData(
    val regionId: Int,
    val regionX: Int,
    val regionY: Int,
    val tiles: LongArray,           // MapTile packed values indexed by MapDefinition.index()
    val settings: ByteArray,         // Tile settings (collision flags, bridges)
    val objects: List<MapObject>     // All objects in this region
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegionData) return false
        return regionId == other.regionId
    }

    override fun hashCode(): Int = regionId
}

/**
 * Tile data extracted from terrain archive.
 */
data class TileInfo(
    val height: Int,
    val overlayId: Int,
    val overlayShape: Int,
    val overlayRotation: Int,
    val underlayId: Int,
    val settings: Int
)

/**
 * Loads complete region data from the cache including terrain and XTEA-encrypted objects.
 *
 * Usage:
 * ```kotlin
 * MapXteas.load()  // Load XTEA keys first
 * val region = RegionLoader.loadRegion(cache, 50, 50)
 * ```
 */
object RegionLoader {
    private class Loaded(val data: RegionData?)

    private val regionCache = ConcurrentHashMap<Int, Loaded>()

    /**
     * Pre-computed tile indices for iteration order (matches client expectation).
     */
    private val tileIndices = IntArray(16384).apply {
        var i = 0
        for (level in 0 until 4) {
            for (localX in 0 until 64) {
                for (localY in 0 until 64) {
                    this[i++] = MapDefinition.index(localX, localY, level)
                }
            }
        }
    }

    /**
     * Load a region by coordinates.
     *
     * @param cache The game cache
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     * @param useCache Whether to cache the result (default true)
     * @return RegionData or null if region doesn't exist
     */
    fun loadRegion(cache: Cache, regionX: Int, regionY: Int, useCache: Boolean = true): RegionData? {
        val regionId = (regionX shl 8) or regionY

        if (!useCache) return loadRegionInternal(cache, regionX, regionY)
        return regionCache.computeIfAbsent(regionId) { Loaded(loadRegionInternal(cache, regionX, regionY)) }.data
    }

    /**
     * Load a region by region ID.
     */
    fun loadRegion(cache: Cache, regionId: Int, useCache: Boolean = true): RegionData? {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff
        return loadRegion(cache, regionX, regionY, useCache)
    }

    private fun loadRegionInternal(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY

        // Load terrain data (m{x}_{y}) - unencrypted
        val terrainArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (terrainArchive == -1) return null

        val terrainData = cache.data(Index.MAPS, terrainArchive) ?: return null

        // Decode terrain tiles and extract settings
        val tiles = LongArray(64 * 64 * 4)
        val settings = ByteArray(64 * 64 * 4)
        decodeTerrain(terrainData, tiles, settings)

        // Load object data (l{x}_{y}) - may be XTEA encrypted
        val objectArchive = cache.archiveId(Index.MAPS, "l${regionX}_$regionY")
        val objects = if (objectArchive != -1) {
            val xtea = MapXteas.getKeys(regionId)
            val objectData = cache.data(Index.MAPS, objectArchive, 0, xtea)
            if (objectData != null && objectData.isNotEmpty()) {
                decodeObjects(objectData, settings, regionX, regionY, xtea != null)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        return RegionData(regionId, regionX, regionY, tiles, settings, objects)
    }

    /**
     * Decode terrain data into tiles and settings arrays.
     */
    private fun decodeTerrain(data: ByteArray, tiles: LongArray, settings: ByteArray) {
        var position = 0
        val size = data.size

        for (index in tileIndices) {
            var height = 0
            var overlayId = 0
            var overlayShape = 0
            var overlayRotation = 0
            var underlayId = 0
            var setting = 0

            while (position < size) {
                val opcode = data[position++].toInt() and 0xff

                when {
                    opcode == 0 -> break
                    opcode == 1 -> {
                        height = data[position++].toInt() and 0xff
                        break
                    }
                    opcode <= 49 -> {
                        overlayId = data[position++].toInt() and 0xff
                        overlayShape = (opcode - 2) / 4
                        overlayRotation = (opcode - 2) and 3
                    }
                    opcode <= 81 -> {
                        setting = opcode - 49
                    }
                    else -> {
                        underlayId = opcode - 81
                    }
                }
            }

            // Pack tile data
            tiles[index] = packTile(height, overlayId, overlayShape, overlayRotation, underlayId, setting)
            settings[index] = setting.toByte()
        }
    }

    /**
     * Pack tile data into a long value.
     * Format matches MapTile value class structure.
     */
    private fun packTile(
        height: Int,
        overlayId: Int,
        overlayShape: Int,
        overlayRotation: Int,
        underlayId: Int,
        settings: Int
    ): Long {
        return height.toLong() or
               (overlayId.toLong() shl 16) or
               (overlayShape.toLong() shl 24) or
               (overlayRotation.toLong() shl 32) or
               (settings.toLong() shl 40) or
               (underlayId.toLong() shl 48)
    }

    /**
     * Decode objects from the object archive data.
     */
    private fun decodeObjects(
        data: ByteArray,
        settings: ByteArray,
        regionTileX: Int,
        regionTileY: Int,
        encrypted: Boolean
    ): List<MapObject> {
        val objects = mutableListOf<MapObject>()

        val decoder = object : MapObjectDecoder() {
            override fun add(
                objectId: Int,
                localX: Int,
                localY: Int,
                level: Int,
                shape: Int,
                rotation: Int,
                regionTileX: Int,
                regionTileY: Int
            ) {
                // Calculate world coordinates
                val worldX = regionTileX * 64 + localX
                val worldY = regionTileY * 64 + localY

                objects.add(MapObject(objectId, worldX, worldY, level, shape, rotation))
            }
        }

        try {
            decoder.decode(data, settings, regionTileX, regionTileY)
        } catch (e: Exception) {
            // A region that silently contains no objects is indistinguishable from one
            // that is genuinely empty, so this failure must never be silent. That is not
            // hypothetical: MapObjectDecoder chained object-id deltas only once where the
            // client's readSum loops, and 22 of 2399 archives threw here and loaded with
            // zero objects -- no doors, stairs or banks -- undetected because this catch
            // said nothing.
            //
            // Wrong/absent XTEA keys are an expected, benign failure (the data decrypts to
            // noise), so they are reported differently from a decode fault on data we could
            // read. Anything in the second class is a real bug worth chasing.
            System.err.println(
                "[RegionLoader] region ($regionTileX, $regionTileY): object decode failed after " +
                    "${objects.size} object(s) - " +
                    if (encrypted) "encrypted, XTEA keys may be wrong: ${e}"
                    else "UNENCRYPTED data, so this is a decoder fault, not a key problem: ${e}"
            )
            // Partial output is worse than none: it looks like a valid region while silently
            // missing everything past the fault. Drop it, having said so above.
            return emptyList()
        }

        return objects
    }

    /**
     * Extract tile info at a specific location.
     */
    fun getTileInfo(tiles: LongArray, localX: Int, localY: Int, level: Int): TileInfo {
        val packed = tiles[MapDefinition.index(localX, localY, level)]
        return TileInfo(
            height = (packed and 0xff).toInt(),
            overlayId = ((packed shr 16) and 0xff).toInt(),
            overlayShape = ((packed shr 24) and 0xff).toInt(),
            overlayRotation = ((packed shr 32) and 0xff).toInt(),
            underlayId = ((packed shr 48) and 0xff).toInt(),
            settings = ((packed shr 40) and 0xff).toInt()
        )
    }

    /**
     * Get tile height at a location (raw value, multiply by 8 for world units).
     */
    fun getTileHeight(tiles: LongArray, localX: Int, localY: Int, level: Int): Int {
        return (tiles[MapDefinition.index(localX, localY, level)] and 0xff).toInt()
    }

    /**
     * Check if a region exists in the cache.
     */
    fun regionExists(cache: Cache, regionX: Int, regionY: Int): Boolean {
        return cache.archiveId(Index.MAPS, "m${regionX}_$regionY") != -1
    }

    /**
     * Get all available region coordinates.
     */
    fun getAvailableRegions(cache: Cache): List<Pair<Int, Int>> {
        val regions = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                if (regionExists(cache, x, y)) {
                    regions.add(Pair(x, y))
                }
            }
        }
        return regions
    }

    /**
     * Clear the region cache.
     */
    fun clearCache() {
        regionCache.clear()
    }

    /**
     * Get cache statistics.
     */
    fun getCacheSize(): Int = regionCache.size
}
