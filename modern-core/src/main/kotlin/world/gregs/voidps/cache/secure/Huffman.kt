package world.gregs.voidps.cache.secure

import org.darkan.core.Logger.logInfo
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.BufferWriter

class Huffman {
    private var masks: IntArray? = null
    private lateinit var frequencies: ByteArray
    private lateinit var decryptKeys: IntArray
    private lateinit var decryptedKeys: IntArray
    private var decryptionValues = listOf(0, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)

    /**
     * Load huffman tree from cache for compression
     */
    fun load(huffman: ByteArray) : Huffman {
        val start = System.currentTimeMillis()
        frequencies = huffman
        masks = IntArray(huffman.size)
        decryptKeys = IntArray(8)
        val freq = IntArray(33)
        var key = 0
        // For each non-zero frequency
        for (index in huffman.indices) {
            val size = huffman[index].toInt()
            if (size == 0) continue
            // Calculate maximum frequency
            val maximumFreq = 1 shl 32 - size
            // zero or the previous minimum
            val currentFreq = freq[size]
            // Store the min
            masks!![index] = currentFreq
            // Set the frequency to the
            freq[size] = if (currentFreq and maximumFreq == 0) { // If the min and max are equal ish?
                // Starting from the bottom find the smallest frequency indices
                for (idx in size - 1 downTo 1) {
                    val leftFreq = freq[idx]
                    if (leftFreq != currentFreq) {
                        break
                    }
                    val rightFreq = 1 shl 32 - idx
                    if (rightFreq and leftFreq != 0) {
                        // Move up the tree?
                        freq[idx] = freq[idx - 1]
                        break
                    }
                    // Merge the two smallest trees
                    freq[idx] = leftFreq + rightFreq
                }
                // Sum of their frequencies
                maximumFreq + currentFreq
            } else {
                // Move up the tree?
                freq[size - 1]
            }
            for (idx in size + 1..32) {
                if (currentFreq == freq[idx]) {
                    freq[idx] = freq[size]
                }
            }

            var decryptIndex = 0
            val value: Long = Int.MAX_VALUE + 1L
            for(count in 0 until size) {
                if (currentFreq and value.ushr(count).toInt() == 0) {
                    decryptIndex++
                } else {
                    if (decryptKeys[decryptIndex] == 0) {
                        decryptKeys[decryptIndex] = key
                    }
                    decryptIndex = decryptKeys[decryptIndex]
                }
                if (decryptKeys.size <= decryptIndex) {
                    val keys = IntArray(decryptKeys.size * 2)
                    System.arraycopy(decryptKeys, 0, keys, 0, decryptKeys.size)
                    decryptKeys = keys
                }
            }
            decryptKeys[decryptIndex] = index xor -0x1
            if (key <= decryptIndex) {
                key = 1 + decryptIndex
            }
        }

        decryptedKeys = decryptKeys.map { it xor -0x1 }.toIntArray()
        logInfo("Huffman tree decoded in ${System.currentTimeMillis() - start}ms")
        return this
    }

    /**
     * Decompresses string of length [characters] using Huffman coding
     * @param packet The packet containing the compressed data
     * @param characters The number of string characters to decompress
     */
    fun decompress(packet: Reader, characters: Int): String {
        val textBuffer = ByteArray(packet.readableBytes())
        packet.readBytes(textBuffer)
        return decompress(textBuffer, characters) ?: ""
    }

    fun decompress(message: ByteArray, length: Int): String? {
        return try {
            if (masks == null) {
                return null
            }
            var charsDecoded = 0
            var keyIndex = 0
            val sb = StringBuilder()
            chars@ for (character in message) {
                for (value in decryptionValues) {
                    if (if (value == 0) character >= 0 else character.toInt() and value == 0) {
                        keyIndex++
                    } else {
                        keyIndex = decryptKeys[keyIndex]
                    }

                    val char = decryptedKeys[keyIndex]
                    if (char >= 0) {
                        sb.append(cp1252ToChar(char))
                        if (length <= ++charsDecoded) {
                            break@chars
                        }
                        keyIndex = 0
                    }
                }
            }
            sb.toString()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Formats, compresses and writes [message] using Huffman coding
     * @param message The message to encode
     */
    fun compress(message: String): ByteArray {
        val writer = BufferWriter(message.length * 2 + 2)
        try {
            // Format the message
            val messageData = formatMessage(message)
            // Write message length
            writer.writeSmart(messageData.size)
            // Write the compressed message
            compress(messageData, writer)
        } catch (exception: Throwable) {
            exception.printStackTrace()
        }
        return writer.toArray()
    }

    /**
     * Compresses [message] using Huffman coding and writes to [builder]
     * @param message The message to compress, split by symbol into a byte array
     * @param builder The packet to write the compressed data too
     */
    private fun compress(message: ByteArray, builder: BufferWriter) {
        try {
            if (masks == null) {
                return
            }
            var key = 0
            val startPosition = builder.position()
            var position = startPosition shl 3
            for (char in message) {
                val character = char.toInt() and 0xff
                val min = masks!![character]
                val size = frequencies[character]

                var offset = position shr 3
                var bitOffset = position and 0x7
                key = key and (-bitOffset shr 31)
                position += size
                val byteSize = (bitOffset + size - 1 shr 3) + offset
                bitOffset += 24
                key += min.ushr(bitOffset)
                builder.setByte(offset, key)

                while (offset < byteSize) {
                    bitOffset -= 8
                    key = min.ushr(bitOffset)
                    builder.setByte(++offset, key)
                }
            }

            // Set the packet position to the correct place
            builder.position(7 + position shr 3)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Replaces unknown symbols with question marks
     * @param message The text to format
     * @return message split by character
     */
    private fun formatMessage(message: String): ByteArray {
        val len = message.length
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            val ch = message[i]
            if (ch.code > 0 && ch.code < 128 || ch.code >= 160 && ch.code <= 255) {
                bytes[i] = ch.code.toByte()
            } else when (ch.code) {
                8364 -> bytes[i] = -128  // €
                8218 -> bytes[i] = -126  // ‚
                402 -> bytes[i] = -125   // ƒ
                8222 -> bytes[i] = -124  // „
                8230 -> bytes[i] = -123  // …
                8224 -> bytes[i] = -122  // †
                8225 -> bytes[i] = -121  // ‡
                710 -> bytes[i] = -120   // ˆ
                8240 -> bytes[i] = -119  // ‰
                352 -> bytes[i] = -118   // Š
                8249 -> bytes[i] = -117  // ‹
                338 -> bytes[i] = -116   // Œ
                381 -> bytes[i] = -114   // Ž
                8216 -> bytes[i] = -111  // '
                8217 -> bytes[i] = -110  // '
                8220 -> bytes[i] = -109  // "
                8221 -> bytes[i] = -108  // "
                8226 -> bytes[i] = -107  // •
                8211 -> bytes[i] = -106  // –
                8212 -> bytes[i] = -105  // —
                732 -> bytes[i] = -104   // ˜
                8482 -> bytes[i] = -103  // ™
                353 -> bytes[i] = -102   // š
                8250 -> bytes[i] = -101  // ›
                339 -> bytes[i] = -100   // œ
                382 -> bytes[i] = -98    // ž
                376 -> bytes[i] = -97    // Ÿ
                else -> bytes[i] = 63    // ?
            }
        }
        return bytes
    }

    companion object {
        private val UNICODE_TABLE = charArrayOf(
            '\u20ac', '\u0000', '\u201a', '\u0192', '\u201e', '\u2026', '\u2020', '\u2021',
            '\u02c6', '\u2030', '\u0160', '\u2039', '\u0152', '\u0000', '\u017d', '\u0000',
            '\u0000', '\u2018', '\u2019', '\u201c', '\u201d', '\u2022', '\u2013', '\u2014',
            '\u02dc', '\u2122', '\u0161', '\u203a', '\u0153', '\u0000', '\u017e', '\u0178'
        )

        fun cp1252ToChar(value: Int): Char {
            if (value in 128..159) {
                val mapped = UNICODE_TABLE[value - 128]
                return if (mapped.code != 0) mapped else '?'
            }
            return value.toChar()
        }
    }
}