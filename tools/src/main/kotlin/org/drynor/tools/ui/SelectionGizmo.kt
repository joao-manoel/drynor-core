package org.drynor.tools.ui

import imgui.ImDrawList
import org.lwjgl.glfw.GLFW.GLFW_ARROW_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_RESIZE_ALL_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_RESIZE_NESW_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_RESIZE_NWSE_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_VRESIZE_CURSOR
import kotlin.math.abs
import kotlin.math.roundToInt

/** A box in stage pixels: the thing a gizmo moves and resizes. */
data class GizmoBox(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Which part of the selection the pointer grabbed.
 *
 * The eight edge and corner handles resize; the body moves. [NONE] means the
 * pointer is not on the selection at all, which is what lets a click fall
 * through to picking a different component.
 */
enum class GizmoHandle {
    NONE,
    BODY,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    /** The GLFW cursor shape that advertises what this handle does. */
    val cursor: Int
        get() = when (this) {
            NONE -> GLFW_ARROW_CURSOR
            BODY -> GLFW_RESIZE_ALL_CURSOR
            LEFT, RIGHT -> GLFW_HRESIZE_CURSOR
            TOP, BOTTOM -> GLFW_VRESIZE_CURSOR
            TOP_LEFT, BOTTOM_RIGHT -> GLFW_RESIZE_NWSE_CURSOR
            TOP_RIGHT, BOTTOM_LEFT -> GLFW_RESIZE_NESW_CURSOR
        }

    val resizes: Boolean get() = this != NONE && this != BODY

    val movesLeftEdge: Boolean get() = this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT

    val movesRightEdge: Boolean get() = this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT

    val movesTopEdge: Boolean get() = this == TOP || this == TOP_LEFT || this == TOP_RIGHT

    val movesBottomEdge: Boolean get() = this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * Direct manipulation of the selected component in the stage.
 *
 * Typing coordinates into a property sheet is a poor way to lay out a component
 * you are looking at; dragging it is the obvious way. This class holds only the
 * geometry of that: given the selection's box in stage pixels and the pointer in
 * the same space, it decides which handle is under the cursor, tracks the drag,
 * and produces the new box.
 *
 * It is deliberately free of any component or document type, so it can drive a
 * cache component today and anything else later, and so its arithmetic can be
 * reasoned about on its own.
 *
 * ### Why the drag is tracked from the original box
 *
 * A drag that applies each frame's delta to the *current* box accumulates the
 * rounding error of every frame, and at a fractional zoom that error is visible
 * within a second of dragging. [begin] therefore snapshots the box and the
 * pointer, and [update] computes the whole transform from that snapshot each
 * time. The result is exact, and releasing and re-grabbing is the only thing
 * that changes the reference.
 */
class SelectionGizmo {

    /** Half-width of a handle's grab area, in screen pixels. */
    var handleGrab: Float = 5f

    /** Snap edges to a grid of this many stage pixels; 1 disables snapping. */
    var snap: Int = 1

    /** Smallest box a resize can produce. */
    var minimumSize: Int = 1

    /** The handle the pointer is over, updated by [hitTest]. */
    var hovered: GizmoHandle = GizmoHandle.NONE
        private set

    /** The handle being dragged, or [GizmoHandle.NONE]. */
    var active: GizmoHandle = GizmoHandle.NONE
        private set

    val dragging: Boolean get() = active != GizmoHandle.NONE

    private var originBox = GizmoBox(0, 0, 0, 0)
    private var originPointerX = 0
    private var originPointerY = 0

    /**
     * Works out which handle sits under a pointer at ([pointerX], [pointerY]),
     * both in stage pixels. [scale] is the stage's zoom, so the grab area stays a
     * constant size on screen rather than shrinking as you zoom out - a handle
     * measured in stage pixels becomes ungrabbable at 25%.
     */
    fun hitTest(box: GizmoBox, pointerX: Int, pointerY: Int, scale: Float): GizmoHandle {
        if (dragging) {
            return active
        }
        val grab = (handleGrab / scale.coerceAtLeast(0.01f)).coerceAtLeast(1.5f)
        val left = box.x
        val right = box.x + box.width
        val top = box.y
        val bottom = box.y + box.height

        val nearLeft = pointerX >= left - grab && pointerX <= left + grab
        val nearRight = pointerX >= right - grab && pointerX <= right + grab
        val nearTop = pointerY >= top - grab && pointerY <= top + grab
        val nearBottom = pointerY >= bottom - grab && pointerY <= bottom + grab
        val withinX = pointerX >= left - grab && pointerX <= right + grab
        val withinY = pointerY >= top - grab && pointerY <= bottom + grab

        hovered = when {
            !withinX || !withinY -> GizmoHandle.NONE
            nearLeft && nearTop -> GizmoHandle.TOP_LEFT
            nearRight && nearTop -> GizmoHandle.TOP_RIGHT
            nearLeft && nearBottom -> GizmoHandle.BOTTOM_LEFT
            nearRight && nearBottom -> GizmoHandle.BOTTOM_RIGHT
            nearLeft -> GizmoHandle.LEFT
            nearRight -> GizmoHandle.RIGHT
            nearTop -> GizmoHandle.TOP
            nearBottom -> GizmoHandle.BOTTOM
            pointerX in left..right && pointerY in top..bottom -> GizmoHandle.BODY
            else -> GizmoHandle.NONE
        }
        return hovered
    }

    /** Starts a drag on [handle]. Returns false when there is nothing to drag. */
    fun begin(handle: GizmoHandle, box: GizmoBox, pointerX: Int, pointerY: Int): Boolean {
        if (handle == GizmoHandle.NONE) {
            return false
        }
        active = handle
        originBox = box
        originPointerX = pointerX
        originPointerY = pointerY
        return true
    }

    /**
     * The box the drag has produced so far, computed from the snapshot taken by
     * [begin]. Returns null when no drag is in progress.
     *
     * [constrain] locks a move to one axis and a corner resize to the original
     * aspect ratio, which is the shift-drag every editor has.
     */
    fun update(pointerX: Int, pointerY: Int, constrain: Boolean = false): GizmoBox? {
        if (!dragging) {
            return null
        }
        var deltaX = pointerX - originPointerX
        var deltaY = pointerY - originPointerY

        if (active == GizmoHandle.BODY) {
            if (constrain) {
                // Lock to whichever axis has moved further, so a nearly-horizontal
                // drag stays exactly horizontal.
                if (abs(deltaX) >= abs(deltaY)) deltaY = 0 else deltaX = 0
            }
            return GizmoBox(
                snapValue(originBox.x + deltaX),
                snapValue(originBox.y + deltaY),
                originBox.width,
                originBox.height,
            )
        }

        var left = originBox.x
        var top = originBox.y
        var right = originBox.x + originBox.width
        var bottom = originBox.y + originBox.height

        if (active.movesLeftEdge) {
            left = snapValue(left + deltaX).coerceAtMost(right - minimumSize)
        }
        if (active.movesRightEdge) {
            right = snapValue(right + deltaX).coerceAtLeast(left + minimumSize)
        }
        if (active.movesTopEdge) {
            top = snapValue(top + deltaY).coerceAtMost(bottom - minimumSize)
        }
        if (active.movesBottomEdge) {
            bottom = snapValue(bottom + deltaY).coerceAtLeast(top + minimumSize)
        }

        var width = right - left
        var height = bottom - top
        if (constrain && originBox.width > 0 && originBox.height > 0 && isCorner(active)) {
            // Hold the original aspect, driven by whichever axis grew more, and
            // grow away from the anchor corner so the opposite corner stays put.
            val ratio = originBox.height.toFloat() / originBox.width
            if (width * ratio >= height) {
                height = (width * ratio).roundToInt().coerceAtLeast(minimumSize)
            } else {
                width = (height / ratio).roundToInt().coerceAtLeast(minimumSize)
            }
            if (active.movesLeftEdge) {
                left = right - width
            }
            if (active.movesTopEdge) {
                top = bottom - height
            }
        }
        return GizmoBox(left, top, width, height)
    }

    fun end() {
        active = GizmoHandle.NONE
    }

    fun cancel() {
        active = GizmoHandle.NONE
    }

    /** The box the drag started from, for an "escape reverts" cancel. */
    fun originalBox(): GizmoBox = originBox

    /**
     * Draws the selection outline and its handles.
     *
     * Everything is in screen space and at a fixed pixel size, so the handles stay
     * grabbable and the outline stays one pixel wide however far the stage is
     * zoomed. [screenX] / [screenY] convert a stage coordinate to a screen one.
     */
    @Suppress("LongParameterList")
    fun draw(
        drawList: ImDrawList,
        box: GizmoBox,
        screenX: (Int) -> Float,
        screenY: (Int) -> Float,
        showHandles: Boolean = true,
    ) {
        val left = screenX(box.x)
        val top = screenY(box.y)
        val right = screenX(box.x + box.width)
        val bottom = screenY(box.y + box.height)

        // A dark outline under the bright one keeps the selection legible over
        // both the light and dark artwork an interface can be made of.
        drawList.addRect(left - 1f, top - 1f, right + 1f, bottom + 1f, SHADOW)
        drawList.addRect(left, top, right, bottom, OUTLINE)
        if (!showHandles) {
            return
        }
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f
        for (handle in HANDLE_POSITIONS) {
            val (handleX, handleY) = when (handle) {
                GizmoHandle.TOP_LEFT -> left to top
                GizmoHandle.TOP -> midX to top
                GizmoHandle.TOP_RIGHT -> right to top
                GizmoHandle.LEFT -> left to midY
                GizmoHandle.RIGHT -> right to midY
                GizmoHandle.BOTTOM_LEFT -> left to bottom
                GizmoHandle.BOTTOM -> midX to bottom
                GizmoHandle.BOTTOM_RIGHT -> right to bottom
                else -> continue
            }
            val lit = handle == hovered || handle == active
            val size = if (lit) HANDLE_SIZE + 1f else HANDLE_SIZE
            drawList.addRectFilled(
                handleX - size, handleY - size,
                handleX + size, handleY + size,
                SHADOW,
            )
            drawList.addRectFilled(
                handleX - size + 1f, handleY - size + 1f,
                handleX + size - 1f, handleY + size - 1f,
                if (lit) OUTLINE else HANDLE_FILL,
            )
        }
    }

    /** Rounds to the snap grid, which is a no-op at a snap of 1. */
    private fun snapValue(value: Int): Int {
        if (snap <= 1) {
            return value
        }
        return ((value.toFloat() / snap).roundToInt()) * snap
    }

    private fun isCorner(handle: GizmoHandle): Boolean =
        handle == GizmoHandle.TOP_LEFT || handle == GizmoHandle.TOP_RIGHT ||
            handle == GizmoHandle.BOTTOM_LEFT || handle == GizmoHandle.BOTTOM_RIGHT

    companion object {
        private const val HANDLE_SIZE = 3.5f

        /** ImGui packs colours as ABGR. */
        private const val OUTLINE = 0xffffd02fL.toInt()
        private const val SHADOW = 0xc0000000L.toInt()
        private const val HANDLE_FILL = 0xffffffffL.toInt()

        private val HANDLE_POSITIONS = listOf(
            GizmoHandle.TOP_LEFT, GizmoHandle.TOP, GizmoHandle.TOP_RIGHT,
            GizmoHandle.LEFT, GizmoHandle.RIGHT,
            GizmoHandle.BOTTOM_LEFT, GizmoHandle.BOTTOM, GizmoHandle.BOTTOM_RIGHT,
        )
    }
}
