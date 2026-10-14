package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 31: Generates Mandelbrot fractal patterns.
 */
class TextureOpMandelbrot : TextureOperation(0, true) {
    private var offsetX: Int = 0
    private var offsetY: Int = 0
    private var zoom: Int = 1365
    private var maxIterations: Int = 20

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            for (x in 0 until currentWidth) {
                val scaledX = (horizontalTable[x] shl 12) / zoom + offsetX
                val scaledY = (verticalTable[y] shl 12) / zoom + offsetY
                val startX = scaledX
                val startY = scaledY
                var realPart = scaledX
                var imagPart = scaledY
                var realSquared = scaledX * scaledX shr 12
                var imagSquared = scaledY * scaledY shr 12

                var iterations = 0
                while (realSquared + imagSquared < 16384 && iterations < maxIterations) {
                    imagPart = (realPart * imagPart shr 12) * 2 + startY
                    realPart = startX + (realSquared - imagSquared)
                    realSquared = realPart * realPart shr 12
                    imagSquared = imagPart * imagPart shr 12
                    iterations++
                }

                output[x] = if (iterations < maxIterations - 1) (iterations shl 12) / maxIterations else 0
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> zoom = buffer.readShort() and 0xFFFF
            1 -> maxIterations = buffer.readShort() and 0xFFFF
            2 -> offsetX = buffer.readShort() and 0xFFFF
            3 -> offsetY = buffer.readShort() and 0xFFFF
        }
    }
}
