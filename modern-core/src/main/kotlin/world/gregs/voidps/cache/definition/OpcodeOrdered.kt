package world.gregs.voidps.cache.definition

/**
 * A definition whose file its encoder reproduces byte for byte.
 *
 * A definition file is a list of records - an opcode byte and its payload - closed by a 0 terminator, and
 * two things about that list survive nowhere in the decoded fields:
 *
 * - Jagex's packer did not write the records in any fixed order. Measured over the whole 727 cache only
 *   1917 of 73893 locs, 155 of 15662 npcs, 7417 of 25355 objs and 7 of 1220 map elements are in ascending
 *   opcode order. Two opcodes also routinely write the same field - a hitmark's fade is either the bare
 *   opcode 11 or opcode 14 with a cycle, a loc's contoured ground mode has six spellings - so which
 *   opcodes a file carried cannot be derived from the values either.
 * - A file may hold the same opcode twice with different payloads. The decoder keeps the last, as the
 *   client does, and the earlier payload is gone.
 *
 * [opcodeOrder] and [shadowedPayloads] close both gaps: the decoder records them
 * ([world.gregs.voidps.cache.DefinitionDecoder.readLoop]) and [world.gregs.voidps.cache.OpcodeEncoder]
 * replays the list, taking each payload from the fields except where one was overwritten.
 */
interface OpcodeOrdered {

    /**
     * Every opcode the file held, in the order it held them, repeats included.
     *
     * Null when that is exactly the order the encoder derives from the fields on its own
     * ([world.gregs.voidps.cache.OpcodeEncoder.opcodes]), which is both the common shape and the one an
     * author writing a definition by hand gets for free. The decoder nulls it only after checking the
     * encoder's own order reproduces the file's, so parity never rests on a presence rule being right:
     * anything the encoder would not emit exactly keeps its explicit order.
     */
    var opcodeOrder: IntArray?

    /**
     * The payload of any record a later record of the same opcode overwrote, indexed to match
     * [opcodeOrder]; null when nothing was overwritten, which is all but a handful of files.
     *
     * The surviving record is written from the fields; a shadowed one is replayed verbatim because its
     * values never reached the definition. An entry stays null when the overwritten record carried the
     * same bytes as the write that replaced it - re-encoding from the fields reproduces those - and for
     * every opcode [repeats] declares to be one record per element rather than a field written twice. A
     * definition with any shadowed payload always keeps its [opcodeOrder], since the two are indexed
     * against each other.
     */
    var shadowedPayloads: Array<ByteArray?>?

    /**
     * Whether [opcode] is a record type that legitimately appears once per array element rather than a
     * field written twice, and so is written from the fields on every occurrence - from its
     * `occurrence`th element - instead of being shadowed.
     */
    fun repeats(opcode: Int): Boolean = false
}
