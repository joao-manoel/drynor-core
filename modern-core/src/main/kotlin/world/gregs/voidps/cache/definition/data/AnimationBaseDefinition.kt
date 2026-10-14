package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One animation skeleton: index 1, one archive of one file.
 *
 * Five columns of one row per transform, no opcodes and no lengths but its own: the transform's kind,
 * whether it is shadowed, which submeshes it applies to, and the model labels it moves. A frame
 * ([AnimationFrameDefinition]) names a base and then walks these transforms in order.
 *
 * Two values the client loses on the way in are kept raw here, because an encoder cannot get them back:
 *
 * - `AnimBase` rewrites a transform type of `6` to `2` in place, so a decoded `2` could have been either;
 *   [types] is the file's byte.
 * - `shadowed` is a byte compared `== 1`, so `0` and `2` both decode to false; [shadowed] is the byte.
 *
 * @param types the transform kind per transform - 0 reset, 1 translate, 2 (and 6) rotate, 3 scale,
 *   5 alpha, 7 colour, 8/9/10 billboard
 * @param shadowed the raw shadow byte per transform
 * @param submeshes the submesh mask per transform
 * @param labels the model labels each transform moves, one array per transform
 */
data class AnimationBaseDefinition(
    override var id: Int = -1,
    var types: IntArray = IntArray(0),
    var shadowed: IntArray = IntArray(0),
    var submeshes: IntArray = IntArray(0),
    var labels: Array<IntArray?> = emptyArray(),
    /** Bytes past the label table, which nothing in the client reads; null for all but two 727 bases. */
    var trailing: ByteArray? = null
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnimationBaseDefinition
        if (id != other.id) return false
        if (!types.contentEquals(other.types)) return false
        if (!shadowed.contentEquals(other.shadowed)) return false
        if (!submeshes.contentEquals(other.submeshes)) return false
        if (!labels.contentDeepEquals(other.labels)) return false
        if (trailing != null) {
            if (other.trailing == null) return false
            if (!trailing.contentEquals(other.trailing)) return false
        } else if (other.trailing != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + types.contentHashCode()
        result = 31 * result + shadowed.contentHashCode()
        result = 31 * result + submeshes.contentHashCode()
        result = 31 * result + labels.contentDeepHashCode()
        result = 31 * result + (trailing?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * The one byte transform count holds 256 as 0, exactly as index 0's does.
         *
         * Bases 161 and 3445 declare 0 transforms in 2,559 bytes; 256 transforms is what those bytes
         * are, and the client - whose reader is length driven - simply builds an empty skeleton.
         */
        const val WRAPPED_COUNT = 256
    }
}
