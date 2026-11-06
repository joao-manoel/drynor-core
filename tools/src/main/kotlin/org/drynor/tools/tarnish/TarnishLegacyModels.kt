package org.drynor.tools.tarnish

/** Tarnish type-2 with no weighted bones has the legacy 18-byte-footer model body. */
internal object TarnishLegacyModels {
    fun nativeBytes(bytes: ByteArray): ByteArray {
        require(bytes.size >= 18)
        if (bytes[bytes.lastIndex - 1] != (-1).toByte() || bytes.last() != (-2).toByte())
            return TarnishModels.nativeBytes(bytes)
        require(bytes.size >= 23) { "Truncated type-2 model footer" }
        val footer = bytes.size - 23
        fun u8(offset: Int) = bytes[footer + offset].toInt() and 255
        fun u16(offset: Int) = (u8(offset) shl 8) or u8(offset + 1)
        require(u8(10) == 0) { "Weighted type-2 model requires skeletal conversion" }
        require(u8(5) in 0..1 && u8(7) in 0..1 && u8(8) in 0..1 && u8(9) in 0..1) { "Unsupported type-2 flags" }
        require(u16(19) == if (u8(9) == 1) u16(0) else 0) { "Unexpected type-2 skin section length" }
        return bytes.copyOfRange(0, footer) + bytes.copyOfRange(footer, footer + 10) +
            bytes.copyOfRange(footer + 11, footer + 19)
    }
}
