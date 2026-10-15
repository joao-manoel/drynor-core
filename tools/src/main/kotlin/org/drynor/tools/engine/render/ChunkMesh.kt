package org.drynor.tools.engine.render

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil

/**
 * A terrain chunk mesh with pre-allocated GL_DYNAMIC_DRAW buffers.
 * Unlike [Mesh] which uses GL_STATIC_DRAW and is immutable after creation,
 * ChunkMesh supports efficient buffer updates via glBufferSubData for
 * real-time terrain editing.
 *
 * Buffers are allocated at creation with an initial vertex capacity,
 * then updated with actual data via [upload]. If the vertex count exceeds
 * the current capacity, buffers are reallocated automatically via
 * glBufferData (full reallocation) rather than crashing.
 *
 * Uses glDrawArrays instead of glDrawElements since terrain uses
 * non-indexed triangle lists (sequential vertex order).
 *
 * @param maxVertices Initial maximum number of vertices this chunk can hold
 * @param hasTexcoords Whether this mesh has texture coordinates (location 2)
 * @param hasAlpha Whether this mesh has per-vertex alpha (location 4)
 */
class ChunkMesh private constructor(
    private var maxVertices: Int,
    val hasTexcoords: Boolean,
    val hasAlpha: Boolean
) {
    var vaoId: Int = 0; private set
    private var posVbo: Int = 0
    private var colorVbo: Int = 0
    private var texcoordVbo: Int = 0
    private var normalVbo: Int = 0
    private var alphaVbo: Int = 0

    /** Actual number of vertices currently uploaded (0 = empty/skip). */
    var vertexCount: Int = 0; private set

    private fun init() {
        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        // Position VBO (location 0, vec3)
        posVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)

        // Color VBO (location 1, vec3 RGB)
        colorVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)

        // Texcoord VBO (location 2, vec2) -- only for textured/alpha variants
        if (hasTexcoords) {
            texcoordVbo = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 2 * 4).toLong(), GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
        }

        // Normal VBO (location 3, vec3)
        normalVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(3)
        glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)

        // Alpha VBO (location 4, float) -- only for alpha variant
        if (hasAlpha) {
            alphaVbo = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 4).toLong(), GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(4)
            glVertexAttribPointer(4, 1, GL_FLOAT, false, 0, 0)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    /**
     * Grow all pre-allocated buffers to accommodate [newMax] vertices.
     * This performs a full glBufferData reallocation on every VBO.
     * Called automatically by [upload] when the vertex count exceeds
     * the current capacity.
     */
    private fun grow(newMax: Int) {
        maxVertices = newMax
        glBindVertexArray(vaoId)

        // Reallocate position VBO
        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        // Reallocate color VBO
        glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        // Reallocate texcoord VBO
        if (hasTexcoords) {
            glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 2 * 4).toLong(), GL_DYNAMIC_DRAW)
        }

        // Reallocate normal VBO
        glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        // Reallocate alpha VBO
        if (hasAlpha) {
            glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 4).toLong(), GL_DYNAMIC_DRAW)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    /**
     * Upload vertex data to the pre-allocated buffers via glBufferSubData.
     * Only uploads [count] vertices worth of data -- no GPU allocation occurs
     * unless [count] exceeds the current buffer capacity, in which case
     * buffers are grown to the next power-of-two size above [count].
     *
     * @param positions Position data (count * 3 floats)
     * @param colors Color data (count * 3 floats)
     * @param texcoords Texture coordinate data (count * 2 floats), null if not textured
     * @param normals Normal data (count * 3 floats)
     * @param alphas Alpha data (count floats), null if not alpha mesh
     * @param count Number of vertices to upload
     */
    fun upload(
        positions: FloatArray, colors: FloatArray,
        texcoords: FloatArray?, normals: FloatArray,
        alphas: FloatArray?, count: Int
    ) {
        if (count > maxVertices) {
            // Grow to the next power of two above the required count
            var newMax = maxVertices
            while (newMax < count) newMax *= 2
            System.err.println("[ChunkMesh] Buffer overflow: $count vertices exceeds capacity $maxVertices, growing to $newMax")
            grow(newMax)
        }

        vertexCount = count
        if (count == 0) return

        glBindVertexArray(vaoId)

        // Upload positions
        var buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(positions, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        // Upload colors
        buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(colors, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        // Upload texcoords
        if (hasTexcoords && texcoords != null) {
            buffer = MemoryUtil.memAllocFloat(count * 2)
            try {
                buffer.put(texcoords, 0, count * 2).flip()
                glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
                glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
            } finally {
                MemoryUtil.memFree(buffer)
            }
        }

        // Upload normals
        buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(normals, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        // Upload alphas
        if (hasAlpha && alphas != null) {
            buffer = MemoryUtil.memAllocFloat(count)
            try {
                buffer.put(alphas, 0, count).flip()
                glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
                glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
            } finally {
                MemoryUtil.memFree(buffer)
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    /** Render this chunk's uploaded vertices. Skips if empty. */
    fun render() {
        if (vertexCount == 0) return
        glBindVertexArray(vaoId)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        glBindVertexArray(0)
    }

    /** Check if this chunk has no uploaded data. */
    fun isEmpty(): Boolean = vertexCount == 0

    /** Release all GPU resources. */
    fun cleanup() {
        glBindVertexArray(0)
        glDeleteBuffers(posVbo)
        glDeleteBuffers(colorVbo)
        if (hasTexcoords) glDeleteBuffers(texcoordVbo)
        glDeleteBuffers(normalVbo)
        if (hasAlpha) glDeleteBuffers(alphaVbo)
        glDeleteVertexArrays(vaoId)
    }

    companion object {
        /** Create a color-only ChunkMesh (position + color + normal). */
        fun createColor(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = false, hasAlpha = false).also { it.init() }
        }

        /** Create a textured ChunkMesh (position + color + texcoord + normal). */
        fun createTextured(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = true, hasAlpha = false).also { it.init() }
        }

        /** Create an alpha ChunkMesh (position + color + texcoord + normal + alpha). */
        fun createAlpha(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = true, hasAlpha = true).also { it.init() }
        }
    }
}
