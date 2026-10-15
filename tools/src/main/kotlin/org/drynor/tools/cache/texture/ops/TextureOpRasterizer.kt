package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.sqrt

/**
 * Type 29: Shape rasterizer.
 * Draws primitive shapes (rectangles, ellipses, lines) onto the texture.
 */
class TextureOpRasterizer : TextureOperation(0, true) {
    private var shapes: Array<RasterizerShape?>? = null

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            rasterize(monochromeImageCache.getAllPaletteData())
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val width = currentWidth
            val height = currentHeight
            val monoBuffer = Array(height) { IntArray(width) }
            rasterize(monoBuffer)

            val allData = colorImageCache.getAllPaletteData()
            for (row in 0 until height) {
                val monoRow = monoBuffer[row]
                val rgbRow = allData[row]
                val redChannel = rgbRow[0]
                val greenChannel = rgbRow[1]
                val blueChannel = rgbRow[2]
                for (x in 0 until width) {
                    val value = monoRow[x]
                    blueChannel[x] = (value and 0xff) shl 4
                    greenChannel[x] = (value and 0xff00) shr 4
                    redChannel[x] = (value and 0xff0000) shr 12
                }
            }
        }
        return output
    }

    @Suppress("UNCHECKED_CAST")
    private fun rasterize(buffer: Array<out IntArray?>) {
        val width = currentWidth
        val height = currentHeight

        // The destination and its clip bounds used to be mutable statics on this class's
        // companion, which every shape helper read directly. Two concurrent generations
        // therefore drew into each other's row buffers. They are now a value passed down
        // the call chain, so a shape can only ever touch the generation that invoked it.
        val target = RasterTarget(buffer as Array<IntArray>, 0, rowEnd, 0, columnEnd)

        if (shapes != null) {
            for (shape in shapes!!) {
                shape?.draw(target, width, height)
            }
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> {
                val count = buffer.readUnsignedByte()
                shapes = arrayOfNulls(count)
                for (i in 0 until count) {
                    val shapeType = buffer.readUnsignedByte()
                    shapes!![i] = when (shapeType) {
                        0 -> RasterizerLine.decode(buffer)
                        1 -> RasterizerBezier.decode(buffer)
                        2 -> RasterizerRectangle.decode(buffer)
                        3 -> RasterizerEllipse.decode(buffer)
                        else -> null
                    }
                }
            }
            1 -> monochrome = buffer.readUnsignedByte() == 1
        }
    }
}

/**
 * Destination rows and clip bounds for one rasterisation pass.
 *
 * Immutable and created per pass, so it can be handed to the shape helpers without any of
 * them being able to observe another generation's target.
 */
class RasterTarget(
    @JvmField val pixels: Array<IntArray>,
    @JvmField val clipLeft: Int,
    @JvmField val clipRight: Int,
    @JvmField val clipTop: Int,
    @JvmField val clipBottom: Int
)

/**
 * Base class for rasterizer shapes.
 *
 * Maps to client's Class149 hierarchy. The base class has three parameters:
 * - fillColor (anInt1743): Fill color for solid shapes, -1 if not used
 * - strokeColor (anInt1741): Stroke/line color, -1 if not used
 * - strokeWidth (anInt1742): Stroke width in pixels
 *
 * The draw dispatch depends on which colors are non-negative:
 * - fillColor >= 0 && strokeColor >= 0 -> drawFilledWithBorder
 * - fillColor >= 0 only -> drawFilled
 * - strokeColor >= 0 only -> drawOutline
 */
sealed class RasterizerShape(
    val fillColor: Int,
    val strokeColor: Int,
    val strokeWidth: Int
) {
    abstract fun drawOutline(target: RasterTarget, width: Int, height: Int)
    abstract fun drawFilled(target: RasterTarget, width: Int, height: Int)
    abstract fun drawFilledWithBorder(target: RasterTarget, width: Int, height: Int)

    fun draw(target: RasterTarget, width: Int, height: Int) {
        if (fillColor >= 0) {
            if (strokeColor >= 0) {
                drawFilledWithBorder(target, width, height)
            } else {
                drawFilled(target, width, height)
            }
        } else if (strokeColor >= 0) {
            drawOutline(target, width, height)
        }
    }
}

/**
 * Type 0: Line drawing shape.
 * Client: Class149_Sub3
 * Constructor: super(-1, color, lineWidth) -> fillColor=-1, strokeColor=color, strokeWidth=lineWidth
 * Draws a line from (x1,y1) to (x2,y2) using Bresenham's algorithm.
 *
 * Decode: 4 shorts (8 bytes) + UMedium (3 bytes) + UByte (1 byte) = 12 bytes total
 */
class RasterizerLine(
    private val x1: Int,
    private val y1: Int,
    private val x2: Int,
    private val y2: Int,
    color: Int,
    lineWidth: Int
) : RasterizerShape(-1, color, lineWidth) {
    override fun drawOutline(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sy1 = y1 * height shr 12
        val sx2 = x2 * width shr 12
        val sy2 = y2 * height shr 12
        drawLineClipped(target, sx1, sy1, sx2, sy2, strokeColor)
    }

    override fun drawFilled(target: RasterTarget, width: Int, height: Int) {
        // Not called (fillColor is always -1)
    }

    override fun drawFilledWithBorder(target: RasterTarget, width: Int, height: Int) {
        // Not called (fillColor is always -1)
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerLine {
            val x1 = buffer.readShort()
            val y1 = buffer.readShort()
            val x2 = buffer.readShort()
            val y2 = buffer.readShort()
            val color = buffer.readUnsignedMedium()
            val lineWidth = buffer.readUnsignedByte()
            return RasterizerLine(x1, y1, x2, y2, color, lineWidth)
        }

        /**
         * Clipped line drawing matching the client's method6159.
         */
        fun drawLineClipped(target: RasterTarget, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            val deltaX = x2 - x1
            val deltaY = y2 - y1
            if (deltaX == 0) {
                if (deltaY != 0) {
                    drawVerticalLineClipped(target, x1, y1, y2, color)
                }
                return
            }
            if (deltaY == 0) {
                drawHorizontalLine(target, x1, x2, y1, color)
                return
            }

            val slope = (deltaY shl 12) / deltaX
            val intercept = y1 - (x1 * slope shr 12)

            val startX: Int
            val startY: Int
            if (x1 < target.clipLeft) {
                startY = (slope * target.clipLeft shr 12) + intercept
                startX = target.clipLeft
            } else if (x1 > target.clipRight) {
                startY = (slope * target.clipRight shr 12) + intercept
                startX = target.clipRight
            } else {
                startY = y1
                startX = x1
            }

            val endX: Int
            val endY: Int
            if (x2 < target.clipLeft) {
                endY = (slope * target.clipLeft shr 12) + intercept
                endX = target.clipLeft
            } else if (x2 > target.clipRight) {
                endY = (slope * target.clipRight shr 12) + intercept
                endX = target.clipRight
            } else {
                endY = y2
                endX = x2
            }

            var clampedStartX = startX
            var clampedStartY = startY
            if (clampedStartY < target.clipTop) {
                clampedStartY = target.clipTop
                clampedStartX = (target.clipTop - intercept shl 12) / slope
            } else if (clampedStartY > target.clipBottom) {
                clampedStartY = target.clipBottom
                clampedStartX = (target.clipBottom - intercept shl 12) / slope
            }

            var clampedEndX = endX
            var clampedEndY = endY
            if (clampedEndY < target.clipTop) {
                clampedEndY = target.clipTop
                clampedEndX = (target.clipTop - intercept shl 12) / slope
            } else if (clampedEndY > target.clipBottom) {
                clampedEndY = target.clipBottom
                clampedEndX = (target.clipBottom - intercept shl 12) / slope
            }

            drawBresenhamLine(target, clampedStartX, clampedStartY, clampedEndX, clampedEndY, color)
        }

        /**
         * Bresenham's line algorithm matching the client's Class149_Sub2.method11220.
         */
        private fun drawBresenhamLine(target: RasterTarget, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            var ix1 = x1
            var iy1 = y1
            var ix2 = x2
            var iy2 = y2

            val dy = iy2 - iy1
            val dx = ix2 - ix1

            if (dx == 0) {
                if (dy != 0) {
                    drawVerticalLine(target, ix1, iy1, iy2, color)
                }
                return
            }
            if (dy == 0) {
                drawHorizontalLineUnchecked(target, ix1, ix2, iy1, color)
                return
            }

            var absDy = if (dy < 0) -dy else dy
            var absDx = if (dx < 0) -dx else dx
            val steep = absDy > absDx

            if (steep) {
                var tmp = ix1; ix1 = iy1; iy1 = tmp
                tmp = ix2; ix2 = iy2; iy2 = tmp
            }

            if (ix1 > ix2) {
                var tmp = ix1; ix1 = ix2; ix2 = tmp
                tmp = iy1; iy1 = iy2; iy2 = tmp
            }

            var y = iy1
            val xLen = ix2 - ix1
            var yLen = iy2 - iy1
            var err = -(xLen shr 1)
            val yStep = if (iy1 < iy2) 1 else -1
            if (yLen < 0) yLen = -yLen

            if (steep) {
                for (x in ix1..ix2) {
                    target.pixels[x][y] = color
                    err += yLen
                    if (err > 0) {
                        y += yStep
                        err -= xLen
                    }
                }
            } else {
                for (x in ix1..ix2) {
                    target.pixels[y][x] = color
                    err += yLen
                    if (err > 0) {
                        y += yStep
                        err -= xLen
                    }
                }
            }
        }

        private fun drawVerticalLine(target: RasterTarget, x: Int, y1: Int, y2: Int, color: Int) {
            if (y1 > y2) {
                var y = y2
                while (y < y1) {
                    target.pixels[y][x] = color
                    y++
                }
            } else {
                var y = y1
                while (y < y2) {
                    target.pixels[y][x] = color
                    y++
                }
            }
        }

        private fun drawVerticalLineClipped(target: RasterTarget, x: Int, y1: Int, y2: Int, color: Int) {
            if (x < target.clipLeft || x > target.clipRight) return
            val clampedY1 = y1.coerceIn(target.clipTop, target.clipBottom)
            val clampedY2 = y2.coerceIn(target.clipTop, target.clipBottom)
            drawVerticalLine(target, x, clampedY1, clampedY2, color)
        }

        private fun drawHorizontalLine(target: RasterTarget, x1: Int, x2: Int, y: Int, color: Int) {
            if (y < target.clipTop || y > target.clipBottom) return
            val startX = minOf(x1, x2).coerceIn(target.clipLeft, target.clipRight)
            val endX = maxOf(x1, x2).coerceIn(target.clipLeft, target.clipRight)
            val row = target.pixels[y]
            for (px in startX..endX) {
                row[px] = color
            }
        }

        private fun drawHorizontalLineUnchecked(target: RasterTarget, x1: Int, x2: Int, y: Int, color: Int) {
            val row = target.pixels[y]
            if (x1 <= x2) {
                for (px in x1..x2) row[px] = color
            } else {
                for (px in x2..x1) row[px] = color
            }
        }
    }
}

/**
 * Type 1: Bezier curve shape.
 * Client: Class149_Sub2
 * Constructor: super(-1, color, lineWidth) -> fillColor=-1, strokeColor=color, strokeWidth=lineWidth
 * Draws a cubic bezier curve with 4 control points.
 *
 * Decode: 8 shorts (16 bytes) + UMedium (3 bytes) + UByte (1 byte) = 20 bytes total
 */
class RasterizerBezier(
    private val x1: Int, private val y1: Int,
    private val cx1: Int, private val cy1: Int,
    private val cx2: Int, private val cy2: Int,
    private val x2: Int, private val y2: Int,
    color: Int,
    lineWidth: Int
) : RasterizerShape(-1, color, lineWidth) {
    override fun drawOutline(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sy1 = y1 * height shr 12
        val scx1 = cx1 * width shr 12
        val scy1 = cy1 * height shr 12
        val scx2 = cx2 * width shr 12
        val scy2 = cy2 * height shr 12
        val sx2 = x2 * width shr 12
        val sy2 = y2 * height shr 12
        drawBezierCurve(target, sx1, sy1, scx1, scy1, scx2, scy2, sx2, sy2, strokeColor)
    }

    override fun drawFilled(target: RasterTarget, width: Int, height: Int) {
        // Not called (fillColor is always -1)
    }

    override fun drawFilledWithBorder(target: RasterTarget, width: Int, height: Int) {
        // Not called (fillColor is always -1)
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerBezier {
            val x1 = buffer.readShort()
            val y1 = buffer.readShort()
            val cx1 = buffer.readShort()
            val cy1 = buffer.readShort()
            val cx2 = buffer.readShort()
            val cy2 = buffer.readShort()
            val x2 = buffer.readShort()
            val y2 = buffer.readShort()
            val color = buffer.readUnsignedMedium()
            val lineWidth = buffer.readUnsignedByte()
            return RasterizerBezier(x1, y1, cx1, cy1, cx2, cy2, x2, y2, color, lineWidth)
        }

        /**
         * Draw a cubic bezier curve by subdividing into line segments.
         * Matches client's Class149_Sub2.method12399/method12117/method4779.
         */
        private fun drawBezierCurve(target: RasterTarget,
            x1: Int, y1: Int, cx1: Int, cy1: Int,
            cx2: Int, cy2: Int, x2: Int, y2: Int, color: Int
        ) {
            // If control points coincide with endpoints, draw a simple line
            if (x1 == cx1 && cy1 == y1 && cx2 == x2 && cy2 == y2) {
                RasterizerLine.drawLineClipped(target, x1, y1, x2, y2, color)
                return
            }

            var prevX = x1
            var prevY = y1
            val ax = x1 * 3
            val ay = y1 * 3
            val bx = cx1 * 3
            val by = cy1 * 3
            val cx = cx2 * 3
            val cy = cy2 * 3
            val dx = x2 - cx + bx - x1
            val dy = by + (y2 - cy) - y1
            val ex = ax + (cx - bx - bx)
            val ey = ay + (cy - by - by)
            val fx = bx - ax
            val fy = by - ay

            var t = 128
            while (t <= 4096) {
                val t2 = t * t shr 12
                val t3 = t2 * t shr 12
                val px = x1 + (dx * t3 + ex * t2 + fx * t shr 12)
                val py = y1 + (dy * t3 + ey * t2 + fy * t shr 12)
                RasterizerLine.drawLineClipped(target, prevX, prevY, px, py, color)
                prevX = px
                prevY = py
                t += 128
            }
        }
    }
}

/**
 * Type 2: Rectangle with optional border.
 * Client: Class149_Sub4
 * Constructor: super(fillColor, strokeColor, strokeWidth)
 * Draws a rectangle, optionally with a border of specified width.
 *
 * Decode: 4 shorts (8 bytes) + UMedium (3 bytes) + UMedium (3 bytes) + UByte (1 byte) = 15 bytes total
 */
class RasterizerRectangle(
    private val x1: Int,
    private val y1: Int,
    private val x2: Int,
    private val y2: Int,
    fill: Int,
    stroke: Int,
    strokeW: Int
) : RasterizerShape(fill, stroke, strokeW) {
    override fun drawOutline(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sx2 = x2 * width shr 12
        val sy1 = y1 * height shr 12
        val sy2 = y2 * height shr 12
        drawRectOutline(target, sx1, sx2, sy1, sy2, strokeColor, strokeWidth)
    }

    override fun drawFilled(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sx2 = x2 * width shr 12
        val sy1 = y1 * height shr 12
        val sy2 = y2 * height shr 12
        drawFilledRect(target, sx1, sx2, sy1, sy2, fillColor)
    }

    override fun drawFilledWithBorder(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sx2 = x2 * width shr 12
        val sy1 = y1 * height shr 12
        val sy2 = y2 * height shr 12
        drawFilledRectWithBorder(target, sx1, sx2, sy1, sy2, fillColor, strokeColor, strokeWidth)
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerRectangle {
            val x1 = buffer.readShort()
            val y1 = buffer.readShort()
            val x2 = buffer.readShort()
            val y2 = buffer.readShort()
            val fill = buffer.readUnsignedMedium()
            val stroke = buffer.readUnsignedMedium()
            val strokeW = buffer.readUnsignedByte()
            return RasterizerRectangle(x1, y1, x2, y2, fill, stroke, strokeW)
        }

        private fun drawRectOutline(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, color: Int, borderWidth: Int) {
            if (x1 >= target.clipLeft && x2 <= target.clipRight &&
                y1 >= target.clipTop && y2 <= target.clipBottom) {
                if (borderWidth == 1) {
                    drawRectOutlineNoClip(target, x1, x2, y1, y2, color)
                } else {
                    drawThickBorder(target, x1, x2, y1, y2, color, borderWidth)
                }
            } else if (borderWidth == 1) {
                drawRectOutlineClipped(target, x1, x2, y1, y2, color)
            }
        }

        private fun drawRectOutlineNoClip(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, color: Int) {
            val buf = target.pixels
            // Top and bottom rows
            val topRow = buf[y1]
            val botRow = buf[y2]
            for (x in x1..x2) {
                topRow[x] = color
                botRow[x] = color
            }
            // Left and right columns
            for (y in y1..y2) {
                buf[y][x1] = color
                buf[y][x2] = color
            }
        }

        private fun drawRectOutlineClipped(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, color: Int) {
            val cX1 = x1.coerceIn(target.clipLeft, target.clipRight)
            val cX2 = x2.coerceIn(target.clipLeft, target.clipRight)
            val cY1 = y1.coerceIn(target.clipTop, target.clipBottom)
            val cY2 = y2.coerceIn(target.clipTop, target.clipBottom)
            val buf = target.pixels
            // Horizontal edges
            if (y1 in target.clipTop..target.clipBottom) {
                for (x in cX1..cX2) buf[y1][x] = color
            }
            if (y2 in target.clipTop..target.clipBottom) {
                for (x in cX1..cX2) buf[y2][x] = color
            }
            // Vertical edges
            if (x1 in target.clipLeft..target.clipRight) {
                for (y in cY1..cY2) buf[y][x1] = color
            }
            if (x2 in target.clipLeft..target.clipRight) {
                for (y in cY1..cY2) buf[y][x2] = color
            }
        }

        private fun drawThickBorder(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, color: Int, borderWidth: Int) {
            val buf = target.pixels
            val bw = borderWidth
            // Top border rows
            for (y in y1 until (y1 + bw).coerceAtMost(y2 + 1)) {
                for (x in x1..x2) buf[y][x] = color
            }
            // Bottom border rows
            for (y in (y2 - bw + 1).coerceAtLeast(y1) .. y2) {
                for (x in x1..x2) buf[y][x] = color
            }
            // Left and right border columns (middle rows)
            for (y in (y1 + bw) until (y2 - bw + 1)) {
                for (x in x1 until (x1 + bw).coerceAtMost(x2 + 1)) buf[y][x] = color
                for (x in (x2 - bw + 1).coerceAtLeast(x1) .. x2) buf[y][x] = color
            }
        }

        private fun drawFilledRect(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, color: Int) {
            val cX1 = x1.coerceIn(target.clipLeft, target.clipRight)
            val cX2 = x2.coerceIn(target.clipLeft, target.clipRight)
            val cY1 = y1.coerceIn(target.clipTop, target.clipBottom)
            val cY2 = y2.coerceIn(target.clipTop, target.clipBottom)
            val buf = target.pixels
            for (y in cY1..cY2) {
                val row = buf[y]
                for (x in cX1..cX2) row[x] = color
            }
        }

        private fun drawFilledRectWithBorder(target: RasterTarget, x1: Int, x2: Int, y1: Int, y2: Int, fillColor: Int, strokeColor: Int, borderWidth: Int) {
            val cX1 = x1.coerceIn(target.clipLeft, target.clipRight)
            val cX2 = x2.coerceIn(target.clipLeft, target.clipRight)
            val cY1 = y1.coerceIn(target.clipTop, target.clipBottom)
            val cY2 = y2.coerceIn(target.clipTop, target.clipBottom)
            val buf = target.pixels
            // Fill entire rectangle with fill color, then draw border on top
            for (y in cY1..cY2) {
                val row = buf[y]
                for (x in cX1..cX2) row[x] = fillColor
            }
            // Draw border
            drawThickBorder(target, cX1, cX2, cY1, cY2, strokeColor, borderWidth)
        }
    }
}

/**
 * Type 3: Ellipse with optional gradient fill.
 * Client: Class149_Sub1
 * Constructor: super(fillColor, strokeColor, strokeWidth)
 * Draws an ellipse, optionally filled with gradient.
 *
 * Decode: 4 shorts (8 bytes) + UMedium (3 bytes) + UMedium (3 bytes) + UByte (1 byte) = 15 bytes total
 */
class RasterizerEllipse(
    private val x1: Int,
    private val y1: Int,
    private val x2: Int,
    private val y2: Int,
    fill: Int,
    stroke: Int,
    strokeW: Int
) : RasterizerShape(fill, stroke, strokeW) {
    override fun drawOutline(target: RasterTarget, width: Int, height: Int) {
        // Not called (method2561 is empty in client's Class149_Sub1)
    }

    override fun drawFilled(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sy1 = y1 * height shr 12
        val sx2 = x2 * width shr 12
        val sy2 = y2 * height shr 12
        fillEllipse(target, sx1, sy1, sx2, sy2, fillColor)
    }

    override fun drawFilledWithBorder(target: RasterTarget, width: Int, height: Int) {
        val sx1 = x1 * width shr 12
        val sy1 = y1 * height shr 12
        val sx2 = x2 * width shr 12
        val sy2 = y2 * height shr 12
        fillEllipseWithBorder(target, sx1, sy1, sx2, sy2, fillColor, strokeColor, strokeWidth)
    }

    companion object {
        fun decode(buffer: BufferReader): RasterizerEllipse {
            val x1 = buffer.readShort()
            val y1 = buffer.readShort()
            val x2 = buffer.readShort()
            val y2 = buffer.readShort()
            val fill = buffer.readUnsignedMedium()
            val stroke = buffer.readUnsignedMedium()
            val strokeW = buffer.readUnsignedByte()
            return RasterizerEllipse(x1, y1, x2, y2, fill, stroke, strokeW)
        }

        /**
         * Fill an ellipse. Matches client's Class149_Sub1.method2556 which calls
         * ArrayUtils.method14584 (midpoint ellipse fill algorithm).
         */
        private fun fillEllipse(target: RasterTarget, cx: Int, cy: Int, rx: Int, ry: Int, color: Int) {
            if (rx <= 0 || ry <= 0) return

            val rxSq = rx * rx
            val rySq = ry * ry
            val buf = target.pixels

            for (dy in -ry..ry) {
                val py = cy + dy
                if (py < target.clipTop || py > target.clipBottom) continue

                val row = buf[py]
                val dySq = dy * dy
                val xRange = sqrt((1.0 - dySq.toDouble() / rySq) * rxSq).toInt()

                val startX = (cx - xRange).coerceIn(target.clipLeft, target.clipRight)
                val endX = (cx + xRange).coerceIn(target.clipLeft, target.clipRight)
                for (px in startX..endX) {
                    row[px] = color
                }
            }
        }

        /**
         * Fill an ellipse with a border ring. Matches client's Class149_Sub1.method2557
         * which calls method5316 (double-ellipse with border width).
         */
        private fun fillEllipseWithBorder(target: RasterTarget, cx: Int, cy: Int, rx: Int, ry: Int, fillColor: Int, strokeColor: Int, borderWidth: Int) {
            if (rx <= 0 || ry <= 0) return

            val innerRx = rx - borderWidth
            val innerRy = ry - borderWidth
            val rxSq = rx * rx
            val rySq = ry * ry
            val buf = target.pixels

            for (dy in -ry..ry) {
                val py = cy + dy
                if (py < target.clipTop || py > target.clipBottom) continue

                val row = buf[py]
                val dySq = dy * dy
                val outerXRange = sqrt((1.0 - dySq.toDouble() / rySq) * rxSq).toInt()

                val innerXRange = if (innerRx > 0 && innerRy > 0) {
                    val innerRxSq = innerRx * innerRx
                    val innerRySq = innerRy * innerRy
                    val ratio = 1.0 - dySq.toDouble() / innerRySq
                    if (ratio > 0) sqrt(ratio * innerRxSq).toInt() else 0
                } else 0

                // Outer ring (stroke)
                val leftOuter = (cx - outerXRange).coerceIn(target.clipLeft, target.clipRight)
                val leftInner = (cx - innerXRange).coerceIn(target.clipLeft, target.clipRight)
                val rightInner = (cx + innerXRange).coerceIn(target.clipLeft, target.clipRight)
                val rightOuter = (cx + outerXRange).coerceIn(target.clipLeft, target.clipRight)

                for (px in leftOuter until leftInner) row[px] = strokeColor
                for (px in leftInner..rightInner) row[px] = fillColor
                for (px in (rightInner + 1)..rightOuter) row[px] = strokeColor
            }
        }
    }
}
