package world.gregs.voidps.cache.definition.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.ObjectDefinition

/**
 * Writes an index 16 loc file.
 *
 * Several opcodes share a field: 17, 18 and 27 all describe how the loc blocks movement and sight, and 21,
 * 81, 93, 94, 95 and 162 all set the contoured ground mode with a different payload width. The mode a
 * definition came from is recovered from the value, and where the values cannot tell two records apart the
 * decoder has kept the file's own opcode order, which decides for us.
 *
 * Options come from [ObjectDefinition.rawOptions] so that a file holding both a public (30 to 34) and a
 * members (150 to 154) record for the same slot writes both back; [members] supplies the members half for a
 * definition built by hand, which is what the two argument encode form is for.
 */
class ObjectEncoder : OpcodeEncoder<ObjectDefinition>() {

    override fun opcodes(definition: ObjectDefinition): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelIds != null && definition.modelTypes != null) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.sizeX != 1) {
            opcodes.add(14)
        }
        if (definition.sizeY != 1) {
            opcodes.add(15)
        }
        if (!definition.blocksSky) {
            opcodes.add(if (definition.solid == 0) 17 else 18)
        }
        if (definition.interactive != -1) {
            opcodes.add(19)
        }
        if (definition.contouredGround == 1.toByte()) {
            opcodes.add(21)
        }
        if (definition.delayShading) {
            opcodes.add(22)
        }
        if (definition.culling == 1) {
            opcodes.add(23)
        }
        if (definition.singleAnimation != -1) {
            opcodes.add(24)
        }
        if (definition.solid == 1) {
            opcodes.add(27)
        }
        if (definition.offsetMultiplier != 64) {
            opcodes.add(28)
        }
        if (definition.brightness != 0) {
            opcodes.add(29)
        }
        val raw = definition.rawOptions
        for (index in 0 until 5) {
            if (if (raw != null) raw[index] != null else definition.options?.get(index) != null) {
                opcodes.add(30 + index)
            }
        }
        if (definition.contrast != 0) {
            opcodes.add(39)
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
        if (definition.mirrored) {
            opcodes.add(62)
        }
        if (!definition.castsShadow) {
            opcodes.add(64)
        }
        if (definition.modelSizeX != 128) {
            opcodes.add(65)
        }
        if (definition.modelSizeZ != 128) {
            opcodes.add(66)
        }
        if (definition.modelSizeY != 128) {
            opcodes.add(67)
        }
        if (definition.blockFlag != 0) {
            opcodes.add(69)
        }
        if (definition.offsetX != 0) {
            opcodes.add(70)
        }
        if (definition.offsetZ != 0) {
            opcodes.add(71)
        }
        if (definition.offsetY != 0) {
            opcodes.add(72)
        }
        if (definition.blocksLand) {
            opcodes.add(73)
        }
        if (definition.ignoreOnRoute) {
            opcodes.add(74)
        }
        if (definition.supportItems != -1) {
            opcodes.add(75)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 92 else 77)
        }
        if (definition.anInt3015 != -1) {
            opcodes.add(78)
        }
        if (definition.anIntArray3036 != null) {
            opcodes.add(79)
        }
        if (definition.contouredGround == 2.toByte()) {
            opcodes.add(81)
        }
        if (definition.hideMinimap) {
            opcodes.add(82)
        }
        if (!definition.aBoolean2972) {
            opcodes.add(88)
        }
        if (!definition.animateImmediately) {
            opcodes.add(89)
        }
        if (definition.isMembers) {
            opcodes.add(91)
        }
        if (definition.contouredGround == 3.toByte()) {
            opcodes.add(if (definition.anInt3023 in Short.MIN_VALUE..Short.MAX_VALUE) 93 else 162)
        }
        if (definition.contouredGround == 4.toByte()) {
            opcodes.add(94)
        }
        if (definition.contouredGround == 5.toByte()) {
            opcodes.add(95)
        }
        if (definition.aBoolean3056) {
            opcodes.add(97)
        }
        if (definition.aBoolean2998) {
            opcodes.add(98)
        }
        if (definition.anInt2987 != -1) {
            opcodes.add(99)
        }
        if (definition.anInt3038 != -1) {
            opcodes.add(100)
        }
        if (definition.anInt2958 != 0) {
            opcodes.add(101)
        }
        if (definition.mapscene != -1) {
            opcodes.add(102)
        }
        if (definition.culling == 0) {
            opcodes.add(103)
        }
        if (definition.anInt3024 != 255) {
            opcodes.add(104)
        }
        if (definition.invertMapScene) {
            opcodes.add(105)
        }
        if (definition.rawPercents != null) {
            opcodes.add(106)
        }
        if (definition.mapDefinitionId != -1) {
            opcodes.add(107)
        }
        for (index in 0 until 5) {
            if (raw?.get(5 + index) != null) {
                opcodes.add(150 + index)
            }
        }
        if (definition.anIntArray2981 != null) {
            opcodes.add(160)
        }
        if (definition.aByte2974 != 0.toByte() || definition.aByte3045 != 0.toByte() ||
            definition.aByte3052 != 0.toByte() || definition.aByte2960 != 0.toByte()
        ) {
            opcodes.add(163)
        }
        if (definition.anInt2964 != 0) {
            opcodes.add(164)
        }
        if (definition.anInt2963 != 0) {
            opcodes.add(165)
        }
        if (definition.anInt3018 != 0) {
            opcodes.add(166)
        }
        if (definition.anInt2983 != 0) {
            opcodes.add(167)
        }
        if (definition.aBoolean2961) {
            opcodes.add(168)
        }
        if (definition.aBoolean2993) {
            opcodes.add(169)
        }
        if (definition.anInt3032 != 960) {
            opcodes.add(170)
        }
        if (definition.anInt2962 != 0) {
            opcodes.add(171)
        }
        if (definition.anInt3050 != 256 || definition.anInt3020 != 256) {
            opcodes.add(173)
        }
        if (definition.aBoolean2992) {
            opcodes.add(177)
        }
        if (definition.anInt2975 != 0) {
            opcodes.add(178)
        }
        if (definition.dynamicTint) {
            opcodes.add(189)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        val array = opcodes.toIntArray()
        array.sort()
        return array
    }

    override fun Writer.encodeOpcode(definition: ObjectDefinition, members: ObjectDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val modelIds = definition.modelIds ?: emptyArray()
                val modelTypes = definition.modelTypes ?: ByteArray(modelIds.size)
                writeByte(modelIds.size)
                for (index in modelIds.indices) {
                    writeByte(modelTypes[index].toInt())
                    writeByte(modelIds[index].size)
                    for (model in modelIds[index]) {
                        writeBigSmart(model)
                    }
                }
            }
            2 -> writeString(definition.name)
            14 -> writeByte(definition.sizeX)
            15 -> writeByte(definition.sizeY)
            17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 82, 88, 89, 91, 94, 97, 98, 103, 105, 168, 169, 177, 189 -> Unit
            19 -> writeByte(definition.interactive)
            24 -> writeBigSmart(definition.singleAnimation)
            28 -> writeByte(definition.offsetMultiplier shr 2)
            29 -> writeByte(definition.brightness)
            in 30..34 -> writeString(definition.rawOptions?.get(opcode - 30) ?: definition.options?.get(opcode - 30))
            39 -> writeByte(definition.contrast / 5)
            40 -> writeColours(definition.originalColours, definition.modifiedColours)
            41 -> writeColours(definition.originalTextureColours, definition.modifiedTextureColours)
            42 -> writeColourPalette(definition.recolourPalette)
            65 -> writeShort(definition.modelSizeX)
            66 -> writeShort(definition.modelSizeZ)
            67 -> writeShort(definition.modelSizeY)
            69 -> writeByte(definition.blockFlag)
            70 -> writeShort(definition.offsetX shr 2)
            71 -> writeShort(definition.offsetZ shr 2)
            72 -> writeShort(definition.offsetY shr 2)
            75 -> writeByte(definition.supportItems)
            77, 92 -> {
                val transforms = definition.transforms ?: IntArray(2) { -1 }
                writeShort(definition.varbit)
                writeShort(definition.varp)
                if (opcode == 92) {
                    writeBigSmart(transforms.last())
                }
                writeByte(transforms.size - 2)
                for (index in 0 until transforms.size - 1) {
                    writeBigSmart(transforms[index])
                }
            }
            78 -> {
                writeShort(definition.anInt3015)
                writeByte(definition.anInt3012)
            }
            79 -> {
                val sounds = definition.anIntArray3036 ?: IntArray(0)
                writeShort(definition.anInt2989)
                writeShort(definition.anInt2971)
                writeByte(definition.anInt3012)
                writeByte(sounds.size)
                for (sound in sounds) {
                    writeShort(sound)
                }
            }
            81 -> writeByte(definition.anInt3023 / 256)
            93 -> writeShort(definition.anInt3023)
            95 -> writeShort(definition.anInt3023)
            99 -> {
                writeByte(definition.anInt2987)
                writeShort(definition.anInt3008)
            }
            100 -> {
                writeByte(definition.anInt3038)
                writeShort(definition.anInt3013)
            }
            101 -> writeByte(definition.anInt2958)
            102 -> writeShort(definition.mapscene)
            104 -> writeByte(definition.anInt3024)
            106 -> {
                val animations = definition.animations ?: IntArray(0)
                val percents = definition.rawPercents ?: IntArray(animations.size)
                writeByte(animations.size)
                for (index in animations.indices) {
                    writeBigSmart(animations[index])
                    writeByte(percents[index])
                }
            }
            107 -> writeShort(definition.mapDefinitionId)
            in 150..154 -> writeString(definition.rawOptions?.get(5 + opcode - 150) ?: members.options?.get(opcode - 150))
            160 -> {
                val values = definition.anIntArray2981 ?: IntArray(0)
                writeByte(values.size)
                for (value in values) {
                    writeShort(value)
                }
            }
            162 -> writeInt(definition.anInt3023)
            163 -> {
                writeByte(definition.aByte2974.toInt())
                writeByte(definition.aByte3045.toInt())
                writeByte(definition.aByte3052.toInt())
                writeByte(definition.aByte2960.toInt())
            }
            164 -> writeShort(definition.anInt2964)
            165 -> writeShort(definition.anInt2963)
            166 -> writeShort(definition.anInt3018)
            167 -> writeShort(definition.anInt2983)
            170 -> writeSmart(definition.anInt3032)
            171 -> writeSmart(definition.anInt2962)
            173 -> {
                writeShort(definition.anInt3050)
                writeShort(definition.anInt3020)
            }
            178 -> writeByte(definition.anInt2975)
            249 -> writeParams(definition.id, definition.params, definition.paramRecords)
            else -> error("Unhandled object definition opcode: $opcode for def ${definition.id}")
        }
    }
}
