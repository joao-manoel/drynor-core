package world.gregs.voidps.cache.definition.data

/**
 * One node of a texture's operation graph.
 *
 * [children] are indices into the texture's own operation list, which is what makes the list a
 * DAG rather than a sequence; how many there are is fixed per [type]. The file also leads every
 * operation with its own index in the list, which is derivable and therefore not kept - the
 * decoder checks it instead.
 */
data class TextureOp(
    /** The operation type, `0` to `39`; the client's `TextureTypeList.implementationForType`. */
    var type: Int = 0,
    /** How many output rows the operation caches; `255` means all of them. */
    var imageCacheCapacity: Int = 0,
    var opcodes: List<TextureOpcode> = emptyList(),
    /** The indices of the operations that feed this one. */
    var children: IntArray = IntArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureOp

        if (type != other.type) return false
        if (imageCacheCapacity != other.imageCacheCapacity) return false
        if (opcodes != other.opcodes) return false
        if (!children.contentEquals(other.children)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + imageCacheCapacity
        result = 31 * result + opcodes.hashCode()
        result = 31 * result + children.contentHashCode()
        return result
    }
}
