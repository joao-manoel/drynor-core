package org.drynor.tools.osrs

import java.nio.charset.Charset

data class ObjectDependencies(
    val name: String,
    val models: Set<Int>,
    val animations: Set<Int>,
    val morphs: Set<Int>,
    val textures: Set<Int>,
    val sounds: Set<Int>,
    val varbits: Set<Int>,
    val varps: Set<Int>,
    val sprites: Set<Int>,
    val mapIcons: Set<Int>
)

/** Dependency grammar from Gielenor ObjectConfig.decodeOSRS; never skip an unknown payload. */
object OsrsObjectDependencies {
    fun parse(data: ByteArray): ObjectDependencies {
        val input = OsrsByteReader(data)
        var name = "null"
        val models = sortedSetOf<Int>()
        val animations = sortedSetOf<Int>()
        val morphs = sortedSetOf<Int>()
        val textures = sortedSetOf<Int>()
        val sounds = sortedSetOf<Int>()
        val varbits = sortedSetOf<Int>()
        val varps = sortedSetOf<Int>()
        val sprites = sortedSetOf<Int>()
        val icons = sortedSetOf<Int>()
        fun reference(set: MutableSet<Int>, absent65535: Boolean = false) {
            input.u16().takeIf { !absent65535 || it != 65535 }?.let(set::add)
        }
        while (true) {
            val opcode = input.u8()
            when (opcode) {
                0 -> {
                    input.requireEnd()
                    return ObjectDependencies(name, models, animations, morphs, textures, sounds, varbits, varps, sprites, icons)
                }
                1 -> repeat(input.u8()) { reference(models); input.u8() }
                2 -> name = input.string()
                5 -> repeat(input.u8()) { reference(models) }
                14, 15, 19, 28, 29, 39, 69, 75, 81 -> input.skip(1)
                17, 18, 21, 22, 23, 27, 62, 64, 73, 74 -> Unit
                24 -> reference(animations, absent65535 = true)
                in 30..34 -> input.string()
                40 -> input.skip(input.u8() * 4)
                41 -> repeat(input.u8()) { reference(textures); reference(textures) }
                65, 66, 67, 70, 71, 72 -> input.skip(2)
                68 -> reference(sprites)
                77, 92 -> {
                    reference(varbits, absent65535 = true)
                    reference(varps, absent65535 = true)
                    if (opcode == 92) reference(morphs, absent65535 = true)
                    repeat(input.u8() + 1) { reference(morphs, absent65535 = true) }
                }
                78 -> { reference(sounds); input.skip(1) }
                79 -> { input.skip(5); repeat(input.u8()) { reference(sounds) } }
                82 -> reference(icons)
                249 -> repeat(input.u8()) {
                    val type = input.u8()
                    require(type in 0..1) { "Invalid parameter type $type" }
                    input.skip(3)
                    if (type == 1) input.string() else input.skip(4)
                }
                else -> error("Unsupported OSRS object opcode $opcode at byte ${input.position - 1}")
            }
        }
    }
}

internal class OsrsByteReader(private val data: ByteArray) {
    var position: Int = 0
        private set

    fun u8(): Int {
        require(position < data.size) { "Unexpected EOF at byte $position" }
        return data[position++].toInt() and 255
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun skip(size: Int) {
        require(size >= 0 && size <= data.size - position) { "Truncated payload at byte $position (need $size)" }
        position += size
    }

    fun string(): String {
        val start = position
        while (u8() != 0) { /* bounded by u8 */ }
        return String(data, start, position - start - 1, CP1252)
    }

    fun requireEnd() = require(position == data.size) { "Unexpected trailing bytes at $position/${data.size}" }

    companion object {
        private val CP1252 = Charset.forName("windows-1252")
    }
}
