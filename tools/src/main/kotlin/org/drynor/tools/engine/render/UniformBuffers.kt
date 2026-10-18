package org.drynor.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL31.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Manages Uniform Buffer Objects (UBOs) for efficient shader data sharing.
 *
 * Uses std140 layout for cross-shader compatibility:
 * - CameraBlock (binding 0): view, projection, viewProjection matrices + camera position
 * - EnvironmentBlock (binding 1): sun direction/colour/terms, fog colour and range
 *
 * Using UBOs reduces per-draw-call uniform updates significantly when many
 * objects share the same camera/environment data.
 */
class UniformBuffers {
    // UBO IDs
    private var cameraUBO: Int = 0
    private var environmentUBO: Int = 0

    // Buffer sizes (std140 aligned)
    // CameraBlock: 3 mat4 (192 bytes) + vec3 padded to vec4 (16 bytes) = 208 bytes, pad to 256 for alignment
    private val cameraBlockSize = 256

    // EnvironmentBlock: 5 vec4 = 80 bytes
    private val environmentBlockSize = EnvironmentSettings.STD140_FLOATS * 4

    // Binding points
    private val cameraBinding = 0
    private val environmentBinding = 1

    // Cached values to avoid redundant updates
    private val cachedView = Matrix4f()
    private val cachedProjection = Matrix4f()
    private val cachedCameraPos = Vector3f()
    private var cameraDirty = true

    // Scratch + last-uploaded snapshot of the environment block. Comparing the packed
    // floats is exact and costs nothing next to a buffer upload, so the environment can
    // be handed in every frame and only reaches the GPU when something actually changed.
    private val environmentScratch = FloatArray(EnvironmentSettings.STD140_FLOATS)
    private val environmentUploaded = FloatArray(EnvironmentSettings.STD140_FLOATS)
    private val environmentDirection = Vector3f()
    private var environmentDirty = true

    /**
     * Initialize the UBOs.
     */
    fun init() {
        // Create Camera UBO
        cameraUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
        glBufferData(GL_UNIFORM_BUFFER, cameraBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, cameraBinding, cameraUBO)

        // Create Environment UBO
        environmentUBO = glGenBuffers()
        glBindBuffer(GL_UNIFORM_BUFFER, environmentUBO)
        glBufferData(GL_UNIFORM_BUFFER, environmentBlockSize.toLong(), GL_DYNAMIC_DRAW)
        glBindBufferBase(GL_UNIFORM_BUFFER, environmentBinding, environmentUBO)

        glBindBuffer(GL_UNIFORM_BUFFER, 0)

        // Initialize with default values to avoid black rendering on first frame
        cameraDirty = true
        environmentDirty = true
        updateEnvironment(EnvironmentSettings())
        updateCamera(
            Matrix4f().identity(),
            Matrix4f().identity(),
            Vector3f(0f, 0f, 0f)
        )
    }

    /**
     * Update camera UBO data.
     */
    fun updateCamera(view: Matrix4f, projection: Matrix4f, cameraPos: Vector3f) {
        // Check if data actually changed
        if (!cameraDirty &&
            cachedView.equals(view) &&
            cachedProjection.equals(projection) &&
            cachedCameraPos.equals(cameraPos)) {
            return
        }

        cachedView.set(view)
        cachedProjection.set(projection)
        cachedCameraPos.set(cameraPos)
        cameraDirty = false

        MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(cameraBlockSize)

            // std140 layout:
            // mat4 view (64 bytes)
            val viewFb = stack.mallocFloat(16)
            view.get(viewFb)
            for (i in 0 until 16) {
                buffer.putFloat(viewFb.get(i))
            }

            // mat4 projection (64 bytes)
            val projFb = stack.mallocFloat(16)
            projection.get(projFb)
            for (i in 0 until 16) {
                buffer.putFloat(projFb.get(i))
            }

            // mat4 viewProjection (64 bytes)
            val viewProj = Matrix4f(projection).mul(view)
            val vpFb = stack.mallocFloat(16)
            viewProj.get(vpFb)
            for (i in 0 until 16) {
                buffer.putFloat(vpFb.get(i))
            }

            // vec3 cameraPos padded to vec4 (16 bytes)
            buffer.putFloat(cameraPos.x)
            buffer.putFloat(cameraPos.y)
            buffer.putFloat(cameraPos.z)
            buffer.putFloat(1.0f) // padding

            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, cameraUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    /**
     * Update the environment UBO from [settings].
     *
     * Safe to call every frame: the packed block is compared against the last upload and
     * only pushed when it differs.
     */
    fun updateEnvironment(settings: EnvironmentSettings) {
        settings.writeStd140(environmentScratch, environmentDirection)

        if (!environmentDirty && environmentScratch.contentEquals(environmentUploaded)) {
            return
        }
        System.arraycopy(environmentScratch, 0, environmentUploaded, 0, environmentScratch.size)
        environmentDirty = false

        MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(environmentBlockSize)
            for (value in environmentScratch) {
                buffer.putFloat(value)
            }
            buffer.flip()

            glBindBuffer(GL_UNIFORM_BUFFER, environmentUBO)
            glBufferSubData(GL_UNIFORM_BUFFER, 0, buffer)
            glBindBuffer(GL_UNIFORM_BUFFER, 0)
        }
    }

    /**
     * Bind a shader's uniform block to the camera UBO.
     */
    fun bindCameraBlock(shader: ShaderProgram, blockName: String = "CameraBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, cameraBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    /**
     * Bind a shader's uniform block to the environment UBO.
     */
    fun bindEnvironmentBlock(shader: ShaderProgram, blockName: String = "EnvironmentBlock") {
        val blockIndex = glGetUniformBlockIndex(shader.id, blockName)
        if (blockIndex != GL_INVALID_INDEX) {
            glUniformBlockBinding(shader.id, blockIndex, environmentBinding)
        } else {
            System.err.println("UBO WARNING: $blockName not found in shader ${shader.id}")
        }
    }

    /**
     * Bind both uniform blocks to a shader.
     */
    fun bindToShader(
        shader: ShaderProgram,
        cameraBlockName: String = "CameraBlock",
        environmentBlockName: String = "EnvironmentBlock"
    ) {
        bindCameraBlock(shader, cameraBlockName)
        bindEnvironmentBlock(shader, environmentBlockName)
    }

    /**
     * Force update on next call (use when camera/environment changed externally).
     */
    fun invalidate() {
        cameraDirty = true
        environmentDirty = true
    }

    /**
     * Clean up GPU resources.
     */
    fun cleanup() {
        if (cameraUBO != 0) {
            glDeleteBuffers(cameraUBO)
            cameraUBO = 0
        }
        if (environmentUBO != 0) {
            glDeleteBuffers(environmentUBO)
            environmentUBO = 0
        }
    }

    companion object {
        /**
         * Get the GLSL code for the camera uniform block.
         */
        fun getCameraBlockGLSL(): String = """
            layout(std140) uniform CameraBlock {
                mat4 view;
                mat4 projection;
                mat4 viewProjection;
                vec3 cameraPos;
            };
        """.trimIndent()

        /**
         * Get the GLSL code for the environment uniform block.
         * Kept in sync with [EnvironmentSettings.writeStd140] and every scene shader.
         */
        fun getEnvironmentBlockGLSL(): String = """
            layout(std140) uniform EnvironmentBlock {
                vec4 sunDirection;
                vec4 sunColour;
                vec4 sunTerms;
                vec4 fogColour;
                vec4 fogRange;
            };
        """.trimIndent()
    }
}
