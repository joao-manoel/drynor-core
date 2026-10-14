package world.gregs.voidps.buffer

/**
 * The single byte character set used by the RuneScape protocol and cache.
 *
 * This is the client's "cp1252" variant, taken verbatim from the 727 client
 * (`Class490.aCharArray5766` / `Utils.UNICODE_TABLE`, used by `Utils.cp1252ToChar`,
 * `Utils.readString`, `Utils.byteForChar` and `Packet.writeString`):
 *
 * - `0x01`-`0x7F` and `0xA0`-`0xFF` map straight to the identical unicode code point.
 * - `0x80`-`0x9F` map through [UNICODE_TABLE]; the five unused slots
 *   (`0x81`, `0x8D`, `0x8F`, `0x90`, `0x9D`) decode to [SUBSTITUTE].
 * - Every other character encodes to [SUBSTITUTE] - the client never emits more than one
 *   byte per character, so encoding must never widen the string.
 *
 * Note this is *not* UTF-8: `String.toByteArray()` would turn a single `£` into two bytes,
 * corrupting both the text and any VarByte/VarShort length prefix in front of it.
 */
object Cp1252 {

    /**
     * The character the client substitutes for anything it cannot represent,
     * both when decoding an unused `0x80`-`0x9F` slot and when encoding an unmappable character.
     */
    const val SUBSTITUTE: Char = '?'

    private val SUBSTITUTE_BYTE: Byte = SUBSTITUTE.code.toByte()

    /**
     * Unicode code points for bytes `0x80`-`0x9F`, indexed by `byte - 0x80`.
     *
     * A NUL entry marks an unused slot, which decodes to [SUBSTITUTE].
     */
    private val UNICODE_TABLE = charArrayOf(
        '\u20ac', // 0x80 euro
        '\u0000', // 0x81 unused
        '\u201a', // 0x82 single low-9 quotation mark
        '\u0192', // 0x83 latin small letter f with hook
        '\u201e', // 0x84 double low-9 quotation mark
        '\u2026', // 0x85 horizontal ellipsis
        '\u2020', // 0x86 dagger
        '\u2021', // 0x87 double dagger
        '\u02c6', // 0x88 modifier letter circumflex accent
        '\u2030', // 0x89 per mille sign
        '\u0160', // 0x8a latin capital letter s with caron
        '\u2039', // 0x8b single left-pointing angle quotation mark
        '\u0152', // 0x8c latin capital ligature oe
        '\u0000', // 0x8d unused
        '\u017d', // 0x8e latin capital letter z with caron
        '\u0000', // 0x8f unused
        '\u0000', // 0x90 unused
        '\u2018', // 0x91 left single quotation mark
        '\u2019', // 0x92 right single quotation mark
        '\u201c', // 0x93 left double quotation mark
        '\u201d', // 0x94 right double quotation mark
        '\u2022', // 0x95 bullet
        '\u2013', // 0x96 en dash
        '\u2014', // 0x97 em dash
        '\u02dc', // 0x98 small tilde
        '\u2122', // 0x99 trade mark sign
        '\u0161', // 0x9a latin small letter s with caron
        '\u203a', // 0x9b single right-pointing angle quotation mark
        '\u0153', // 0x9c latin small ligature oe
        '\u0000', // 0x9d unused
        '\u017e', // 0x9e latin small letter z with caron
        '\u0178'  // 0x9f latin capital letter y with diaeresis
    )

    /**
     * Reverse of [UNICODE_TABLE], covering only the characters that live in `0x80`-`0x9F`.
     * Unused slots are excluded so they fall through to [SUBSTITUTE], exactly like the client.
     */
    private val ENCODE_TABLE: Map<Char, Byte> = buildMap(UNICODE_TABLE.size) {
        for (index in UNICODE_TABLE.indices) {
            val char = UNICODE_TABLE[index]
            if (char.code != 0) {
                put(char, (index + 128).toByte())
            }
        }
    }

    /**
     * Decodes a single byte to its character.
     *
     * @param value the byte, either signed or already masked to `0..255`
     */
    fun decode(value: Int): Char {
        val code = value and 0xff
        if (code in 128..159) {
            val char = UNICODE_TABLE[code - 128]
            return if (char.code == 0) SUBSTITUTE else char
        }
        return code.toChar()
    }

    /**
     * Decodes a single byte to its character.
     *
     * Note `0` is the string terminator in the protocol; readers stop before reaching it,
     * so it is never decoded in practice. Decoding it here yields a NUL character.
     */
    fun decode(value: Byte): Char = decode(value.toInt())

    /**
     * Encodes a single character to its byte, returning [SUBSTITUTE] for anything
     * with no single byte representation. Never throws and never widens.
     */
    fun encode(char: Char): Byte {
        val code = char.code
        if (code in 1..127 || code in 160..255) {
            return code.toByte()
        }
        return ENCODE_TABLE[char] ?: SUBSTITUTE_BYTE
    }

    /**
     * Encodes [value] to exactly `value.length` bytes.
     */
    fun encode(value: CharSequence): ByteArray = ByteArray(value.length) { encode(value[it]) }
}
