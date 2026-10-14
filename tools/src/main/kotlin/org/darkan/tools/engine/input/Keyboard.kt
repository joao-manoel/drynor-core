package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*

/**
 * One discrete key press reported by GLFW.
 *
 * The pressed/down/released arrays answer "is this key held", which is what a
 * camera wants. An interface's key hooks need the opposite: every press in the
 * order it happened, with the modifiers that were active and whether it was an
 * auto-repeat, because a hook that fires per keystroke has to fire twice when a
 * key is struck twice inside a frame.
 */
data class KeyEvent(
    val key: Int,
    val scancode: Int,
    val mods: Int,
    /** True for GLFW's auto-repeat presses rather than the initial press. */
    val repeat: Boolean,
)

class Keyboard {
    private val keyStates = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyPressed = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyReleased = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyRepeated = BooleanArray(GLFW_KEY_LAST + 1)

    private val pressEvents = ArrayList<KeyEvent>()
    private val typedChars = ArrayList<Int>()

    /** Modifier bitmask from the most recent key event, as GLFW's `GLFW_MOD_*`. */
    var mods: Int = 0
        private set

    /**
     * Presses seen this frame, oldest first, including auto-repeats. Cleared by
     * [update], so a consumer has to read it within the frame.
     */
    val presses: List<KeyEvent> get() = pressEvents

    /** Unicode codepoints typed this frame, in order. */
    val chars: List<Int> get() = typedChars

    fun onKeyEvent(key: Int, scancode: Int, action: Int, mods: Int) {
        if (key < 0 || key > GLFW_KEY_LAST) return

        this.mods = mods
        when (action) {
            GLFW_PRESS -> {
                keyStates[key] = true
                keyPressed[key] = true
                pressEvents.add(KeyEvent(key, scancode, mods, repeat = false))
            }
            GLFW_RELEASE -> {
                keyStates[key] = false
                keyReleased[key] = true
            }
            // Auto-repeat was previously dropped on the floor. It has to be kept:
            // held-key behaviour such as a scrollbar arrow or a text field's
            // backspace is driven by the repeats, not by the initial press.
            GLFW_REPEAT -> {
                keyRepeated[key] = true
                pressEvents.add(KeyEvent(key, scancode, mods, repeat = true))
            }
        }
    }

    fun onChar(codepoint: Int) {
        typedChars.add(codepoint)
    }

    fun isKeyDown(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyStates[key] else false
    }

    fun isKeyPressed(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyPressed[key] else false
    }

    fun isKeyReleased(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyReleased[key] else false
    }

    /** True when the key produced an auto-repeat this frame. */
    fun isKeyRepeated(key: Int): Boolean {
        return if (key in 0..GLFW_KEY_LAST) keyRepeated[key] else false
    }

    /** True for the initial press or any auto-repeat, which is what shortcuts want. */
    fun isKeyTyped(key: Int): Boolean = isKeyPressed(key) || isKeyRepeated(key)

    val shift: Boolean get() = isKeyDown(GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW_KEY_RIGHT_SHIFT)

    val control: Boolean get() = isKeyDown(GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW_KEY_RIGHT_CONTROL)

    val alt: Boolean get() = isKeyDown(GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW_KEY_RIGHT_ALT)

    fun update() {
        keyPressed.fill(false)
        keyReleased.fill(false)
        keyRepeated.fill(false)
        pressEvents.clear()
        typedChars.clear()
    }
}
