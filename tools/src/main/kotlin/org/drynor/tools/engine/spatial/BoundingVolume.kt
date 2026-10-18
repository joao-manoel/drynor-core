package org.drynor.tools.engine.spatial

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Axis-Aligned Bounding Box for spatial queries and frustum culling.
 */
data class AABB(
    val min: Vector3f = Vector3f(Float.MAX_VALUE),
    val max: Vector3f = Vector3f(-Float.MAX_VALUE)
) {
    val center: Vector3f
        get() = Vector3f(
            (min.x + max.x) * 0.5f,
            (min.y + max.y) * 0.5f,
            (min.z + max.z) * 0.5f
        )

    val halfExtents: Vector3f
        get() = Vector3f(
            (max.x - min.x) * 0.5f,
            (max.y - min.y) * 0.5f,
            (max.z - min.z) * 0.5f
        )

    val sizeX: Float get() = max.x - min.x
    val sizeY: Float get() = max.y - min.y
    val sizeZ: Float get() = max.z - min.z

    fun isValid(): Boolean =
        min.x <= max.x && min.y <= max.y && min.z <= max.z

    /**
     * Expand this AABB to include a point.
     */
    fun expand(point: Vector3f): AABB = AABB(
        Vector3f(min(min.x, point.x), min(min.y, point.y), min(min.z, point.z)),
        Vector3f(max(max.x, point.x), max(max.y, point.y), max(max.z, point.z))
    )

    /**
     * Expand this AABB to include another AABB.
     */
    fun expand(other: AABB): AABB = AABB(
        Vector3f(min(min.x, other.min.x), min(min.y, other.min.y), min(min.z, other.min.z)),
        Vector3f(max(max.x, other.max.x), max(max.y, other.max.y), max(max.z, other.max.z))
    )

    /**
     * Test if this AABB contains a point.
     */
    fun contains(point: Vector3f): Boolean =
        point.x >= min.x && point.x <= max.x &&
        point.y >= min.y && point.y <= max.y &&
        point.z >= min.z && point.z <= max.z

    /**
     * Test if this AABB intersects another AABB.
     */
    fun intersects(other: AABB): Boolean =
        min.x <= other.max.x && max.x >= other.min.x &&
        min.y <= other.max.y && max.y >= other.min.y &&
        min.z <= other.max.z && max.z >= other.min.z

    /**
     * Get bounding sphere that encloses this AABB.
     */
    fun toBoundingSphere(): BoundingSphere {
        val c = center
        val radius = sqrt(
            halfExtents.x * halfExtents.x +
            halfExtents.y * halfExtents.y +
            halfExtents.z * halfExtents.z
        )
        return BoundingSphere(c, radius)
    }

    /**
     * Transform this AABB by a matrix, returning a new AABB that encloses the transformed corners.
     */
    fun transform(matrix: Matrix4f): AABB {
        val corners = arrayOf(
            Vector3f(min.x, min.y, min.z),
            Vector3f(max.x, min.y, min.z),
            Vector3f(min.x, max.y, min.z),
            Vector3f(max.x, max.y, min.z),
            Vector3f(min.x, min.y, max.z),
            Vector3f(max.x, min.y, max.z),
            Vector3f(min.x, max.y, max.z),
            Vector3f(max.x, max.y, max.z)
        )

        var result = AABB()
        for (corner in corners) {
            matrix.transformPosition(corner)
            result = result.expand(corner)
        }
        return result
    }

    companion object {
        /**
         * Create an AABB from a center point and half-extents.
         */
        fun fromCenterExtents(center: Vector3f, halfExtents: Vector3f): AABB = AABB(
            Vector3f(center.x - halfExtents.x, center.y - halfExtents.y, center.z - halfExtents.z),
            Vector3f(center.x + halfExtents.x, center.y + halfExtents.y, center.z + halfExtents.z)
        )

        /**
         * Create an AABB from an array of positions (x, y, z interleaved).
         */
        fun fromPositions(positions: FloatArray): AABB {
            if (positions.isEmpty()) return AABB()

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE

            var i = 0
            while (i < positions.size) {
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                minX = min(minX, x)
                minY = min(minY, y)
                minZ = min(minZ, z)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                maxZ = max(maxZ, z)
                i += 3
            }

            return AABB(Vector3f(minX, minY, minZ), Vector3f(maxX, maxY, maxZ))
        }
    }
}

/**
 * Bounding sphere for fast coarse-grained culling.
 */
data class BoundingSphere(
    val center: Vector3f = Vector3f(),
    val radius: Float = 0f
) {
    /**
     * Test if this sphere contains a point.
     */
    fun contains(point: Vector3f): Boolean {
        val dx = point.x - center.x
        val dy = point.y - center.y
        val dz = point.z - center.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    /**
     * Test if this sphere intersects another sphere.
     */
    fun intersects(other: BoundingSphere): Boolean {
        val dx = other.center.x - center.x
        val dy = other.center.y - center.y
        val dz = other.center.z - center.z
        val distSq = dx * dx + dy * dy + dz * dz
        val radiusSum = radius + other.radius
        return distSq <= radiusSum * radiusSum
    }

    /**
     * Transform this sphere by a matrix (approximation using uniform scale).
     */
    fun transform(matrix: Matrix4f): BoundingSphere {
        val newCenter = Vector3f(center)
        matrix.transformPosition(newCenter)

        // Extract approximate uniform scale from matrix
        val scaleX = sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02())
        val scaleY = sqrt(matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12())
        val scaleZ = sqrt(matrix.m20() * matrix.m20() + matrix.m21() * matrix.m21() + matrix.m22() * matrix.m22())
        val maxScale = max(max(scaleX, scaleY), scaleZ)

        return BoundingSphere(newCenter, radius * maxScale)
    }

    /**
     * Merge with another sphere to create a bounding sphere that encloses both.
     */
    fun merge(other: BoundingSphere): BoundingSphere {
        val dx = other.center.x - center.x
        val dy = other.center.y - center.y
        val dz = other.center.z - center.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)

        // One sphere contains the other
        if (dist + other.radius <= radius) return this
        if (dist + radius <= other.radius) return other

        // Calculate new sphere
        val newRadius = (dist + radius + other.radius) * 0.5f
        val t = (newRadius - radius) / dist
        val newCenter = Vector3f(
            center.x + dx * t,
            center.y + dy * t,
            center.z + dz * t
        )
        return BoundingSphere(newCenter, newRadius)
    }
}

/**
 * 2D axis-aligned bounding rectangle for terrain regions.
 */
data class Rect(
    val minX: Float,
    val minZ: Float,
    val maxX: Float,
    val maxZ: Float
) {
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
    val width: Float get() = maxX - minX
    val height: Float get() = maxZ - minZ

    fun contains(x: Float, z: Float): Boolean =
        x >= minX && x <= maxX && z >= minZ && z <= maxZ

    fun intersects(other: Rect): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
        minZ <= other.maxZ && maxZ >= other.minZ

    /**
     * Subdivide into 4 quadrants.
     */
    fun subdivide(): Array<Rect> {
        val midX = centerX
        val midZ = centerZ
        return arrayOf(
            Rect(minX, minZ, midX, midZ),     // SW
            Rect(midX, minZ, maxX, midZ),     // SE
            Rect(minX, midZ, midX, maxZ),     // NW
            Rect(midX, midZ, maxX, maxZ)      // NE
        )
    }

    companion object {
        fun fromCenterSize(centerX: Float, centerZ: Float, width: Float, height: Float): Rect {
            val halfW = width * 0.5f
            val halfH = height * 0.5f
            return Rect(centerX - halfW, centerZ - halfH, centerX + halfW, centerZ + halfH)
        }
    }
}
