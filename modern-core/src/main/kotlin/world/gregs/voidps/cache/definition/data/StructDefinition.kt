package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.Parameterized

data class StructDefinition(
    override var id: Int = -1,
    override var params: Map<Int, Any>? = null,
) : Definition, Parameterized, OpcodeOrdered {

    /**
     * The params in file order, kept only when [params] cannot reproduce the file: 37 shipped
     * structs carry the same param key twice and the map keeps only the last write. Null - and
     * therefore absent from the source tree - for every other struct.
     */
    var paramRecords: List<KeyedValue>? = null

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null


    /** Gets an int value from params by key, returning [default] if not found. */
    @JvmOverloads
    fun getIntValue(key: Int, default: Int = 0): Int = params?.get(key) as? Int ?: default

    /** Gets a string value from params by key. */
    fun getStringValue(key: Int): String? = params?.get(key) as? String

    /** Gets the params map, or empty map if null. Java interop convenience. */
    fun getValues(): Map<Int, Any> = params ?: emptyMap()

    companion object {
        val EMPTY = StructDefinition()
    }
}
