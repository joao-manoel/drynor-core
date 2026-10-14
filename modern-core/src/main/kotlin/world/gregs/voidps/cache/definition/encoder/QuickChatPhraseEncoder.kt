package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.QuickChatPhraseDefinition
import world.gregs.voidps.cache.definition.data.QuickChatType

/**
 * A quick chat phrase - archive 1 of indices 24 and 25 - byte for byte.
 *
 * Opcode 1's string is the phrase split on `<`, one fragment per dynamic value slot plus a trailing
 * one, so joining the fragments back with `<` is the file's string exactly. Opcode 3's entries are a
 * value type and then as many shorts as that type declares; a type the table does not know carries
 * none, which is what the client does too and what the decoder recorded.
 */
class QuickChatPhraseEncoder : OpcodeEncoder<QuickChatPhraseDefinition>() {

    override fun opcodes(definition: QuickChatPhraseDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: QuickChatPhraseDefinition,
        members: QuickChatPhraseDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> writeString(definition.stringParts?.joinToString("<") ?: "")
            2 -> {
                val responses = definition.responses ?: IntArray(0)
                writeByte(responses.size)
                for (response in responses) {
                    writeShort(response)
                }
            }
            3 -> {
                val types = definition.types ?: IntArray(0)
                writeByte(types.size)
                for (index in types.indices) {
                    writeShort(types[index])
                    val length = QuickChatType.getType(types[index])?.length ?: 0
                    val ids = definition.ids?.getOrNull(index) ?: IntArray(0)
                    for (value in 0 until length) {
                        writeShort(ids[value])
                    }
                }
            }
            4 -> {}
            else -> error("Unhandled quick chat phrase opcode $opcode for definition ${definition.id}.")
        }
    }

    companion object {

        /** The opcodes a file with these values carries when it was packed canonically. */
        fun opcodesOf(definition: QuickChatPhraseDefinition): IntArray {
            val opcodes = ArrayList<Int>(4)
            if (definition.stringParts != null) {
                opcodes.add(1)
            }
            if (definition.responses != null) {
                opcodes.add(2)
            }
            if (definition.types != null) {
                opcodes.add(3)
            }
            if (!definition.searchable) {
                opcodes.add(4)
            }
            return opcodes.toIntArray()
        }
    }
}
