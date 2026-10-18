package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Type 23: Creates a kaleidoscope effect by mirroring the input texture.
 */
class TextureOpKaleidoscope : TextureOperation(1, false) {

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]
            for (x in 0 until currentWidth) {
                val (mirroredX, mirroredY) = calculateKaleidoscopeCoordinates(x, y)
                val input = getChildColorOutput(0, mirroredY)
                outputRed[x] = input[0][mirroredX]
                outputGreen[x] = input[1][mirroredX]
                outputBlue[x] = input[2][mirroredX]
            }
        }
        return output
    }

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            for (x in 0 until currentWidth) {
                val (mirroredX, mirroredY) = calculateKaleidoscopeCoordinates(x, y)
                val input = getChildMonochromeOutput(0, mirroredY)
                output[x] = input[mirroredX]
            }
        }
        return output
    }

    private fun calculateKaleidoscopeCoordinates(x: Int, y: Int): Pair<Int, Int> {
        val horizontalPos = horizontalTable[x]
        val verticalPos = verticalTable[y]
        val angle = atan2((horizontalPos - 2048).toDouble(), (verticalPos - 2048).toDouble()).toFloat()

        var mirroredX: Int
        var mirroredY: Int

        when {
            angle >= -PI && angle <= -2.356194490192345 -> {
                mirroredX = x
                mirroredY = y
            }
            angle <= -1.5707963267948966 && angle >= -2.356194490192345 -> {
                mirroredX = y
                mirroredY = x
            }
            angle <= -0.7853981633974483 && angle >= -1.5707963267948966 -> {
                mirroredX = currentWidth - y
                mirroredY = x
            }
            angle <= 0.0f && angle >= -0.7853981633974483 -> {
                mirroredX = x
                mirroredY = currentHeight - y
            }
            angle >= 0.0f && angle <= 0.7853981633974483 -> {
                mirroredX = currentWidth - x
                mirroredY = currentHeight - y
            }
            angle >= 0.7853981633974483 && angle <= 1.5707963267948966 -> {
                mirroredX = currentWidth - y
                mirroredY = currentHeight - x
            }
            angle >= 1.5707963267948966 && angle <= 2.356194490192345 -> {
                mirroredX = y
                mirroredY = currentHeight - x
            }
            angle >= 2.356194490192345 && angle <= PI -> {
                mirroredX = currentWidth - x
                mirroredY = y
            }
            else -> {
                mirroredX = x
                mirroredY = y
            }
        }
        mirroredX = mirroredX and rowEnd
        mirroredY = mirroredY and columnEnd
        return Pair(mirroredX, mirroredY)
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        if (opcode == 0) {
            monochrome = buffer.readUnsignedByte() == 1
        }
    }
}
