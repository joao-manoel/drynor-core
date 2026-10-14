package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * The equipment defaults: index 28 archive 6, the client's `EquipmentDefaultsType`.
 *
 * Which worn slots an obj may be customised in, which slot the shield and the weapon are, and the slots
 * whose models an animation hides.
 */
data class BodyDefinition(
    override var id: Int = -1,
    /** Opcode 1: one byte per worn slot, non zero where the slot takes a customised obj. */
    var disabledSlots: IntArray = IntArray(0),
    /** Opcode 3: the shield slot. */
    var anInt4506: Int = -1,
    /** Opcode 4: the weapon slot. */
    var anInt4504: Int = -1,
    /** Opcode 5: the slots an animation hides along with the shield. */
    var anIntArray4501: IntArray? = null,
    /** Opcode 6: the slots an animation hides along with the weapon. */
    var anIntArray4507: IntArray? = null,
    override var opcodeOrder: IntArray? = null,
    override var shadowedPayloads: Array<ByteArray?>? = null
) : Definition, OpcodeOrdered {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BodyDefinition

        if (id != other.id) return false
        if (!disabledSlots.contentEquals(other.disabledSlots)) return false
        if (anInt4506 != other.anInt4506) return false
        if (anInt4504 != other.anInt4504) return false
        if (anIntArray4501 != null) {
            if (other.anIntArray4501 == null) return false
            if (!anIntArray4501.contentEquals(other.anIntArray4501)) return false
        } else if (other.anIntArray4501 != null) return false
        if (anIntArray4507 != null) {
            if (other.anIntArray4507 == null) return false
            if (!anIntArray4507.contentEquals(other.anIntArray4507)) return false
        } else if (other.anIntArray4507 != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + disabledSlots.contentHashCode()
        result = 31 * result + anInt4506
        result = 31 * result + anInt4504
        result = 31 * result + (anIntArray4501?.contentHashCode() ?: 0)
        result = 31 * result + (anIntArray4507?.contentHashCode() ?: 0)
        return result
    }
}