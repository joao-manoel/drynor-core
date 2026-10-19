package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.NPCS
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.encoder.NPCEncoder

class NPCDecoder(val members: Boolean = true) : DefinitionDecoder<NPCDefinition>(NPCS) {

    private val encoder = NPCEncoder()

    override fun create(size: Int) = Array(size) { NPCDefinition(it) }

    override fun canonicalOpcodes(definition: NPCDefinition): IntArray = encoder.opcodes(definition)

    // Index 18 packs 128 npcs per archive (id ushr 7 below); the base class assumes 256.
    override fun size(cache: Cache): Int {
        val archive = cache.lastArchiveId(index)
        return if (archive < 0) -1 else archive * 128 + cache.lastFileId(index, archive)
    }

    override fun getFile(id: Int) = id and 0x7f

    override fun getArchive(id: Int) = id ushr 7

    override  fun NPCDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                val length = buffer.readUnsignedByte()
                modelIds = IntArray(length)
                for (count in 0 until length) {
                    modelIds!![count] = buffer.readBigSmart()
                }
            }
            2 -> name = buffer.readString()
            12 -> size = buffer.readUnsignedByte()
            in 30..34 -> {
                val option = buffer.readString()
                options[opcode - 30] = option
                rawOption(opcode - 30, option)
            }
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            42 -> readColourPalette(buffer)
            60 -> dialogueModels = IntArray(buffer.readUnsignedByte()) { buffer.readBigSmart() }
            93 -> drawMinimapDot = false
            95 -> combat = buffer.readUnsignedShort()
            97 -> scaleXY = buffer.readUnsignedShort()
            98 -> scaleZ = buffer.readUnsignedShort()
            99 -> priorityRender = true
            100 -> lightModifier = buffer.readByte()
            101 -> shadowModifier = 5 * buffer.readByte()
            102 -> headIcon = buffer.readUnsignedShort()
            103 -> rotation = buffer.readUnsignedShort()
            // NPC transforms use unsigned short for IDs (not bigSmart like objects)
            106, 118 -> {
                varbit = buffer.readUnsignedShort()
                if (varbit == 65535) varbit = -1
                varp = buffer.readUnsignedShort()
                if (varp == 65535) varp = -1
                var last = -1
                if (opcode == 118) {
                    last = buffer.readUnsignedShort()
                    if (last == 65535) last = -1
                }
                val length = buffer.readUnsignedByte()
                transforms = IntArray(length + 2)
                for (count in 0..length) {
                    transforms!![count] = buffer.readUnsignedShort()
                    if (transforms!![count] == 65535) transforms!![count] = -1
                }
                transforms!![length + 1] = last
            }
            107 -> clickable = false
            109 -> slowWalk = false
            111 -> animateIdle = false
            113 -> {
                primaryShadowColour = buffer.readUnsignedShort().toShort()
                secondaryShadowColour = buffer.readUnsignedShort().toShort()
            }
            114 -> {
                primaryShadowModifier = buffer.readByte().toByte()
                secondaryShadowModifier = buffer.readByte().toByte()
            }
            119 -> walkMask = buffer.readByte().toByte()
            121 -> {
                translations = arrayOfNulls(modelIds!!.size)
                val length = buffer.readUnsignedByte()
                for (count in 0 until length) {
                    val index = buffer.readUnsignedByte()
                    translations!![index] = intArrayOf(
                        buffer.readByte(),
                        buffer.readByte(),
                        buffer.readByte()
                    )
                }
            }
            122 -> hitbarGraphic = buffer.readUnsignedShort()
            123 -> height = buffer.readUnsignedShort()
            125 -> respawnDirection = buffer.readByte().toByte()
            127 -> renderEmote = buffer.readUnsignedShort()
            128 -> moveSpeed = buffer.readUnsignedByte()
            134 -> {
                // Order per client: walk, crawl(teleport), idle, run
                walkSound = buffer.readUnsignedShort()
                if (walkSound == 65535) {
                    walkSound = -1
                }
                crawlSound = buffer.readUnsignedShort()
                if (crawlSound == 65535) {
                    crawlSound = -1
                }
                idleSound = buffer.readUnsignedShort()
                if (idleSound == 65535) {
                    idleSound = -1
                }
                runSound = buffer.readUnsignedShort()
                if (runSound == 65535) {
                    runSound = -1
                }
                soundDistance = buffer.readUnsignedByte()
            }
            135 -> {
                primaryCursorOp = buffer.readUnsignedByte()
                primaryCursor = buffer.readUnsignedShort()
            }
            136 -> {
                secondaryCursorOp = buffer.readUnsignedByte()
                secondaryCursor = buffer.readUnsignedShort()
            }
            137 -> attackCursor = buffer.readUnsignedShort()
            // Opcode 138: overhead graphic (bigSmart for rev 727)
            138 -> armyIcon = buffer.readBigSmart()
            139 -> graphicId = buffer.readUnsignedShort()
            140 -> ambientSoundVolume = buffer.readUnsignedByte()
            141 -> visiblePriority = true
            142 -> mapFunction = buffer.readUnsignedShort()
            143 -> invisiblePriority = true
            in 150..154 -> {
                val option = buffer.readString()
                options[opcode - 150] = option
                rawOption(5 + opcode - 150, option)
                if (!members) {
                    options[opcode - 150] = null
                }
            }
            155 -> {
                hue = buffer.readByte().toByte()
                saturation = buffer.readByte().toByte()
                lightness = buffer.readByte().toByte()
                opacity = buffer.readByte().toByte()
            }
            158 -> mainOptionIndex = 1.toByte()
            159 -> mainOptionIndex = 0.toByte()
            160 -> {
                val length = buffer.readUnsignedByte()
                campaigns = IntArray(length) { buffer.readUnsignedShort() }
            }
            162 -> aBoolean2883 = true
            163 -> anInt2803 = buffer.readUnsignedByte()
            164 -> {
                anInt2844 = buffer.readUnsignedShort()
                anInt2852 = buffer.readUnsignedShort()
            }
            165 -> anInt2831 = buffer.readUnsignedByte()
            168 -> anInt2862 = buffer.readUnsignedByte()
            // Opcode 169: hasTint = false (boolean flag, no data bytes)
            169 -> { /* hasTint = false */ }
            249 -> {
                val records = readParameterRecords(buffer)
                if (records.size != (params?.size ?: 0)) {
                    paramRecords = records
                }
            }
            else -> error("Unhandled NPC definition opcode: $opcode for def $id")
        }
    }

    /** Keeps an option record as the file held it; [NPCDefinition.options] merges 30 to 34 with 150 to 154. */
    private fun NPCDefinition.rawOption(index: Int, option: String) {
        val raw = rawOptions ?: arrayOfNulls<String>(10).also { rawOptions = it }
        raw[index] = option
    }
}
