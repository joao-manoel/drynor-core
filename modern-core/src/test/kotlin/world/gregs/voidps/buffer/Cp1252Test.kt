package world.gregs.voidps.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client's single byte charset, mirrored from the 727 client
 * (`Class490.aCharArray5766` / `Utils.UNICODE_TABLE`, `Utils.byteForChar`, `Packet.writeString`).
 */
class Cp1252Test {

    /**
     * Byte to character for the whole `0x80`-`0x9f` range, straight out of the client.
     * The unused slots ([UNUSED_SLOTS]) are absent; they decode to '?'.
     */
    private val extendedRange = mapOf(
        0x80 to '€',
        0x82 to '‚',
        0x83 to 'ƒ',
        0x84 to '„',
        0x85 to '…',
        0x86 to '†',
        0x87 to '‡',
        0x88 to 'ˆ',
        0x89 to '‰',
        0x8a to 'Š',
        0x8b to '‹',
        0x8c to 'Œ',
        0x8e to 'Ž',
        0x91 to '‘',
        0x92 to '’',
        0x93 to '“',
        0x94 to '”',
        0x95 to '•',
        0x96 to '–',
        0x97 to '—',
        0x98 to '˜',
        0x99 to '™',
        0x9a to 'š',
        0x9b to '›',
        0x9c to 'œ',
        0x9e to 'ž',
        0x9f to 'Ÿ'
    )

    @Test
    fun `ascii decodes to the identical code point`() {
        for (code in 1..127) {
            assertEquals(code.toChar(), Cp1252.decode(code), "decode 0x${code.toString(16)}")
        }
    }

    @Test
    fun `ascii encodes to the identical byte`() {
        for (code in 1..127) {
            assertEquals(code.toByte(), Cp1252.encode(code.toChar()), "encode 0x${code.toString(16)}")
        }
    }

    @Test
    fun `ascii strings encode to one byte per character, unchanged`() {
        val value = "Hello, world! 0123456789 ~!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val encoded = Cp1252.encode(value)
        assertEquals(value.length, encoded.size)
        // Byte identical to the previous UTF-8 behaviour, which is what pure ascii traffic used.
        assertTrue(encoded.contentEquals(value.toByteArray(Charsets.UTF_8)))
        assertEquals(value, decodeAll(encoded))
    }

    @Test
    fun `extended range round trips through the client table`() {
        for ((byte, char) in extendedRange) {
            assertEquals(char, Cp1252.decode(byte), "decode 0x${byte.toString(16)}")
            assertEquals(byte.toByte(), Cp1252.encode(char), "encode ${char.code}")
        }
    }

    @Test
    fun `unused extended slots decode to the substitute`() {
        for (byte in UNUSED_SLOTS) {
            assertEquals(Cp1252.SUBSTITUTE, Cp1252.decode(byte), "decode 0x${byte.toString(16)}")
        }
    }

    @Test
    fun `every byte in the extended range is accounted for`() {
        assertEquals(32, extendedRange.size + UNUSED_SLOTS.size)
    }

    @Test
    fun `latin supplement range is an identity mapping`() {
        for (code in 160..255) {
            assertEquals(code.toChar(), Cp1252.decode(code), "decode 0x${code.toString(16)}")
            assertEquals(code.toByte(), Cp1252.encode(code.toChar()), "encode 0x${code.toString(16)}")
        }
    }

    @Test
    fun `signed bytes decode the same as masked ints`() {
        for (code in 1..255) {
            assertEquals(Cp1252.decode(code), Cp1252.decode(code.toByte()), "0x${code.toString(16)}")
        }
    }

    @Test
    fun `unencodable characters fall back to a single substitute byte`() {
        // Outside latin-1 and not in the 0x80-0x9f table: cjk, latin extended-A, a lone surrogate,
        // a box drawing glyph, and the ascii control character the protocol uses as a terminator.
        val unencodable = listOf('中', 'Ā', '\ud83d', '─', '\u0000')
        for (char in unencodable) {
            assertEquals(Cp1252.SUBSTITUTE.code.toByte(), Cp1252.encode(char), "encode ${char.code}")
        }
        val encoded = Cp1252.encode("a中b")
        assertEquals(3, encoded.size)
        assertEquals("a?b", decodeAll(encoded))
    }

    @Test
    fun `pound sign encodes to one byte, not two`() {
        val encoded = Cp1252.encode("£")
        assertEquals(1, encoded.size)
        assertEquals(0xa3.toByte(), encoded[0])
        assertEquals('£', Cp1252.decode(encoded[0]))
    }

    @Test
    fun `euro sign encodes to one byte, not three`() {
        val encoded = Cp1252.encode("€")
        assertEquals(1, encoded.size)
        assertEquals(0x80.toByte(), encoded[0])
        assertEquals('€', Cp1252.decode(encoded[0]))
    }

    private fun decodeAll(bytes: ByteArray) = String(CharArray(bytes.size) { Cp1252.decode(bytes[it]) })

    private companion object {
        /** `0x80`-`0x9f` slots the client leaves empty. */
        val UNUSED_SLOTS = listOf(0x81, 0x8d, 0x8f, 0x90, 0x9d)
    }
}
