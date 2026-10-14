package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.NPCDefinition

/**
 * Writes an index 18 npc file.
 *
 * Transform ids are plain shorts here rather than the bigSmarts a loc uses, 65535 standing in for -1.
 * Options come from [NPCDefinition.rawOptions] so that a file holding both a public (30 to 34) and a members
 * (150 to 154) record for the same slot writes both back; [members] supplies the members half for a
 * definition built by hand, which is what the two argument encode form is for.
 */
class NPCEncoder : OpcodeEncoder<NPCDefinition>() {

    override fun opcodes(definition: NPCDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelIds != null) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.size != 1) {
            opcodes.add(12)
        }
        val raw = definition.rawOptions
        for (index in 0 until 5) {
            if (if (raw != null) raw[index] != null else definition.options.getOrNull(index) != null) {
                opcodes.add(30 + index)
            }
        }
        if (definition.originalColours != null && definition.modifiedColours != null) {
            opcodes.add(40)
        }
        if (definition.originalTextureColours != null && definition.modifiedTextureColours != null) {
            opcodes.add(41)
        }
        if (definition.recolourPalette != null) {
            opcodes.add(42)
        }
        if (definition.dialogueModels != null) {
            opcodes.add(60)
        }
        if (!definition.drawMinimapDot) {
            opcodes.add(93)
        }
        if (definition.combat != -1) {
            opcodes.add(95)
        }
        if (definition.scaleXY != 128) {
            opcodes.add(97)
        }
        if (definition.scaleZ != 128) {
            opcodes.add(98)
        }
        if (definition.priorityRender) {
            opcodes.add(99)
        }
        if (definition.lightModifier != 0) {
            opcodes.add(100)
        }
        if (definition.shadowModifier != 0) {
            opcodes.add(101)
        }
        if (definition.headIcon != -1) {
            opcodes.add(102)
        }
        if (definition.rotation != 32) {
            opcodes.add(103)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 118 else 106)
        }
        if (!definition.clickable) {
            opcodes.add(107)
        }
        if (!definition.slowWalk) {
            opcodes.add(109)
        }
        if (!definition.animateIdle) {
            opcodes.add(111)
        }
        if (definition.primaryShadowColour != 0.toShort() || definition.secondaryShadowColour != 0.toShort()) {
            opcodes.add(113)
        }
        if (definition.primaryShadowModifier != (-96).toByte() || definition.secondaryShadowModifier != (-16).toByte()) {
            opcodes.add(114)
        }
        if (definition.walkMask != 0.toByte()) {
            opcodes.add(119)
        }
        if (definition.translations != null) {
            opcodes.add(121)
        }
        if (definition.hitbarGraphic != -1) {
            opcodes.add(122)
        }
        if (definition.height != -1) {
            opcodes.add(123)
        }
        if (definition.respawnDirection != 4.toByte()) {
            opcodes.add(125)
        }
        if (definition.renderEmote != -1) {
            opcodes.add(127)
        }
        if (definition.moveSpeed != -1) {
            opcodes.add(128)
        }
        if (definition.walkSound != -1 || definition.crawlSound != -1 || definition.idleSound != -1 ||
            definition.runSound != -1 || definition.soundDistance != 0
        ) {
            opcodes.add(134)
        }
        if (definition.primaryCursorOp != -1 || definition.primaryCursor != -1) {
            opcodes.add(135)
        }
        if (definition.secondaryCursorOp != -1 || definition.secondaryCursor != -1) {
            opcodes.add(136)
        }
        if (definition.attackCursor != -1) {
            opcodes.add(137)
        }
        if (definition.armyIcon != -1) {
            opcodes.add(138)
        }
        if (definition.graphicId != -1) {
            opcodes.add(139)
        }
        if (definition.ambientSoundVolume != 255) {
            opcodes.add(140)
        }
        if (definition.visiblePriority) {
            opcodes.add(141)
        }
        if (definition.mapFunction != -1) {
            opcodes.add(142)
        }
        if (definition.invisiblePriority) {
            opcodes.add(143)
        }
        for (index in 0 until 5) {
            if (raw?.get(5 + index) != null) {
                opcodes.add(150 + index)
            }
        }
        if (definition.hue != 0.toByte() || definition.saturation != 0.toByte() ||
            definition.lightness != 0.toByte() || definition.opacity != 0.toByte()
        ) {
            opcodes.add(155)
        }
        if (definition.mainOptionIndex == 1.toByte()) {
            opcodes.add(158)
        }
        if (definition.mainOptionIndex == 0.toByte()) {
            opcodes.add(159)
        }
        if (definition.campaigns != null) {
            opcodes.add(160)
        }
        if (definition.aBoolean2883) {
            opcodes.add(162)
        }
        if (definition.anInt2803 != -1) {
            opcodes.add(163)
        }
        if (definition.anInt2844 != 256 || definition.anInt2852 != 256) {
            opcodes.add(164)
        }
        if (definition.anInt2831 != 0) {
            opcodes.add(165)
        }
        if (definition.anInt2862 != 0) {
            opcodes.add(168)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: NPCDefinition, members: NPCDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val models = definition.modelIds ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            2 -> writeString(definition.name)
            12 -> writeByte(definition.size)
            in 30..34 -> writeString(definition.rawOptions?.get(opcode - 30) ?: definition.options.getOrNull(opcode - 30))
            40 -> writeColours(definition.originalColours, definition.modifiedColours)
            41 -> writeColours(definition.originalTextureColours, definition.modifiedTextureColours)
            42 -> writeColourPalette(definition.recolourPalette)
            60 -> {
                val models = definition.dialogueModels ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            93, 99, 107, 109, 111, 141, 143, 158, 159, 162, 169 -> Unit
            95 -> writeShort(definition.combat)
            97 -> writeShort(definition.scaleXY)
            98 -> writeShort(definition.scaleZ)
            100 -> writeByte(definition.lightModifier)
            101 -> writeByte(definition.shadowModifier / 5)
            102 -> writeShort(definition.headIcon)
            103 -> writeShort(definition.rotation)
            106, 118 -> {
                val transforms = definition.transforms ?: IntArray(2) { -1 }
                writeShort(definition.varbit)
                writeShort(definition.varp)
                if (opcode == 118) {
                    writeShort(transforms.last())
                }
                writeByte(transforms.size - 2)
                for (index in 0 until transforms.size - 1) {
                    writeShort(transforms[index])
                }
            }
            113 -> {
                writeShort(definition.primaryShadowColour.toInt())
                writeShort(definition.secondaryShadowColour.toInt())
            }
            114 -> {
                writeByte(definition.primaryShadowModifier.toInt())
                writeByte(definition.secondaryShadowModifier.toInt())
            }
            119 -> writeByte(definition.walkMask.toInt())
            121 -> {
                val translations = definition.translations ?: arrayOfNulls(0)
                writeByte(translations.count { it != null })
                for (index in translations.indices) {
                    val translation = translations[index] ?: continue
                    writeByte(index)
                    writeByte(translation[0])
                    writeByte(translation[1])
                    writeByte(translation[2])
                }
            }
            122 -> writeShort(definition.hitbarGraphic)
            123 -> writeShort(definition.height)
            125 -> writeByte(definition.respawnDirection.toInt())
            127 -> writeShort(definition.renderEmote)
            128 -> writeByte(definition.moveSpeed)
            134 -> {
                writeShort(definition.walkSound)
                writeShort(definition.crawlSound)
                writeShort(definition.idleSound)
                writeShort(definition.runSound)
                writeByte(definition.soundDistance)
            }
            135 -> {
                writeByte(definition.primaryCursorOp)
                writeShort(definition.primaryCursor)
            }
            136 -> {
                writeByte(definition.secondaryCursorOp)
                writeShort(definition.secondaryCursor)
            }
            137 -> writeShort(definition.attackCursor)
            138 -> writeBigSmart(definition.armyIcon)
            139 -> writeShort(definition.graphicId)
            140 -> writeByte(definition.ambientSoundVolume)
            142 -> writeShort(definition.mapFunction)
            in 150..154 -> writeString(definition.rawOptions?.get(5 + opcode - 150) ?: members.options.getOrNull(opcode - 150))
            155 -> {
                writeByte(definition.hue.toInt())
                writeByte(definition.saturation.toInt())
                writeByte(definition.lightness.toInt())
                writeByte(definition.opacity.toInt())
            }
            160 -> {
                val campaigns = definition.campaigns ?: IntArray(0)
                writeByte(campaigns.size)
                for (campaign in campaigns) {
                    writeShort(campaign)
                }
            }
            163 -> writeByte(definition.anInt2803)
            164 -> {
                writeShort(definition.anInt2844)
                writeShort(definition.anInt2852)
            }
            165 -> writeByte(definition.anInt2831)
            168 -> writeByte(definition.anInt2862)
            249 -> writeParams(definition.id, definition.params, definition.paramRecords)
            else -> error("Unhandled NPC definition opcode: $opcode for def ${definition.id}")
        }
    }
}
