package org.drynor.tools.tarnish

/** Type-3 without weighted bones has the type-1 body and three extra footer bytes. */
internal object TarnishModels {
    fun nativeBytes(bytes: ByteArray): ByteArray {
        require(bytes.size >= 18)
        if (bytes[bytes.lastIndex - 1] != (-1).toByte()) return bytes
        if (bytes.last() == (-1).toByte()) return bytes
        require(bytes.last() == (-3).toByte()) { "Unsupported Tarnish model layout" }
        require(bytes.size >= 26)
        val footer = bytes.size - 26
        fun u8(i: Int) = bytes[footer + i].toInt() and 255
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        require(u8(11) == 0) { "Weighted skeletal model needs a dedicated converter" }
        require(u16(22) == if (u8(10) == 1) u16(0) else 0) { "Unexpected vertex skin section length" }
        return bytes.copyOfRange(0, footer) + bytes.copyOfRange(footer, footer + 11) +
            bytes.copyOfRange(footer + 12, footer + 22) + byteArrayOf(-1, -1)
    }
}
