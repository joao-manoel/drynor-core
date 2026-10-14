package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.Extra

/**
 * One font's metrics from index 13: the advance of each of the 256 cp1252 glyphs, the line
 * spacing and padding, and - in the variable width form - the per glyph coverage profiles the
 * client turns into a kerning table.
 *
 * [version] onwards are what the file carries and the client throws away, so that
 * [world.gregs.voidps.cache.definition.encoder.FontEncoder] can write it back byte for byte; see
 * `docs/cache-source.md`, "fontmetrics". [kerningAdjustments] is the only field that is *derived*
 * rather than read - the client computes it from the coverage profiles - so it is the one field
 * the source tree does not write.
 */
data class FontDefinition(
    override var id: Int = -1,
    var glyphWidths: ByteArray = byteArrayOf(),
    var kerningAdjustments: Array<ByteArray>? = null,
    var verticalSpacing: Int = 0,
    var topPadding: Int = 0,
    var bottomPadding: Int = 0,
    /** The format byte, which the client refuses to read anything but 0 of. */
    var version: Int = 0,
    /**
     * The variable width form: the file carries a coverage profile per glyph and the client
     * derives [kerningAdjustments] and [verticalSpacing] from them. None of the 727 cache's 27
     * fonts uses it - every one is the fixed width form, 263 bytes long.
     */
    var variableWidth: Boolean = false,
    /** How many coverage bytes each glyph has, and the width the client kerns against. */
    var glyphCoverageWidths: IntArray? = null,
    /** Each glyph's coverage height. */
    var glyphCoverageHeights: IntArray? = null,
    /** Per glyph, [glyphCoverageWidths] absolute offsets, delta coded in the file; the client's `horizontalOffsets`. */
    var horizontalOffsets: Array<IntArray>? = null,
    /** Per glyph, the second offset table, the same shape as [horizontalOffsets]; the client's `verticalOffsets`. */
    var verticalOffsets: Array<IntArray>? = null,
    /**
     * The two bytes between the vertical spacing and the padding that the client reads and
     * discards. Every one of the 727 fonts carries a nonzero pair, so they are not padding.
     */
    var unusedByte1: Int = 0,
    var unusedByte2: Int = 0,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : Definition, Extra {

    private fun glyphWidth(glyph: Int): Int {
        return glyphWidths[glyph].toInt() and 0xff
    }

    fun textWidth(input: String, icons: Array<IndexedGraphic>? = null): Int {
        var tagStart = -1
        var lastChar = -1
        var totalWidth = 0
        for (index in input.indices) {
            var current = input[index]
            if (current.code == 60) {
                tagStart = index
                continue
            }
            if (current.code == 62 && tagStart != -1) {
                val tag = input.substring(tagStart + 1, index)
                tagStart = -1
                val tagChar = htmlEntityToChar(tag)
                if (tagChar == null) {
                    totalWidth += graphicWidth(tag, icons) ?: continue
                    lastChar = -1
                    continue
                }
                current = tagChar
            }
            if (tagStart == -1) {
                totalWidth += glyphWidths[charToByte(current) and 0xff].toInt() and 0xff
                if (kerningAdjustments != null && lastChar != -1) {
                    totalWidth += kerningAdjustments!![lastChar][current.code].toInt()
                }
                lastChar = current.code
            }
        }
        return totalWidth
    }

    fun truncateText(input: String, maxWidth: Int, icons: Array<IndexedGraphic>? = emptyArray()): String {
        var maximumWidth = maxWidth
        if (maximumWidth >= textWidth(input, icons)) {
            return input
        }
        maximumWidth -= textWidth("...", null)
        var tagStart = -1
        var lastChar = -1
        var totalWidth = 0
        var prefix = ""
        for (index in input.indices) {
            var current = input[index]
            if (current.code == 60) {
                tagStart = index
                continue
            }
            if (current.code == 62 && tagStart != -1) {
                val tag = input.substring(tagStart - -1, index)
                tagStart = -1
                val tagChar = htmlEntityToChar(tag)
                if (tagChar == null) {
                    totalWidth += graphicWidth(tag, icons) ?: continue
                    lastChar = -1
                    if (totalWidth > maximumWidth) {
                        return "$prefix..."
                    }
                    prefix = input.substring(0, index - -1)
                    continue
                }
                current = tagChar
            }
            if (tagStart == -1) {
                totalWidth += 0xff and glyphWidths[charToByte(current) and 0xff].toInt()
                if (kerningAdjustments != null && lastChar != -1) {
                    totalWidth += kerningAdjustments!![lastChar][current.code].toInt()
                }
                lastChar = current.code
                var total = totalWidth
                if (kerningAdjustments != null) {
                    total += kerningAdjustments!![current.code][46].toInt()
                }
                if (total > maximumWidth) {
                    return "$prefix..."
                }
                prefix = input.substring(0, index + 1)
            }
        }
        return input
    }

    fun splitLines(input: String, width: Int, icons: Array<IndexedGraphic>? = null) = splitLines(input, intArrayOf(width), icons)

    fun splitLines(input: String, widths: IntArray? = null, icons: Array<IndexedGraphic>? = null): List<String> {
        val output = mutableListOf<String>()
        var totalWidth = 0
        var lineStart = 0
        var lineLength = -1
        var wordWidth = 0
        var wordStart = 0
        var tagStart = -1
        var lastChar = -1
        for (index in input.indices) {
            var current: Int = charToByte(input[index]) and 0xff
            var extraWidth = 0
            if (current == 60) {
                tagStart = index
                continue
            }
            var currentWidth: Int
            if (tagStart == -1) {
                extraWidth += glyphWidth(current)
                currentWidth = index
                if (kerningAdjustments != null && lastChar != -1) {
                    extraWidth += kerningAdjustments!![lastChar][current].toInt()
                }
                lastChar = current
            } else {
                if (current != 62) {
                    continue
                }
                currentWidth = tagStart
                val tag = input.substring(1 + tagStart, index)
                tagStart = -1
                if (tag == "br") {
                    output.add(input.substring(lineStart, index - -1))
                    lineStart = index + 1
                    lastChar = -1
                    lineLength = -1
                    totalWidth = 0
                    continue
                }
                fun addKernelWidth(glyph: Int) {
                    extraWidth += glyphWidth(glyph)
                    if (kerningAdjustments != null && lastChar != -1) {
                        extraWidth += kerningAdjustments!![lastChar][glyph].toInt()
                    }
                    lastChar = glyph
                }
                when (tag) {
                    "lt" -> addKernelWidth(60)
                    "gt" -> addKernelWidth(62)
                    "nbsp" -> addKernelWidth(160)
                    "shy" -> addKernelWidth(173)
                    "times" -> addKernelWidth(215)
                    "euro" -> addKernelWidth(8364)
                    "copy" -> addKernelWidth(169)
                    "reg" -> addKernelWidth(174)
                    else -> {
                        totalWidth += graphicWidth(tag, icons) ?: continue
                        lastChar = -1
                    }
                }
                current = -1
            }
            if (extraWidth <= 0) {
                continue
            }
            totalWidth += extraWidth
            if (widths == null) {
                continue
            }
            if (current == 32) {
                wordStart = 1
                wordWidth = totalWidth
                lineLength = index
            }
            if (totalWidth > widths[if (widths.size > output.size) output.size else widths.size - 1]) {
                if (lineLength >= 0) {
                    output.add(input.substring(lineStart, lineLength + 1 - wordStart))
                    lineStart = lineLength + 1
                    lastChar = -1
                    lineLength = -1
                    totalWidth -= wordWidth
                } else {
                    output.add(input.substring(lineStart, currentWidth))
                    lineStart = currentWidth
                    lastChar = -1
                    lineLength = -1
                    totalWidth = extraWidth
                }
            }
            if (current == 45) {
                wordWidth = totalWidth
                lineLength = index
                wordStart = 0
            }
        }
        if (lineStart < input.length) {
            output.add(input.substring(lineStart))
        }
        return output
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FontDefinition

        if (id != other.id) return false
        if (!glyphWidths.contentEquals(other.glyphWidths)) return false
        if (kerningAdjustments != null) {
            if (other.kerningAdjustments == null) return false
            if (!kerningAdjustments.contentDeepEquals(other.kerningAdjustments)) return false
        } else if (other.kerningAdjustments != null) return false
        if (verticalSpacing != other.verticalSpacing) return false
        if (topPadding != other.topPadding) return false
        if (bottomPadding != other.bottomPadding) return false
        if (version != other.version) return false
        if (variableWidth != other.variableWidth) return false
        if (!glyphCoverageWidths.contentEquals(other.glyphCoverageWidths)) return false
        if (!glyphCoverageHeights.contentEquals(other.glyphCoverageHeights)) return false
        if (!horizontalOffsets.contentDeepEquals(other.horizontalOffsets)) return false
        if (!verticalOffsets.contentDeepEquals(other.verticalOffsets)) return false
        if (unusedByte1 != other.unusedByte1) return false
        if (unusedByte2 != other.unusedByte2) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + glyphWidths.contentHashCode()
        result = 31 * result + (kerningAdjustments?.contentDeepHashCode() ?: 0)
        result = 31 * result + verticalSpacing
        result = 31 * result + topPadding
        result = 31 * result + bottomPadding
        result = 31 * result + version
        result = 31 * result + variableWidth.hashCode()
        result = 31 * result + (glyphCoverageWidths?.contentHashCode() ?: 0)
        result = 31 * result + (glyphCoverageHeights?.contentHashCode() ?: 0)
        result = 31 * result + (horizontalOffsets?.contentDeepHashCode() ?: 0)
        result = 31 * result + (verticalOffsets?.contentDeepHashCode() ?: 0)
        result = 31 * result + unusedByte1
        result = 31 * result + unusedByte2
        result = 31 * result + stringId.hashCode()
        result = 31 * result + (extras?.hashCode() ?: 0)
        return result
    }

    companion object {
        private fun graphicWidth(tag: String, icons: Array<IndexedGraphic>?): Int? {
            if (!tag.startsWith("img=") || icons == null) {
                return null
            }
            return try {
                val id = parseInt(tag.substring(4))
                icons[id].scaleWidth()
            } catch (exception: Exception) {
                null
            }
        }

        private fun htmlEntityToChar(tag: String): Char? {
            return when (tag) {
                "lt" -> '<'
                "gt" -> '>'
                "nbsp" -> '\u00a0'
                "shy" -> '\u00ad'
                "times" -> '\u00d7'
                "euro" -> '\u20ac'
                "copy" -> '\u00a9'
                "reg" -> '\u00ae'
                else -> null
            }
        }

        private fun parseInt(string: String, radix: Int = 10, positive: Boolean = true): Int {
            require(radix in 2..36) { "Invalid radix: $radix" }
            var negative = false
            var valid = false
            var result = 0
            for (index in string.indices) {
                var digit = string[index].code
                if (index == 0) {
                    if (digit == 45) {
                        negative = true
                        continue
                    }
                    if (digit == 43 && positive) {
                        continue
                    }
                }
                digit -= if (digit in 48..57) 48 else if (digit in 65..90) 55 else if (digit in 97..122) 87 else throw NumberFormatException()
                if (digit >= radix) {
                    throw NumberFormatException()
                }
                if (negative) {
                    digit = -digit
                }
                val temp = (radix * result) + digit
                if (result != temp / radix) {
                    throw NumberFormatException()
                }
                valid = true
                result = temp
            }
            if (!valid) {
                throw NumberFormatException()
            }
            return result
        }

        private fun charToByte(c: Char): Int {
            if (c.code in 1..127 || c.code in 160..255) {
                return c.code
            }
            return when (c.code) {
                8364 -> -128
                8218 -> -126
                402 -> -125
                8222 -> -124
                8230 -> -123
                8224 -> -122
                8225 -> -121
                710 -> -120
                8240 -> -119
                352 -> -118
                8249 -> -117
                338 -> -116
                381 -> -114
                8216 -> -111
                8217 -> -110
                8220 -> -109
                8221 -> -108
                8226 -> -107
                8211 -> -106
                8212 -> -105
                732 -> -104
                8482 -> -103
                353 -> -102
                8250 -> -101
                339 -> -100
                382 -> -98
                376 -> -97
                else -> 63
            }
        }

        val EMPTY = FontDefinition()
    }

}