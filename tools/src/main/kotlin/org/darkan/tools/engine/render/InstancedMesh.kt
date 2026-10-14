package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL31.glDrawElementsInstanced
import org.lwjgl.opengl.GL33.glVertexAttribDivisor
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer

/**
 * A mesh that supports GPU instancing for rendering many copies efficiently.
 *
 * Instance data (model matrices) are stored in a VBO and updated per-frame.
 * Uses vertex attribute divisor to feed one matrix per instance.
 *
 * Vertex attribute layout:
 * - 0: position (vec3)
 * - 1: color (vec3)
 * - 2: texCoord (vec2)
 * - 3: normal (vec3)
 * - 4-7: instanceMatrix (mat4, one vec4 per column)
 *
 * ## GL resources are created on first instance, not on construction
 *
 * Registering a model is not the same as drawing it. The map viewer registers every unique mesh
 * in every loaded region here, then draws the scene through the indirect and static-batch paths
 * instead — `instanced=0` in the capture stats, every frame. Building a VAO, five buffers and
 * their uploads for each of those was the whole of `gl:modelRegister`: 0.3 ms of main-thread GL
 * time per mesh, several thousand meshes per load, for geometry this class never drew.
 *
 * So construction only keeps the arrays, and the GL objects appear the moment an instance is
 * actually added. A mesh nothing instances costs one small object and no driver calls; one that
 * is instanced behaves exactly as before, just with its upload deferred to the frame that needs
 * it. [render] returns on a zero instance count before touching GL at all.
 */
class InstancedMesh(
    private var positions: FloatArray?,
    private var colors: FloatArray? = null,
    private var textureCoords: FloatArray? = null,
    private var normals: FloatArray? = null,
    private var indices: IntArray?,
    private val maxInstances: Int = 10000
) {
    private var vaoId: Int = 0
    private val vboIds = mutableListOf<Int>()
    private var indexVboId: Int = 0
    private var instanceVboId: Int = 0

    /** True once [createGlResources] has run. */
    private var glReady = false

    val vertexCount: Int = indices?.size ?: 0

    /**
     * Instance data staging buffer (16 floats per matrix), grown on demand.
     *
     * [maxInstances] is a ceiling, not a size. The region pipeline registers every model with a
     * ceiling of 50,000, which as an eager allocation is 3.2 MB of pinned native memory *and* a
     * 3.2 MB `GL_DYNAMIC_DRAW` buffer the driver has to zero — about 0.3 ms of GL-thread time per
     * mesh, paid whether or not a single instance is ever drawn through it. A town at render
     * distance 3 registers a few thousand meshes and draws all of them through the indirect and
     * static-batch paths instead, so that was the entire cost of `gl:modelRegister` and none of
     * it bought anything.
     *
     * Allocating on first use and doubling from there keeps the ceiling's meaning (an instance
     * beyond it is still refused) while making registration free for meshes nothing instances.
     */
    private var instanceBuffer: FloatBuffer? = null

    /** Instances the staging buffer and the GPU buffer can currently hold. */
    private var instanceCapacity = 0
    private var instanceCount = 0
    private var instanceDataDirty = false

    // Temp matrix for storing transforms
    private val tempFloats = FloatArray(16)

    /**
     * Build the VAO and vertex buffers, and release the CPU-side arrays.
     *
     * Called the first time an instance is added. Must run on the GL thread, which every caller
     * that can reach it (the per-frame instance batching) already is.
     */
    private fun createGlResources() {
        if (glReady) return
        glReady = true

        val positions = this.positions ?: FloatArray(0)
        val colors = this.colors
        val textureCoords = this.textureCoords
        val normals = this.normals
        val indices = this.indices ?: IntArray(0)

        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        // Position VBO (attribute 0)
        val posBuffer = MemoryUtil.memAllocFloat(positions.size)
        try {
            posBuffer.put(positions).flip()
            val posVbo = glGenBuffers()
            vboIds.add(posVbo)
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(0, 0) // Per-vertex, not per-instance
        } finally {
            MemoryUtil.memFree(posBuffer)
        }

        // Color VBO (attribute 1)
        // Always set up colors - use provided colors or default to white (1,1,1) for all vertices
        val colorData = colors ?: FloatArray(positions.size) { 1f } // Default white if no colors
        val colorBuffer = MemoryUtil.memAllocFloat(colorData.size)
        try {
            colorBuffer.put(colorData).flip()
            val colorVbo = glGenBuffers()
            vboIds.add(colorVbo)
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferData(GL_ARRAY_BUFFER, colorBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(1, 0) // Per-vertex, not per-instance
        } finally {
            MemoryUtil.memFree(colorBuffer)
        }

        // Texture coordinate VBO (attribute 2)
        if (textureCoords != null) {
            val texBuffer = MemoryUtil.memAllocFloat(textureCoords.size)
            try {
                texBuffer.put(textureCoords).flip()
                val texVbo = glGenBuffers()
                vboIds.add(texVbo)
                glBindBuffer(GL_ARRAY_BUFFER, texVbo)
                glBufferData(GL_ARRAY_BUFFER, texBuffer, GL_STATIC_DRAW)
                glEnableVertexAttribArray(2)
                glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
                glVertexAttribDivisor(2, 0) // Per-vertex
            } finally {
                MemoryUtil.memFree(texBuffer)
            }
        }

        // Normal VBO (attribute 3)
        // Always set up normals - use provided normals or default to up (0,1,0) for all vertices
        val normalData = normals ?: FloatArray(positions.size) { i ->
            when (i % 3) {
                1 -> 1f  // Y component = 1 (up)
                else -> 0f
            }
        }
        val normalBuffer = MemoryUtil.memAllocFloat(normalData.size)
        try {
            normalBuffer.put(normalData).flip()
            val normalVbo = glGenBuffers()
            vboIds.add(normalVbo)
            glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
            glBufferData(GL_ARRAY_BUFFER, normalBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(3)
            glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(3, 0) // Per-vertex
        } finally {
            MemoryUtil.memFree(normalBuffer)
        }

        // Instance matrix VBO (attributes 4-7)
        instanceVboId = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        // Sized to nothing until an instance is actually added; see [instanceBuffer].
        // glVertexAttribPointer below only records *which* buffer feeds attributes 4-7, so a
        // later glBufferData resize of the same buffer name leaves this VAO state valid.
        glBufferData(GL_ARRAY_BUFFER, 0L, GL_DYNAMIC_DRAW)

        val vec4Size = 4 * 4 // 4 floats * 4 bytes
        val mat4Size = vec4Size * 4 // 4 vec4s per mat4

        // Set up 4 vertex attributes for the 4 columns of the matrix
        for (i in 0 until 4) {
            val attribIndex = 4 + i
            glEnableVertexAttribArray(attribIndex)
            glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, mat4Size, (i * vec4Size).toLong())
            glVertexAttribDivisor(attribIndex, 1) // One per instance
        }

        // Index VBO
        val indexBuffer = MemoryUtil.memAllocInt(indices.size)
        try {
            indexBuffer.put(indices).flip()
            indexVboId = glGenBuffers()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexVboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(indexBuffer)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        // The GL buffers own this geometry now.
        this.positions = null
        this.colors = null
        this.textureCoords = null
        this.normals = null
        this.indices = null
    }

    /**
     * Begin a new batch of instances.
     * Clears the instance buffer for new data.
     */
    fun beginBatch() {
        instanceBuffer?.clear()
        instanceCount = 0
        instanceDataDirty = true
    }

    /**
     * Make room for one more instance, growing the staging and GPU buffers if needed.
     *
     * Returns the staging buffer, or null when [maxInstances] is already reached.
     *
     * The GPU buffer is resized with `glBufferData`, which orphans its contents — harmless here
     * because [endBatch] always re-uploads the whole batch from offset zero.
     */
    private fun instanceSlot(): FloatBuffer? {
        if (instanceCount >= maxInstances) return null
        createGlResources()
        val existing = instanceBuffer
        if (existing != null && instanceCount < instanceCapacity) return existing

        var capacity = if (instanceCapacity == 0) INITIAL_INSTANCE_CAPACITY else instanceCapacity * 2
        while (capacity <= instanceCount) capacity *= 2
        if (capacity > maxInstances) capacity = maxInstances

        val grown = if (existing == null) {
            MemoryUtil.memAllocFloat(capacity * 16)
        } else {
            // memRealloc preserves the contents but not the position, and the instances written
            // so far have to stay where they are.
            val position = existing.position()
            MemoryUtil.memRealloc(existing, capacity * 16).also { it.position(position) }
        }
        instanceBuffer = grown
        instanceCapacity = capacity

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        glBufferData(GL_ARRAY_BUFFER, (capacity * 16 * 4).toLong(), GL_DYNAMIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return grown
    }

    /**
     * Add an instance with the given model matrix.
     *
     * @return true if the instance was added, false if buffer is full
     */
    fun addInstance(modelMatrix: Matrix4f): Boolean {
        val buffer = instanceSlot() ?: return false

        // Get matrix data as column-major floats
        modelMatrix.get(tempFloats)
        buffer.put(tempFloats)
        instanceCount++
        return true
    }

    /**
     * Add an instance with a pre-computed 16-float column-major matrix array.
     * Avoids per-instance Matrix4f allocation on the hot path.
     *
     * @param matrixData 16 floats representing a 4x4 column-major matrix
     * @return true if the instance was added, false if buffer is full
     */
    fun addInstanceRaw(matrixData: FloatArray): Boolean {
        val buffer = instanceSlot() ?: return false

        buffer.put(matrixData)
        instanceCount++
        return true
    }

    /**
     * Add an instance at a position with optional scale.
     */
    fun addInstance(x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        val buffer = instanceSlot() ?: return false

        // Build a simple TRS matrix inline
        tempFloats[0] = scale;  tempFloats[1] = 0f;     tempFloats[2] = 0f;     tempFloats[3] = 0f
        tempFloats[4] = 0f;     tempFloats[5] = scale;  tempFloats[6] = 0f;     tempFloats[7] = 0f
        tempFloats[8] = 0f;     tempFloats[9] = 0f;     tempFloats[10] = scale; tempFloats[11] = 0f
        tempFloats[12] = x;     tempFloats[13] = y;     tempFloats[14] = z;     tempFloats[15] = 1f

        buffer.put(tempFloats)
        instanceCount++
        return true
    }

    /**
     * End the batch and upload instance data to GPU.
     */
    fun endBatch() {
        if (!instanceDataDirty || instanceCount == 0) return
        val buffer = instanceBuffer ?: return

        buffer.flip()

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        // Use glBufferSubData for partial updates (more efficient than glBufferData)
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        instanceDataDirty = false
    }

    /**
     * Render all instances.
     */
    fun render() {
        if (instanceCount == 0) return

        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
    }

    /**
     * Render instances in wireframe mode.
     */
    fun renderWireframe() {
        if (instanceCount == 0) return

        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    /**
     * Get current instance count.
     */
    fun getInstanceCount(): Int = instanceCount

    /**
     * Check if the instance buffer is full.
     */
    fun isFull(): Boolean = instanceCount >= maxInstances

    /**
     * Clean up GPU resources.
     */
    fun cleanup() {
        positions = null
        colors = null
        textureCoords = null
        normals = null
        indices = null

        if (glReady) {
            glDisableVertexAttribArray(0)
            glBindBuffer(GL_ARRAY_BUFFER, 0)

            vboIds.forEach { glDeleteBuffers(it) }
            glDeleteBuffers(instanceVboId)
            glDeleteBuffers(indexVboId)

            glBindVertexArray(0)
            glDeleteVertexArrays(vaoId)
            glReady = false
        }

        instanceBuffer?.let { MemoryUtil.memFree(it) }
        instanceBuffer = null
        instanceCapacity = 0
    }

    companion object {
        /**
         * Instances the staging buffer holds the first time one is added. Small enough that a
         * mesh nothing instances costs nothing, large enough that the common cases (a handful of
         * copies of a piece of scenery) never grow twice.
         */
        private const val INITIAL_INSTANCE_CAPACITY = 64

        /**
         * Create an instanced mesh from an existing Mesh's data.
         * Note: The original Mesh should not be cleaned up while this is in use
         * as this creates new GPU resources.
         */
        fun fromMeshData(
            positions: FloatArray,
            colors: FloatArray? = null,
            textureCoords: FloatArray? = null,
            normals: FloatArray? = null,
            indices: IntArray,
            maxInstances: Int = 10000
        ): InstancedMesh {
            return InstancedMesh(positions, colors, textureCoords, normals, indices, maxInstances)
        }
    }
}
