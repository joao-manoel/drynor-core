package org.drynor.tools.engine.spatial

import org.drynor.tools.engine.render.Mesh
import org.joml.Vector2f
import org.joml.Vector3f
import kotlin.math.sqrt

/**
 * Level of Detail group containing multiple mesh LODs.
 */
data class LODGroup(
    val id: String,
    val center: Vector3f,
    val meshes: List<Mesh>,         // LOD 0 = highest detail, increasing = lower detail
    val boundingSphere: BoundingSphere
) {
    /**
     * Get the LOD mesh for a given level.
     * Clamps to available LODs if requested level is out of range.
     */
    fun getMesh(lodLevel: Int): Mesh? {
        if (meshes.isEmpty()) return null
        return meshes[lodLevel.coerceIn(0, meshes.size - 1)]
    }

    /**
     * Get number of LOD levels available.
     */
    val lodCount: Int get() = meshes.size
}

/**
 * Result of LOD selection for an object.
 */
data class LODSelection(
    val lodGroup: LODGroup,
    val lodLevel: Int,
    val distanceSquared: Float,
    val mesh: Mesh?
)

/**
 * System for managing level-of-detail selection based on distance.
 *
 * Objects register multiple mesh LODs, and the system selects the appropriate
 * LOD based on distance from camera.
 *
 * Default LOD distances:
 * - LOD 0: 0-50 units (full detail)
 * - LOD 1: 50-150 units
 * - LOD 2: 150-400 units
 * - LOD 3: 400-1000 units
 * - Beyond 1000: lowest LOD or culled
 */
class LODSystem(
    var lodDistances: FloatArray = floatArrayOf(50f, 150f, 400f, 1000f)
) {
    private val lodGroups = mutableMapOf<String, LODGroup>()

    // Pre-squared distances for faster comparison
    private var lodDistancesSq = lodDistances.map { it * it }.toFloatArray()

    // Statistics
    private var lastSelectionTimeNanos = 0L
    private var lodCounts = IntArray(5) // Count per LOD level

    /**
     * Set LOD distance thresholds.
     */
    fun setLODDistances(distances: FloatArray) {
        lodDistances = distances
        lodDistancesSq = distances.map { it * it }.toFloatArray()
    }

    /**
     * Register a LOD group with multiple mesh levels.
     *
     * @param id Unique identifier for this LOD group
     * @param center World position center of the object
     * @param meshes List of meshes from highest to lowest detail
     */
    fun registerLODGroup(id: String, center: Vector3f, meshes: List<Mesh>) {
        if (meshes.isEmpty()) return

        // Calculate bounding sphere from first (highest detail) mesh
        // For simplicity, use a default radius - in practice this would be computed from mesh bounds
        val radius = 10f // Default radius, should be computed from mesh

        val group = LODGroup(
            id = id,
            center = center,
            meshes = meshes,
            boundingSphere = BoundingSphere(center, radius)
        )

        lodGroups[id] = group
    }

    /**
     * Register a LOD group with a pre-computed bounding sphere.
     */
    fun registerLODGroup(id: String, center: Vector3f, meshes: List<Mesh>, radius: Float) {
        if (meshes.isEmpty()) return

        val group = LODGroup(
            id = id,
            center = center,
            meshes = meshes,
            boundingSphere = BoundingSphere(center, radius)
        )

        lodGroups[id] = group
    }

    /**
     * Unregister a LOD group.
     */
    fun unregisterLODGroup(id: String) {
        lodGroups.remove(id)
    }

    /**
     * Get the appropriate LOD mesh for an object based on camera position.
     *
     * @param id LOD group ID
     * @param cameraPos Camera world position
     * @return The appropriate mesh, or null if not found or too far
     */
    fun getLODMesh(id: String, cameraPos: Vector3f): Mesh? {
        val group = lodGroups[id] ?: return null
        val distSq = group.center.distanceSquared(cameraPos)
        val lodLevel = calculateLODLevel(distSq)
        return group.getMesh(lodLevel)
    }

    /**
     * Get detailed LOD selection for an object.
     */
    fun selectLOD(id: String, cameraPos: Vector3f): LODSelection? {
        val group = lodGroups[id] ?: return null
        val distSq = group.center.distanceSquared(cameraPos)
        val lodLevel = calculateLODLevel(distSq)
        val mesh = group.getMesh(lodLevel)

        return LODSelection(
            lodGroup = group,
            lodLevel = lodLevel,
            distanceSquared = distSq,
            mesh = mesh
        )
    }

    /**
     * Calculate LOD level from squared distance.
     */
    private fun calculateLODLevel(distanceSquared: Float): Int {
        for ((i, thresholdSq) in lodDistancesSq.withIndex()) {
            if (distanceSquared < thresholdSq) {
                return i
            }
        }
        return lodDistancesSq.size // Lowest LOD or beyond
    }

    /**
     * Select LODs for all groups and return selections.
     */
    fun selectAllLODs(cameraPos: Vector3f): List<LODSelection> {
        val startTime = System.nanoTime()

        // Reset counts
        lodCounts.fill(0)

        val selections = mutableListOf<LODSelection>()

        for ((_, group) in lodGroups) {
            val distSq = group.center.distanceSquared(cameraPos)
            val lodLevel = calculateLODLevel(distSq)
            val mesh = group.getMesh(lodLevel)

            if (mesh != null) {
                selections.add(LODSelection(group, lodLevel, distSq, mesh))
                lodCounts[lodLevel.coerceIn(0, lodCounts.size - 1)]++
            }
        }

        lastSelectionTimeNanos = System.nanoTime() - startTime

        return selections
    }

    /**
     * Select LODs only for visible groups (after frustum culling).
     */
    fun selectVisibleLODs(cameraPos: Vector3f, frustum: Frustum): List<LODSelection> {
        val startTime = System.nanoTime()

        lodCounts.fill(0)
        val selections = mutableListOf<LODSelection>()

        for ((_, group) in lodGroups) {
            // Quick frustum test
            if (!frustum.isSphereVisible(group.boundingSphere)) {
                continue
            }

            val distSq = group.center.distanceSquared(cameraPos)
            val lodLevel = calculateLODLevel(distSq)
            val mesh = group.getMesh(lodLevel)

            if (mesh != null) {
                selections.add(LODSelection(group, lodLevel, distSq, mesh))
                lodCounts[lodLevel.coerceIn(0, lodCounts.size - 1)]++
            }
        }

        lastSelectionTimeNanos = System.nanoTime() - startTime

        return selections
    }

    /**
     * Select terrain LOD level based on 2D distance.
     * Useful for terrain chunks that use different thresholds.
     */
    fun selectTerrainLOD(
        regionCenter: Vector2f,
        cameraPos: Vector2f,
        terrainLodDistances: FloatArray = lodDistances
    ): Int {
        val dx = regionCenter.x - cameraPos.x
        val dz = regionCenter.y - cameraPos.y
        val distSq = dx * dx + dz * dz

        for ((i, threshold) in terrainLodDistances.withIndex()) {
            if (distSq < threshold * threshold) {
                return i
            }
        }
        return terrainLodDistances.size
    }

    /**
     * Get a LOD group by ID.
     */
    fun getLODGroup(id: String): LODGroup? = lodGroups[id]

    /**
     * Get all registered LOD group IDs.
     */
    fun getAllGroupIds(): Set<String> = lodGroups.keys.toSet()

    /**
     * Get statistics about the LOD system.
     */
    fun getStats(): LODStats {
        return LODStats(
            totalGroups = lodGroups.size,
            selectionTimeNanos = lastSelectionTimeNanos,
            lodDistances = lodDistances.toList(),
            lodCounts = lodCounts.toList()
        )
    }

    /**
     * Clear all LOD groups.
     */
    fun clear() {
        lodGroups.clear()
        lodCounts.fill(0)
    }

    /**
     * Clean up resources.
     * Note: This does NOT cleanup the meshes - that's the caller's responsibility.
     */
    fun cleanup() {
        clear()
    }
}

data class LODStats(
    val totalGroups: Int,
    val selectionTimeNanos: Long,
    val lodDistances: List<Float>,
    val lodCounts: List<Int>
) {
    val selectionTimeMs: Float get() = selectionTimeNanos / 1_000_000f
}

/**
 * Helper function to simplify mesh for LOD generation.
 * This is a placeholder - real implementation would use mesh decimation algorithms.
 */
object MeshSimplifier {
    /**
     * Create a simplified version of a mesh.
     *
     * @param positions Original positions
     * @param indices Original indices
     * @param targetRatio Target ratio of triangles to keep (0.0 to 1.0)
     * @return Simplified positions and indices, or null if simplification fails
     */
    fun simplify(
        positions: FloatArray,
        indices: IntArray,
        targetRatio: Float
    ): Pair<FloatArray, IntArray>? {
        // Placeholder implementation - returns original if ratio is high
        if (targetRatio >= 0.9f) {
            return Pair(positions, indices)
        }

        // For now, just return original - real implementation would use
        // quadric error metrics or similar algorithm
        return Pair(positions, indices)
    }
}
