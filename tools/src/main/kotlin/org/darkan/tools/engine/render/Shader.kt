package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER

class Shader(private val type: Int) {
    var id: Int = 0
        private set

    fun compile(source: String) {
        id = glCreateShader(type)
        if (id == 0) {
            throw RuntimeException("Error creating shader of type: $type")
        }

        glShaderSource(id, source)
        glCompileShader(id)

        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            val log = glGetShaderInfoLog(id, 1024)
            throw RuntimeException("Error compiling shader: $log")
        }
    }

    fun cleanup() {
        if (id != 0) {
            glDeleteShader(id)
        }
    }

    companion object {
        fun vertex(source: String): Shader {
            return Shader(GL_VERTEX_SHADER).apply { compile(source) }
        }

        fun fragment(source: String): Shader {
            return Shader(GL_FRAGMENT_SHADER).apply { compile(source) }
        }

        /**
         * Compile a compute shader. Requires GL 4.3 / ARB_compute_shader; check the
         * capability before calling, the compile itself only reports a GLSL error.
         */
        fun compute(source: String): Shader {
            return Shader(GL_COMPUTE_SHADER).apply { compile(source) }
        }
    }
}
