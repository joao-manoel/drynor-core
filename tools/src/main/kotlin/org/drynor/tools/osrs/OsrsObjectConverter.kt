package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.ObjectDefinition

/**
 * Strict Gielenor decodeOSRS -> native 727 conversion. Reference maps use physical Gielenor IDs.
 * Morph selectors are deliberately rejected: Gielenor disables their vars, so copying either its
 * forced variant or OSRS vars would invent behavior. Sounds and minimap assets also need a separate
 * namespace conversion. No shape-dependent Y correction is baked into a shared definition.
 * [staticPreview] explicitly removes animation, mapscene/map-icon and ambient sound references;
 * the caller must list these omissions from OsrsObjectDependencies in its export manifest.
 * It is unsuitable for gameplay exports.
 */
object OsrsObjectConverter {
    fun convert(
        id: Int,
        data: ByteArray,
        modelMap: Map<Int, Int>,
        animationMap: Map<Int, Int>,
        objectMap: Map<Int, Int>,
        staticPreview: Boolean = false
    ): ObjectDefinition {
        val input = OsrsByteReader(data)
        val result = ObjectDefinition(id)
        fun mapped(raw: Int, offset: Int, map: Map<Int, Int>, kind: String): Int =
            requireNotNull(map[raw + offset]) { "Object $id: missing $kind mapping for ${raw + offset}" }
                .also { require(it >= 0) { "Object $id: invalid $kind destination $it" } }
        fun signedByte() = input.u8().toByte().toInt()
        fun signedShort() = input.u16().toShort().toInt()
        fun material(raw: Int): Short {
            require(raw != 59) { "Object $id: material 59 requires composed Infernal Cape texture" }
            return when (raw) { 10 -> 8; 7 -> 42; else -> raw }.toShort()
        }
        while (true) {
            when (val opcode = input.u8()) {
                0 -> { input.requireEnd(); return result }
                1 -> {
                    val count = input.u8()
                    val types = ByteArray(count)
                    val models = Array(count) { index ->
                        val model = mapped(input.u16(), 200000, modelMap, "model")
                        types[index] = input.u8().toByte()
                        intArrayOf(model)
                    }
                    if (result.modelIds == null) { result.modelIds = models; result.modelTypes = types }
                }
                5 -> {
                    val models = IntArray(input.u8()) { mapped(input.u16(), 200000, modelMap, "model") }
                    // Gielenor exposes opcode 5's models for every shape, unlike vanilla OSRS.
                    if (result.modelIds == null) {
                        result.modelIds = Array(23) { models.copyOf() }
                        result.modelTypes = ByteArray(23) { it.toByte() }
                    }
                }
                2 -> result.name = input.string()
                14 -> result.sizeX = input.u8()
                15 -> result.sizeY = input.u8()
                17 -> { result.blocksSky = false; result.solid = 0; result.block = result.block and ObjectDefinition.PROJECTILE.inv() }
                18 -> { result.blocksSky = false; result.block = result.block and ObjectDefinition.PROJECTILE.inv() }
                19 -> result.interactive = input.u8()
                21 -> result.contouredGround = 1
                22 -> result.delayShading = true
                23 -> result.culling = 1
                24 -> {
                    val raw = input.u16()
                    if (raw != 65535 && !staticPreview) {
                        val animation = mapped(raw, 20000, animationMap, "animation")
                        result.singleAnimation = animation
                        result.animations = intArrayOf(animation)
                    }
                }
                27 -> result.solid = 1
                28 -> result.offsetMultiplier = input.u8() shl 2
                29 -> result.brightness = signedByte()
                in 30..34 -> {
                    val options = result.options ?: arrayOfNulls<String>(6).also { result.options = it; it[5] = "Examine" }
                    options[opcode - 30] = input.string().takeUnless { it.equals("hidden", true) }
                }
                39 -> result.contrast = signedByte() * 5
                40, 41 -> {
                    val count = input.u8()
                    val from = ShortArray(count)
                    val to = ShortArray(count)
                    repeat(count) {
                        val a = input.u16(); val b = input.u16()
                        from[it] = if (opcode == 41) material(a) else a.toShort()
                        to[it] = if (opcode == 41) material(b) else b.toShort()
                    }
                    if (opcode == 40) { result.originalColours = from; result.modifiedColours = to }
                    else { result.originalTextureColours = from; result.modifiedTextureColours = to }
                }
                62 -> result.mirrored = true
                64 -> result.castsShadow = false
                65 -> result.modelSizeX = input.u16()
                66 -> result.modelSizeZ = input.u16()
                67 -> result.modelSizeY = input.u16()
                68 -> { input.u16(); check(staticPreview) { "Object $id: OSRS mapscene requires an explicit sprite mapping" } }
                69 -> result.blockFlag = input.u8()
                // The native encoder divides by four. Do not apply Gielenor's additional shape Y patch.
                70 -> result.offsetX = signedShort() shl 2
                71 -> result.offsetZ = signedShort() shl 2
                72 -> result.offsetY = signedShort() shl 2
                73 -> result.blocksLand = true
                74 -> { result.ignoreOnRoute = true; result.block = result.block and ObjectDefinition.ROUTE.inv() }
                75 -> result.supportItems = input.u8()
                77, 92 -> error("Object $id: morph opcode $opcode requires a reviewed selector policy (${objectMap.size} object mappings supplied)")
                78 -> {
                    input.u16(); input.u8()
                    check(staticPreview) { "Object $id: sound opcode $opcode requires an explicit sound mapping" }
                }
                79 -> {
                    input.u16(); input.u16(); input.u8()
                    repeat(input.u8()) { input.u16() }
                    check(staticPreview) { "Object $id: sound opcode $opcode requires an explicit sound mapping" }
                }
                81 -> { result.contouredGround = 2; result.anInt3023 = input.u8() * 256 }
                82 -> { input.u16(); check(staticPreview) { "Object $id: OSRS map icon requires an explicit map-element mapping" } }
                249 -> {
                    val records = ArrayList<KeyedValue>()
                    repeat(input.u8()) {
                        val type = input.u8()
                        require(type in 0..1) { "Object $id: invalid parameter type $type" }
                        val key = (input.u8() shl 16) or (input.u8() shl 8) or input.u8()
                        val value: Any = if (type == 1) input.string() else
                            (input.u8() shl 24) or (input.u8() shl 16) or (input.u8() shl 8) or input.u8()
                        records += KeyedValue(key, value)
                    }
                    val merged = result.paramRecords.orEmpty() + records
                    result.paramRecords = merged
                    result.params = merged.associate { it.key to it.value }
                }
                else -> error("Object $id: unsupported OSRS object opcode $opcode at byte ${input.position - 1}")
            }
        }
    }
}
