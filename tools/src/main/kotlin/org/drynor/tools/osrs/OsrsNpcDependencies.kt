package org.drynor.tools.osrs

import java.nio.charset.Charset

data class NpcDependencies(
    val name: String,
    val models: Set<Int>,
    val animations: Set<Int>,
    val morphs: Set<Int>,
    val textures: Set<Int>,
    val varbits: Set<Int> = emptySet(),
    val varps: Set<Int> = emptySet(),
    val headIcons: Set<Int> = emptySet(),
)

/** Reads the OSRS dialect used by Gielenor NPCConfig.readValuesOSRS, before ID offsets. */
object OsrsNpcDependencies {
    fun parse(data: ByteArray): NpcDependencies {
        val reader = NpcDependencyReader(data)
        var name = "null"
        val models = linkedSetOf<Int>()
        val animations = linkedSetOf<Int>()
        val morphs = linkedSetOf<Int>()
        val textures = linkedSetOf<Int>()
        val varbits = linkedSetOf<Int>()
        val varps = linkedSetOf<Int>()
        val headIcons = linkedSetOf<Int>()
        fun reference(target: MutableSet<Int>) {
            val id = reader.unsignedShort()
            if (id != 65535) target.add(id)
        }
        while (true) {
            val opcode = reader.unsignedByte()
            when (opcode) {
                0 -> {
                    require(reader.remaining == 0) { "Trailing NPC bytes at ${reader.position}" }
                    return NpcDependencies(name, models, animations, morphs, textures, varbits, varps, headIcons)
                }
                1, 60 -> repeat(reader.unsignedByte()) { reference(models) }
                2 -> name = reader.string()
                12, 100, 101 -> reader.skip(1)
                13, 14, 15, 16 -> reference(animations)
                17 -> repeat(4) { reference(animations) }
                in 30..34 -> reader.string()
                40 -> reader.skip(reader.unsignedByte() * 4)
                41 -> repeat(reader.unsignedByte()) { repeat(2) { reference(textures) } }
                42 -> reader.skip(reader.unsignedByte())
                93, 99, 107, 109, 111 -> Unit
                95, 97, 98, 103 -> reader.skip(2)
                102 -> reference(headIcons)
                106, 118 -> {
                    reference(varbits)
                    reference(varps)
                    if (opcode == 118) reference(morphs)
                    repeat(reader.unsignedByte() + 1) { reference(morphs) }
                }
                249 -> repeat(reader.unsignedByte()) {
                    val type = reader.unsignedByte()
                    require(type == 0 || type == 1) { "Invalid NPC parameter type $type" }
                    reader.skip(3)
                    if (type == 1) reader.string() else reader.skip(4)
                }
                else -> throw IllegalArgumentException("Unsupported OSRS NPC opcode $opcode at ${reader.position - 1}")
            }
        }
    }
}

private class NpcDependencyReader(private val data: ByteArray) {
    var position = 0
        private set
    val remaining: Int get() = data.size - position

    fun unsignedByte(): Int {
        require(remaining > 0) { "Truncated NPC definition at $position" }
        return data[position++].toInt() and 255
    }

    fun unsignedShort(): Int = (unsignedByte() shl 8) or unsignedByte()

    fun skip(count: Int) {
        require(count >= 0 && count <= remaining) { "Truncated NPC definition at $position: need $count bytes" }
        position += count
    }

    fun string(): String {
        val start = position
        while (unsignedByte() != 0) Unit
        return String(data, start, position - start - 1, Charset.forName("windows-1252"))
    }
}
