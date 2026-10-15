package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import org.drynor.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Type 12: Waveform pattern generator.
 * Generates sine wave or triangular wave patterns.
 */
class TextureOpWaveform : TextureOperation(0, true) {
    private var waveType: Int = 0
    private var waveShape: Int = 0
    private var frequency: Int = 1

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalValue = verticalTable[y]
            val verticalOffset = (verticalValue - 2048) shr 1

            for (x in 0 until currentWidth) {
                val horizontalValue = horizontalTable[x]
                val horizontalOffset = (horizontalValue - 2048) shr 1
                var waveValue: Int

                if (waveType == 0) {
                    waveValue = (horizontalValue - verticalValue) * frequency
                } else {
                    val distanceSquared = (verticalOffset * verticalOffset + horizontalOffset * horizontalOffset) shr 12
                    val distance = sqrt((distanceSquared / 4096.0f).toDouble()).toInt() * 4096
                    waveValue = (distance * frequency * PI).toInt()
                }

                waveValue -= waveValue and -4096

                when (waveShape) {
                    0 -> waveValue = (TextureRaster.PALETTE_SIN[waveValue shr 4 and 0xff] + 4096) shr 1
                    2 -> {
                        waveValue -= 2048
                        if (waveValue < 0) waveValue = -waveValue
                        waveValue = (2048 - waveValue) shl 1
                    }
                }

                output[x] = waveValue
            }
        }
        return output
    }

    override fun postDecode() {
        TextureRaster.calculateTable()
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> waveType = buffer.readUnsignedByte()
            1 -> waveShape = buffer.readUnsignedByte()
            3 -> frequency = buffer.readUnsignedByte()
        }
    }
}
