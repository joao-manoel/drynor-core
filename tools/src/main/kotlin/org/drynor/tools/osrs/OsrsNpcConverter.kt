package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition

data class ConvertedNpc(val npc: NPCDefinition, val bas: BASDefinition, val warnings: List<String>)

/** Converts raw Gielenor OSRS NPC bytes; reference maps are keyed by physical Gielenor IDs.
 * Gielenor synthesizes only idle/walk in Class349. Here the explicitly stored directional and turn
 * sequences are retained in native BAS slots as well. Missing slots remain absent (no invented run).
 * Preview omissions are reported; this converter does not apply NPC-specific Java customizations.
 */
object OsrsNpcConverter {
    fun convert(targetId: Int, data: ByteArray, modelMap: Map<Int, Int>, animationMap: Map<Int, Int>,
                basId: Int, preview: Boolean = true): ConvertedNpc {
        require(targetId >= 0 && basId in 0..65534) { "Invalid NPC/BAS destination" }
        val input = OsrsByteReader(data)
        val npc = NPCDefinition(targetId, renderEmote = basId)
        val bas = BASDefinition(basId)
        val warnings = mutableListOf("Gielenor NPC-specific Java overrides are outside the raw definition conversion")
        val records = mutableListOf<KeyedValue>()
        fun mapped(raw: Int, offset: Int, map: Map<Int, Int>, kind: String): Int =
            requireNotNull(map[raw + offset]) { "NPC $targetId: missing $kind mapping ${raw + offset}" }
                .also { require(it >= 0) { "Invalid $kind destination $it" } }
        fun animation(): Int = input.u16().let { if (it == 65535) -1 else mapped(it, 20000, animationMap, "animation") }
        fun omit(message: String) { require(preview) { message }; warnings.add(message) }
        fun material(raw: Int): Short {
            require(raw != 59) { "NPC $targetId: material 59 requires composed Infernal Cape texture" }
            return when (raw) { 10 -> 8; 7 -> 42; else -> raw }.toShort()
        }
        while (true) when (val opcode = input.u8()) {
            0 -> {
                input.requireEnd()
                if (records.isNotEmpty()) {
                    npc.params = records.associate { it.key to it.value }
                    if (npc.params!!.size != records.size) npc.paramRecords = records.toList()
                }
                return ConvertedNpc(npc, bas, warnings.toList())
            }
            1 -> npc.modelIds = IntArray(input.u8()) { mapped(input.u16(), 200000, modelMap, "model") }
            2 -> npc.name = input.string()
            12 -> npc.size = input.u8().also { require(it > 0) { "Zero NPC size" } }
            13 -> bas.standAnimation = animation()
            14 -> bas.walkAnimation = animation()
            15 -> bas.standTurn1 = animation()
            16 -> bas.standTurn2 = animation()
            17 -> {
                bas.walkAnimation = animation()
                bas.walkDir3 = animation() // Backward, relative angle 6144..10240 in native client.
                bas.walkDir2 = animation() // OSRS left.
                bas.walkDir1 = animation() // OSRS right.
            }
            in 30..34 -> npc.options[opcode - 30] = input.string().takeUnless { it.equals("hidden", true) }
            40, 41 -> {
                val from = ShortArray(input.u8())
                val to = ShortArray(from.size)
                repeat(from.size) {
                    val a = input.u16(); val b = input.u16()
                    from[it] = if (opcode == 41) material(a) else a.toShort()
                    to[it] = if (opcode == 41) material(b) else b.toShort()
                }
                if (opcode == 40) { npc.originalColours = from; npc.modifiedColours = to }
                else { npc.originalTextureColours = from; npc.modifiedTextureColours = to }
            }
            60 -> npc.dialogueModels = IntArray(input.u8()) { mapped(input.u16(), 200000, modelMap, "head model") }
            93 -> npc.drawMinimapDot = false
            95 -> npc.combat = input.u16()
            97 -> npc.scaleXY = input.u16()
            98 -> npc.scaleZ = input.u16()
            99 -> npc.priorityRender = true
            100 -> npc.lightModifier = input.u8().toByte().toInt()
            101 -> npc.shadowModifier = input.u8().toByte().toInt() * 5
            102 -> {
                val icon = input.u16()
                if (icon != 65535) omit("NPC $targetId: omitted OSRS head icon $icon; sprite mapping unverified")
            }
            103 -> npc.rotation = input.u16()
            106, 118 -> error("NPC $targetId: OSRS morphs require explicit selector/target conversion")
            107 -> npc.clickable = false
            109 -> npc.slowWalk = false
            111 -> omit("NPC $targetId: omitted OSRS opcode 111; native animation/shadow semantics unverified")
            249 -> repeat(input.u8()) {
                val type = input.u8()
                require(type in 0..1) { "Invalid NPC param type $type" }
                val key = (input.u8() shl 16) or (input.u8() shl 8) or input.u8()
                val value: Any = if (type == 1) input.string() else
                    (input.u8() shl 24) or (input.u8() shl 16) or (input.u8() shl 8) or input.u8()
                records.add(KeyedValue(key, value))
            }
            else -> error("Unsupported OSRS NPC opcode $opcode at ${input.position - 1}")
        }
    }
}
