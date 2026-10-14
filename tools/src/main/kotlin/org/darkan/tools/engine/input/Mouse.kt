package org.darkan.tools.engine.input

import org.lwjgl.glfw.GLFW.*
import kotlin.math.abs

class Mouse {
    private val buttonStates = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonPressed = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val buttonReleased = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)

    /** Where each button went down, so a drag can be measured from its origin. */
    private val pressX = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val pressY = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val pressTime = LongArray(GLFW_MOUSE_BUTTON_LAST + 1)

    /** Consecutive clicks within the double-click window, per button. */
    private val clickCounts = IntArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickTime = LongArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickX = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val lastClickY = DoubleArray(GLFW_MOUSE_BUTTON_LAST + 1)

    /** Modifier bitmask from the most recent button event, as GLFW's `GLFW_MOD_*`. */
    var mods: Int = 0
        private set

    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set
    var deltaX: Double = 0.0
        private set
    var deltaY: Double = 0.0
        private set
    var scrollX: Double = 0.0
        private set
    var scrollY: Double = 0.0
        private set

    /** True when the pointer moved this frame, which gates mouse-move hooks. */
    var moved: Boolean = false
        private set

    private var lastX: Double = 0.0
    private var lastY: Double = 0.0
    private var firstMove: Boolean = true

    fun onMouseButton(button: Int, action: Int, mods: Int) {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) return

        this.mods = mods
        when (action) {
            GLFW_PRESS -> {
                buttonStates[button] = true
                buttonPressed[button] = true
                pressX[button] = x
                pressY[button] = y
                val now = System.nanoTime()
                pressTime[button] = now
                // A double click is two presses close in both time and space; a
                // press that travelled restarts the count, which is what stops a
                // drag-and-release-and-drag from reading as a double click.
                val quick = (now - lastClickTime[button]) < DOUBLE_CLICK_NANOS
                val still = abs(x - lastClickX[button]) <= DOUBLE_CLICK_SLOP &&
                    abs(y - lastClickY[button]) <= DOUBLE_CLICK_SLOP
                clickCounts[button] = if (quick && still) clickCounts[button] + 1 else 1
                lastClickTime[button] = now
                lastClickX[button] = x
                lastClickY[button] = y
            }
            GLFW_RELEASE -> {
                buttonStates[button] = false
                buttonReleased[button] = true
            }
        }
    }

    /**
     * Treat the next cursor position as a new origin rather than a movement.
     *
     * Called when the window takes focus or the cursor re-enters it. GLFW does not report
     * where the pointer went while it was elsewhere, so [lastX]/[lastY] are stale and the
     * first event back would otherwise produce a delta the size of however far the pointer
     * travelled off-window — enough to swing the camera to a completely different framing
     * on the frame focus arrives, and enough to make two runs of the same capture disagree.
     */
    fun resetDeltaReference() {
        firstMove = true
    }

    fun onCursorPos(xpos: Double, ypos: Double) {
        if (firstMove) {
            lastX = xpos
            lastY = ypos
            firstMove = false
            // The reference has only just been established, so there is no movement to
            // report yet; anything left over from before the gap is not this frame's.
            deltaX = 0.0
            deltaY = 0.0
            x = xpos
            y = ypos
            moved = true
            return
        }

        deltaX = xpos - lastX
        deltaY = ypos - lastY
        lastX = xpos
        lastY = ypos
        x = xpos
        y = ypos
        moved = true
    }

    fun onScroll(xoffset: Double, yoffset: Double) {
        scrollX += xoffset
        scrollY += yoffset
    }

    fun isButtonDown(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonStates[button] else false
    }

    fun isButtonPressed(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonPressed[button] else false
    }

    fun isButtonReleased(button: Int): Boolean {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) buttonReleased[button] else false
    }

    /** How many clicks in the current run; 2 on the press that completes a double click. */
    fun clickCount(button: Int): Int {
        return if (button in 0..GLFW_MOUSE_BUTTON_LAST) clickCounts[button] else 0
    }

    fun isDoubleClick(button: Int): Boolean = isButtonPressed(button) && clickCount(button) == 2

    /** Where [button] went down, valid while it is held. */
    fun pressOriginX(button: Int): Double =
        if (button in 0..GLFW_MOUSE_BUTTON_LAST) pressX[button] else 0.0

    fun pressOriginY(button: Int): Double =
        if (button in 0..GLFW_MOUSE_BUTTON_LAST) pressY[button] else 0.0

    /** Milliseconds [button] has been held, or 0 when it is up. */
    fun heldMillis(button: Int): Long {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST || !buttonStates[button]) return 0
        return (System.nanoTime() - pressTime[button]) / 1_000_000
    }

    fun update() {
        buttonPressed.fill(false)
        buttonReleased.fill(false)
        deltaX = 0.0
        deltaY = 0.0
        scrollX = 0.0
        scrollY = 0.0
        moved = false
    }

    companion object {
        private const val DOUBLE_CLICK_NANOS = 400_000_000L
        private const val DOUBLE_CLICK_SLOP = 4.0
    }
}
