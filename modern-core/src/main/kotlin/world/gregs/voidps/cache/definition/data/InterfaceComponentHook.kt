package world.gregs.voidps.cache.definition.data

/**
 * A client script event hook bound to an interface component.
 *
 * Stored in the cache as a list of values where the first is the script to run
 * and the remainder are the arguments bound to it, which can be an [Int], a
 * [String] or a [Long]. Negative argument values below -2147483639 are
 * placeholders the client swaps for event data (mouse position, source
 * component, clicked option, typed key) before the script is executed.
 */
data class InterfaceComponentHook(
    var script: Int = -1,
    var args: Array<Any> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InterfaceComponentHook

        if (script != other.script) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = script
        result = 31 * result + args.contentHashCode()
        return result
    }
}
