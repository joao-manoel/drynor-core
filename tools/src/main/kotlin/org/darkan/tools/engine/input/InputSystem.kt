package org.darkan.tools.engine.input

import org.darkan.tools.engine.window.Window
import org.lwjgl.glfw.GLFW.*

class InputSystem(private val window: Window) {
    val keyboard = Keyboard()
    val mouse = Mouse()

    var mouseCaptured: Boolean = false
        private set

    fun init() {
        window.setKeyCallback { key, scancode, action, mods ->
            keyboard.onKeyEvent(key, scancode, action, mods)
        }

        window.setCharCallback { codepoint ->
            keyboard.onChar(codepoint)
        }

        window.setMouseButtonCallback { button, action, mods ->
            mouse.onMouseButton(button, action, mods)
        }

        window.setCursorPosCallback { xpos, ypos ->
            mouse.onCursorPos(xpos, ypos)
        }

        window.setScrollCallback { xoffset, yoffset ->
            mouse.onScroll(xoffset, yoffset)
        }

        window.setFocusCallback { focused ->
            if (focused) mouse.resetDeltaReference()
        }
    }

    fun update() {
        keyboard.update()
        mouse.update()
    }

    fun captureMouse() {
        if (!mouseCaptured) {
            window.setCursorMode(GLFW_CURSOR_DISABLED)
            mouseCaptured = true
        }
    }

    fun releaseMouse() {
        if (mouseCaptured) {
            window.setCursorMode(GLFW_CURSOR_NORMAL)
            mouseCaptured = false
        }
    }

    fun toggleMouseCapture() {
        if (mouseCaptured) releaseMouse() else captureMouse()
    }
}
