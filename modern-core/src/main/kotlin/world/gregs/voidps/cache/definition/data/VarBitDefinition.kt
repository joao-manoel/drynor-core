package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class VarBitDefinition(
    override var id: Int = -1,
    var index: Int = 0,
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VarBitDefinition

        if (id != other.id) return false
        if (index != other.index) return false
        if (startBit != other.startBit) return false
        if (endBit != other.endBit) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + index
        result = 31 * result + startBit
        result = 31 * result + endBit
        return result
    }
}
