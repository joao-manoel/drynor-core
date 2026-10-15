package org.drynor.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glUniform1ui
import org.lwjgl.system.MemoryStack

class ShaderProgram {
    var id: Int = 0
        private set
    private val uniforms = mutableMapOf<String, Int>()

    fun init() {
        id = glCreateProgram()
        if (id == 0) {
            throw RuntimeException("Could not create shader program")
        }
    }

    fun attachShader(shader: Shader) {
        glAttachShader(id, shader.id)
    }

    fun link() {
        glLinkProgram(id)
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) {
            val log = glGetProgramInfoLog(id, 1024)
            throw RuntimeException("Error linking shader program: $log")
        }

        glValidateProgram(id)
        if (glGetProgrami(id, GL_VALIDATE_STATUS) == 0) {
            System.err.println("Warning validating shader program: ${glGetProgramInfoLog(id, 1024)}")
        }
    }

    fun createUniform(name: String) {
        val location = glGetUniformLocation(id, name)
        if (location < 0) {
            System.err.println("Warning: Could not find uniform '$name' in shader program")
        }
        uniforms[name] = location
    }

    fun setUniform(name: String, value: Int) {
        uniforms[name]?.let { glUniform1i(it, value) }
    }

    fun setUniform(name: String, value: Boolean) {
        uniforms[name]?.let { glUniform1i(it, if (value) 1 else 0) }
    }

    fun setUniform(name: String, value: Float) {
        uniforms[name]?.let { glUniform1f(it, value) }
    }

    fun setUniform(name: String, value: Vector3f) {
        uniforms[name]?.let { glUniform3f(it, value.x, value.y, value.z) }
    }

    fun setUniform(name: String, value: Vector4f) {
        uniforms[name]?.let { glUniform4f(it, value.x, value.y, value.z, value.w) }
    }

    /** Set an unsigned int uniform. Kotlin has no unsigned Int, so [value] is reinterpreted. */
    fun setUniformUInt(name: String, value: Int) {
        uniforms[name]?.let { glUniform1ui(it, value) }
    }

    /**
     * Set an array-of-vec4 uniform from tightly packed floats.
     * [values] must hold `4 * count` entries.
     */
    fun setUniformVec4Array(name: String, values: FloatArray) {
        uniforms[name]?.let { glUniform4fv(it, values) }
    }

    fun setUniform(name: String, value: Matrix4f) {
        val loc = uniforms[name] ?: return
        MemoryStack.stackPush().use { stack ->
            val fb = stack.mallocFloat(16)
            value.get(fb)
            glUniformMatrix4fv(loc, false, fb)
        }
    }

    fun bind() {
        glUseProgram(id)
    }

    fun unbind() {
        glUseProgram(0)
    }

    fun cleanup() {
        unbind()
        if (id != 0) {
            glDeleteProgram(id)
        }
    }

    companion object {
        fun create(vertexSource: String, fragmentSource: String): ShaderProgram {
            val program = ShaderProgram()
            program.init()

            val vertexShader = Shader.vertex(vertexSource)
            val fragmentShader = Shader.fragment(fragmentSource)

            program.attachShader(vertexShader)
            program.attachShader(fragmentShader)
            program.link()

            vertexShader.cleanup()
            fragmentShader.cleanup()

            return program
        }

        /**
         * Build a compute-only program from [computeSource] and resolve [uniformNames].
         *
         * The caller is responsible for having checked that compute shaders are available
         * (`caps.OpenGL43 || caps.GL_ARB_compute_shader`) — the context version string is
         * not a reliable proxy for that, since the window asks for a 3.3 core context and
         * drivers routinely hand back one that still exposes 4.x features.
         */
        fun createCompute(computeSource: String, vararg uniformNames: String): ShaderProgram {
            val program = ShaderProgram()
            program.init()

            val computeShader = Shader.compute(computeSource)
            program.attachShader(computeShader)
            program.link()
            computeShader.cleanup()

            uniformNames.forEach { program.createUniform(it) }
            return program
        }

        fun createWithUniforms(
            vertexSource: String,
            fragmentSource: String,
            vararg uniformNames: String
        ): ShaderProgram {
            val program = create(vertexSource, fragmentSource)
            uniformNames.forEach { program.createUniform(it) }
            return program
        }
    }
}
