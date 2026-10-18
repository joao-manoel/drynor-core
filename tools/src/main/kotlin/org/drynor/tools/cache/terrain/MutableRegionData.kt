package org.drynor.tools.cache.terrain

/**
 * A mutable deep copy of [TerrainLoader.RegionData] with dirty tracking.
 * Editor tools modify this data, and the dirty tracking system tells
 * [TerrainEditManager] which chunks need rebuilding.
 *
 * Chunk coordinates map tiles to 8x8 chunks: chunkX = tileX / 8, chunkY = tileY / 8.
 */
class MutableRegionData(source: TerrainLoader.RegionData) {
    val regionId = source.regionId

    /** Mutable tile data: [plane][x][y] */
    val tiles: Array<Array<Array<MutableTileData>>> = Array(4) { plane ->
        Array(64) { x ->
            Array(64) { y ->
                val t = source.tiles[plane][x][y]
                MutableTileData(t.height, t.overlayId, t.overlayShape, t.overlayRotation, t.underlayId, t.flags)
            }
        }
    }

    // Dirty tracking
    private val dirtyChunks = mutableSetOf<ChunkCoord>()
    var heightsDirty = false; private set
    var blendingDirty = false; private set

    fun setHeight(plane: Int, x: Int, y: Int, height: Int) {
        tiles[plane][x][y].height = height
        heightsDirty = true
        // Mark chunks for tiles within 1-tile radius (shared corners affect normals)
        markChunksDirty(x - 1, y - 1, x + 1, y + 1)
    }

    fun setUnderlayId(plane: Int, x: Int, y: Int, id: Int) {
        tiles[plane][x][y].underlayId = id
        blendingDirty = true
        // 5-tile radius for blending window cascade
        markChunksDirty(x - 5, y - 5, x + 5, y + 5)
    }

    fun setOverlayId(plane: Int, x: Int, y: Int, id: Int, shape: Int, rot: Int) {
        tiles[plane][x][y].apply {
            overlayId = id
            overlayShape = shape
            overlayRotation = rot
        }
        // 1-tile radius for neighbor blend analysis
        markChunksDirty(x - 1, y - 1, x + 1, y + 1)
    }

    fun setFlags(plane: Int, x: Int, y: Int, flags: Int) {
        tiles[plane][x][y].flags = flags
    }

    private fun markChunksDirty(minX: Int, minY: Int, maxX: Int, maxY: Int) {
        val cx0 = (minX.coerceIn(0, 63)) / CHUNK_SIZE
        val cy0 = (minY.coerceIn(0, 63)) / CHUNK_SIZE
        val cx1 = (maxX.coerceIn(0, 63)) / CHUNK_SIZE
        val cy1 = (maxY.coerceIn(0, 63)) / CHUNK_SIZE
        for (cx in cx0..cx1) {
            for (cy in cy0..cy1) {
                dirtyChunks.add(ChunkCoord(cx, cy))
            }
        }
    }

    fun getDirtyChunks(): Set<ChunkCoord> = dirtyChunks.toSet()
    fun hasDirty(): Boolean = dirtyChunks.isNotEmpty()

    fun clearDirty() {
        dirtyChunks.clear()
        heightsDirty = false
        blendingDirty = false
    }

    /** Convert to immutable TileData array for TerrainLoader functions. */
    fun toTileArray(): Array<Array<Array<TerrainLoader.TileData>>> = Array(4) { plane ->
        Array(64) { x ->
            Array(64) { y ->
                val t = tiles[plane][x][y]
                TerrainLoader.TileData(t.height, t.overlayId, t.overlayShape, t.overlayRotation, t.underlayId, t.flags)
            }
        }
    }

    /** Convert to immutable RegionData for TerrainLoader functions. */
    fun toRegionData(): TerrainLoader.RegionData = TerrainLoader.RegionData(regionId, toTileArray())

    companion object {
        const val CHUNK_SIZE = 8
    }
}

data class MutableTileData(
    var height: Int,
    var overlayId: Int,
    var overlayShape: Int,
    var overlayRotation: Int,
    var underlayId: Int,
    var flags: Int
)

/** Identifies a chunk within a region (0-7, 0-7). */
data class ChunkCoord(val chunkX: Int, val chunkY: Int)
