package org.darkan.tools.util

import org.joml.Vector3f

object CoordinateUtil {
    /**
     * Default scale factor for converting RS coordinates to OpenGL world units.
     * RS coordinates are typically much larger, this brings them to manageable size.
     */
    const val DEFAULT_SCALE = 1f / 128f

    /**
     * Convert RuneScape coordinates to OpenGL coordinates.
     * RS: X = east/west, Y = height (positive = DOWN), Z = north/south
     * OpenGL: X = right, Y = up (positive = UP), Z = out of screen (towards camera)
     *
     * RS Y axis is inverted (positive = down), so we negate it.
     * Only negate Y to preserve face winding order.
     */
    fun rsToOpenGL(x: Int, y: Int, z: Int, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x * scale, -y * scale, -z * scale)
    }

    /**
     * Convert RuneScape coordinates to OpenGL coordinates (float version).
     */
    fun rsToOpenGL(x: Float, y: Float, z: Float, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x * scale, -y * scale, -z * scale)
    }

    /**
     * Convert OpenGL coordinates back to RuneScape coordinates.
     */
    fun openGLToRS(x: Float, y: Float, z: Float, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x / scale, -y / scale, -z / scale)
    }

    /**
     * Convert RS tile coordinates to world position.
     * RS tiles are 512 units apart.
     */
    fun tileToWorld(tileX: Int, tileZ: Int, height: Int = 0, scale: Float = DEFAULT_SCALE): Vector3f {
        return rsToOpenGL(tileX * 512, height, tileZ * 512, scale)
    }

    /**
     * Convert world position to RS tile coordinates.
     */
    fun worldToTile(pos: Vector3f, scale: Float = DEFAULT_SCALE): Pair<Int, Int> {
        val rs = openGLToRS(pos.x, pos.y, pos.z, scale)
        return Pair((rs.x / 512).toInt(), (rs.z / 512).toInt())
    }

    /**
     * Get the center of a region (64x64 tiles).
     */
    fun regionCenter(regionX: Int, regionY: Int, scale: Float = DEFAULT_SCALE): Vector3f {
        val centerTileX = regionX * 64 + 32
        val centerTileZ = regionY * 64 + 32
        return tileToWorld(centerTileX, centerTileZ, 0, scale)
    }

    /**
     * Get region ID from tile coordinates.
     */
    fun getRegionId(tileX: Int, tileY: Int): Int {
        return (tileX shr 6 shl 8) or (tileY shr 6)
    }

    /**
     * Get tile coordinates from region ID and local coordinates.
     */
    fun getTileFromRegion(regionId: Int, localX: Int, localY: Int): Pair<Int, Int> {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff
        return Pair(regionX * 64 + localX, regionY * 64 + localY)
    }

    /**
     * Calculate normal vector for a triangle.
     */
    fun calculateNormal(v0: Vector3f, v1: Vector3f, v2: Vector3f): Vector3f {
        val edge1 = Vector3f()
        val edge2 = Vector3f()
        val normal = Vector3f()

        v1.sub(v0, edge1)
        v2.sub(v0, edge2)
        edge1.cross(edge2, normal)
        normal.normalize()

        return normal
    }
}
