package org.darkan.tools.engine.render

import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL14.glMultiDrawElements
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer

/**
 * A VAO plus its buffer objects, built from vertex data packed into native memory.
 *
 * Construction is GL-only: generate two buffer objects, upload them, describe the
 * attributes. The packing that feeds it lives in [MeshBuffers] and needs no GL context.
 *
 * Every vertex attribute shares **one** buffer object, each occupying its own contiguous
 * run — see [MeshBuffers] for why. Attribute layout as the shaders see it is unchanged:
 * each run is still tightly packed, so the stride stays 0 and only the base offset and the
 * component type differ (colour arrives as normalized bytes, which the shaders read as the
 * same floats a float attribute gave them).
 *
 * The [MeshBuffers] handed in is **consumed**: it is freed once uploaded, whether or not
 * the upload throws. Do not reuse it afterwards.
 */
class Mesh(buffers: MeshBuffers) {
    var vaoId: Int = 0
        private set
    private val vboIds = mutableListOf<Int>()
    val vertexCount: Int = buffers.indexCount

    /** Build from heap arrays, packing them inline. */
    constructor(
        positions: FloatArray,
        colors: FloatArray? = null,
        textureCoords: FloatArray? = null,
        normals: FloatArray? = null,
        indices: IntArray,
        /** Number of components per color vertex: 3 for RGB, 4 for RGBA */
        colorComponents: Int = 3
    ) : this(
        MeshBuffers.pack(
            positions = positions,
            colors = colors,
            texCoords = textureCoords,
            normals = normals,
            indices = indices,
            colorComponents = colorComponents
        )
    )

    init {
        try {
            vaoId = glGenVertexArrays()
            glBindVertexArray(vaoId)

            val vertexVbo = glGenBuffers()
            vboIds.add(vertexVbo)
            glBindBuffer(GL_ARRAY_BUFFER, vertexVbo)
            glBufferData(GL_ARRAY_BUFFER, buffers.vertexData, GL_STATIC_DRAW)
            for (attribute in buffers.attributes) {
                glEnableVertexAttribArray(attribute.location)
                glVertexAttribPointer(
                    attribute.location,
                    attribute.components,
                    attribute.type,
                    attribute.normalized,
                    0,
                    attribute.byteOffset.toLong()
                )
            }

            val indexVbo = glGenBuffers()
            vboIds.add(indexVbo)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexVbo)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, buffers.indices, GL_STATIC_DRAW)

            glBindBuffer(GL_ARRAY_BUFFER, 0)
            glBindVertexArray(0)
        } finally {
            buffers.free()
        }
    }

    var primitiveType: Int = GL_TRIANGLES

    fun render() {
        glBindVertexArray(vaoId)
        glDrawElements(primitiveType, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    // Scratch buffers for [renderRanges], grown on demand and freed in [cleanup].
    // Only the handful of merged meshes ever call it, so a per-mesh pair costs nothing
    // and avoids any cross-mesh sharing hazard.
    private var multiDrawCounts: IntBuffer? = null
    private var multiDrawOffsets: PointerBuffer? = null
    private var multiDrawCapacity = 0

    /**
     * Draw a subset of this mesh's index buffer as a single `glMultiDrawElements` call.
     *
     * Used by the merged terrain meshes, which pack every loaded region's geometry into
     * one buffer as contiguous per-region index ranges. Frustum culling then selects the
     * ranges to submit while the driver still only sees one draw call, so the terrain
     * draw count stays constant no matter how many regions are loaded.
     *
     * @param firstIndices Start of each range, in indices (not bytes)
     * @param counts Index count of each range
     * @param rangeCount How many entries of the two arrays to use
     */
    /**
     * Draw one contiguous index range as a plain `glDrawElements`.
     *
     * The single-range case of [renderRanges]. Worth its own path because a multi-draw of
     * one range costs the driver noticeably more than the equivalent draw call, and a
     * partially visible batch very often comes down to one surviving span.
     */
    fun renderRange(firstIndex: Int, count: Int) {
        if (count <= 0) return
        glBindVertexArray(vaoId)
        glDrawElements(primitiveType, count, GL_UNSIGNED_INT, firstIndex.toLong() * Int.SIZE_BYTES)
        glBindVertexArray(0)
    }

    fun renderRanges(firstIndices: IntArray, counts: IntArray, rangeCount: Int) {
        if (rangeCount <= 0) return
        ensureMultiDrawCapacity(rangeCount)
        val countBuffer = multiDrawCounts!!
        val offsetBuffer = multiDrawOffsets!!
        for (i in 0 until rangeCount) {
            countBuffer.put(i, counts[i])
            offsetBuffer.put(i, firstIndices[i].toLong() * Int.SIZE_BYTES)
        }
        countBuffer.position(0)
        countBuffer.limit(rangeCount)
        offsetBuffer.position(0)
        offsetBuffer.limit(rangeCount)

        glBindVertexArray(vaoId)
        glMultiDrawElements(primitiveType, countBuffer, GL_UNSIGNED_INT, offsetBuffer)
        glBindVertexArray(0)
    }

    private fun ensureMultiDrawCapacity(rangeCount: Int) {
        if (multiDrawCapacity >= rangeCount && multiDrawCounts != null) {
            multiDrawCounts!!.clear()
            multiDrawOffsets!!.clear()
            return
        }
        multiDrawCounts?.let { MemoryUtil.memFree(it) }
        multiDrawOffsets?.let { MemoryUtil.memFree(it) }
        val capacity = maxOf(rangeCount, 64)
        multiDrawCounts = MemoryUtil.memAllocInt(capacity)
        multiDrawOffsets = MemoryUtil.memAllocPointer(capacity)
        multiDrawCapacity = capacity
    }

    fun renderWireframe() {
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    /**
     * Render the mesh as lines (GL_LINES primitive type).
     * Useful for outlines and debug visualization.
     */
    fun renderLines() {
        glBindVertexArray(vaoId)
        glDrawElements(GL_LINES, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    fun cleanup() {
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        vboIds.forEach { glDeleteBuffers(it) }
        glBindVertexArray(0)
        glDeleteVertexArrays(vaoId)
        multiDrawCounts?.let { MemoryUtil.memFree(it) }
        multiDrawCounts = null
        multiDrawOffsets?.let { MemoryUtil.memFree(it) }
        multiDrawOffsets = null
        multiDrawCapacity = 0
    }

    companion object {
        /**
         * Create a mesh with a per-vertex alpha attribute for multi-pass alpha blending.
         * Used for terrain layers rendered on top of an opaque base with alpha blending
         * (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA). Each vertex carries an alpha value
         * that controls how much this layer replaces the base at that point.
         *
         * Vertex attributes:
         *   location 0: position (vec3)
         *   location 1: color (vec3 RGB)
         *   location 2: texCoord (vec2)
         *   location 3: normal (vec3)
         *   location 4: alpha (float)
         */
        fun createWithAlpha(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            alphas: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                alphas = alphas
            )
        )

        /**
         * Create a mesh with a per-vertex atlas transform attribute for atlased texture rendering.
         * The atlas transform (vec4) at location 5 provides (u0, v0, uScale, vScale) to map
         * local texture coordinates into atlas UV space via: atlasUV = offset + fract(localUV) * scale.
         *
         * Vertex attributes:
         *   location 0: position (vec3)
         *   location 1: color (vec3 RGB)
         *   location 2: texCoord (vec2)
         *   location 3: normal (vec3)
         *   location 5: atlasTransform (vec4)
         */
        fun createWithAtlasTransform(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            atlasTransforms: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                atlasTransforms = atlasTransforms
            )
        )

        /**
         * Create a mesh with both per-vertex alpha and atlas transform attributes.
         * Combines the alpha blending attribute (location 4) with the atlas transform
         * attribute (location 5) for alpha-blended terrain rendered from an atlas.
         *
         * Vertex attributes:
         *   location 0: position (vec3)
         *   location 1: color (vec3 RGB)
         *   location 2: texCoord (vec2)
         *   location 3: normal (vec3)
         *   location 4: alpha (float)
         *   location 5: atlasTransform (vec4)
         */
        fun createWithAtlasAndAlpha(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            alphas: FloatArray,
            atlasTransforms: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                alphas = alphas,
                atlasTransforms = atlasTransforms
            )
        )

        fun createTriangle(): Mesh {
            val positions = floatArrayOf(
                0.0f, 0.5f, 0.0f,
                -0.5f, -0.5f, 0.0f,
                0.5f, -0.5f, 0.0f
            )
            val colors = floatArrayOf(
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
            )
            val indices = intArrayOf(0, 1, 2)
            return Mesh(positions, colors, null, null, indices)
        }

        fun createCube(size: Float = 1.0f): Mesh {
            val s = size / 2
            val positions = floatArrayOf(
                // Front face
                -s, -s, s,  s, -s, s,  s, s, s,  -s, s, s,
                // Back face
                -s, -s, -s,  -s, s, -s,  s, s, -s,  s, -s, -s,
                // Top face
                -s, s, -s,  -s, s, s,  s, s, s,  s, s, -s,
                // Bottom face
                -s, -s, -s,  s, -s, -s,  s, -s, s,  -s, -s, s,
                // Right face
                s, -s, -s,  s, s, -s,  s, s, s,  s, -s, s,
                // Left face
                -s, -s, -s,  -s, -s, s,  -s, s, s,  -s, s, -s
            )
            val colors = FloatArray(positions.size) { 0.7f }
            val indices = intArrayOf(
                0, 1, 2, 0, 2, 3,       // Front
                4, 5, 6, 4, 6, 7,       // Back
                8, 9, 10, 8, 10, 11,    // Top
                12, 13, 14, 12, 14, 15, // Bottom
                16, 17, 18, 16, 18, 19, // Right
                20, 21, 22, 20, 22, 23  // Left
            )
            return Mesh(positions, colors, null, null, indices)
        }

        fun createGrid(size: Float, divisions: Int): Mesh {
            val positions = mutableListOf<Float>()
            val colors = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            val indices = mutableListOf<Int>()

            val step = size / divisions
            val halfSize = size / 2
            var index = 0

            for (i in 0..divisions) {
                val pos = -halfSize + i * step
                // Lines along Z
                positions.addAll(listOf(pos, 0f, -halfSize))
                positions.addAll(listOf(pos, 0f, halfSize))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                normals.addAll(listOf(0f, 1f, 0f))
                normals.addAll(listOf(0f, 1f, 0f))
                indices.add(index++)
                indices.add(index++)

                // Lines along X
                positions.addAll(listOf(-halfSize, 0f, pos))
                positions.addAll(listOf(halfSize, 0f, pos))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                normals.addAll(listOf(0f, 1f, 0f))
                normals.addAll(listOf(0f, 1f, 0f))
                indices.add(index++)
                indices.add(index++)
            }

            return Mesh(
                positions.toFloatArray(),
                colors.toFloatArray(),
                null,
                normals.toFloatArray(),
                indices.toIntArray()
            ).apply { primitiveType = GL_LINES }
        }
    }
}
