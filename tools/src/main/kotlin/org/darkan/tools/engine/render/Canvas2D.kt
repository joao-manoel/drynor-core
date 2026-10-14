package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glScissor
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL20.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Immediate-mode 2D quad batcher for screen-space (UI) drawing.
 *
 * Coordinates are in pixels with the origin at the top-left of the window and
 * +Y running down, matching the RS software toolkit that the interface renderer
 * is modelled on. Quads are accumulated into a single dynamic VBO and flushed
 * whenever the bound texture or the scissor rectangle changes, so a whole
 * interface tree usually costs a handful of draw calls.
 *
 * Clipping uses `glScissor`, which is an exact match for the client's
 * rectangular `setClipping` regions.
 */
class Canvas2D(
    vertexSource: String,
    fragmentSource: String,
    private val maxQuads: Int = 8192
) {
    private val shader = ShaderProgram.createWithUniforms(
        vertexSource, fragmentSource,
        "projection", "uiTexture", "useTexture"
    )

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private val vertices: FloatBuffer = MemoryUtil.memAllocFloat(maxQuads * QUAD_FLOATS)

    private val projection = Matrix4f()
    private val clipStack = ArrayList<IntArray>()

    private var quadCount = 0

    /** Currently bound GL texture, or 0 when drawing untextured geometry. */
    private var boundTexture = 0
    private var currentClip = intArrayOf(0, 0, 0, 0)

    private var screenWidth = 0
    private var screenHeight = 0

    /** Number of draw calls issued since the last [begin]. Useful for profiling. */
    var drawCalls: Int = 0
        private set

    init {
        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        vboId = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vboId)
        glBufferData(GL_ARRAY_BUFFER, (maxQuads * QUAD_FLOATS * 4).toLong(), GL_DYNAMIC_DRAW)

        val stride = VERTEX_FLOATS * 4
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 8L)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 16L)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    /**
     * Begins a UI pass covering the whole window. Depth testing is disabled and
     * alpha blending enabled for the duration of the pass; [end] restores them.
     */
    fun begin(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        drawCalls = 0
        quadCount = 0
        boundTexture = 0
        vertices.clear()

        projection.identity().ortho(0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_SCISSOR_TEST)

        clipStack.clear()
        currentClip = intArrayOf(0, 0, width, height)
        applyScissor()

        shader.bind()
        shader.setUniform("projection", projection)
        shader.setUniform("uiTexture", 0)
        shader.setUniform("useTexture", false)
    }

    /**
     * Re-applies the pass's GL state after something else has drawn in the
     * middle of it - a 3D model rendered into a component viewport, say, which
     * rebinds shaders and moves the viewport and scissor box. Flush first.
     */
    fun restoreState() {
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_SCISSOR_TEST)
        applyScissor()
        boundTexture = 0
        shader.bind()
        shader.setUniform("projection", projection)
        shader.setUniform("uiTexture", 0)
        shader.setUniform("useTexture", false)
    }

    /** The clip rectangle currently in force, as left, top, right, bottom. */
    fun clip(): IntArray = currentClip.copyOf()

    /** Flushes any pending geometry and restores the default 3D render state. */
    fun end() {
        flush()
        shader.unbind()
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
        clipStack.clear()
    }

    /**
     * Intersects the current clip rectangle with the given bounds and pushes the
     * result. Returns false when the intersection is empty, in which case the
     * caller should skip drawing entirely - but [popClip] must still be called.
     */
    fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        clipStack.add(currentClip)
        val clipped = intArrayOf(
            maxOf(x0, currentClip[0]),
            maxOf(y0, currentClip[1]),
            minOf(x1, currentClip[2]),
            minOf(y1, currentClip[3])
        )
        if (!clipped.contentEquals(currentClip)) {
            flush()
            currentClip = clipped
            applyScissor()
        }
        return currentClip[0] < currentClip[2] && currentClip[1] < currentClip[3]
    }

    fun popClip() {
        if (clipStack.isEmpty()) {
            return
        }
        val restored = clipStack.removeAt(clipStack.size - 1)
        if (!restored.contentEquals(currentClip)) {
            flush()
            currentClip = restored
            applyScissor()
        }
    }

    /** Fills the axis-aligned rectangle [x, x + width) x [y, y + height). */
    fun fillRect(x: Int, y: Int, width: Int, height: Int, argb: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        useTexture(0)
        val left = x.toFloat()
        val top = y.toFloat()
        val right = (x + width).toFloat()
        val bottom = (y + height).toFloat()
        quad(left, top, right, top, right, bottom, left, bottom, 0f, 0f, 1f, 1f, argb)
    }

    /**
     * Draws a rectangle outline inside the component bounds, matching the
     * client's `drawRectangleOutline` which strokes the border on the inside.
     */
    fun drawRectOutline(x: Int, y: Int, width: Int, height: Int, argb: Int, thickness: Int = 1) {
        if (width <= 0 || height <= 0) {
            return
        }
        val stroke = thickness.coerceAtLeast(1).coerceAtMost(minOf(width, height))
        fillRect(x, y, width, stroke, argb)
        fillRect(x, y + height - stroke, width, stroke, argb)
        fillRect(x, y + stroke, stroke, height - stroke * 2, argb)
        fillRect(x + width - stroke, y + stroke, stroke, height - stroke * 2, argb)
    }

    /**
     * Draws a line between two points as a thickness-wide quad, centred on the
     * line. The end point is inclusive: the client's line rasteriser covers
     * (x1, y1) itself, so the quad is extended one pixel along the direction,
     * the same correction the client's GL toolkit applies.
     *
     * The client rasterises thin lines with Bresenham; for the axis-aligned and
     * diagonal cases interface components use, the quad is pixel-equivalent.
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int, thickness: Int = 1) {
        val stroke = thickness.coerceAtLeast(1)
        val dx = (x1 - x0).toFloat()
        val dy = (y1 - y0).toFloat()
        val length = hypot(dx, dy)
        if (length <= 0f) {
            fillRect(x0, y0, stroke, stroke, argb)
            return
        }
        useTexture(0)
        val dirX = dx / length
        val dirY = dy / length
        val endX = x1 + dirX
        val endY = y1 + dirY
        // Perpendicular offset of half the stroke width on each side of the line.
        val offsetX = -dirY * stroke * 0.5f
        val offsetY = dirX * stroke * 0.5f
        quad(
            x0 + offsetX, y0 + offsetY,
            endX + offsetX, endY + offsetY,
            endX - offsetX, endY - offsetY,
            x0 - offsetX, y0 - offsetY,
            0f, 0f, 1f, 1f, argb
        )
    }

    /**
     * Draws a texture stretched over the given rectangle, modulated by [argb].
     * The UV rectangle allows drawing a sub-region, e.g. a glyph from an atlas.
     */
    fun drawTexture(
        textureId: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        argb: Int = -1,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 1f,
        v1: Float = 1f
    ) {
        if (textureId == 0 || width <= 0f || height <= 0f) {
            return
        }
        useTexture(textureId)
        quad(
            x, y,
            x + width, y,
            x + width, y + height,
            x, y + height,
            u0, v0, u1, v1, argb
        )
    }

    /**
     * Draws a texture rotated about its centre. [angle] is in radians, clockwise
     * on screen (matching the client's 2D graphic angle, which increases clockwise
     * because +Y runs down).
     */
    fun drawTextureRotated(
        textureId: Int,
        centreX: Float,
        centreY: Float,
        width: Float,
        height: Float,
        angle: Float,
        argb: Int = -1,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 1f,
        v1: Float = 1f
    ) {
        if (textureId == 0 || width <= 0f || height <= 0f) {
            return
        }
        useTexture(textureId)
        val halfWidth = width * 0.5f
        val halfHeight = height * 0.5f
        val sin = sin(angle)
        val cos = cos(angle)

        fun rotatedX(cornerX: Float, cornerY: Float) = centreX + cornerX * cos - cornerY * sin
        fun rotatedY(cornerX: Float, cornerY: Float) = centreY + cornerX * sin + cornerY * cos

        quad(
            rotatedX(-halfWidth, -halfHeight), rotatedY(-halfWidth, -halfHeight),
            rotatedX(halfWidth, -halfHeight), rotatedY(halfWidth, -halfHeight),
            rotatedX(halfWidth, halfHeight), rotatedY(halfWidth, halfHeight),
            rotatedX(-halfWidth, halfHeight), rotatedY(-halfWidth, halfHeight),
            u0, v0, u1, v1, argb
        )
    }

    /** Submits the pending batch. Called automatically on state changes. */
    fun flush() {
        if (quadCount == 0) {
            return
        }
        vertices.flip()

        glBindVertexArray(vaoId)
        glBindBuffer(GL_ARRAY_BUFFER, vboId)
        glBufferSubData(GL_ARRAY_BUFFER, 0L, vertices)

        shader.setUniform("useTexture", boundTexture != 0)
        if (boundTexture != 0) {
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, boundTexture)
        }

        glDrawArrays(GL_TRIANGLES, 0, quadCount * VERTS_PER_QUAD)
        drawCalls++

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        vertices.clear()
        quadCount = 0
    }

    fun cleanup() {
        shader.cleanup()
        glDeleteBuffers(vboId)
        glDeleteVertexArrays(vaoId)
        MemoryUtil.memFree(vertices)
    }

    private fun useTexture(textureId: Int) {
        if (textureId != boundTexture) {
            flush()
            boundTexture = textureId
        }
    }

    private fun applyScissor() {
        val width = (currentClip[2] - currentClip[0]).coerceAtLeast(0)
        val height = (currentClip[3] - currentClip[1]).coerceAtLeast(0)
        // glScissor is bottom-left origin, our coordinates are top-left origin.
        glScissor(currentClip[0], screenHeight - currentClip[1] - height, width, height)
    }

    @Suppress("LongParameterList")
    private fun quad(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        argb: Int
    ) {
        if (quadCount >= maxQuads) {
            flush()
        }
        val alpha = ((argb ushr 24) and 0xff) / 255f
        val red = ((argb shr 16) and 0xff) / 255f
        val green = ((argb shr 8) and 0xff) / 255f
        val blue = (argb and 0xff) / 255f

        vertex(x0, y0, u0, v0, red, green, blue, alpha)
        vertex(x1, y1, u1, v0, red, green, blue, alpha)
        vertex(x2, y2, u1, v1, red, green, blue, alpha)

        vertex(x0, y0, u0, v0, red, green, blue, alpha)
        vertex(x2, y2, u1, v1, red, green, blue, alpha)
        vertex(x3, y3, u0, v1, red, green, blue, alpha)

        quadCount++
    }

    private fun vertex(
        x: Float, y: Float,
        u: Float, v: Float,
        red: Float, green: Float, blue: Float, alpha: Float
    ) {
        vertices.put(x).put(y)
        vertices.put(u).put(v)
        vertices.put(red).put(green).put(blue).put(alpha)
    }

    companion object {
        private const val VERTEX_FLOATS = 8
        private const val VERTS_PER_QUAD = 6
        private const val QUAD_FLOATS = VERTEX_FLOATS * VERTS_PER_QUAD
    }
}
