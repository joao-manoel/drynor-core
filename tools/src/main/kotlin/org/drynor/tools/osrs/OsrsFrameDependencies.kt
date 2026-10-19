package org.drynor.tools.osrs

data class OsrsFrameInfo(val baseId: Int, val transformCount: Int)

data class OsrsBaseInfo(val transformCount: Int)

/**
 * Structural probe for the legacy OSRS frame/base profile, not a 727 compatibility decoder.
 * Unlike 727 AnimFrame's four-byte header, this frame starts with u16 base/u8 count.
 * Unlike 727 AnimBase, this base has no shadow flags or submesh masks.
 * Cached skeletal animations and extended base transformation types are not covered.
 */
object OsrsFrameDependencies {
    fun parseFrame(data: ByteArray): OsrsFrameInfo {
        val reader = FrameDependencyReader(data)
        val baseId = reader.u16()
        val count = reader.u8()
        val flags = IntArray(count) {
            reader.u8().also { flag ->
                require(flag and 7 == flag) { "Unsupported legacy OSRS frame flags $flag" }
            }
        }
        for (flag in flags) {
            repeat(Integer.bitCount(flag)) { reader.signedSmart() }
        }
        reader.requireEnd()
        return OsrsFrameInfo(baseId, count)
    }

    fun parseBase(data: ByteArray): OsrsBaseInfo {
        val reader = FrameDependencyReader(data)
        val count = reader.u8()
        repeat(count) {
            val type = reader.u8()
            require(type in 0..5) { "Unsupported legacy OSRS base transform type $type" }
        }
        val lengths = IntArray(count) { reader.u8() }
        for (length in lengths) reader.skip(length)
        reader.requireEnd()
        return OsrsBaseInfo(count)
    }
}

private class FrameDependencyReader(private val data: ByteArray) {
    private var position = 0

    fun u8(): Int {
        require(position < data.size) { "Truncated OSRS frame/base at $position" }
        return data[position++].toInt() and 255
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun signedSmart(): Int {
        val first = u8()
        return if (first < 128) first - 64 else ((first shl 8) or u8()) - 49152
    }

    fun skip(count: Int) {
        require(count >= 0 && count <= data.size - position) { "Truncated OSRS frame/base at $position" }
        position += count
    }

    fun requireEnd() {
        require(position == data.size) { "Trailing OSRS frame/base bytes at $position" }
    }
}
