package org.darkan.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.darkan.core.cache.ItemDefinitions
import kotlin.jvm.Transient as JvmTransient
import kotlin.math.floor

/**
 * A stack of one item type, optionally carrying per-stack metadata.
 *
 * This is legacy `com.rs.lib.game.Item`, and the whole container layer
 * ([org.darkan.core.model.GroundItem], `ItemsContainer`, `Inventory`, `Equipment`, `Bank`, shops,
 * trade and the GE) is written against its exact quirks, so they are reproduced here rather than
 * tidied up:
 *
 *  * The ordinary constructors clamp a non-positive amount up to 1 and never throw (legacy
 *    `Item.java:59-65`). `Equipment.setSlot` returns `new Item(-1, -1)` as its "a plugin cancelled
 *    the equip" sentinel, and the trade screen builds items straight from a client-supplied integer,
 *    so a constructor that rejected those amounts would take the server down.
 *  * [setAmount] ignores a negative amount (legacy `Item.java:75-80`), which is what keeps an
 *    over-subtraction - `Bank.deleteItem`, `Chunk.addGroundItem`'s stack merge - from writing a
 *    negative amount into a persisted stack.
 *  * [getMetaDataI] defaults to -1, not 0 (legacy `Item.java:153-155`), because callers use the
 *    default as an "absent" marker and distinguish it from a stored zero.
 *  * [getMetaData] hands back the live map reference, empty or not, and [containsMetaData] is
 *    "has a map at all" (legacy `Item.java:110-112`, `:175-177`). `ItemsContainer.getNumberOf` and
 *    `ItemsContainer.remove(preferredSlot, item)` compare those maps by reference identity.
 */
@Serializable
data class Item @JvmOverloads constructor(
    var id: Int,
    /**
     * The size of the stack.
     *
     * The JVM setter is renamed so that [setAmount] can carry legacy's negative-amount guard; write
     * through [setAmount] (or Kotlin's `item.setAmount(n)`) unless you specifically want the
     * unchecked write, the way legacy `GroundItem` overrode the guard away.
     */
    @set:JvmName("setAmountUnchecked")
    var amount: Int = 1,
    /**
     * Metadata, absent until something is stored.
     *
     * Nullable because legacy `com.rs.lib.game.Item`'s three-argument constructor accepts a null map
     * and leaves the field null - Java callers pass `item.getMetaData()` straight through when
     * merging stacks (`ItemsContainer.add`), which is null for every ordinary item.
     */
    var metadata: MutableMap<String, @Contextual Any>? = null
) : Cloneable {

    /**
     * Transient slot index, not serialized.
     *
     * Legacy's field is a plain `int`, so an item that has never been through
     * `ItemsContainer.set`/`add` reads back slot 0, and callers such as `Inventory.replace` and the
     * `refresh(item.getSlot())` sites depend on that.
     */
    @Transient
    var slot: Int = 0

    @Transient
    @JvmTransient
    var owner: ItemSlotOwner? = null

    fun markChanged() {
        owner?.itemChanged(this)
    }

    init {
        // Legacy Item(int, int) -> Item(int, int, false): a non-positive amount becomes 1.
        if (amount <= 0) amount = 1
    }

    /**
     * Legacy `Item(int id, int amount, boolean amt0)`: `amt0` keeps [amount] exactly as given,
     * zero and negative included, instead of clamping it to 1.
     */
    constructor(id: Int, amount: Int, amt0: Boolean) : this(id, amount) {
        if (amt0) this.amount = amount
    }

    fun withSlot(slot: Int): Item {
        this.slot = slot
        return this
    }

    /**
     * Legacy `Item.setAmount(int)`: sets the amount unless it is negative, and returns this so
     * Java callers can chain.
     */
    fun setAmount(amount: Int): Item {
        if (amount >= 0) {
            this.amount = amount
            markChanged()
        }
        return this
    }

    /** Legacy-named alias of [setAmount]; modifies the amount in place and returns this. */
    fun withAmount(amount: Int): Item = setAmount(amount)

    fun withMeta(key: String, value: Any) = copy(metadata = (metadata?.toMutableMap() ?: HashMap()).apply { put(key, value) })

    /**
     * This item's definition.
     *
     * Routed through [ItemDefinitions.getDefs] so an id the cache does not have - the `Item(-1, -1)`
     * cancelled-equip sentinel, an empty GE collection-box slot - reads back a blank definition
     * instead of throwing, exactly as legacy `Item.getDefinitions()` did.
     */
    val definitions get() = ItemDefinitions.getDefs(id)
    val defs get() = definitions
    val name get() = definitions.name

    /** The metadata map, created on demand. Legacy `addMetaData` allocates the map the same way. */
    private fun meta(): MutableMap<String, Any> {
        markChanged()
        return metadata ?: HashMap<String, Any>().also { metadata = it }
    }

    @JvmOverloads
    fun getMetaInt(key: String, default: Int = -1): Int = getMetaDataI(key, default)

    fun getMetaData(key: String): Any? = metadata?.get(key)

    @Suppress("UNCHECKED_CAST")
    fun <T> getMetaDataO(key: String): T? = metadata?.get(key) as? T

    /**
     * Legacy `getMetaDataI(String)` / `getMetaDataI(String, int)`.
     *
     * The default is -1, which is how callers tell an absent key from a stored zero. A non-integer
     * number is floored, matching legacy's `(int) Math.floor((double) value)`.
     */
    @JvmOverloads
    fun getMetaDataI(key: String, default: Int = -1): Int {
        val v = metadata?.get(key) ?: return default
        return when (v) {
            is Int -> v
            is Number -> floor(v.toDouble()).toInt()
            else -> default
        }
    }

    fun addMetaData(key: String, value: Any): Item { meta()[key] = value; return this }

    /**
     * Legacy `setMetaDataO(String, Object)`: a null value removes the key, and the previous value is
     * returned (null when there was none).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> setMetaDataO(key: String, value: Any?): T? {
        val map = meta()
        val old = if (value == null) map.remove(key) else map.put(key, value)
        return old as? T
    }

    /**
     * The live metadata map, or null if this item has never had one.
     *
     * Legacy `getMetaData()` returns the field as it stands, so an emptied map is still returned;
     * `ItemsContainer` compares these maps by reference identity and would treat two independent
     * empty maps as the same stack if they were collapsed to null.
     */
    fun getMetaData(): MutableMap<String, Any>? = metadata

    fun deleteMetaData() {
        metadata = null
        markChanged()
    }

    /** Legacy `containsMetaData()` - whether a map has ever been allocated, empty or not. */
    fun containsMetaData(): Boolean = metadata != null

    fun incMetaDataI(key: String): Int {
        val newVal = getMetaDataI(key) + 1
        meta()[key] = newVal
        return newVal
    }

    /** Changes this item's ID in-place. */
    fun changeId(newId: Int) {
        id = newId
        markChanged()
    }

    fun decMetaDataI(key: String): Int {
        val newVal = getMetaDataI(key) - 1
        meta()[key] = newVal
        return newVal
    }

    @JvmOverloads
    fun getMetaDataD(key: String, default: Double = 0.0): Double {
        val v = metadata?.get(key) ?: return default
        return when (v) {
            is Double -> v
            is Number -> v.toDouble()
            else -> default
        }
    }

    fun getMetaString(key: String, default: String? = null): String? = metadata?.get(key) as? String ?: default

    /**
     * A copy with its own metadata map.
     *
     * Legacy `clone()` and `new Item(Item)` both go through the clamping three-argument constructor
     * (`com.rs.lib.game.Item:37-44`), so the copy's amount is clamped and its [slot] is left at the
     * default rather than carried over.
     */
    public override fun clone(): Item = Item(id, amount, metadata?.toMutableMap())

    override fun toString() = "Item($id, amount=$amount)"

    companion object {
        @JvmField
        val EMPTY = Item(-1, 0, true)
    }
}
