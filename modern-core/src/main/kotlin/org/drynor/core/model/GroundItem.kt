package org.drynor.core.model

import world.gregs.voidps.type.Tile

enum class GroundItemType {
    NORMAL,
    INVISIBLE,
    FOREVER
}

/**
 * An item lying on the floor.
 *
 * Legacy `com.rs.lib.game.GroundItem` extended `Item`; here it wraps one, so the wrapper reproduces
 * the parts of that relationship the world depends on:
 *
 *  * It holds its **own** [Item], built the way legacy's `super(item.getId(), item.getAmount(),
 *    item.getMetaData())` did (legacy `GroundItem.java:45-55`). Merging floor stacks
 *    (`Chunk.addGroundItem`) mutates that copy, so it can never reach back into the inventory slot
 *    the drop came from.
 *  * [ownerUsername] is legacy's `creatorUsername`/`creatorId`: it records who dropped the item and
 *    is **never** cleared, so the ironman pickup restriction and the pickup log keep working after
 *    the drop goes public. Visibility is [visibleToId], which is what [removeOwner] clears - legacy
 *    `GroundItem.java:111-113` zeroed `visibleToId` alone.
 *  * Identity, not structure, decides equality. Legacy never overrode `equals`, and
 *    `Chunk.groundItemList`/the owner mappings remove *this* instance; `World.addGroundItem` drops N
 *    structurally identical singles for a non-stackable stack, which a data class would collapse.
 */
open class GroundItem @JvmOverloads constructor(
    item: Item,
    val tile: Tile,
    /** Legacy `creatorUsername`. Permanent: see the class comment. */
    val ownerUsername: String? = null,
    val type: GroundItemType = GroundItemType.NORMAL,
    val respawnTicks: Int = 25,
    var privateTime: Int = -1,
    var deleteTime: Int = -1,
) {
    /** This floor stack's own item. Shares the source's metadata map, exactly as legacy did. */
    val item: Item = Item(item.id, item.amount, item.metadata)

    /** Legacy `creatorId`/`getSourceId()` - the owner name's hash, or 0 for a public drop. */
    val sourceId: Int = ownerUsername?.hashCode() ?: 0

    /**
     * Legacy `visibleToId`: the owner's hash while only they can see the drop, 0 once it is public.
     * Cleared by [removeOwner]; it keys the per-owner ground item mappings in `Chunk`.
     */
    var visibleToId: Int = sourceId
        private set

    val id get() = item.id
    val amount get() = item.amount

    /** Legacy setter - modifies the wrapped item's ID in-place. */
    fun setId(newId: Int) { item.id = newId }
    val definitions get() = item.definitions
    val defs get() = item.defs
    val name get() = item.name
    val metaData get() = item.metadata
    val isPrivate get() = visibleToId != 0
    val isInvisible get() = type == GroundItemType.INVISIBLE
    val isRespawn get() = type == GroundItemType.FOREVER

    /** Legacy alias for [ownerUsername]; survives [removeOwner], as legacy's `creatorUsername` did. */
    fun getCreatorUsername(): String? = ownerUsername

    fun getMetaData(key: String): Any? = item.getMetaData(key)

    /** Legacy `Item.getMetaDataI` - the default is -1, the "absent" marker. */
    @JvmOverloads
    fun getMetaDataI(key: String, default: Int = -1) = item.getMetaDataI(key, default)
    fun addMetaData(key: String, value: Any) = item.addMetaData(key, value)

    /** Returns true if the wrapped item has any metadata entries. */
    fun containsMetaData(): Boolean = item.containsMetaData()

    /**
     * Sets the amount on the wrapped item, bypassing [Item.setAmount]'s negative guard the way
     * legacy `GroundItem.setAmount` overrode it away (legacy `GroundItem.java:39-43`).
     */
    fun setAmount(amount: Int) { item.amount = amount }

    /** Makes this a public drop. Legacy clears only the visibility, not the creator. */
    fun removeOwner() {
        visibleToId = 0
    }

    var ticks: Int = 0
        private set

    open fun tick() { ticks++ }

    /**
     * Legacy `setHiddenTime(int)`: schedules the reveal that many ticks from **now**, i.e. relative
     * to the ticks already elapsed, unlike [privateTime] which is an absolute threshold
     * (legacy `GroundItem.java:143-145`).
     */
    fun setHiddenTime(ticks: Int) {
        privateTime = this.ticks + ticks
    }

    override fun toString() = "GroundItem($item, $tile${ownerUsername?.let { ", owner=$it" } ?: ""})"
}
