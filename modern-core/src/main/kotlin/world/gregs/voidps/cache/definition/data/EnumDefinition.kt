package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class EnumDefinition(
    override var id: Int = -1,
    var keyType: Char = 0.toChar(),
    var valueType: Char = 0.toChar(),
    var defaultString: String = "null",
    var defaultInt: Int = 0,
    var length: Int = 0,
    var map: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    /**
     * Capacity of the sparse value array of the opcode 7 and 8 forms, which the client sizes from it, or -1
     * for the opcode 5 and 6 forms that carry no array. Always at least [length].
     */
    var arraySize: Int = -1,
    /**
     * The entry records in file order, kept only when the file listed a key more than once - [map] keeps the
     * last value of each. Null when [map] holds every record.
     */
    var entries: List<KeyedValue>? = null
) : Definition, Extra, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null
    fun getKey(value: Any) = map?.filterValues { it == value }?.keys?.lastOrNull() ?: -1

    fun getInt(id: Int) = map?.get(id) as? Int ?: defaultInt

    fun randomInt() = map?.values?.random() as? Int ?: defaultInt

    fun getString(id: Int) = map?.get(id) as? String ?: defaultString

    /** Legacy alias for [getString]. */
    fun getStringValue(id: Int) = getString(id)

    /** Legacy alias for [getInt]. */
    fun getIntValue(id: Int) = getInt(id)

    /** Legacy alias for [getInt] - indexed by position. */
    fun getIntValueAtIndex(id: Int) = getInt(id)

    /** Gets the value at the given key. */
    fun getValue(id: Any): Any? = map?.get(id as? Int ?: return null)

    /** Legacy alias for [defaultInt]. */
    fun getDefaultIntValue(): Int = defaultInt

    /** Gets the values map, or empty map if null. */
    fun getValues(): Map<Int, Any> = map ?: emptyMap()

    /** Gets the number of entries in this enum. */
    fun getSize(): Int = map?.size ?: 0

    /** Gets the key for a given value, or -1 if not found. */
    fun getKeyForValue(value: Any): Int {
        val m = map ?: return -1
        for ((k, v) in m) {
            if (v == value) return k
        }
        return -1
    }

    companion object {
        val EMPTY = EnumDefinition()
    }
}