package org.drynor.tools.engine.input

import org.drynor.tools.cache.map.ObjectRenderer
import world.gregs.voidps.cache.definition.data.ModelDefinition
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.terrain.TerrainLoader
import org.drynor.tools.engine.render.Camera
import org.drynor.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import kotlin.math.floor

/**
 * Handles tile selection via raycasting from mouse position to terrain.
 *
 * Uses ray-plane intersection to find which tile the user clicked on.
 * The terrain is treated as a heightmap, with each tile having height at its corners.
 */
class TilePicker {

    /**
     * Information about a selected tile.
     */
    data class SelectedTile(
        val regionX: Int,
        val regionY: Int,
        val localX: Int,
        val localY: Int,
        val plane: Int,
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float
    )

    /**
     * Information about an object on a tile, including its textures.
     */
    data class TileObject(
        val mapObject: MapObject,
        val definition: ObjectDefinition,
        val modelIds: List<Int>,
        val textureIds: Set<Int>  // Unique texture IDs used by all models
    )

    /**
     * Detailed tile data retrieved from TerrainLoader.
     */
    data class TileDetails(
        val tile: SelectedTile,
        val tileData: TerrainLoader.TileData?,
        val underlayDef: UnderlayDefinition?,
        val overlayDef: OverlayDefinition?,
        val cornerHeights: FloatArray,  // [SW, SE, NW, NE]
        val cornerUVsOverlay: Array<FloatArray>?,  // [[u, v], ...] for overlay
        val cornerUVsUnderlay: Array<FloatArray>?,  // [[u, v], ...] for underlay
        val objects: List<TileObject> = emptyList()  // Objects on this tile
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TileDetails
            return tile == other.tile
        }

        override fun hashCode(): Int = tile.hashCode()
    }

    // Currently selected tile
    var selectedTile: SelectedTile? = null
        private set

    var selectedTileDetails: TileDetails? = null
        private set

    /**
     * Supplies the tile grid of any region the ray may cross.
     *
     * The picker works in absolute world space and asks for whichever region a sample
     * lands in, so a click is answered by the region actually under the cursor rather
     * than by the one the camera happens to be centred on. Returning null means "that
     * region is not loaded", which the picker treats exactly like empty space: the ray
     * passes through and no hit is reported there.
     */
    fun interface TerrainProvider {
        fun tiles(regionX: Int, regionY: Int): Array<Array<Array<TerrainLoader.TileData>>>?
    }

    // Ray for debugging
    private val rayOrigin = Vector3f()
    private val rayDirection = Vector3f()

    // Cached matrices for unprojection
    private val inverseViewProj = Matrix4f()
    private val nearPoint = Vector4f()
    private val farPoint = Vector4f()

    /**
     * Convert screen coordinates to a world ray.
     *
     * @param mouseX Mouse X in screen pixels
     * @param mouseY Mouse Y in screen pixels
     * @param screenWidth Window width
     * @param screenHeight Window height
     * @param camera The camera for view/projection matrices
     * @param aspectRatio Window aspect ratio
     */
    fun screenToWorldRay(
        mouseX: Float,
        mouseY: Float,
        screenWidth: Int,
        screenHeight: Int,
        camera: Camera,
        aspectRatio: Float
    ) {
        // Convert to normalized device coordinates [-1, 1]
        val ndcX = (2f * mouseX / screenWidth) - 1f
        val ndcY = 1f - (2f * mouseY / screenHeight)  // Y is inverted

        // Get view-projection matrix and invert it
        val viewMatrix = camera.getViewMatrix()
        val projMatrix = camera.getProjectionMatrix(aspectRatio)
        projMatrix.mul(viewMatrix, inverseViewProj)
        inverseViewProj.invert()

        // Unproject near and far points
        nearPoint.set(ndcX, ndcY, -1f, 1f)
        inverseViewProj.transform(nearPoint)
        nearPoint.div(nearPoint.w)

        farPoint.set(ndcX, ndcY, 1f, 1f)
        inverseViewProj.transform(farPoint)
        farPoint.div(farPoint.w)

        // Ray origin is camera position (or near point for orthographic)
        rayOrigin.set(nearPoint.x, nearPoint.y, nearPoint.z)

        // Ray direction
        rayDirection.set(
            farPoint.x - nearPoint.x,
            farPoint.y - nearPoint.y,
            farPoint.z - nearPoint.z
        ).normalize()
    }

    /**
     * Pick a tile from the current ray across every loaded region.
     *
     * The ray is marched in absolute world space and each sample is resolved to whichever
     * region contains it, so the picker answers clicks anywhere the terrain is drawn - not
     * only inside the region the editor is centred on. That matters because the camera
     * normally sits outside its own region (a region is 256 world units across and the
     * default framing pulls back further than that), which puts most of the lower half of
     * the viewport over a neighbouring region.
     *
     * @param provider Supplies the tile grid for a region, or null when it is not loaded
     * @param plane The current plane
     * @return The selected tile, or null if no intersection
     */
    fun pickTile(provider: TerrainProvider, plane: Int): SelectedTile? {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        // March along the ray to find terrain intersection
        // Use binary search refinement for accuracy
        val maxDistance = 4000f
        val stepSize = tileSize * 0.5f  // Half tile per step

        val currentPoint = Vector3f(rayOrigin)
        val step = Vector3f(rayDirection).mul(stepSize)

        // A surface crossing is only real when BOTH ends of the step sit over loaded
        // terrain. Seeding `lastAbove = true` and carrying it across samples that had no
        // terrain under them reported a crossing on the first sample that did, and the
        // bisection then bracketed across a gap and clamped to the grid edge - the "every
        // click picks x=0" failure. `hasPrevSample` breaks the bracket whenever the ray
        // leaves loaded ground, so a hit needs a genuine above->below transition with both
        // samples over terrain that exists.
        var hasPrevSample = false
        var lastAbove = false
        val lastPoint = Vector3f(rayOrigin)

        var distance = 0f
        while (distance < maxDistance) {
            currentPoint.add(step)
            distance += stepSize

            val terrainHeight = heightAt(
                provider, plane, currentPoint.x, currentPoint.z, tileSize, heightScale
            )

            if (terrainHeight == null) {
                hasPrevSample = false
                lastPoint.set(currentPoint)
                continue
            }

            val isAbove = currentPoint.y > terrainHeight

            // Check for intersection (ray crosses terrain surface)
            if (hasPrevSample && lastAbove && !isAbove) {
                // Binary search refinement
                val intersection = binarySearchIntersection(
                    lastPoint, currentPoint, provider, plane, tileSize, heightScale
                )

                val globalX = floor(intersection.x / tileSize).toInt()
                val globalY = floor(-intersection.z / tileSize).toInt()
                if (globalX < 0 || globalY < 0) return null

                selectedTile = SelectedTile(
                    regionX = globalX / TILES_PER_REGION,
                    regionY = globalY / TILES_PER_REGION,
                    localX = globalX % TILES_PER_REGION,
                    localY = globalY % TILES_PER_REGION,
                    plane = plane,
                    worldX = intersection.x,
                    worldY = intersection.y,
                    worldZ = intersection.z
                )
                return selectedTile
            }

            hasPrevSample = true
            lastAbove = isAbove
            lastPoint.set(currentPoint)
        }

        return null
    }

    /**
     * Height of one terrain vertex in absolute tile-grid coordinates, or null when the
     * region owning it is not loaded.
     *
     * Vertex (gx, gy) is shared between regions: it is local (gx % 64, gy % 64) of region
     * (gx / 64, gy / 64), and the vertex on a region's east edge is the same vertex as the
     * one on its neighbour's west edge. Resolving corners this way is what keeps the
     * interpolated surface continuous where two loaded regions meet.
     */
    private fun vertexHeight(
        provider: TerrainProvider,
        plane: Int,
        globalX: Int,
        globalY: Int,
        heightScale: Float
    ): Float? {
        if (globalX < 0 || globalY < 0) return null
        val tiles = provider.tiles(globalX / TILES_PER_REGION, globalY / TILES_PER_REGION) ?: return null
        if (plane < 0 || plane >= tiles.size) return null
        return tiles[plane][globalX % TILES_PER_REGION][globalY % TILES_PER_REGION].height * heightScale
    }

    /**
     * Bilinearly interpolated terrain height at a world position, or null when no loaded
     * region covers it.
     *
     * Only the tile the sample falls in has to be loaded. Its three other corners are
     * taken from the true neighbouring vertex when that region is loaded, and otherwise
     * fall back to this region's clamped edge vertex - which reproduces the flat edge band
     * the single-region picker produced, rather than punching an unpickable seam around
     * whatever happens to be loaded.
     */
    private fun heightAt(
        provider: TerrainProvider,
        plane: Int,
        worldX: Float,
        worldZ: Float,
        tileSize: Float,
        heightScale: Float
    ): Float? {
        val gridX = worldX / tileSize
        val gridY = -worldZ / tileSize  // Z is negated in OpenGL
        val globalX = floor(gridX).toInt()
        val globalY = floor(gridY).toInt()

        val h00 = vertexHeight(provider, plane, globalX, globalY, heightScale) ?: return null
        val h10 = vertexHeight(provider, plane, globalX + 1, globalY, heightScale) ?: h00
        val h01 = vertexHeight(provider, plane, globalX, globalY + 1, heightScale) ?: h00
        val h11 = vertexHeight(provider, plane, globalX + 1, globalY + 1, heightScale) ?: h10

        val fx = (gridX - globalX).coerceIn(0f, 1f)
        val fz = (gridY - globalY).coerceIn(0f, 1f)

        val h0 = h00 + (h10 - h00) * fx  // South edge
        val h1 = h01 + (h11 - h01) * fx  // North edge

        return h0 + (h1 - h0) * fz
    }

    /**
     * Binary search to refine intersection point.
     */
    private fun binarySearchIntersection(
        above: Vector3f,
        below: Vector3f,
        provider: TerrainProvider,
        plane: Int,
        tileSize: Float,
        heightScale: Float
    ): Vector3f {
        val mid = Vector3f()
        val a = Vector3f(above)
        val b = Vector3f(below)

        for (i in 0 until 10) {  // 10 iterations for good precision
            mid.set(a).add(b).mul(0.5f)

            // Both endpoints sit over loaded terrain and the segment is half a tile long,
            // so the midpoint does too; a null here would mean the bracket is meaningless,
            // and keeping the above-end fixed simply ends the refinement where it is
            // rather than letting a missing sample drag the result across the gap.
            val terrainHeight = heightAt(provider, plane, mid.x, mid.z, tileSize, heightScale)
                ?: return mid

            if (mid.y > terrainHeight) {
                a.set(mid)
            } else {
                b.set(mid)
            }
        }

        return mid
    }

    /**
     * Update tile details from cache definitions.
     *
     * @param terrainTiles The terrain tile data array
     * @param cache The game cache for loading definitions
     * @param regionObjects Optional list of MapObjects in the region for object lookup
     * @param baseWorldX World X coordinate of region origin (regionX * 64)
     * @param baseWorldY World Y coordinate of region origin (regionY * 64)
     */
    fun updateTileDetails(
        terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>?,
        cache: Cache,
        regionObjects: List<MapObject>? = null,
        baseWorldX: Int = 0,
        baseWorldY: Int = 0
    ) {
        val tile = selectedTile ?: run {
            selectedTileDetails = null
            return
        }

        val tileData = terrainTiles?.getOrNull(tile.plane)
            ?.getOrNull(tile.localX)
            ?.getOrNull(tile.localY)

        if (tileData == null) {
            selectedTileDetails = null
            return
        }

        val heightScale = TerrainLoader.getHeightScale()

        // Get corner heights
        val nextX = (tile.localX + 1).coerceAtMost(63)
        val nextY = (tile.localY + 1).coerceAtMost(63)

        val cornerHeights = floatArrayOf(
            terrainTiles[tile.plane][tile.localX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][nextX][tile.localY].height * heightScale,
            terrainTiles[tile.plane][tile.localX][nextY].height * heightScale,
            terrainTiles[tile.plane][nextX][nextY].height * heightScale
        )

        // Get definitions
        val underlayDef = if (tileData.underlayId > 0) {
            TerrainLoader.getUnderlayDefinition(tileData.underlayId)
        } else null

        val overlayDef = if (tileData.overlayId > 0) {
            TerrainLoader.getOverlayDefinition(tileData.overlayId)
        } else null

        // Calculate UV coordinates for overlay
        val overlayUVs = if (overlayDef != null && overlayDef.texture >= 0) {
            val scale = if (overlayDef.scale > 0) overlayDef.scale.toFloat() else 512f
            val uvScale = 1f / scale

            // Local tile positions (0-512 within tile)
            // Corners: SW(0,0), SE(512,0), NW(0,512), NE(512,512)
            arrayOf(
                floatArrayOf(0f, 0f),                           // SW
                floatArrayOf(512f * uvScale, 0f),               // SE
                floatArrayOf(0f, 512f * uvScale),               // NW
                floatArrayOf(512f * uvScale, 512f * uvScale)    // NE
            )
        } else null

        // Calculate UV coordinates for underlay
        val underlayUVs = if (underlayDef != null && underlayDef.texture >= 0) {
            val scale = if (underlayDef.scale > 0) underlayDef.scale.toFloat() else 512f
            val uvScale = 1f / scale

            arrayOf(
                floatArrayOf(0f, 0f),
                floatArrayOf(512f * uvScale, 0f),
                floatArrayOf(0f, 512f * uvScale),
                floatArrayOf(512f * uvScale, 512f * uvScale)
            )
        } else null

        // Collect objects at this tile position
        val tileObjects = collectObjectsAtTile(
            cache, regionObjects, baseWorldX, baseWorldY,
            tile.localX, tile.localY, tile.plane
        )

        selectedTileDetails = TileDetails(
            tile = tile,
            tileData = tileData,
            underlayDef = underlayDef,
            overlayDef = overlayDef,
            cornerHeights = cornerHeights,
            cornerUVsOverlay = overlayUVs,
            cornerUVsUnderlay = underlayUVs,
            objects = tileObjects
        )
    }

    /**
     * Collect all objects at the specified tile position.
     * Also includes objects that span multiple tiles (sizeX > 1 or sizeY > 1).
     */
    private fun collectObjectsAtTile(
        cache: Cache,
        regionObjects: List<MapObject>?,
        baseWorldX: Int,
        baseWorldY: Int,
        localX: Int,
        localY: Int,
        plane: Int
    ): List<TileObject> {
        if (regionObjects == null) return emptyList()

        val result = mutableListOf<TileObject>()
        val worldX = baseWorldX + localX
        val worldY = baseWorldY + localY

        for (obj in regionObjects) {
            // Check if object is on this tile (considering object size)
            val def = ObjectRenderer.getDefinition(obj.id) ?: continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Check if the tile is within the object's footprint
            val objMinX = obj.x
            val objMaxX = obj.x + sizeX - 1
            val objMinY = obj.y
            val objMaxY = obj.y + sizeY - 1

            if (worldX in objMinX..objMaxX && worldY in objMinY..objMaxY && obj.level == plane) {
                val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
                if (modelIds.isEmpty()) continue

                // Collect texture IDs from all models
                val textureIds = mutableSetOf<Int>()
                for (modelId in modelIds) {
                    val modelDef = ModelLoader.loadDefinition(cache, modelId)
                    if (modelDef != null) {
                        collectTextureIds(modelDef, textureIds)
                    }
                }

                result.add(TileObject(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    textureIds = textureIds
                ))
            }
        }

        return result
    }

    /**
     * Collect unique texture IDs from a model definition.
     */
    private fun collectTextureIds(def: ModelDefinition, textureIds: MutableSet<Int>) {
        val faceTextures = def.faceTextures ?: return
        for (i in 0 until def.faceCount) {
            val textureIdSigned = faceTextures[i].toInt()
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
            if (textureId >= 0) {
                textureIds.add(textureId)
            }
        }
    }

    /**
     * Clear the selection.
     */
    fun clearSelection() {
        selectedTile = null
        selectedTileDetails = null
    }

    /**
     * Get the current ray origin for debugging.
     */
    fun getRayOrigin(): Vector3f = rayOrigin

    /**
     * Get the current ray direction for debugging.
     */
    fun getRayDirection(): Vector3f = rayDirection

    companion object {
        private const val TILES_PER_REGION = 64
    }
}
