package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * A param (attribute) type declaration.
 *
 * Params are the key-value slots read by parameterized definitions and pushed by the
 * CS2 param opcodes (`OC_PARAM`, `NPC_PARAM`, `LC_PARAM`, `STRUCT_PARAM`, `CC_GETINTPARAM`, ...).
 * The declared [type] decides which stack the value lands on, and which default is used when the
 * owning definition doesn't carry the param.
 */
data class ParamDefinition(
    override var id: Int = -1,
    var type: Char = 0.toChar(),
    var defaultInt: Int = 0,
    var defaultString: String? = null,
    var autoDisable: Boolean = true,
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null


    /** Whether this param holds a string; ints otherwise. */
    fun isString(): Boolean = type == 's'

    companion object {
        val EMPTY = ParamDefinition()
    }
}
