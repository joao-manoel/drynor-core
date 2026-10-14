package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.FONT_METRICS
import world.gregs.voidps.cache.definition.data.FontDefinition
import kotlin.math.max
import kotlin.math.min

class FontDecoder : DefinitionDecoder<FontDefinition>(FONT_METRICS) {

    override fun size(cache: Cache): Int {
        return cache.lastArchiveId(index)
    }

    override fun create(size: Int) = Array(size) { FontDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: FontDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun FontDefinition.read(opcode: Int, buffer: Reader) {
        version = buffer.readUnsignedByte()
        if (version != 0) {
            return
        }
        variableWidth = buffer.readUnsignedBoolean()
        glyphWidths = ByteArray(256)
        buffer.readBytes(glyphWidths)
        if (variableWidth) {
            val widths = IntArray(GLYPHS) { buffer.readUnsignedByte() }
            val heights = IntArray(GLYPHS) { buffer.readUnsignedByte() }
            // Both profiles are `widths[glyph]` bytes long, as in the client: the heights are
            // what it kerns against, not what it reads.
            val left = profiles(buffer, widths)
            val right = profiles(buffer, widths)
            glyphCoverageWidths = widths
            glyphCoverageHeights = heights
            horizontalOffsets = left
            verticalOffsets = right
            kerningAdjustments = Array(GLYPHS) { first ->
                ByteArray(GLYPHS) { second ->
                    calculateKerning(glyphWidths, second, first, left, right, heights, widths).toByte()
                }
            }
            verticalSpacing = heights[SPACE] + widths[SPACE]
        } else {
            verticalSpacing = buffer.readUnsignedByte()
        }
        unusedByte1 = buffer.readUnsignedByte()
        unusedByte2 = buffer.readUnsignedByte()
        topPadding = buffer.readUnsignedByte()
        bottomPadding = buffer.readUnsignedByte()
    }

    companion object {
        private fun calculateKerning(
            widths: ByteArray,
            rightGlyphIndex: Int,
            leftGlyphIndex: Int,
            rightGlyphData: Array<IntArray>,
            leftGlyphData: Array<IntArray>,
            leftOffsets: IntArray,
            rightOffsets: IntArray
        ): Int {
            if (leftGlyphIndex == 32 || leftGlyphIndex == 160 || rightGlyphIndex == 32 || rightGlyphIndex == 160) {
                return 0
            }
            val leftOffset = leftOffsets[leftGlyphIndex]
            val leftLimit = rightOffsets[leftGlyphIndex] + leftOffset
            val rightOffset = leftOffsets[rightGlyphIndex]
            val rightLimit = rightOffsets[rightGlyphIndex] + rightOffset
            val minOffset = max(leftOffset, rightOffset)
            val maxLimit = min(rightLimit, leftLimit)
            var minDelta = widths[leftGlyphIndex].toInt() and 0xff
            if (minDelta > widths[rightGlyphIndex].toInt() and 0xff) {
                minDelta = widths[rightGlyphIndex].toInt() and 0xff
            }
            val leftGlyph = leftGlyphData[leftGlyphIndex]
            val rightGlyph = rightGlyphData[rightGlyphIndex]
            var leftIndex = leftOffset - minOffset
            var rightIndex = rightOffset - minOffset
            for (index in minOffset until maxLimit) {
                val delta = rightGlyph[rightIndex++] + leftGlyph[leftIndex++]
                if (delta < minDelta) {
                    minDelta = delta
                }
            }
            return -minDelta
        }
        /**
         * One coverage profile per glyph, [lengths] bytes each, delta coded from 0 and wrapping
         * in a byte exactly as the client's `(offset + readByte()).toByte()` does.
         */
        private fun profiles(buffer: Reader, lengths: IntArray) = Array(GLYPHS) { index ->
            var total = 0
            IntArray(lengths[index]) {
                total = (total + buffer.readByte()).toByte().toInt()
                total
            }
        }

        /** The cp1252 code points a font has a glyph for. */
        const val GLYPHS = 256

        /** The space, whose profile the client reads the line spacing out of. */
        private const val SPACE = 32
    }
}