package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 16: Generates a herringbone pattern.
 */
class TextureOpHerringbone : TextureOperation(0, true) {
    private var scaleX: Int = 1
    private var scaleY: Int = 1
    private var threshold: Int = 204

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            for (x in 0 until currentWidth) {
                val horizontalPos = horizontalTable[x]
                val verticalPos = verticalTable[y]
                var tileX = horizontalPos * scaleX shr 12
                val tileY = verticalPos * scaleY shr 12
                val offsetX = horizontalPos % (4096 / scaleX) * scaleX
                val offsetY = verticalPos % (4096 / scaleY) * scaleY

                if (offsetY < threshold) {
                    tileX -= tileY
                    while (tileX < 0) tileX += 4
                    while (tileX > 3) tileX -= 4
                    if (tileX != 1) {
                        output[x] = 0
                        continue
                    }
                    if (offsetX < threshold) {
                        output[x] = 0
                        continue
                    }
                }

                if (offsetX < threshold) {
                    tileX -= tileY
                    while (tileX < 0) tileX += 4
                    while (tileX > 3) tileX -= 4
                    if (tileX > 0) {
                        output[x] = 0
                        continue
                    }
                }

                output[x] = 4096
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> scaleX = buffer.readUnsignedByte()
            1 -> scaleY = buffer.readUnsignedByte()
            2 -> threshold = buffer.readShort() and 0xFFFF
        }
    }
}
