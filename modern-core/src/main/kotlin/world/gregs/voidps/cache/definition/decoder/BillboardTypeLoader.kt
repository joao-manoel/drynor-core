package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and caches BillboardType configs from cache Index 29 (BILLBOARDS).
 *
 * Matches the client's BillboardTypeList pattern: configs are stored in
 * archive 0 with the billboard ID as the file ID. Results are cached in
 * a ConcurrentHashMap for fast, thread-safe repeated lookups.
 *
 * A billboard with no data in the cache returns a default BillboardType
 * (all default field values), matching the client behavior where
 * BillboardTypeList.list() creates a new BillboardType even if
 * billboardData is null.
 */
object BillboardTypeLoader {

    private val cache = ConcurrentHashMap<Int, BillboardType>()

    private val decoder = BillboardDecoder()

    /**
     * Get a BillboardType by ID, loading from the game cache if not yet cached.
     *
     * @param gameCache The game cache to load from
     * @param billboardId The billboard config ID
     * @return The decoded BillboardType, or a default instance if not found in cache
     */
    fun get(gameCache: Cache, billboardId: Int): BillboardType {
        return cache.computeIfAbsent(billboardId) {
            val definition = BillboardType(id = billboardId)
            val data = gameCache.data(Index.BILLBOARDS, BillboardDecoder.ARCHIVE, billboardId)
            if (data != null) {
                decoder.readLoop(definition, BufferReader(data))
            }
            definition
        }
    }

    /**
     * Clear the cached billboard types.
     */
    fun clearCache() {
        cache.clear()
    }
}
