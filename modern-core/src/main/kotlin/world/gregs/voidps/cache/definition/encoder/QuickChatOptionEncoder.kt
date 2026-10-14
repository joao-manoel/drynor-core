package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.QuickChatOptionDefinition

/**
 * A quick chat menu - archive 0 of indices 24 and 25 - byte for byte.
 *
 * Four opcodes, two of them a count and a list of `(id, hotkey)` pairs. One 727 file writes opcode 4
 * first, so the order is replayed from the file rather than derived; see
 * [world.gregs.voidps.cache.definition.OpcodeOrdered].
 */
class QuickChatOptionEncoder : OpcodeEncoder<QuickChatOptionDefinition>() {

    override fun opcodes(definition: QuickChatOptionDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: QuickChatOptionDefinition,
        members: QuickChatOptionDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> writeString(definition.optionText)
            2 -> pairs(definition.quickReplyOptions, definition.navigateChars)
            3 -> pairs(definition.dynamicData, definition.staticData)
            4 -> {}
            else -> error("Unhandled quick chat option opcode $opcode for definition ${definition.id}.")
        }
    }

    /** A count byte and then a short id and a cp1252 hotkey byte per entry, 0 for no hotkey. */
    private fun Writer.pairs(ids: IntArray?, hotkeys: CharArray?) {
        val values = ids ?: IntArray(0)
        writeByte(values.size)
        for (index in values.indices) {
            writeShort(values[index])
            val hotkey = hotkeys?.getOrNull(index) ?: ' '
            writeByte(if (hotkey.code == 0) 0 else Cp1252.encode(hotkey).toInt())
        }
    }

    companion object {

        /** The opcodes a file with these values carries when it was packed canonically. */
        fun opcodesOf(definition: QuickChatOptionDefinition): IntArray {
            val opcodes = ArrayList<Int>(4)
            if (definition.optionText != null) {
                opcodes.add(1)
            }
            if (definition.quickReplyOptions != null) {
                opcodes.add(2)
            }
            if (definition.dynamicData != null) {
                opcodes.add(3)
            }
            if (definition.unusedFlag4) {
                opcodes.add(4)
            }
            return opcodes.toIntArray()
        }
    }
}
