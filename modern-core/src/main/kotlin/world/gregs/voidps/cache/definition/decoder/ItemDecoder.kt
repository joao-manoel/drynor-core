package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ITEMS
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.encoder.ItemEncoder

class ItemDecoder : DefinitionDecoder<ItemDefinition>(ITEMS) {

    private val encoder = ItemEncoder()

    override fun create(size: Int) = Array(size) { ItemDefinition(it) }

    override fun canonicalOpcodes(definition: ItemDefinition): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun ItemDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // Opcode 1: model ID (bigSmart for rev 727)
            1 -> modelId = buffer.readBigSmart()
            2 -> name = buffer.readString()
            4 -> graphicScale = buffer.readUnsignedShort()
            5 -> graphicPitch = buffer.readUnsignedShort()
            6 -> graphicCameraRoll = buffer.readUnsignedShort()
            7 -> {
                graphicTranslateX = buffer.readUnsignedShort()
                if (graphicTranslateX > 32767) {
                    graphicTranslateX -= 65536
                }
            }
            8 -> {
                graphicTranslateY = buffer.readUnsignedShort()
                if (graphicTranslateY > 32767) {
                    graphicTranslateY -= 65536
                }
            }
            11 -> stackable = 1
            12 -> cost = buffer.readInt()
            13 -> wearPos = buffer.readUnsignedByte()
            14 -> wearPos2 = buffer.readUnsignedByte()
            16 -> members = true
            18 -> multiStackSize = buffer.readUnsignedShort()
            // Opcodes 23-26: equipment model IDs (bigSmart for rev 727)
            23 -> primaryMaleModel = buffer.readBigSmart()
            24 -> secondaryMaleModel = buffer.readBigSmart()
            25 -> primaryFemaleModel = buffer.readBigSmart()
            26 -> secondaryFemaleModel = buffer.readBigSmart()
            27 -> wearPos3 = buffer.readUnsignedByte()
            in 30..34 -> floorOptions[opcode - 30] = buffer.readString()
            in 35..39 -> options[opcode - 35] = buffer.readString()
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            42 -> readColourPalette(buffer)
            // Opcode 43: tooltip color (int + boolean flag) - skip data
            43 -> buffer.readInt()
            65 -> exchangeable = true
            // Opcodes 78-79: tertiary equipment model IDs (bigSmart for rev 727)
            78 -> tertiaryMaleModel = buffer.readBigSmart()
            79 -> tertiaryFemaleModel = buffer.readBigSmart()
            // Opcodes 90-93: dialogue head model IDs (bigSmart for rev 727)
            90 -> primaryMaleDialogueHead = buffer.readBigSmart()
            91 -> primaryFemaleDialogueHead = buffer.readBigSmart()
            92 -> secondaryMaleDialogueHead = buffer.readBigSmart()
            93 -> secondaryFemaleDialogueHead = buffer.readBigSmart()
            95 -> graphicCameraYaw = buffer.readUnsignedShort()
            96 -> dummyItem = buffer.readUnsignedByte()
            97 -> noteId = buffer.readUnsignedShort()
            98 -> notedTemplateId = buffer.readUnsignedShort()
            in 100..109 -> {
                if (stackIds == null) {
                    stackAmounts = IntArray(10)
                    stackIds = IntArray(10)
                }
                stackIds!![opcode - 100] = buffer.readUnsignedShort()
                stackAmounts!![opcode - 100] = buffer.readUnsignedShort()
            }
            110 -> floorScaleX = buffer.readUnsignedShort()
            111 -> floorScaleZ = buffer.readUnsignedShort()
            112 -> floorScaleY = buffer.readUnsignedShort()
            113 -> ambience = buffer.readByte()
            114 -> diffusion = buffer.readByte() * 5
            115 -> team = buffer.readUnsignedByte()
            121 -> lendId = buffer.readUnsignedShort()
            122 -> lendTemplateId = buffer.readUnsignedShort()
            125 -> {
                maleWieldX = buffer.readByte() shl 2
                maleWieldZ = buffer.readByte() shl 2
                maleWieldY = buffer.readByte() shl 2
            }
            126 -> {
                femaleWieldX = buffer.readByte() shl 2
                femaleWieldZ = buffer.readByte() shl 2
                femaleWieldY = buffer.readByte() shl 2
            }
            127 -> {
                primaryCursorOpcode = buffer.readUnsignedByte()
                primaryCursor = buffer.readUnsignedShort()
            }
            128 -> {
                secondaryCursorOpcode = buffer.readUnsignedByte()
                secondaryCursor = buffer.readUnsignedShort()
            }
            129 -> {
                primaryInterfaceCursorOpcode = buffer.readUnsignedByte()
                primaryInterfaceCursor = buffer.readUnsignedShort()
            }
            130 -> {
                secondaryInterfaceCursorOpcode = buffer.readUnsignedByte()
                secondaryInterfaceCursor = buffer.readUnsignedShort()
            }
            132 -> {
                val length = buffer.readUnsignedByte()
                campaigns = IntArray(length) { buffer.readUnsignedShort() }
            }
            134 -> pickSizeShift = buffer.readUnsignedByte()
            139 -> singleNoteId = buffer.readUnsignedShort()
            140 -> singleNoteTemplateId = buffer.readUnsignedShort()
            249 -> {
                val records = readParameterRecords(buffer)
                if (records.size != (params?.size ?: 0)) {
                    paramRecords = records
                }
            }
            else -> error("Unhandled item definition opcode: $opcode for def $id")
        }
    }

    override fun changeValues(definitions: Array<ItemDefinition>, definition: ItemDefinition) {
        if (definition.notedTemplateId != -1) {
            definition.toNote(definitions.getOrNull(definition.notedTemplateId), definitions.getOrNull(definition.noteId))
        }
        if (definition.lendTemplateId != -1) {
            definition.toLend(definitions.getOrNull(definition.lendId), definitions.getOrNull(definition.lendTemplateId))
        }
        if (definition.singleNoteTemplateId != -1) {
            definition.toSingleNote(definitions.getOrNull(definition.singleNoteTemplateId), definitions.getOrNull(definition.singleNoteId))
        }
    }
}