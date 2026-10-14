package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.DefaultsDefinition
import world.gregs.voidps.cache.definition.data.InputSubscriberRecord

/**
 * Index 28's archives, except equipment, byte for byte.
 *
 * Like the decoder, the layout is chosen by the definition's id - which archive it is - because the same
 * opcode means a different thing in each. Every record is written from a field that is null when the file
 * did not carry it, so presence needs no separate flag.
 */
class DefaultsEncoder : OpcodeEncoder<DefaultsDefinition>() {

    override fun opcodes(definition: DefaultsDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: DefaultsDefinition,
        members: DefaultsDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (definition.id) {
            DefaultsDefinition.SCENE_CUBE_TEXTURES -> for (texture in definition.cubeTextures!!) {
                writeShort(texture)
            }
            DefaultsDefinition.ENTITY -> entity(definition, opcode)
            DefaultsDefinition.TITLE_SCREEN_MUSIC -> writeShort(definition.titleScreenMusic!!)
            DefaultsDefinition.INTERACTIONS -> interactions(definition, opcode)
            DefaultsDefinition.CUTSCENE -> subscriber(input(definition, opcode))
            else -> error("Index 28 archive ${definition.id} has no defaults type.")
        }
    }

    private fun Writer.entity(definition: DefaultsDefinition, opcode: Int) {
        when (opcode) {
            1 -> {
                val x = definition.hitOffsetsX!!
                val y = definition.hitOffsetsY!!
                for (hit in x.indices) {
                    writeShort(x[hit])
                    writeShort(y[hit])
                }
            }
            2 -> writeBigSmart(definition.profilingMesh!!)
            3 -> writeByte(definition.maximumHits!!)
            4 -> {}
            5 -> writeMedium(definition.loginInterface!!)
            6 -> writeMedium(definition.lobbyWindow!!)
            7 -> for (record in definition.recolours!!) {
                writeShort(record.source)
                writeShort(record.destinations.size)
                for (destination in record.destinations) {
                    writeShort(destination)
                }
            }
            8 -> {}
            9 -> writeByte(definition.npcMessageDuration!!)
            10 -> {}
            11 -> writeByte(definition.playerMessageDuration!!)
            12 -> {
                writeShort(definition.gameWidth!!)
                writeShort(definition.gameHeight!!)
            }
            13 -> writeByte(definition.maxHitbars!!)
            14 -> writeByte(definition.maxVisibleHitbars!!)
            15 -> writeByte(definition.hitbarHeight!!)
            else -> error("Unhandled entity defaults opcode $opcode.")
        }
    }

    private fun Writer.interactions(definition: DefaultsDefinition, opcode: Int) {
        when (opcode) {
            1, 2, 3, 4, 8, 9, 10 -> subscriber(input(definition, opcode))
            5, 6, 7 -> keys(input(definition, opcode).keys)
            11 -> {}
            12 -> writeInt(definition.tooltipColourMembers!!)
            13 -> writeInt(definition.tooltipColourFree!!)
            else -> error("Unhandled interaction defaults opcode $opcode.")
        }
    }

    /** The type byte and the body it names. */
    private fun Writer.subscriber(record: InputSubscriberRecord) {
        val type = record.type ?: error("Input binding ${record.opcode} has no subscriber type.")
        writeByte(type)
        when (type) {
            InputSubscriberRecord.COMBINED -> {
                writeByte(record.clickType!!)
                writeByte(record.minimumClicks!!)
                keys(record.keys)
            }
            InputSubscriberRecord.KEY_PRESS -> {
                writeByte(record.code!!)
                writeByte(record.flags!!)
            }
            InputSubscriberRecord.KEY_HOLD -> keys(record.keys)
            else -> error("Unknown input subscriber type $type under opcode ${record.opcode}.")
        }
    }

    private fun Writer.keys(keys: IntArray?) {
        val held = keys ?: IntArray(0)
        writeByte(held.size)
        for (key in held) {
            writeByte(key)
        }
    }

    private fun input(definition: DefaultsDefinition, opcode: Int): InputSubscriberRecord =
        definition.inputs?.firstOrNull { it.opcode == opcode }
            ?: error("Defaults ${definition.id} writes opcode $opcode but has no input binding for it.")

    companion object {

        /** The opcodes a file with these values carries when it was packed canonically, ascending. */
        fun opcodesOf(definition: DefaultsDefinition): IntArray {
            val opcodes = ArrayList<Int>(16)
            when (definition.id) {
                DefaultsDefinition.SCENE_CUBE_TEXTURES -> if (definition.cubeTextures != null) {
                    opcodes.add(1)
                }
                DefaultsDefinition.ENTITY -> {
                    if (definition.hitOffsetsX != null) {
                        opcodes.add(1)
                    }
                    if (definition.profilingMesh != null) {
                        opcodes.add(2)
                    }
                    if (definition.maximumHits != null) {
                        opcodes.add(3)
                    }
                    if (definition.alwaysShowContextMenu != null) {
                        opcodes.add(4)
                    }
                    if (definition.loginInterface != null) {
                        opcodes.add(5)
                    }
                    if (definition.lobbyWindow != null) {
                        opcodes.add(6)
                    }
                    if (definition.recolours != null) {
                        opcodes.add(7)
                    }
                    if (definition.npcMessages != null) {
                        opcodes.add(8)
                    }
                    if (definition.npcMessageDuration != null) {
                        opcodes.add(9)
                    }
                    if (definition.playerMessages != null) {
                        opcodes.add(10)
                    }
                    if (definition.playerMessageDuration != null) {
                        opcodes.add(11)
                    }
                    if (definition.gameWidth != null) {
                        opcodes.add(12)
                    }
                    if (definition.maxHitbars != null) {
                        opcodes.add(13)
                    }
                    if (definition.maxVisibleHitbars != null) {
                        opcodes.add(14)
                    }
                    if (definition.hitbarHeight != null) {
                        opcodes.add(15)
                    }
                }
                DefaultsDefinition.TITLE_SCREEN_MUSIC -> if (definition.titleScreenMusic != null) {
                    opcodes.add(1)
                }
                DefaultsDefinition.INTERACTIONS -> {
                    for (record in definition.inputs ?: emptyList()) {
                        opcodes.add(record.opcode)
                    }
                    if (definition.minimenuCursor != null) {
                        opcodes.add(11)
                    }
                    if (definition.tooltipColourMembers != null) {
                        opcodes.add(12)
                    }
                    if (definition.tooltipColourFree != null) {
                        opcodes.add(13)
                    }
                    opcodes.sort()
                }
                DefaultsDefinition.CUTSCENE -> if (definition.inputs != null) {
                    opcodes.add(1)
                }
            }
            return opcodes.toIntArray()
        }
    }
}
