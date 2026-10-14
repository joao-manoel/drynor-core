package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class PlayerVariableParameterDefinition(
    override var id: Int = -1,
    var type: Int = 0,
    /** The cp1252 stack type descriptor (client `VarpType.typeChar`, opcode 1). Unused in 727. */
    var typeChar: Char = 0.toChar()
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null
}