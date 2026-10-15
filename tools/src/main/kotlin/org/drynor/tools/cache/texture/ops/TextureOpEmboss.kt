package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Type 32: Emboss filter that creates a 3D relief effect.
 */
class TextureOpEmboss : TextureOperation(1, true) {
    private var intensity: Int = 4096
    private var azimuth: Int = 3216  // Light direction horizontal angle
    private var elevation: Int = 3216  // Light direction vertical angle
    private val lightVector = IntArray(3)

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val scale = intensity * widthScale shr 12
            val prevRow = getChildMonochromeOutput(0, (y - 1) and columnEnd)
            val currentRow = getChildMonochromeOutput(0, y)
            val nextRow = getChildMonochromeOutput(0, (y + 1) and columnEnd)

            for (x in 0 until currentWidth) {
                val dY = (nextRow[x] - prevRow[x]) * scale shr 12
                val dX = scale * (currentRow[(x - 1) and rowEnd] - currentRow[(x + 1) and rowEnd]) shr 12
                var absX = dX shr 4
                var absY = dY shr 4
                if (absX < 0) absX = -absX
                if (absX > 255) absX = 255
                if (absY < 0) absY = -absY
                if (absY > 255) absY = 255

                val lookupIndex = absX + (absY * (absY + 1) shr 1)
                val normalScale = NORMAL_TABLE[lookupIndex].toInt() and 0xff
                val normalX = dX * normalScale shr 8
                val normalY = dY * normalScale shr 8
                val normalZ = normalScale * 4096 shr 8

                val dotProduct = (normalX * lightVector[0] shr 12) +
                        (lightVector[1] * normalY shr 12) +
                        (normalZ * lightVector[2] shr 12)
                output[x] = dotProduct
            }
        }
        return output
    }

    override fun postDecode() {
        computeLightVector()
    }

    private fun computeLightVector() {
        val cosElevation = cos(elevation / 4096.0)
        lightVector[0] = (sin(azimuth / 4096.0) * cosElevation * 4096.0).toInt()
        lightVector[1] = (cos(azimuth / 4096.0) * cosElevation * 4096.0).toInt()
        lightVector[2] = (sin(elevation / 4096.0) * 4096.0).toInt()

        // Normalize the light vector
        val magSquared = (lightVector[0] * lightVector[0] shr 12) +
                (lightVector[1] * lightVector[1] shr 12) +
                (lightVector[2] * lightVector[2] shr 12)
        val magnitude = (sqrt((magSquared shr 12).toDouble()) * 4096.0).toInt()
        if (magnitude != 0) {
            lightVector[0] = (lightVector[0] shl 12) / magnitude
            lightVector[1] = (lightVector[1] shl 12) / magnitude
            lightVector[2] = (lightVector[2] shl 12) / magnitude
        }
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> intensity = buffer.readShort() and 0xFFFF
            1 -> azimuth = buffer.readShort() and 0xFFFF
            2 -> elevation = buffer.readShort() and 0xFFFF
        }
    }

    companion object {
        /** Precomputed normal magnitude lookup table for emboss calculations */
        private val NORMAL_TABLE: ByteArray

        init {
            var index = 0
            NORMAL_TABLE = ByteArray(32896)
            for (y in 0..255) {
                for (x in 0..y) {
                    NORMAL_TABLE[index++] = (255.0 / sqrt((x * x + y * y + 65535) / 65535.0)).toInt().toByte()
                }
            }
        }
    }
}
