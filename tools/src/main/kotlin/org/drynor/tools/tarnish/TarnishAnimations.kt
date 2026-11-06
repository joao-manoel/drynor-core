package org.drynor.tools.tarnish

import org.drynor.tools.osrs.OsrsAnimationConverter
import org.drynor.tools.osrs.OsrsByteReader
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/** Strict legacy animation adapter: never interprets modern skeletal payloads as frames. */
internal object TarnishAnimations {
    data class Sequence(val durations: IntArray, val frames: IntArray, val loopOffset: Int)

    fun sequence(bytes: ByteArray): Sequence {
        val reader = OsrsByteReader(bytes)
        var durations: IntArray? = null
        var frames: IntArray? = null
        var loop = -1
        val seen = mutableSetOf<Int>()
        while (true) {
            when (val opcode = reader.u8()) {
                0 -> {
                    reader.requireEnd()
                    return Sequence(requireNotNull(durations), requireNotNull(frames), loop)
                }
                1 -> {
                    require(seen.add(opcode)) { "Duplicate sequence frames" }
                    val count = reader.u16()
                    require(count in 1..32767)
                    durations = IntArray(count) { reader.u16() }
                    val files = IntArray(count) { reader.u16() }
                    frames = IntArray(count) { (reader.u16() shl 16) or files[it] }
                }
                2 -> { require(seen.add(opcode)); loop = reader.u16() }
                else -> error("Unreviewed Tarnish sequence opcode $opcode; modern animations/sounds/equipment must not be dropped")
            }
        }
    }

    fun convertSequence(target: Int, source: Sequence, groups: Map<Int, Int>): AnimationDefinition = AnimationDefinition(target).apply {
        durations = source.durations.copyOf()
        frames = source.frames.map { packed ->
            val group = groups.getValue(packed ushr 16)
            require(group in 0..32767) { "Native signed frame group overflow" }
            val file = packed and 65535
            require(file <= 32767) { "Native signed frame file overflow" }
            (group shl 16) or file
        }.toIntArray()
        loopOffset = source.loopOffset
        animatingPrecedence = 0
        walkingPrecedence = 0
    }

    fun records(bytes: ByteArray, highestIdHeader: Boolean = false): Map<Int, ByteArray> {
        val reader = ByteBuffer.wrap(bytes)
        require(reader.remaining() >= 2)
        val count = reader.short.toInt() and 65535
        val result = linkedMapOf<Int, ByteArray>()
        repeat(if (highestIdHeader) count + 1 else count) {
            require(reader.remaining() >= 4)
            val id = reader.short.toInt() and 65535
            val size = reader.short.toInt() and 65535
            require(size <= reader.remaining() && id !in result)
            result[id] = ByteArray(size).also(reader::get)
        }
        require(!reader.hasRemaining()) { "Unindexed animation records" }
        return result
    }

    fun frames(cache: TarnishCache, group: Int): Map<Int, ByteArray> {
        val bytes = GZIPInputStream(ByteArrayInputStream(cache.file(2, group))).use { stream ->
            val result = stream.readNBytes(8 * 1024 * 1024 + 1)
            require(result.size <= 8 * 1024 * 1024) { "Frame group decompression limit" }
            result
        }
        val reader = ByteBuffer.wrap(bytes)
        val highest = reader.short.toInt() and 65535
        val result = linkedMapOf<Int, ByteArray>()
        repeat(highest + 1) {
            require(reader.remaining() >= 5)
            val id = reader.short.toInt() and 65535
            val size = ((reader.get().toInt() and 255) shl 16) or ((reader.get().toInt() and 255) shl 8) or (reader.get().toInt() and 255)
            require(id <= highest && id !in result && size <= reader.remaining())
            result[id] = ByteArray(size).also(reader::get)
            if (id == highest) { require(!reader.hasRemaining()); return result }
        }
        error("Missing highest Tarnish frame $highest")
    }

    fun legacyBase(bytes: ByteArray): ByteArray {
        val reader = OsrsByteReader(bytes)
        val count = reader.u8()
        repeat(count) { require(reader.u8() in 0..5) { "Unreviewed legacy transform type" } }
        val sizes = IntArray(count) { reader.u8() }
        sizes.forEach(reader::skip)
        val boundary = reader.position
        if (boundary < bytes.size) require(reader.u16() == 0) { "Weighted skeletal base requires a dedicated converter" }
        reader.requireEnd()
        return bytes.copyOf(boundary)
    }

    fun convertBase(target: Int, bytes: ByteArray) = OsrsAnimationConverter.convertBase(target, legacyBase(bytes))
    // Tarnish uses standard OSRS legacy transforms. Native 727 has four times the geometry
    // precision and a 16384-step rotation circle; the shared converter supplies the scale bridge.
    fun convertFrame(target: Int, bytes: ByteArray, base: world.gregs.voidps.cache.definition.data.AnimationBaseDefinition) =
        OsrsAnimationConverter.convertFrame(target, bytes, base)
}
