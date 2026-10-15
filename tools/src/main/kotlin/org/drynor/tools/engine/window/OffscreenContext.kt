package org.drynor.tools.engine.window

import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL

object OffscreenContext {

    private var handle: Long = 0

    fun open() {
        check(handle == 0L) { "an offscreen context is already open." }
        check(glfwInit()) { "GLFW would not start; run this under a display or Xvfb." }
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        handle = glfwCreateWindow(SURFACE, SURFACE, "Drynor", 0, 0)
        check(handle != 0L) { "could not create an offscreen GL window." }
        glfwMakeContextCurrent(handle)
        GL.createCapabilities()
    }

    fun close() {
        if (handle == 0L) {
            return
        }
        glfwDestroyWindow(handle)
        glfwTerminate()
        handle = 0
    }

    inline fun <T> use(block: () -> T): T {
        open()
        try {
            return block()
        } finally {
            close()
        }
    }

    const val SURFACE = 64
}
