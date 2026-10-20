package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition

/** Converts Gielenor's legacy OSRS assets; rejects modern skeletal and unknown dialects. */
object OsrsAnimationConverter {
    fun convertBase(targetId: Int, data: ByteArray): AnimationBaseDefinition {
        require(targetId in 0..65535)
        val reader = AnimationImportReader(data)
        val count = reader.u8()
        val types = IntArray(count) {
            reader.u8().also { require(it in 0..5 || it == 9 || it == 10) { "Unsupported OSRS transform type $it" } }
        }
        val lengths = IntArray(count) { reader.u8() }
        val labels = Array<IntArray?>(count) { index -> IntArray(lengths[index]) { reader.u8() } }
        reader.end()
        // Gielenor leaves shadow flags false and installs -1 submesh masks for OSRS bases.
        return AnimationBaseDefinition(targetId, types, IntArray(count), IntArray(count) { 65535 }, labels)
    }

    fun convertFrame(targetPackedId: Int, data: ByteArray, convertedBase: AnimationBaseDefinition): AnimationFrameDefinition {
        require(convertedBase.id in 0..65535)
        val reader = AnimationImportReader(data)
        reader.u16() // Source base allocation is resolved by the caller, not by arithmetic here.
        val count = reader.u8()
        require(count <= convertedBase.types.size) { "Frame exceeds skeleton transform count" }
        val flags = IntArray(count) { reader.u8().also { require(it and 7 == it) { "Unsupported OSRS frame flags $it" } } }
        val values = mutableListOf<Int>()
        val wide = mutableListOf<Int>()
        for (index in flags.indices) {
            repeat(Integer.bitCount(flags[index])) {
                val sourceWide = reader.peek() >= 128
                val raw = reader.smart()
                val value = when (convertedBase.types[index]) {
                    1 -> (raw * 4).toShort().toInt()
                    // Rotation is periodic. 727 shifts this value by two on load; Gielenor
                    // first multiplies by sixteen, then performs that same masked shift.
                    2, 9 -> (raw * 16) and 4095
                    else -> raw
                }
                require(value in -16384..16383) { "Translation $value cannot be represented by a 727 signed smart" }
                if (sourceWide || value !in -64..63) wide.add(values.size)
                values.add(value)
            }
        }
        reader.end()
        // Keep sparse flags: omitted scale components must remain the client's default 128.
        return AnimationFrameDefinition(targetPackedId, convertedBase.id, 1, flags, values.toIntArray(), wide.takeIf { it.isNotEmpty() }?.toIntArray())
    }

    /**
     * Map keys are physical Gielenor frame-set IDs (raw sequence archive + 5000).
     * Preview mode explicitly drops sounds and equipment overrides whose IDs need separate imports.
     * Gielenor AnimationConfig.decodeOSRS opcode 4 reads no payload and sets only a local
     * discarded boolean. Its runtime behavior is therefore preserved by emitting no native opcode.
     */
    fun convertSequence(
        targetId: Int,
        data: ByteArray,
        frameSetMap: Map<Int, Int>,
        previewWithoutSoundsAndEquipment: Boolean = false,
    ): AnimationDefinition {
        val reader = AnimationImportReader(data)
        val result = AnimationDefinition(targetId)
        val seen = mutableSetOf<Int>()
        fun frames(count: Int): IntArray {
            val files = IntArray(count) { reader.u16() }
            return IntArray(count) { index ->
                val source = reader.u16()
                if (source == 65535 && files[index] == 65535) -1 else {
                    val target = requireNotNull(frameSetMap[source + 5000]) { "Missing frame set mapping ${source + 5000}" }
                    require(target in 0..65534) { "Frame set mapping exceeds packed ID range" }
                    (target shl 16) or files[index]
                }
            }
        }
        while (true) {
            val opcode = reader.u8()
            if (opcode == 0) break
            require(seen.add(opcode)) { "Duplicate OSRS sequence opcode $opcode" }
            when (opcode) {
                1 -> {
                    val count = reader.u16()
                    require(count <= 32767) { "727 sequence frame count exceeds signed short" }
                    result.durations = IntArray(count) { reader.u16() }
                    result.frames = frames(count)
                }
                2 -> result.loopOffset = reader.u16()
                3 -> {
                    val indices = IntArray(reader.u8()) { reader.u8() }
                    result.interleaveOrder = BooleanArray(256).also { flags -> indices.forEach { flags[it] = true } }
                    result.interleaveRecords = indices
                }
                4 -> Unit // Gielenor AnimationConfig.decodeOSRS: local boolean only, no retained state.
                5 -> result.priority = reader.u8()
                6, 7 -> {
                    val value = reader.u16()
                    require(previewWithoutSoundsAndEquipment) { "Equipment override $value requires explicit item mapping" }
                }
                8 -> result.maxLoops = reader.u8()
                9 -> result.animatingPrecedence = reader.u8()
                10 -> result.walkingPrecedence = reader.u8()
                11 -> result.replayMode = reader.u8()
                12 -> result.expressionFrames = frames(reader.u8())
                13 -> {
                    val count = reader.u8()
                    repeat(count) { reader.u8(); reader.u16() }
                    require(previewWithoutSoundsAndEquipment || count == 0) { "Sounds require explicit sound mapping" }
                }
                else -> throw IllegalArgumentException("Unsupported OSRS sequence opcode $opcode")
            }
        }
        reader.end()
        // Same post-decode defaults as the legacy and 727 clients.
        if (result.animatingPrecedence == -1) result.animatingPrecedence = if (result.interleaveOrder == null) 0 else 2
        if (result.walkingPrecedence == -1) result.walkingPrecedence = if (result.interleaveOrder == null) 0 else 2
        return result
    }
}

private class AnimationImportReader(private val data: ByteArray) {
    private var position = 0
    fun peek(): Int {
        require(position < data.size) { "Truncated OSRS animation at $position" }
        return data[position].toInt() and 255
    }
    fun u8(): Int = peek().also { position++ }
    fun u16(): Int = (u8() shl 8) or u8()
    fun smart(): Int = if (peek() < 128) u8() - 64 else u16() - 49152
    fun end() { require(position == data.size) { "Trailing OSRS animation bytes at $position" } }
}
