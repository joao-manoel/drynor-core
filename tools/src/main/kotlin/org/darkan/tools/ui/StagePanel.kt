package org.darkan.tools.ui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import org.darkan.tools.engine.render.RenderTarget
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The dockable viewport the interface is drawn into.
 *
 * The interface renders into an off-screen [RenderTarget] and this panel shows
 * that texture, which buys three things a direct back-buffer draw cannot:
 *
 * - **The stage is a first-class rectangle.** Root-level components resolve their
 *   centred, far-edge and proportional modes against it, so it has to be exactly
 *   the size of the container the client would mount the interface in - not
 *   whatever is left of the window after the panels. A panel docked over a
 *   back-buffer draw either hides the interface or silently changes its layout.
 * - **Zoom.** Interfaces are authored at 512 x 334 and a developer needs to see
 *   single pixels. Scaling a texture is free; re-rendering at a scale would make
 *   every hit test and every hook's mouse coordinate wrong.
 * - **An honest pointer.** [pointerX] / [pointerY] map the cursor back into stage
 *   pixels through the same transform the image was drawn with, so the coordinate
 *   a CS2 hook receives is the coordinate the client would give it, at any zoom
 *   and any pan.
 *
 * ### Frame contract
 *
 * Call [render] while building the ImGui frame, then draw the interface into
 * [RenderTarget] *after* it and before `ImGui.render()`. That order is not
 * incidental: [render] is what sizes the target and reads back the panel
 * geometry, and ImGui only samples the texture when the draw data is submitted,
 * so the contents written after this call are the contents that appear - with no
 * frame of lag.
 */
class StagePanel(private val target: RenderTarget) {

    var visible: Boolean = true

    /** The client's fixed-mode game window, which most interfaces are authored for. */
    val stageWidth = ImInt(DEFAULT_WIDTH)
    val stageHeight = ImInt(DEFAULT_HEIGHT)

    /** Scale the stage texture is shown at; 1 is one stage pixel per screen pixel. */
    var zoom: Float = 1f
        private set

    /** Grow the stage to fill the panel instead of holding a fixed size. */
    var matchPanel: Boolean = false
        private set

    /** Scale the stage to fit the panel rather than using [zoom] directly. */
    var fitToPanel: Boolean = false
        private set

    var showGrid: Boolean = false
        private set

    var gridSize: Int = 16
        private set

    /** Draw a checkerboard behind the stage so transparent areas read as empty. */
    var showCheckerboard: Boolean = true
        private set

    // --- Panel geometry, valid after render() ------------------------------

    /** Where the stage image landed on screen, in ImGui screen coordinates. */
    var imageX: Float = 0f
        private set
    var imageY: Float = 0f
        private set
    var imageScale: Float = 1f
        private set

    /** True when the pointer is inside the stage image. */
    var pointerInside: Boolean = false
        private set

    /** Pointer position in stage pixels, or -1 when it is outside the image. */
    var pointerX: Int = -1
        private set
    var pointerY: Int = -1
        private set

    /** True when the stage image itself is hovered, so input belongs to the interface. */
    var hovered: Boolean = false
        private set

    private var panX = 0f
    private var panY = 0f

    /** Set when the target could not be allocated, so the caller skips drawing. */
    private var ready = false

    /** Extra controls drawn into the panel's toolbar by the host. */
    var toolbar: (() -> Unit)? = null

    /** Overlay drawn on top of the stage image, in screen coordinates. */
    var overlay: (() -> Unit)? = null

    /** Reported in the status line. */
    var status: String = ""

    fun setZoom(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Converts a stage-space x to an on-screen x. */
    fun screenX(stageX: Int): Float = imageX + stageX * imageScale

    fun screenY(stageY: Int): Float = imageY + stageY * imageScale

    /** Recentres the view and returns the zoom to 1. */
    fun resetView() {
        panX = 0f
        panY = 0f
        zoom = 1f
        fitToPanel = false
    }

    /**
     * Draws the panel and sizes the render target. Returns true when the caller
     * should render the interface into the target this frame; false when the panel
     * is closed, collapsed or degenerate, in which case nothing should be drawn.
     */
    fun render(fps: Int): Boolean {
        ready = false
        if (!visible) {
            return false
        }

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val open = ImGui.begin("Stage", ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        ImGui.popStyleVar()
        if (!open) {
            ImGui.end()
            return false
        }

        drawToolbar(fps)

        val availableWidth = ImGui.getContentRegionAvailX()
        val availableHeight = ImGui.getContentRegionAvailY()
        if (availableWidth < 1f || availableHeight < 1f) {
            ImGui.end()
            return false
        }

        // The stage size has to be decided before the target is allocated, and
        // "match panel" means the interface is laid out against the panel itself.
        if (matchPanel) {
            stageWidth.set(availableWidth.toInt().coerceIn(1, MAX_STAGE))
            stageHeight.set(availableHeight.toInt().coerceIn(1, MAX_STAGE))
        }
        val width = stageWidth.get().coerceIn(1, MAX_STAGE)
        val height = stageHeight.get().coerceIn(1, MAX_STAGE)

        if (!target.resize(width, height)) {
            ImGuiText.disabled("Could not allocate a ${width}x$height render target.")
            ImGui.end()
            return false
        }

        if (fitToPanel || matchPanel) {
            zoom = minOf(availableWidth / width, availableHeight / height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
        handleZoomInput()

        val drawWidth = width * zoom
        val drawHeight = height * zoom
        // Centre the stage in the panel, then apply the pan. Clamping the pan to
        // the overflow keeps the stage reachable: an unclamped pan can push it off
        // screen with no visible way back.
        val slackX = maxOf(0f, drawWidth - availableWidth)
        val slackY = maxOf(0f, drawHeight - availableHeight)
        panX = panX.coerceIn(-slackX, slackX)
        panY = panY.coerceIn(-slackY, slackY)

        val originX = ImGui.getCursorScreenPosX()
        val originY = ImGui.getCursorScreenPosY()
        imageX = originX + ((availableWidth - drawWidth) / 2f + panX).let { floor(it) }
        imageY = originY + ((availableHeight - drawHeight) / 2f + panY).let { floor(it) }
        imageScale = zoom

        val drawList = ImGui.getWindowDrawList()
        if (showCheckerboard) {
            drawCheckerboard(drawList, imageX, imageY, drawWidth, drawHeight)
        }

        // An invisible button over the whole panel is what makes the panel itself
        // draggable and scrollable without ImGui stealing the events for the image.
        ImGui.setCursorScreenPos(originX, originY)
        ImGui.invisibleButton("##stage", availableWidth, availableHeight)
        val panelHovered = ImGui.isItemHovered()

        // Drawn under the input-owning invisible button; V is flipped since framebuffer rows start at the bottom.
        drawList.addImage(
            target.textureId.toLong(),
            imageX, imageY,
            imageX + drawWidth, imageY + drawHeight,
            0f, 1f,
            1f, 0f,
        )

        if (showGrid && zoom >= GRID_MIN_ZOOM) {
            drawGrid(drawList, imageX, imageY, drawWidth, drawHeight)
        }
        drawList.addRect(
            imageX - 1f, imageY - 1f,
            imageX + drawWidth + 1f, imageY + drawHeight + 1f,
            BORDER_COLOUR,
        )

        updatePointer(width, height, drawWidth, drawHeight, panelHovered)
        handlePanInput(panelHovered)

        overlay?.invoke()

        drawStatus(width, height)
        ImGui.end()
        ready = true
        return true
    }

    /** True when [render] sized the target and the interface should be drawn. */
    fun shouldRender(): Boolean = ready

    private fun drawToolbar(fps: Int) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6f, 3f)
        ImGui.dummy(0f, 1f)
        ImGui.indent(6f)

        val match = ImBoolean(matchPanel)
        if (ImGui.checkbox("Match panel", match)) {
            matchPanel = match.get()
        }
        if (!matchPanel) {
            ImGui.sameLine()
            val fit = ImBoolean(fitToPanel)
            if (ImGui.checkbox("Fit", fit)) {
                fitToPanel = fit.get()
            }
            ImGui.sameLine()
            ImGui.setNextItemWidth(70f)
            if (ImGui.inputInt("##w", stageWidth, 0)) {
                stageWidth.set(stageWidth.get().coerceIn(1, MAX_STAGE))
            }
            ImGui.sameLine()
            ImGuiText.disabled("x")
            ImGui.sameLine()
            ImGui.setNextItemWidth(70f)
            if (ImGui.inputInt("##h", stageHeight, 0)) {
                stageHeight.set(stageHeight.get().coerceIn(1, MAX_STAGE))
            }
            ImGui.sameLine()
            for ((label, size) in PRESETS) {
                if (ImGui.smallButton(label)) {
                    stageWidth.set(size.first)
                    stageHeight.set(size.second)
                    fitToPanel = false
                }
                ImGui.sameLine()
            }
        }

        ImGuiText.disabled("|")
        ImGui.sameLine()
        ImGuiText.disabled("%.0f%%".format(zoom * 100f))
        ImGui.sameLine()
        if (ImGui.smallButton("1x")) {
            setZoom(1f)
            fitToPanel = false
        }
        ImGui.sameLine()
        if (ImGui.smallButton("2x")) {
            setZoom(2f)
            fitToPanel = false
        }
        ImGui.sameLine()
        if (ImGui.smallButton("reset")) {
            resetView()
        }

        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        val grid = ImBoolean(showGrid)
        if (ImGui.checkbox("Grid", grid)) {
            showGrid = grid.get()
        }
        if (showGrid) {
            ImGui.sameLine()
            ImGui.setNextItemWidth(60f)
            val size = ImInt(gridSize)
            if (ImGui.inputInt("##grid", size, 0)) {
                gridSize = size.get().coerceIn(1, 256)
            }
        }
        ImGui.sameLine()
        val checker = ImBoolean(showCheckerboard)
        if (ImGui.checkbox("Checker", checker)) {
            showCheckerboard = checker.get()
        }

        toolbar?.let {
            ImGui.sameLine()
            ImGuiText.disabled("|")
            ImGui.sameLine()
            it()
        }

        ImGui.sameLine()
        ImGuiText.disabled("   $fps FPS")

        ImGui.unindent(6f)
        ImGui.popStyleVar()
        ImGui.separator()
    }

    /**
     * Control-scroll zooms about the cursor, which is the behaviour every graphics
     * tool has: the stage pixel under the pointer stays under the pointer.
     */
    private fun handleZoomInput() {
        if (!ImGui.isWindowHovered() || !ImGui.getIO().keyCtrl) {
            return
        }
        val wheel = ImGui.getIO().mouseWheel
        if (wheel == 0f) {
            return
        }
        val before = zoom
        setZoom(zoom * if (wheel > 0f) ZOOM_STEP else 1f / ZOOM_STEP)
        fitToPanel = false
        if (zoom == before) {
            return
        }
        // Keep the anchor fixed: the pan has to absorb the change in the distance
        // from the image origin to the cursor.
        val anchorX = ImGui.getIO().mousePosX - imageX
        val anchorY = ImGui.getIO().mousePosY - imageY
        panX -= anchorX * (zoom / before - 1f)
        panY -= anchorY * (zoom / before - 1f)
    }

    /** Middle-drag, or space-drag, pans - left-drag belongs to the interface. */
    private fun handlePanInput(panelHovered: Boolean) {
        val dragging = ImGui.isMouseDragging(ImGuiMouseButton.Middle) ||
            (ImGui.isMouseDragging(ImGuiMouseButton.Left) && ImGui.getIO().keyAlt)
        if (!dragging || !(panelHovered || ImGui.isWindowFocused())) {
            return
        }
        panX += ImGui.getIO().mouseDeltaX
        panY += ImGui.getIO().mouseDeltaY
    }

    private fun updatePointer(
        stagePixelWidth: Int,
        stagePixelHeight: Int,
        drawWidth: Float,
        drawHeight: Float,
        panelHovered: Boolean,
    ) {
        val mouseX = ImGui.getIO().mousePosX
        val mouseY = ImGui.getIO().mousePosY
        val inside = mouseX >= imageX && mouseX < imageX + drawWidth &&
            mouseY >= imageY && mouseY < imageY + drawHeight
        pointerInside = inside
        hovered = inside && panelHovered
        if (!inside) {
            pointerX = -1
            pointerY = -1
            return
        }
        pointerX = ((mouseX - imageX) / imageScale).toInt().coerceIn(0, stagePixelWidth - 1)
        pointerY = ((mouseY - imageY) / imageScale).toInt().coerceIn(0, stagePixelHeight - 1)
    }

    private fun drawStatus(width: Int, height: Int) {
        ImGui.setCursorScreenPos(
            ImGui.getWindowPosX() + 8f,
            ImGui.getWindowPosY() + ImGui.getWindowSizeY() - ImGui.getTextLineHeightWithSpacing() - 4f,
        )
        val position = if (pointerInside) "$pointerX, $pointerY" else "-"
        ImGuiText.disabled("${width}x$height   pointer $position   $status")
    }

    /**
     * A checkerboard behind the stage. Drawn in screen space at a fixed cell size
     * rather than in stage space, so it stays readable at every zoom instead of
     * turning into moire at small scales.
     */
    private fun drawCheckerboard(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        drawList.addRectFilled(x, y, x + width, y + height, CHECKER_DARK)
        val columns = (width / CHECKER_CELL).toInt() + 1
        val rows = (height / CHECKER_CELL).toInt() + 1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row + column) % 2 != 0) {
                    continue
                }
                val cellX = x + column * CHECKER_CELL
                val cellY = y + row * CHECKER_CELL
                drawList.addRectFilled(
                    cellX, cellY,
                    minOf(cellX + CHECKER_CELL, x + width),
                    minOf(cellY + CHECKER_CELL, y + height),
                    CHECKER_LIGHT,
                )
            }
        }
    }

    private fun drawGrid(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        val step = gridSize * imageScale
        if (step < 2f) {
            return
        }
        var offset = 0f
        while (offset <= width) {
            val lineX = (x + offset).roundToInt().toFloat()
            drawList.addLine(lineX, y, lineX, y + height, GRID_COLOUR)
            offset += step
        }
        offset = 0f
        while (offset <= height) {
            val lineY = (y + offset).roundToInt().toFloat()
            drawList.addLine(x, lineY, x + width, lineY, GRID_COLOUR)
            offset += step
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 512
        const val DEFAULT_HEIGHT = 334
        private const val MAX_STAGE = 4096

        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 16f
        private const val ZOOM_STEP = 1.25f
        private const val GRID_MIN_ZOOM = 0.5f

        private const val CHECKER_CELL = 8f

        /** ImGui colours are ABGR-packed, not ARGB. */
        private const val CHECKER_DARK = 0xff2b2b2bL.toInt()
        private const val CHECKER_LIGHT = 0xff333333L.toInt()
        private const val GRID_COLOUR = 0x30ffffffL.toInt()
        private const val BORDER_COLOUR = 0xff5a5a5aL.toInt()

        private val PRESETS = listOf(
            "512" to (512 to 334),
            "765" to (765 to 503),
            "1024" to (1024 to 768),
        )
    }
}
