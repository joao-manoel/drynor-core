package org.drynor.tools.osrs

data class SequenceDependencies(
    val frameIds: Set<Int>,
    /** Sound IDs extracted from the high 16 bits of the packed OSRS sound value. */
    val sounds: Set<Int>,
    /** Raw hand override values: item IDs are encoded with +512; zero means hide equipment. */
    val equipment: Set<Int>,
)

/** Gielenor AnimationDefinitions.readValuesOSRS dialect; frame IDs retain OSRS archive/file packing. */
object OsrsSequenceDependencies {
    fun parse(bytes: ByteArray): SequenceDependencies {
        val reader = SequenceDependencyReader(bytes)
        val frames = linkedSetOf<Int>()
        val sounds = linkedSetOf<Int>()
        val equipment = linkedSetOf<Int>()
        fun frameList(count: Int) {
            val files = IntArray(count) { reader.u16() }
            repeat(count) { index ->
                val packed = (reader.u16() shl 16) or files[index]
                if (packed != -1) frames.add(packed)
            }
        }
        while (true) {
            when (val opcode = reader.u8()) {
                0 -> {
                    require(reader.remaining == 0) { "Trailing sequence bytes at ${reader.position}" }
                    return SequenceDependencies(frames, sounds, equipment)
                }
                1 -> {
                    val count = reader.u16()
                    reader.skip(count * 2)
                    frameList(count)
                }
                2, 23, 24 -> reader.skip(2)
                3 -> reader.skip(reader.u8())
                4, 14, 15, 16, 18 -> Unit
                5, 8, 9, 10, 11, 17, 22 -> reader.skip(1)
                6, 7 -> {
                    val encoded = reader.u16()
                    if (encoded != 65535) equipment.add(encoded)
                }
                12 -> frameList(reader.u8())
                13 -> repeat(reader.u8()) {
                    val packed = (reader.u8() shl 16) or reader.u16()
                    if (packed != 0) sounds.add(packed ushr 8)
                }
                19 -> reader.skip(2)
                119 -> reader.skip(3)
                20 -> reader.skip(5)
                120 -> reader.skip(6)
                249 -> repeat(reader.u8()) {
                    val type = reader.u8()
                    require(type == 0 || type == 1) { "Invalid sequence parameter type $type" }
                    reader.skip(3)
                    if (type == 1) while (reader.u8() != 0) Unit else reader.skip(4)
                }
                else -> throw IllegalArgumentException("Unsupported OSRS sequence opcode $opcode at ${reader.position - 1}")
            }
        }
    }
}

private class SequenceDependencyReader(private val data: ByteArray) {
    var position = 0
        private set
    val remaining: Int get() = data.size - position

    fun u8(): Int {
        require(remaining > 0) { "Truncated sequence at $position" }
        return data[position++].toInt() and 255
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun skip(count: Int) {
        require(count >= 0 && count <= remaining) { "Truncated sequence at $position: need $count bytes" }
        position += count
    }
}
