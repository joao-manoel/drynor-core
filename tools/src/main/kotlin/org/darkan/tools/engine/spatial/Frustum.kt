package org.darkan.tools.engine.spatial

import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Frustum culling using JOML's optimized FrustumIntersection.
 *
 * This class wraps JOML's frustum testing to provide a clean API
 * for culling objects against the view frustum.
 */
class Frustum {
    private val intersection = FrustumIntersection()
    private val viewProjection = Matrix4f()
    private var dirty = true

    // Cached frustum planes for custom queries
    private val planes = Array(6) { Vector3f() }
    private val planeDistances = FloatArray(6)

    /**
     * Update the frustum from view and projection matrices.
     */
    fun update(view: Matrix4f, projection: Matrix4f) {
        projection.mul(view, viewProjection)
        intersection.set(viewProjection)
        extractPlanes()
        dirty = false
    }

    /**
     * Update the frustum from a combined view-projection matrix.
     */
    fun update(viewProjection: Matrix4f) {
        this.viewProjection.set(viewProjection)
        intersection.set(viewProjection)
        extractPlanes()
        dirty = false
    }

    /**
     * Test if an AABB is visible in the frustum.
     *
     * @return OUTSIDE, INTERSECT, or INSIDE
     */
    fun testAABB(aabb: AABB): Int {
        return intersection.intersectAab(
            aabb.min.x, aabb.min.y, aabb.min.z,
            aabb.max.x, aabb.max.y, aabb.max.z
        )
    }

    /**
     * Test if an AABB is at least partially visible (fast reject).
     */
    fun isAABBVisible(aabb: AABB): Boolean {
        return intersection.testAab(
            aabb.min.x, aabb.min.y, aabb.min.z,
            aabb.max.x, aabb.max.y, aabb.max.z
        )
    }

    /**
     * Test an AABB stored as six consecutive floats — `minX, minY, minZ, maxX, maxY, maxZ`
     * — starting at [offset] in [boxes].
     *
     * For callers that test thousands of boxes per frame and keep them packed in one array
     * rather than as [AABB] objects, so the walk stays sequential in memory. See
     * `RegionBatches` for the layout.
     */
    fun isAABBVisible(boxes: FloatArray, offset: Int): Boolean {
        return intersection.testAab(
            boxes[offset], boxes[offset + 1], boxes[offset + 2],
            boxes[offset + 3], boxes[offset + 4], boxes[offset + 5]
        )
    }

    /**
     * Test if a bounding sphere is visible.
     */
    fun isSphereVisible(sphere: BoundingSphere): Boolean {
        return intersection.testSphere(
            sphere.center.x, sphere.center.y, sphere.center.z,
            sphere.radius
        )
    }

    /**
     * Test if a bounding sphere is visible (direct parameters).
     */
    fun isSphereVisible(x: Float, y: Float, z: Float, radius: Float): Boolean {
        return intersection.testSphere(x, y, z, radius)
    }

    /**
     * Test if a point is inside the frustum.
     */
    fun isPointVisible(x: Float, y: Float, z: Float): Boolean {
        return intersection.testPoint(x, y, z)
    }

    /**
     * Test if a point is inside the frustum.
     */
    fun isPointVisible(point: Vector3f): Boolean {
        return intersection.testPoint(point.x, point.y, point.z)
    }

    /**
     * Test a 2D rect on the XZ plane (for terrain regions).
     * Assumes Y extends from yMin to yMax.
     */
    fun isRectVisible(rect: Rect, yMin: Float = -1000f, yMax: Float = 1000f): Boolean {
        return intersection.testAab(
            rect.minX, yMin, rect.minZ,
            rect.maxX, yMax, rect.maxZ
        )
    }

    /**
     * Get the signed distance from a plane to a point.
     * Positive = in front (visible side), negative = behind.
     */
    fun distanceToPlane(planeIndex: Int, x: Float, y: Float, z: Float): Float {
        val n = planes[planeIndex]
        return n.x * x + n.y * y + n.z * z + planeDistances[planeIndex]
    }

    /**
     * Copy the six normalized planes into [out] as tightly packed `(nx, ny, nz, d)` quads,
     * ready to hand to a shader as a `vec4[6]` uniform.
     *
     * A sphere of radius `r` centred at `p` is outside plane `i` when
     * `dot(n_i, p) + d_i < -r`, which is the same convention [distanceToPlane] uses.
     *
     * @param out array of at least 24 floats
     */
    fun writePlanes(out: FloatArray) {
        require(out.size >= 24) { "frustum plane array needs 24 floats, got ${out.size}" }
        for (i in 0 until 6) {
            val n = planes[i]
            out[i * 4] = n.x
            out[i * 4 + 1] = n.y
            out[i * 4 + 2] = n.z
            out[i * 4 + 3] = planeDistances[i]
        }
    }

    /**
     * Extract frustum planes for manual queries.
     * Planes are: LEFT, RIGHT, BOTTOM, TOP, NEAR, FAR
     */
    private fun extractPlanes() {
        val m = viewProjection

        // Left plane
        planes[0].set(m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20())
        planeDistances[0] = m.m33() + m.m30()
        normalizePlane(0)

        // Right plane
        planes[1].set(m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20())
        planeDistances[1] = m.m33() - m.m30()
        normalizePlane(1)

        // Bottom plane
        planes[2].set(m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21())
        planeDistances[2] = m.m33() + m.m31()
        normalizePlane(2)

        // Top plane
        planes[3].set(m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21())
        planeDistances[3] = m.m33() - m.m31()
        normalizePlane(3)

        // Near plane
        planes[4].set(m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22())
        planeDistances[4] = m.m33() + m.m32()
        normalizePlane(4)

        // Far plane
        planes[5].set(m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22())
        planeDistances[5] = m.m33() - m.m32()
        normalizePlane(5)
    }

    private fun normalizePlane(index: Int) {
        val n = planes[index]
        val invLen = 1f / n.length()
        n.mul(invLen)
        planeDistances[index] *= invLen
    }

    companion object {
        const val OUTSIDE = FrustumIntersection.OUTSIDE
        const val INTERSECT = FrustumIntersection.INTERSECT
        const val INSIDE = FrustumIntersection.INSIDE

        const val PLANE_LEFT = 0
        const val PLANE_RIGHT = 1
        const val PLANE_BOTTOM = 2
        const val PLANE_TOP = 3
        const val PLANE_NEAR = 4
        const val PLANE_FAR = 5
    }
}
