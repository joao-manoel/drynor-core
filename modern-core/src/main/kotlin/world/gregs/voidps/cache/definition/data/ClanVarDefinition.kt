package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.cs2.CS2Type

data class ClanVarDefinition(
    override var id: Int = -1,
    var type: CS2Type? = null,
    /**
     * The cp1252 descriptor byte opcode 1 carried. [type] is [CS2Type.forJagexDesc] of it, which
     * collapses `o`/`O` onto one type and every unknown descriptor onto `int`, so the descriptor
     * itself is kept for the encoder.
     */
    var typeChar: Char = 0.toChar(),
    var baseVar: Int = -1,
    var startBit: Int = 0,
    var endBit: Int = 0
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null


    val maxSize: Long
        get() = if (baseVar == -1) {
            when (type) {
                CS2Type.INT -> Int.MAX_VALUE.toLong()
                CS2Type.LONG -> Long.MAX_VALUE
                else -> -1L
            }
        } else {
            1L shl (endBit - startBit)
        }
}
