package org.drynor.tools.engine.spatial

import org.drynor.tools.engine.render.Camera
import org.joml.Vector3f

/**
 * Result of culling operation containing visible regions and objects.
 */
data class CullResult(
    val visibleRegions: List<RegionChunk>,
    val visibleObjects: List<CullableObject>,
    val cullingTimeNanos: Long,
    val totalRegions: Int,
    val totalObjects: Int
) {
    val visibleRegionCount: Int get() = visibleRegions.size
    val visibleObjectCount: Int get() = visibleObjects.size
    val regionCullRatio: Float get() = if (totalRegions > 0) 1f - (visibleRegionCount.toFloat() / totalRegions) else 0f
    val objectCullRatio: Float get() = if (totalObjects > 0) 1f - (visibleObjectCount.toFloat() / totalObjects) else 0f
}

/**
 * Represents a terrain region chunk for 2D spatial partitioning.
 */
data class RegionChunk(
    val regionId: Int,
    val regionX: Int,
    val regionY: Int,
    val bounds: Rect,
    val yMin: Float = -100f,
    val yMax: Float = 500f
) {
    // Per-region octree for 3D objects within this region
    var objectTree: Octree<CullableObject>? = null
        private set

    fun initObjectTree(worldBounds: AABB) {
        if (objectTree == null) {
            objectTree = Octree(worldBounds, maxDepth = 4, maxObjects = 16)
        }
    }
}

/**
 * Interface for objects that can be culled.
 */
interface CullableObject {
    val objectId: Int
    val modelId: Int
    val bounds: AABB
    val position: Vector3f
    val regionId: Int
    val plane: Int
}

/**
 * Simple implementation of CullableObject for scene objects.
 *
 * Contains full transform data (position, rotation, scale, offset, terrain tilt) needed
 * for correct rendering. Transform order matches the RS client:
 * Mirror -> RotateY -> TiltX -> TiltZ -> Scale -> Offset -> HeightAdjust -> Translate
 *
 * @param objectId The object definition ID
 * @param modelId The model ID to render
 * @param bounds Bounding box for culling
 * @param position World position (translation)
 * @param regionId The region this object belongs to
 * @param plane The plane level (0-3)
 * @param shape The object shape (0-22, used for roof filtering)
 * @param rotation Rotation around Y axis in radians
 * @param scale Scale factors (default 1,1,1)
 * @param offset Model offset applied after rotation (from definition offsetX/Y/Z)
 * @param tiltX Terrain alignment X-axis rotation in radians (pitch)
 * @param tiltZ Terrain alignment Z-axis rotation in radians (roll)
 * @param heightAdjustment Terrain height adjustment (applied after offset)
 * @param mirrored Whether to mirror the model (negate Z before rotation)
 */
data class SceneObject(
    override val objectId: Int,
    override val modelId: Int,
    override val bounds: AABB,
    override val position: Vector3f,
    override val regionId: Int,
    override val plane: Int = 0,
    val shape: Int = 0,
    val rotation: Float = 0f,
    val scale: Vector3f = Vector3f(1f, 1f, 1f),
    val offset: Vector3f = Vector3f(0f, 0f, 0f),
    val tiltX: Float = 0f,
    val tiltZ: Float = 0f,
    val heightAdjustment: Float = 0f,
    val mirrored: Boolean = false
) : CullableObject

/**
 * High-performance culling system using hierarchical spatial structures.
 *
 * Uses a 2-level hierarchy:
 * 1. Quadtree for terrain regions (2D on XZ plane)
 * 2. Per-region Octrees for 3D objects
 *
 * Performance target: < 0.5ms for 10,000 objects
 */
class CullingSystem(
    private val worldBounds: Rect = Rect(-10000f, -10000f, 10000f, 10000f),
    private val worldYMin: Float = -1000f,
    private val worldYMax: Float = 1000f
) {
    // Region-level quadtree for coarse culling
    private val regionQuadtree = Quadtree<RegionChunk>(worldBounds, maxDepth = 4, maxObjects = 4)

    // Map of region ID to chunk for fast lookup
    private val regions = mutableMapOf<Int, RegionChunk>()

    // Global octree for objects not assigned to a region
    private val globalOctree = Octree<CullableObject>(
        AABB(
            Vector3f(worldBounds.minX, worldYMin, worldBounds.minZ),
            Vector3f(worldBounds.maxX, worldYMax, worldBounds.maxZ)
        ),
        maxDepth = 6,
        maxObjects = 16
    )

    // Statistics
    private var totalRegions = 0
    private var totalObjects = 0

    /**
     * Register a terrain region chunk.
     */
    fun registerRegion(chunk: RegionChunk) {
        regions[chunk.regionId] = chunk
        regionQuadtree.insert(chunk, chunk.bounds)

        // Initialize the region's object tree
        val regionWorldBounds = AABB(
            Vector3f(chunk.bounds.minX, chunk.yMin, chunk.bounds.minZ),
            Vector3f(chunk.bounds.maxX, chunk.yMax, chunk.bounds.maxZ)
        )
        chunk.initObjectTree(regionWorldBounds)

        totalRegions++
    }

    /**
     * Unregister a terrain region chunk.
     */
    fun unregisterRegion(regionId: Int) {
        val chunk = regions.remove(regionId) ?: return
        regionQuadtree.remove(chunk)
        totalRegions--
    }

    /**
     * Register an object for culling.
     *
     * @param obj The object to register
     * @param regionId The region this object belongs to, or -1 for global
     */
    fun registerObject(obj: CullableObject) {
        val region = regions[obj.regionId]
        if (region != null && region.objectTree != null) {
            region.objectTree!!.insert(obj, obj.bounds)
        } else {
            globalOctree.insert(obj, obj.bounds)
        }
        totalObjects++
    }

    /**
     * Unregister an object.
     */
    fun unregisterObject(obj: CullableObject) {
        val region = regions[obj.regionId]
        val removed = if (region != null && region.objectTree != null) {
            region.objectTree!!.remove(obj)
        } else {
            globalOctree.remove(obj)
        }
        if (removed) totalObjects--
    }

    /**
     * Perform frustum culling and return visible regions and objects.
     */
    fun cull(camera: Camera, aspectRatio: Float): CullResult {
        val startTime = System.nanoTime()

        val frustum = camera.getFrustum(aspectRatio)

        // Phase 1: Cull regions using quadtree
        val visibleRegions = regionQuadtree.query(frustum, worldYMin, worldYMax)

        // Phase 2: Cull objects within visible regions
        val visibleObjects = mutableListOf<CullableObject>()

        for (region in visibleRegions) {
            region.objectTree?.let { tree ->
                visibleObjects.addAll(tree.query(frustum))
            }
        }

        // Also query global octree for objects not in any region
        visibleObjects.addAll(globalOctree.query(frustum))

        val endTime = System.nanoTime()

        return CullResult(
            visibleRegions = visibleRegions,
            visibleObjects = visibleObjects,
            cullingTimeNanos = endTime - startTime,
            totalRegions = totalRegions,
            totalObjects = totalObjects
        )
    }

    /**
     * Perform culling with a custom frustum.
     */
    fun cull(frustum: Frustum): CullResult {
        val startTime = System.nanoTime()

        val visibleRegions = regionQuadtree.query(frustum, worldYMin, worldYMax)
        val visibleObjects = mutableListOf<CullableObject>()

        for (region in visibleRegions) {
            region.objectTree?.let { tree ->
                visibleObjects.addAll(tree.query(frustum))
            }
        }

        visibleObjects.addAll(globalOctree.query(frustum))

        val endTime = System.nanoTime()

        return CullResult(
            visibleRegions = visibleRegions,
            visibleObjects = visibleObjects,
            cullingTimeNanos = endTime - startTime,
            totalRegions = totalRegions,
            totalObjects = totalObjects
        )
    }

    /**
     * Query objects in a region of space (for picking, collision, etc).
     */
    fun queryAABB(aabb: AABB): List<CullableObject> {
        val results = mutableListOf<CullableObject>()

        // Query regions that intersect the AABB
        val rect = Rect(aabb.min.x, aabb.min.z, aabb.max.x, aabb.max.z)
        val affectedRegions = regionQuadtree.query(rect)

        for (region in affectedRegions) {
            region.objectTree?.let { tree ->
                results.addAll(tree.query(aabb))
            }
        }

        results.addAll(globalOctree.query(aabb))

        return results
    }

    /**
     * Raycast against all objects.
     */
    fun raycast(origin: Vector3f, direction: Vector3f, maxDistance: Float = Float.MAX_VALUE): List<CullableObject> {
        val results = mutableListOf<CullableObject>()

        // For a proper raycast, we'd need to determine which regions the ray passes through
        // For now, query all regions and filter by ray intersection
        for (region in regions.values) {
            region.objectTree?.let { tree ->
                results.addAll(tree.raycast(origin, direction, maxDistance))
            }
        }

        results.addAll(globalOctree.raycast(origin, direction, maxDistance))

        return results
    }

    /**
     * Clear all regions and objects.
     */
    fun clear() {
        regionQuadtree.clear()
        regions.clear()
        globalOctree.clear()
        totalRegions = 0
        totalObjects = 0
    }

    /**
     * Get the region at a world position.
     */
    fun getRegionAt(x: Float, z: Float): RegionChunk? {
        val results = regionQuadtree.query(Rect(x, z, x, z))
        return results.firstOrNull()
    }

    /**
     * Get statistics about the culling system.
     */
    fun getStats(): CullingStats {
        return CullingStats(
            totalRegions = totalRegions,
            totalObjects = totalObjects,
            globalOctreeStats = globalOctree.getStats()
        )
    }
}

data class CullingStats(
    val totalRegions: Int,
    val totalObjects: Int,
    val globalOctreeStats: OctreeStats
)
