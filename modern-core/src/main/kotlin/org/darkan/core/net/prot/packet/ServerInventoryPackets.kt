/**
 * Inventory and Grand Exchange packets.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.GrandExchangeOffer
import org.darkan.core.net.prot.dto.InvSlotUpdate

data class UpdateInvPartial(
    val containerId: Int,
    val negativeKey: Boolean,
    val updates: Array<InvSlotUpdate>,
) : ServerProt {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateInvPartial
        if (containerId != other.containerId) return false
        if (negativeKey != other.negativeKey) return false
        if (!updates.contentEquals(other.updates)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = containerId
        result = 31 * result + negativeKey.hashCode()
        result = 31 * result + updates.contentHashCode()
        return result
    }
}

data class UpdateInvFull(
    val containerId: Int,
    val negativeKey: Boolean,
    val itemIds: IntArray,
    val amounts: IntArray,
    val tooltips: Array<String?>? = null,
) : ServerProt {
    init {
        require(itemIds.size == amounts.size) { "itemIds and amounts must have same length" }
        require(tooltips == null || tooltips.size == itemIds.size) { "tooltips must match itemIds length" }
    }

    private val presentTooltips: Array<String?>?
        get() = tooltips?.takeIf { array -> array.any { it != null } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateInvFull
        if (containerId != other.containerId) return false
        if (negativeKey != other.negativeKey) return false
        if (!itemIds.contentEquals(other.itemIds)) return false
        if (!amounts.contentEquals(other.amounts)) return false
        if (!presentTooltips.contentEquals(other.presentTooltips)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = containerId
        result = 31 * result + negativeKey.hashCode()
        result = 31 * result + itemIds.contentHashCode()
        result = 31 * result + amounts.contentHashCode()
        result = 31 * result + presentTooltips.contentHashCode()
        return result
    }
}

data class UpdateInvStopTransmit(val containerId: Int, val negativeKey: Boolean) : ServerProt

/** A Grand Exchange slot's state, or a cleared slot when [offer] is null. */
data class UpdateGESlot(val slot: Int, val offer: GrandExchangeOffer?) : ServerProt
