/**
 * Payload types carried by the inventory and Grand Exchange packets.
 */
package org.drynor.core.net.prot.dto

sealed interface InvTooltip {
    data object Keep : InvTooltip
    data object Clear : InvTooltip
    data class Set(val text: String) : InvTooltip
}

data class InvSlotUpdate(val slot: Int, val itemId: Int, val amount: Int, val tooltip: InvTooltip = InvTooltip.Clear)

data class GrandExchangeOffer(
    val progress: Int,
    val itemId: Int,
    val price: Int,
    val amount: Int,
    val currentAmount: Int,
    val totalPrice: Int,
)
