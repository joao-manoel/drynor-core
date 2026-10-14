package org.darkan.tools.engine.render

import org.darkan.tools.engine.spatial.AABB
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Key for batching geometry by material properties.
 */
data class BatchKey(
    val textureId: Int,      // -1 for untextured
    val atlasPageIndex: Int  // -1 if not using atlas
) {
    companion object {
        val UNTEXTURED = BatchKey(-1, -1)
    }
}

/**
 * Transformed geometry data ready for batching.
 */
data class TransformedGeometry(
    val positions: FloatArray,    // Already transformed to world space
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,     // Already transformed
    val indices: IntArray,
    /**
     * Spatial cell of the object this geometry came from.
     *
     * The merge concatenates a whole region's worth of one material into a single mesh,
     * which is why the frustum can only accept or reject 64 tiles at a time. Carrying the
     * cell through the merge lets consecutive geometry from the same cell be recorded as
     * an index range, so the same mesh can be drawn a cell at a time — see
     * [MergedGeometry.rangeFirst]. Zero when sub-region ranges are switched off.
     */
    val cellKey: Long = 0L,
    /** World-space `minX, minY, minZ, maxX, maxY, maxZ` of this geometry, or null. */
    val bounds: FloatArray? = null
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransformedGeometry) return false
        return positions.contentEquals(other.positions) &&
               indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        return result
    }
}

/**
 * Builder for transformed geometry from local-space data.
 */
class GeometryTransformer {
    private val tempPos = Vector3f()
    private val tempNormal = Vector3f()
    private val normalMatrix = Matrix4f()

    /**
     * Transform geometry by a model matrix.
     * Uses the mathematically correct inverse-transpose for normal transformation.
     */
    fun transform(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ): TransformedGeometry {
        return transformInternal(positions, colors, textureCoords, normals, indices, modelMatrix, useInverseTranspose = true)
    }

    /**
     * Transform geometry by a model matrix, using mat3(model) for normals.
     * This matches the instanced shader's normal transformation
     * (`mat3 normalMatrix = mat3(model); vNormal = normalMatrix * aNormal;`)
     * which is needed for pixel-identical output when replacing instanced rendering
     * with static batched rendering.
     */
    fun transformMatchingInstanced(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ): TransformedGeometry {
        return transformInternal(positions, colors, textureCoords, normals, indices, modelMatrix, useInverseTranspose = false)
    }

    /**
     * Internal transform implementation.
     *
     * @param useInverseTranspose If true, uses inverse(transpose(model)) for normals (correct).
     *        If false, uses mat3(model) directly (matches instanced shader behavior).
     */
    private fun transformInternal(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f,
        useInverseTranspose: Boolean
    ): TransformedGeometry {
        val transformedPositions = FloatArray(positions.size)
        val transformedNormals = normals?.let { FloatArray(it.size) }

        if (useInverseTranspose) {
            // Calculate normal matrix as inverse(transpose(model))
            modelMatrix.invert(normalMatrix)
            normalMatrix.transpose()
        }
        // else: use modelMatrix directly for normals (mat3(model) behavior)

        // Transform positions
        var i = 0
        while (i < positions.size) {
            tempPos.set(positions[i], positions[i + 1], positions[i + 2])
            modelMatrix.transformPosition(tempPos)
            transformedPositions[i] = tempPos.x
            transformedPositions[i + 1] = tempPos.y
            transformedPositions[i + 2] = tempPos.z
            i += 3
        }

        // Transform normals
        if (normals != null && transformedNormals != null) {
            val effectiveNormalMatrix = if (useInverseTranspose) normalMatrix else modelMatrix
            i = 0
            while (i < normals.size) {
                tempNormal.set(normals[i], normals[i + 1], normals[i + 2])
                effectiveNormalMatrix.transformDirection(tempNormal)
                tempNormal.normalize()
                transformedNormals[i] = tempNormal.x
                transformedNormals[i + 1] = tempNormal.y
                transformedNormals[i + 2] = tempNormal.z
                i += 3
            }
        }

        return TransformedGeometry(
            positions = transformedPositions,
            colors = colors?.copyOf(),
            textureCoords = textureCoords?.copyOf(),
            normals = transformedNormals,
            // A mirrored object's transform reverses handedness, so baking it into the
            // vertices reverses every triangle's winding too. Back-face culling then
            // discards the whole object rather than shading it wrongly, which is what
            // "missing squares" in Lumbridge's roofs actually were.
            indices = if (modelMatrix.determinant3x3() < 0f) reverseWinding(indices) else indices.copyOf()
        )
    }

    /**
     * Copy [indices] with each triangle's last two vertices swapped, flipping the
     * winding order without touching the vertex data.
     */
    private fun reverseWinding(indices: IntArray): IntArray {
        val out = indices.copyOf()
        var t = 0
        while (t + 2 < out.size) {
            val tmp = out[t + 1]
            out[t + 1] = out[t + 2]
            out[t + 2] = tmp
            t += 3
        }
        return out
    }
}

/**
 * Batches multiple static objects into single merged meshes.
 *
 * Use this for terrain, buildings, props, and other non-moving geometry.
 * Significantly reduces draw calls by combining many objects into one.
 */
class StaticBatcher {
    private val geometryByKey = mutableMapOf<BatchKey, MutableList<TransformedGeometry>>()
    private val builtMeshes = mutableMapOf<BatchKey, Mesh>()
    private var dirty = false

    /**
     * Add static geometry to a batch.
     */
    fun addGeometry(key: BatchKey, geometry: TransformedGeometry) {
        geometryByKey.getOrPut(key) { mutableListOf() }.add(geometry)
        dirty = true
    }

    /**
     * Add geometry with an object transform.
     */
    fun addGeometry(
        key: BatchKey,
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ) {
        val transformer = GeometryTransformer()
        val transformed = transformer.transform(
            positions, colors, textureCoords, normals, indices, modelMatrix
        )
        addGeometry(key, transformed)
    }

    /**
     * Build merged meshes from accumulated geometry.
     * Call this after adding all geometry before rendering.
     *
     * @return Map of batch key to merged mesh
     */
    fun buildMeshes(): Map<BatchKey, Mesh> {
        if (!dirty && builtMeshes.isNotEmpty()) {
            return builtMeshes
        }

        // Clean up old meshes
        for (mesh in builtMeshes.values) {
            mesh.cleanup()
        }
        builtMeshes.clear()

        for ((key, geometries) in geometryByKey) {
            if (geometries.isEmpty()) continue

            val mesh = mergeGeometry(geometries)
            builtMeshes[key] = mesh
        }

        dirty = false
        return builtMeshes
    }

    /**
     * Merge multiple geometries into a single mesh.
     */
    private fun mergeGeometry(geometries: List<TransformedGeometry>): Mesh {
        val merged = mergeGeometryData(geometries)
        return Mesh(merged.positions, merged.colors, merged.texCoords, merged.normals, merged.indices)
    }

    companion object {
        /**
         * Concatenate a batch's geometries into flat vertex/index arrays.
         *
         * Split out of [mergeGeometry] so the region loader can do this
         * allocation-and-copy work on a background thread and leave the GL thread
         * with nothing but the buffer upload. Touches no GL state and no shared
         * mutable state, so it is safe to call from any thread.
         *
         * The target is deliberately a heap `FloatArray` rather than native memory.
         * Concatenating into `MemoryUtil` buffers here was measured and is markedly
         * slower — see [MeshBuffers] for the numbers. Packing stays on the consumer side,
         * where the same block of native memory is allocated, filled, uploaded and freed
         * over and over instead of hundreds of cold blocks being held at once.
         */
        fun mergeGeometryData(geometries: List<TransformedGeometry>): MergedGeometry {
        // Calculate total sizes
        var totalVertices = 0
        var totalIndices = 0
        val hasColors = geometries.any { it.colors != null }
        val hasTexCoords = geometries.any { it.textureCoords != null }
        val hasNormals = geometries.any { it.normals != null }

        for (geo in geometries) {
            totalVertices += geo.vertexCount
            totalIndices += geo.indices.size
        }

        // Allocate arrays
        val positions = FloatArray(totalVertices * 3)
        val colors = if (hasColors) FloatArray(totalVertices * 3) else null
        val texCoords = if (hasTexCoords) FloatArray(totalVertices * 2) else null
        val normals = if (hasNormals) FloatArray(totalVertices * 3) else null
        val indices = IntArray(totalIndices)

        // Sub-region index ranges.
        //
        // Deliberately runs of the *existing* order rather than a regrouping: the batch is
        // drawn with blending on, so reordering its triangles could change the image where
        // two translucent surfaces overlap. A run is a maximal stretch of consecutive
        // geometries sharing a cell, and glMultiDrawElements executes the ranges in the
        // order given, so submitting every range is byte-for-byte the same draw as
        // submitting the whole buffer. This is only worth anything because the geometries
        // arrive in object order and the cache stores objects tile by tile, so consecutive
        // objects are usually neighbours.
        val rangeFirst = IntArray(geometries.size)
        val rangeCount = IntArray(geometries.size)
        val rangeBounds = FloatArray(geometries.size * 6)
        var ranges = 0
        var currentCell = 0L
        var currentIsOpen = false

        // Merge
        var posOffset = 0
        var colorOffset = 0
        var texOffset = 0
        var normalOffset = 0
        var indexOffset = 0
        var vertexOffset = 0

        for (geo in geometries) {
            if (!currentIsOpen || geo.cellKey != currentCell) {
                currentCell = geo.cellKey
                currentIsOpen = true
                rangeFirst[ranges] = indexOffset
                rangeCount[ranges] = 0
                val o = ranges * 6
                rangeBounds[o] = Float.POSITIVE_INFINITY
                rangeBounds[o + 1] = Float.POSITIVE_INFINITY
                rangeBounds[o + 2] = Float.POSITIVE_INFINITY
                rangeBounds[o + 3] = Float.NEGATIVE_INFINITY
                rangeBounds[o + 4] = Float.NEGATIVE_INFINITY
                rangeBounds[o + 5] = Float.NEGATIVE_INFINITY
                ranges++
            }
            rangeCount[ranges - 1] += geo.indices.size
            val box = geo.bounds
            if (box != null) {
                val o = (ranges - 1) * 6
                for (axis in 0..2) {
                    if (box[axis] < rangeBounds[o + axis]) rangeBounds[o + axis] = box[axis]
                    if (box[3 + axis] > rangeBounds[o + 3 + axis]) rangeBounds[o + 3 + axis] = box[3 + axis]
                }
            } else {
                // No bounds means the range cannot be culled; make it unrejectable rather
                // than letting an empty box delete real geometry from the scene.
                val o = (ranges - 1) * 6
                rangeBounds[o] = Float.NaN
            }

            // Copy positions
            System.arraycopy(geo.positions, 0, positions, posOffset, geo.positions.size)
            posOffset += geo.positions.size

            // Copy colors (or fill with white if this geo has none)
            if (colors != null) {
                if (geo.colors != null) {
                    System.arraycopy(geo.colors, 0, colors, colorOffset, geo.colors.size)
                } else {
                    // Fill with white
                    for (i in 0 until geo.vertexCount * 3) {
                        colors[colorOffset + i] = 1f
                    }
                }
                colorOffset += geo.vertexCount * 3
            }

            // Copy texture coordinates
            if (texCoords != null) {
                if (geo.textureCoords != null) {
                    System.arraycopy(geo.textureCoords, 0, texCoords, texOffset, geo.textureCoords.size)
                } else {
                    // Fill with zeros
                    for (i in 0 until geo.vertexCount * 2) {
                        texCoords[texOffset + i] = 0f
                    }
                }
                texOffset += geo.vertexCount * 2
            }

            // Copy normals
            if (normals != null) {
                if (geo.normals != null) {
                    System.arraycopy(geo.normals, 0, normals, normalOffset, geo.normals.size)
                } else {
                    // Fill with up vector
                    for (i in 0 until geo.vertexCount) {
                        normals[normalOffset + i * 3] = 0f
                        normals[normalOffset + i * 3 + 1] = 1f
                        normals[normalOffset + i * 3 + 2] = 0f
                    }
                }
                normalOffset += geo.vertexCount * 3
            }

            // Copy and offset indices
            for (idx in geo.indices) {
                indices[indexOffset++] = idx + vertexOffset
            }
            vertexOffset += geo.vertexCount
        }

        return MergedGeometry(
            positions, colors, texCoords, normals, indices, AABB.fromPositions(positions),
            rangeFirst = rangeFirst.copyOf(ranges),
            rangeCount = rangeCount.copyOf(ranges),
            rangeBounds = rangeBounds.copyOf(ranges * 6)
        )
        }
    }

    /**
     * Get a built mesh for a batch key.
     */
    fun getMesh(key: BatchKey): Mesh? = builtMeshes[key]

    /**
     * Get all built meshes.
     */
    fun getAllMeshes(): Map<BatchKey, Mesh> = builtMeshes.toMap()

    /**
     * Get statistics about the batches.
     */
    fun getStats(): BatcherStats {
        var totalVertices = 0
        var totalTriangles = 0

        for (geometries in geometryByKey.values) {
            for (geo in geometries) {
                totalVertices += geo.vertexCount
                totalTriangles += geo.triangleCount
            }
        }

        return BatcherStats(
            batchCount = builtMeshes.size,
            totalObjects = geometryByKey.values.sumOf { it.size },
            totalVertices = totalVertices,
            totalTriangles = totalTriangles
        )
    }

    /**
     * Clear all geometry and meshes.
     */
    fun clear() {
        for (mesh in builtMeshes.values) {
            mesh.cleanup()
        }
        builtMeshes.clear()
        geometryByKey.clear()
        dirty = false
    }

    /**
     * Clean up GPU resources.
     */
    fun cleanup() = clear()
}

/**
 * Flat vertex/index arrays for one merged batch, ready to hand to a [Mesh].
 * Produced off the GL thread by [StaticBatcher.mergeGeometryData].
 */
class MergedGeometry(
    val positions: FloatArray,
    val colors: FloatArray?,
    val texCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    /**
     * World-space bounds of the merged batch.
     *
     * Batch geometry is already baked into world space, so this is directly testable
     * against the view frustum with no per-frame transform. Computed here, on the loader
     * thread that already has the positions in cache, rather than on the GL thread.
     */
    val bounds: AABB,
    /**
     * Contiguous index ranges inside [indices], one per spatial cell, in draw order.
     *
     * A single range covering the whole buffer when sub-region ranges are switched off,
     * which is exactly the draw the pass issued before they existed. See the run
     * construction in [StaticBatcher.mergeGeometryData].
     */
    val rangeFirst: IntArray = intArrayOf(0),
    val rangeCount: IntArray = intArrayOf(indices.size),
    /**
     * `minX, minY, minZ, maxX, maxY, maxZ` per range, in [rangeFirst] order.
     *
     * A range whose box is not finite and well ordered is drawn unconditionally, for the
     * same reason [org.darkan.tools.engine.region.BatchDraw.cullable] exists: a NaN makes
     * every plane test fail, which a frustum test reads as "outside".
     */
    val rangeBounds: FloatArray = FloatArray(6) { Float.NaN }
) {
    /** Copy into native memory, ready for [Mesh] to upload. */
    fun pack(): MeshBuffers = MeshBuffers.pack(
        positions = positions,
        colors = colors,
        texCoords = texCoords,
        normals = normals,
        indices = indices
    )
}

data class BatcherStats(
    val batchCount: Int,
    val totalObjects: Int,
    val totalVertices: Int,
    val totalTriangles: Int
)
