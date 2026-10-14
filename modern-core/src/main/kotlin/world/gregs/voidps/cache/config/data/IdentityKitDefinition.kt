package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.Recolourable

data class IdentityKitDefinition(
    override var id: Int = -1,
    var bodyPartId: Int = 0,
    /**
     * Opcode 3's two bytes. The client's `IdkType` has no opcode 3 at all, so what it means is
     * unknown; 13 of the 652 shipped files carry it and it is kept so they can be written back.
     */
    var unknown3: Int = -1,
    var modelIds: IntArray? = null,
    override var originalColours: ShortArray? = null,
    override var modifiedColours: ShortArray? = null,
    override var originalTextureColours: ShortArray? = null,
    override var modifiedTextureColours: ShortArray? = null,
    val headModels: IntArray = intArrayOf(-1, -1, -1, -1, -1)
) : Definition, Recolourable, OpcodeOrdered {

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

        other as IdentityKitDefinition

        if (id != other.id) return false
        if (bodyPartId != other.bodyPartId) return false
        if (unknown3 != other.unknown3) return false
        if (modelIds != null) {
            if (other.modelIds == null) return false
            if (!modelIds.contentEquals(other.modelIds)) return false
        } else if (other.modelIds != null) return false
        if (originalColours != null) {
            if (other.originalColours == null) return false
            if (!originalColours.contentEquals(other.originalColours)) return false
        } else if (other.originalColours != null) return false
        if (modifiedColours != null) {
            if (other.modifiedColours == null) return false
            if (!modifiedColours.contentEquals(other.modifiedColours)) return false
        } else if (other.modifiedColours != null) return false
        if (originalTextureColours != null) {
            if (other.originalTextureColours == null) return false
            if (!originalTextureColours.contentEquals(other.originalTextureColours)) return false
        } else if (other.originalTextureColours != null) return false
        if (modifiedTextureColours != null) {
            if (other.modifiedTextureColours == null) return false
            if (!modifiedTextureColours.contentEquals(other.modifiedTextureColours)) return false
        } else if (other.modifiedTextureColours != null) return false
        if (!headModels.contentEquals(other.headModels)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + bodyPartId
        result = 31 * result + unknown3
        result = 31 * result + (modelIds?.contentHashCode() ?: 0)
        result = 31 * result + (originalColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedColours?.contentHashCode() ?: 0)
        result = 31 * result + (originalTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + headModels.contentHashCode()
        return result
    }
}