package org.darkan.tools.cache.map.environment

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and caches per-region environment/lighting data.
 *
 * Standalone by design: it re-reads `m{x}_{y}` from `Index.MAPS` and skips the tile section
 * itself, so the terrain hot path in `TerrainLoader` is untouched. Map archives are small
 * and already cached by the cache layer, so the extra read costs little and only happens
 * once per region.
 */
object MapEnvironmentLoader {

    private val cached = ConcurrentHashMap<Int, RegionEnvironment>()

    /** Region key used by the cache map, matching the RS region id packing. */
    private fun key(regionX: Int, regionY: Int) = (regionX shl 8) or regionY

    /**
     * Environment for a region, or null when the region has no map archive.
     * Regions with a map archive but no effects block return an empty [RegionEnvironment].
     */
    fun load(cache: Cache, regionX: Int, regionY: Int): RegionEnvironment? {
        cached[key(regionX, regionY)]?.let { return it }

        val archive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (archive == -1) return null
        val data = cache.data(Index.MAPS, archive) ?: return null

        val decoded = MapEnvironmentDecoder.decode(data, regionX, regionY) { id ->
            EnvironmentConfigs.light(cache, id)
        }
        cached[key(regionX, regionY)] = decoded
        return decoded
    }

    /**
     * Seed the cache with an environment that was just written back to disk.
     *
     * The live [Cache] keeps its own decompressed-archive cache and has no invalidation hook,
     * so after a save it would keep handing out the pre-save bytes. Storing the written state
     * here keeps the viewer - and "Reset to cache" - consistent with what is now on disk.
     */
    fun store(regionX: Int, regionY: Int, environment: RegionEnvironment) {
        cached[key(regionX, regionY)] = environment
    }

    /**
     * Environment for a chunk, falling back to [MapEnvironment.DEFAULT] when the region has
     * no map archive or defines no environment.
     */
    fun environment(cache: Cache, regionX: Int, regionY: Int, chunkX: Int, chunkY: Int): MapEnvironment =
        load(cache, regionX, regionY)?.environment(chunkX, chunkY) ?: MapEnvironment.DEFAULT

    /** Resolved cube texture for an environment, falling back to the scene-wide default. */
    fun cubeTexture(cache: Cache, environment: MapEnvironment): CubeTextureRef? =
        environment.cubeTexture ?: EnvironmentConfigs.defaultCubeTexture(cache)

    /** Skybox config referenced by an environment, or null when it does not override the skybox. */
    fun skybox(cache: Cache, environment: MapEnvironment): SkyboxType? =
        environment.skybox?.let { EnvironmentConfigs.skybox(cache, it.id) }

    /** Suns belonging to a skybox config, in declaration order. */
    fun suns(cache: Cache, skybox: SkyboxType): List<SunType> {
        val ids = skybox.sunIds ?: return emptyList()
        return ids.map { EnvironmentConfigs.sun(cache, it) }
    }

    fun clear() {
        cached.clear()
        EnvironmentConfigs.clear()
    }
}
