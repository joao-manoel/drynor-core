package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * Writes an opcode keyed definition file: every record the definition carries, followed by the 0
 * terminator.
 *
 * An encoder built on this is byte exact by construction. The file is a list of records, so the encode is
 * driven by a list of opcodes rather than by a fixed sequence of `if (field != default)` blocks:
 * [OpcodeOrdered.opcodeOrder] when the file had an order of its own, and this encoder's own [opcodes]
 * - derived from the values alone - when it did not, which is also what a definition authored by hand
 * gets. Each payload comes from the fields, except where a record was overwritten by a later one of the
 * same opcode and the decoder had to keep its bytes ([OpcodeOrdered.shadowedPayloads]).
 *
 * [encodeOpcode] therefore only has to write one record's payload, never decide whether to write it.
 */
abstract class OpcodeEncoder<T> : DefinitionEncoder<T> where T : Definition, T : OpcodeOrdered {

    final override fun Writer.encode(definition: T, members: T) {
        val order = definition.opcodeOrder ?: opcodes(definition)
        val shadowed = definition.shadowedPayloads
        val occurrences = HashMap<Int, Int>(order.size)
        for (index in order.indices) {
            val opcode = order[index]
            val occurrence = occurrences.merge(opcode, 1, Int::plus)!! - 1
            writeByte(opcode)
            val payload = if (shadowed == null || index >= shadowed.size) null else shadowed[index]
            if (payload != null) {
                // An overwritten record: its values never reached the fields, so its bytes are replayed.
                writeBytes(payload)
            } else {
                encodeOpcode(definition, members, opcode, occurrence)
            }
        }
        writeByte(0)
    }

    /**
     * Every opcode this encoder writes for [definition] on its own, in the order it writes them, with an
     * opcode [OpcodeOrdered.repeats] declares repeatable listed once per element.
     *
     * This is the canonical order: a file that was packed in it needs no [OpcodeOrdered.opcodeOrder], and
     * the decoder compares against this to decide that ([DefinitionDecoder.canonicalOpcodes]).
     */
    abstract fun opcodes(definition: T): IntArray

    /**
     * Writes the payload of one record, the opcode byte already written.
     *
     * @param members supplies the members only half of a field a non members decode blanked, for a
     * definition built by hand; it is [definition] itself for the one argument encode
     * @param occurrence how many records of this opcode came before it, for the opcodes that legitimately
     * repeat one record per array element
     */
    protected abstract fun Writer.encodeOpcode(definition: T, members: T, opcode: Int, occurrence: Int)
}
