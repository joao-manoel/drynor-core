package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.decoder.FontDecoder

/**
 * The exact inverse of [FontDecoder].
 *
 * A fixed width font is 263 bytes and has no choices in it. The variable width form writes the two
 * coverage tables and re-delta-codes the offset profiles; nothing in the 727 cache uses it, so it
 * is here for completeness rather than because a font exercises it, and it refuses to guess -
 * a definition whose profiles do not agree with its coverage widths is an error, not a wrong file.
 */
class FontEncoder {

    fun Writer.encode(definition: FontDefinition) {
        writeByte(definition.version)
        require(definition.version == 0) {
            "Font ${definition.id} is version ${definition.version}; the client only reads version 0."
        }
        writeByte(definition.variableWidth)
        require(definition.glyphWidths.size == FontDecoder.GLYPHS) {
            "Font ${definition.id} has ${definition.glyphWidths.size} glyph widths, not ${FontDecoder.GLYPHS}."
        }
        writeBytes(definition.glyphWidths)
        if (definition.variableWidth) {
            val widths = table(definition, definition.glyphCoverageWidths, "glyphCoverageWidths")
            val heights = table(definition, definition.glyphCoverageHeights, "glyphCoverageHeights")
            for (width in widths) {
                writeByte(width)
            }
            for (height in heights) {
                writeByte(height)
            }
            writeProfiles(definition, definition.horizontalOffsets, widths, "horizontalOffsets")
            writeProfiles(definition, definition.verticalOffsets, widths, "verticalOffsets")
        } else {
            writeByte(definition.verticalSpacing)
        }
        writeByte(definition.unusedByte1)
        writeByte(definition.unusedByte2)
        writeByte(definition.topPadding)
        writeByte(definition.bottomPadding)
    }

    /** One offset table, delta coded from 0 and wrapped in a byte, as the decoder read it. */
    private fun Writer.writeProfiles(
        definition: FontDefinition,
        profiles: Array<IntArray>?,
        lengths: IntArray,
        name: String
    ) {
        val glyphs = profiles ?: error("Font ${definition.id} is variable width but has no $name.")
        require(glyphs.size == FontDecoder.GLYPHS) {
            "Font ${definition.id} has ${glyphs.size} $name, not ${FontDecoder.GLYPHS}."
        }
        for (glyph in glyphs.indices) {
            val offsets = glyphs[glyph]
            require(offsets.size == lengths[glyph]) {
                "Font ${definition.id} glyph $glyph has ${offsets.size} $name for ${lengths[glyph]} coverage bytes."
            }
            var previous = 0
            for (offset in offsets) {
                writeByte(offset - previous)
                previous = offset
            }
        }
    }

    private fun table(definition: FontDefinition, values: IntArray?, name: String): IntArray {
        val table = values ?: error("Font ${definition.id} is variable width but has no $name.")
        require(table.size == FontDecoder.GLYPHS) {
            "Font ${definition.id} has ${table.size} $name, not ${FontDecoder.GLYPHS}."
        }
        return table
    }
}
