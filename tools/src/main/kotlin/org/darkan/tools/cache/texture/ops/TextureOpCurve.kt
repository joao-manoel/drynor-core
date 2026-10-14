package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import org.darkan.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 8: Applies curve interpolation/tone mapping.
 * Maps input values through a configurable curve defined by control points.
 */
class TextureOpCurve : TextureOperation(1, true) {
    private var curvePoints: Array<IntArray>? = null
    private var startExtension: IntArray? = null
    private var endExtension: IntArray? = null
    private var interpolationType: Int = 0
    private val curveLookup: ShortArray = ShortArray(257)

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val input = getChildMonochromeOutput(0, y)
            for (x in 0 until currentWidth) {
                var index = input[x] shr 4
                if (index < 0) index = 0
                if (index > 256) index = 256
                output[x] = curveLookup[index].toInt()
            }
        }
        return output
    }

    private fun getPointValue(index: Int): IntArray {
        return when {
            index < 0 -> startExtension!!
            index >= curvePoints!!.size -> endExtension!!
            else -> curvePoints!![index]
        }
    }

    override fun postDecode() {
        if (curvePoints == null) {
            curvePoints = arrayOf(intArrayOf(0, 0), intArrayOf(4096, 4096))
        }
        if (curvePoints!!.size < 2) {
            throw RuntimeException("Curve requires at least 2 points")
        }

        if (interpolationType == 2) {
            computeExtensions()
        }

        TextureRaster.calculateTable()
        computeCurveLookup()
    }

    private fun computeExtensions() {
        val first = curvePoints!![0]
        val second = curvePoints!![1]
        val secondLast = curvePoints!![curvePoints!!.size - 2]
        val last = curvePoints!![curvePoints!!.size - 1]
        startExtension = intArrayOf(
            first[0] - second[0] + first[0],
            first[1] - second[1] + first[1]
        )
        endExtension = intArrayOf(
            secondLast[0] - last[0] + secondLast[0],
            secondLast[1] - last[1] + secondLast[1]
        )
    }

    private fun computeCurveLookup() {
        when (interpolationType) {
            1 -> computeCosineCurve()
            2 -> computeCubicCurve()
            else -> computeLinearCurve()
        }
    }

    private fun computeLinearCurve() {
        for (i in 0..256) {
            val x = i shl 4
            var segmentIndex = 1
            while (segmentIndex < curvePoints!!.size - 1 && curvePoints!![segmentIndex][0] <= x) {
                segmentIndex++
            }

            val p0 = curvePoints!![segmentIndex - 1]
            val p1 = curvePoints!![segmentIndex]
            val t = ((x - p0[0]) shl 12) / (p1[0] - p0[0])
            val invT = 4096 - t
            var result = (t * p1[1] + invT * p0[1]) shr 12

            if (result <= -32768) result = -32767
            if (result >= 32768) result = 32767
            curveLookup[i] = result.toShort()
        }
    }

    private fun computeCosineCurve() {
        for (i in 0..256) {
            val x = i shl 4
            var segmentIndex = 1
            while (segmentIndex < curvePoints!!.size - 1 && curvePoints!![segmentIndex][0] <= x) {
                segmentIndex++
            }

            val p0 = curvePoints!![segmentIndex - 1]
            val p1 = curvePoints!![segmentIndex]
            val t = ((x - p0[0]) shl 12) / (p1[0] - p0[0])
            val cosT = (4096 - TextureRaster.PALETTE_COS[(t shr 5) and 0xff]) shr 1
            val invCosT = 4096 - cosT
            var result = (cosT * p1[1] + invCosT * p0[1]) shr 12

            if (result <= -32768) result = -32767
            if (result >= 32768) result = 32767
            curveLookup[i] = result.toShort()
        }
    }

    private fun computeCubicCurve() {
        for (i in 0..256) {
            val x = i shl 4
            var segmentIndex = 1
            while (segmentIndex < curvePoints!!.size - 1 && curvePoints!![segmentIndex][0] <= x) {
                segmentIndex++
            }

            val p0 = curvePoints!![segmentIndex - 1]
            val p1 = curvePoints!![segmentIndex]
            val y0 = getPointValue(segmentIndex - 2)[1]
            val y1 = p0[1]
            val y2 = p1[1]
            val y3 = getPointValue(segmentIndex + 1)[1]

            val t = ((x - p0[0]) shl 12) / (p1[0] - p0[0])
            val t2 = t * t shr 12
            val a = y1 + (y3 - y2 - y0)
            val b = y0 - y1 - a
            val c = y2 - y0
            val t3Factor = t2 * (a * t shr 12) shr 12
            val t2Factor = b * t2 shr 12
            val t1Factor = t * c shr 12
            var result = t3Factor + t2Factor + t1Factor + y1

            if (result <= -32768) result = -32767
            if (result >= 32768) result = 32767
            curveLookup[i] = result.toShort()
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            interpolationType = buffer.readUnsignedByte()
            curvePoints = Array(buffer.readUnsignedByte()) { IntArray(2) }
            for (i in curvePoints!!.indices) {
                curvePoints!![i][0] = buffer.readShort() and 0xFFFF
                curvePoints!![i][1] = buffer.readShort() and 0xFFFF
            }
        }
    }
}
