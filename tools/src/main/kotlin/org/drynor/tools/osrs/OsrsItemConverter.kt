package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.data.ItemDefinition

/** Gielenor OSRS item records, not its native 718/727 records. Unknown payloads fail closed. */
object OsrsItemConverter {
    fun decode(id: Int, bytes: ByteArray, unconvertedLinks: MutableMap<Int, Int>? = null): ItemDefinition {
        val input = OsrsByteReader(bytes)
        val item = ItemDefinition(id)
        fun model() = input.u16() + 200000
        fun signed() = input.u16().toShort().toInt()
        fun integer() = (input.u16() shl 16) or input.u16()
        while (true) when (val opcode = input.u8()) {
            0 -> { input.requireEnd(); return item }
            1 -> item.modelId = model()
            2 -> item.name = input.string()
            4 -> item.graphicScale = input.u16()
            5 -> item.graphicPitch = input.u16()
            6 -> item.graphicCameraRoll = input.u16()
            7 -> item.graphicTranslateX = signed()
            8 -> item.graphicTranslateY = signed()
            11 -> item.stackable = 1
            12 -> item.cost = integer()
            16 -> item.members = true
            23 -> { item.primaryMaleModel = model(); require(input.u8() == 0) { "Nonzero OSRS male offset requires explicit conversion" } }
            24 -> item.secondaryMaleModel = model()
            25 -> { item.primaryFemaleModel = model(); require(input.u8() == 0) { "Nonzero OSRS female offset requires explicit conversion" } }
            26 -> item.secondaryFemaleModel = model()
            in 30..34 -> item.floorOptions[opcode - 30] = input.string().takeUnless { it.equals("hidden", true) }
            in 35..39 -> item.options[opcode - 35] = input.string()
            40, 41 -> {
                val size = input.u8()
                val from = ShortArray(size); val to = ShortArray(size)
                repeat(size) { from[it] = input.u16().toShort(); to[it] = input.u16().toShort() }
                if (opcode == 40) { item.originalColours = from; item.modifiedColours = to }
                else { item.originalTextureColours = from; item.modifiedTextureColours = to }
            }
            42 -> item.team = input.u8().toByte().toInt()
            65 -> item.exchangeable = true
            78 -> item.tertiaryMaleModel = model()
            79 -> item.tertiaryFemaleModel = model()
            95 -> item.graphicCameraYaw = input.u16()
            110 -> item.floorScaleX = input.u16()
            111 -> item.floorScaleZ = input.u16()
            112 -> item.floorScaleY = input.u16()
            113 -> item.ambience = input.u8().toByte().toInt()
            114 -> item.diffusion = input.u8().toByte().toInt() * 5
            115 -> item.team = input.u8()
            148, 149 -> requireNotNull(unconvertedLinks) { "OSRS placeholder links need explicit handling" }.put(opcode, input.u16() + 30000)
            249 -> {
                val params = linkedMapOf<Int, Any>()
                repeat(input.u8()) {
                    val type = input.u8(); require(type in 0..1)
                    val key = (input.u8() shl 16) or input.u16()
                    require(key !in params) { "Duplicate OSRS item parameter $key requires preservation" }
                    params[key] = if (type == 1) input.string() else integer()
                }
                item.params = params
            }
            else -> error("Unsupported OSRS item opcode $opcode at ${input.position - 1}")
        }
    }
}
