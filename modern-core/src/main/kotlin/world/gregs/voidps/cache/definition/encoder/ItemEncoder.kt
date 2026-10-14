package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ItemDefinition

/**
 * Writes an index 19 obj file.
 *
 * The ground options (30 to 34) and the inventory options (35 to 39) each keep one entry of their array at a
 * non null default - "Take" and "Drop" - so a record is written for an option only when it differs from that.
 */
class ItemEncoder : OpcodeEncoder<ItemDefinition>() {

    override fun opcodes(definition: ItemDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelId != 0) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.graphicScale != 2000) {
            opcodes.add(4)
        }
        if (definition.graphicPitch != 0) {
            opcodes.add(5)
        }
        if (definition.graphicCameraRoll != 0) {
            opcodes.add(6)
        }
        if (definition.graphicTranslateX != 0) {
            opcodes.add(7)
        }
        if (definition.graphicTranslateY != 0) {
            opcodes.add(8)
        }
        if (definition.stackable == 1) {
            opcodes.add(11)
        }
        if (definition.cost != 1) {
            opcodes.add(12)
        }
        if (definition.wearPos != -1) {
            opcodes.add(13)
        }
        if (definition.wearPos2 != -1) {
            opcodes.add(14)
        }
        if (definition.members) {
            opcodes.add(16)
        }
        if (definition.multiStackSize != -1) {
            opcodes.add(18)
        }
        if (definition.primaryMaleModel != -1) {
            opcodes.add(23)
        }
        if (definition.secondaryMaleModel != -1) {
            opcodes.add(24)
        }
        if (definition.primaryFemaleModel != -1) {
            opcodes.add(25)
        }
        if (definition.secondaryFemaleModel != -1) {
            opcodes.add(26)
        }
        if (definition.wearPos3 != -1) {
            opcodes.add(27)
        }
        for (index in 0 until 5) {
            if (definition.floorOptions.getOrNull(index) != floorOption(index)) {
                opcodes.add(30 + index)
            }
        }
        for (index in 0 until 5) {
            if (definition.options.getOrNull(index) != option(index)) {
                opcodes.add(35 + index)
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
        if (definition.exchangeable) {
            opcodes.add(65)
        }
        if (definition.tertiaryMaleModel != -1) {
            opcodes.add(78)
        }
        if (definition.tertiaryFemaleModel != -1) {
            opcodes.add(79)
        }
        if (definition.primaryMaleDialogueHead != -1) {
            opcodes.add(90)
        }
        if (definition.primaryFemaleDialogueHead != -1) {
            opcodes.add(91)
        }
        if (definition.secondaryMaleDialogueHead != -1) {
            opcodes.add(92)
        }
        if (definition.secondaryFemaleDialogueHead != -1) {
            opcodes.add(93)
        }
        if (definition.graphicCameraYaw != 0) {
            opcodes.add(95)
        }
        if (definition.dummyItem != 0) {
            opcodes.add(96)
        }
        if (definition.noteId != -1) {
            opcodes.add(97)
        }
        if (definition.notedTemplateId != -1) {
            opcodes.add(98)
        }
        val stackIds = definition.stackIds
        val stackAmounts = definition.stackAmounts
        if (stackIds != null && stackAmounts != null) {
            for (index in 0 until 10) {
                if (stackIds[index] != 0 || stackAmounts[index] != 0) {
                    opcodes.add(100 + index)
                }
            }
        }
        if (definition.floorScaleX != 128) {
            opcodes.add(110)
        }
        if (definition.floorScaleZ != 128) {
            opcodes.add(111)
        }
        if (definition.floorScaleY != 128) {
            opcodes.add(112)
        }
        if (definition.ambience != 0) {
            opcodes.add(113)
        }
        if (definition.diffusion != 0) {
            opcodes.add(114)
        }
        if (definition.team != 0) {
            opcodes.add(115)
        }
        if (definition.lendId != -1) {
            opcodes.add(121)
        }
        if (definition.lendTemplateId != -1) {
            opcodes.add(122)
        }
        if (definition.maleWieldX != 0 || definition.maleWieldZ != 0 || definition.maleWieldY != 0) {
            opcodes.add(125)
        }
        if (definition.femaleWieldX != 0 || definition.femaleWieldZ != 0 || definition.femaleWieldY != 0) {
            opcodes.add(126)
        }
        if (definition.primaryCursorOpcode != -1 || definition.primaryCursor != -1) {
            opcodes.add(127)
        }
        if (definition.secondaryCursorOpcode != -1 || definition.secondaryCursor != -1) {
            opcodes.add(128)
        }
        if (definition.primaryInterfaceCursorOpcode != -1 || definition.primaryInterfaceCursor != -1) {
            opcodes.add(129)
        }
        if (definition.secondaryInterfaceCursorOpcode != -1 || definition.secondaryInterfaceCursor != -1) {
            opcodes.add(130)
        }
        if (definition.campaigns != null) {
            opcodes.add(132)
        }
        if (definition.pickSizeShift != 0) {
            opcodes.add(134)
        }
        if (definition.singleNoteId != -1) {
            opcodes.add(139)
        }
        if (definition.singleNoteTemplateId != -1) {
            opcodes.add(140)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ItemDefinition, members: ItemDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.modelId)
            2 -> writeString(definition.name)
            4 -> writeShort(definition.graphicScale)
            5 -> writeShort(definition.graphicPitch)
            6 -> writeShort(definition.graphicCameraRoll)
            7 -> writeShort(definition.graphicTranslateX)
            8 -> writeShort(definition.graphicTranslateY)
            11 -> Unit
            12 -> writeInt(definition.cost)
            13 -> writeByte(definition.wearPos)
            14 -> writeByte(definition.wearPos2)
            16 -> Unit
            18 -> writeShort(definition.multiStackSize)
            23 -> writeBigSmart(definition.primaryMaleModel)
            24 -> writeBigSmart(definition.secondaryMaleModel)
            25 -> writeBigSmart(definition.primaryFemaleModel)
            26 -> writeBigSmart(definition.secondaryFemaleModel)
            27 -> writeByte(definition.wearPos3)
            in 30..34 -> writeString(definition.floorOptions.getOrNull(opcode - 30))
            in 35..39 -> writeString(definition.options.getOrNull(opcode - 35))
            40 -> writeColours(definition.originalColours, definition.modifiedColours)
            41 -> writeColours(definition.originalTextureColours, definition.modifiedTextureColours)
            42 -> writeColourPalette(definition.recolourPalette)
            65 -> Unit
            78 -> writeBigSmart(definition.tertiaryMaleModel)
            79 -> writeBigSmart(definition.tertiaryFemaleModel)
            90 -> writeBigSmart(definition.primaryMaleDialogueHead)
            91 -> writeBigSmart(definition.primaryFemaleDialogueHead)
            92 -> writeBigSmart(definition.secondaryMaleDialogueHead)
            93 -> writeBigSmart(definition.secondaryFemaleDialogueHead)
            95 -> writeShort(definition.graphicCameraYaw)
            96 -> writeByte(definition.dummyItem)
            97 -> writeShort(definition.noteId)
            98 -> writeShort(definition.notedTemplateId)
            in 100..109 -> {
                writeShort(definition.stackIds?.get(opcode - 100) ?: 0)
                writeShort(definition.stackAmounts?.get(opcode - 100) ?: 0)
            }
            110 -> writeShort(definition.floorScaleX)
            111 -> writeShort(definition.floorScaleZ)
            112 -> writeShort(definition.floorScaleY)
            113 -> writeByte(definition.ambience)
            114 -> writeByte(definition.diffusion / 5)
            115 -> writeByte(definition.team)
            121 -> writeShort(definition.lendId)
            122 -> writeShort(definition.lendTemplateId)
            125 -> {
                writeByte(definition.maleWieldX shr 2)
                writeByte(definition.maleWieldZ shr 2)
                writeByte(definition.maleWieldY shr 2)
            }
            126 -> {
                writeByte(definition.femaleWieldX shr 2)
                writeByte(definition.femaleWieldZ shr 2)
                writeByte(definition.femaleWieldY shr 2)
            }
            127 -> {
                writeByte(definition.primaryCursorOpcode)
                writeShort(definition.primaryCursor)
            }
            128 -> {
                writeByte(definition.secondaryCursorOpcode)
                writeShort(definition.secondaryCursor)
            }
            129 -> {
                writeByte(definition.primaryInterfaceCursorOpcode)
                writeShort(definition.primaryInterfaceCursor)
            }
            130 -> {
                writeByte(definition.secondaryInterfaceCursorOpcode)
                writeShort(definition.secondaryInterfaceCursor)
            }
            132 -> {
                val campaigns = definition.campaigns ?: IntArray(0)
                writeByte(campaigns.size)
                for (campaign in campaigns) {
                    writeShort(campaign)
                }
            }
            134 -> writeByte(definition.pickSizeShift)
            139 -> writeShort(definition.singleNoteId)
            140 -> writeShort(definition.singleNoteTemplateId)
            249 -> writeParams(definition.id, definition.params, definition.paramRecords)
            else -> error("Unhandled item definition opcode: $opcode for def ${definition.id}")
        }
    }

    private fun floorOption(index: Int) = if (index == 2) "Take" else null

    private fun option(index: Int) = if (index == 4) "Drop" else null
}
