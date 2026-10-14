package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One animation frame: index 0, one file of a frame set archive.
 *
 * A frame is a sparse edit of the skeleton its [baseId] names ([AnimationBaseDefinition]): for each of
 * the base's transforms in turn a flag byte says which of the x, y and z components the frame moves, and
 * the components it does move follow as signed smarts in one run at the end of the file.
 *
 * The fields are the file and nothing else. Everything the client's `AnimFrame` derives - the compacted
 * per-transform arrays, the rotation values rescaled `(v shl 2) and 0x3fff`, the `128` it substitutes for
 * a missing scale component, the tween bits it takes out of the flag byte, the "skipped reset"
 * back-references it computes from the base's types - is lossy or derived and none of it is here:
 *
 * - a rotation's `shl 2` is not invertible, so [values] holds the smart the file held;
 * - a component the flag byte leaves clear has no bytes at all, which a decoded `0` or `128` cannot be
 *   told apart from, so [flags] is kept raw rather than rebuilt from the values;
 * - flag bits `0x20`, `0x40` and `0x80` are read and thrown away by the client, so a raw flag byte is
 *   the only thing that can carry them.
 *
 * @param baseId the skeleton this frame transforms, an index 1 archive id
 * @param header the file's first byte, which nothing in the client ever reads; 1 in every 727 frame
 * @param flags one byte per base transform, `0x1` x, `0x2` y, `0x4` z, `0x8`/`0x10` tween, rest unused
 * @param values the components the flags asked for, in transform order and x, y, z order within one
 * @param wideValues the positions in [values] the file wrote in the two byte smart form though the one
 *   byte form would have held them - layout fidelity, and null for every frame in the 727 cache
 */
data class AnimationFrameDefinition(
    override var id: Int = -1,
    var baseId: Int = -1,
    var header: Int = 1,
    var flags: IntArray = IntArray(0),
    var values: IntArray = IntArray(0),
    var wideValues: IntArray? = null
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnimationFrameDefinition
        if (id != other.id) return false
        if (baseId != other.baseId) return false
        if (header != other.header) return false
        if (!flags.contentEquals(other.flags)) return false
        if (!values.contentEquals(other.values)) return false
        if (wideValues != null) {
            if (other.wideValues == null) return false
            if (!wideValues.contentEquals(other.wideValues)) return false
        } else if (other.wideValues != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + baseId
        result = 31 * result + header
        result = 31 * result + flags.contentHashCode()
        result = 31 * result + values.contentHashCode()
        result = 31 * result + (wideValues?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * The one byte transform count holds 256 as 0.
         *
         * Index 0 archive 162's frames each declare 0 transforms and then carry 256 flag bytes, which is
         * a packer that wrote `count and 0xff`; the client reads 0, finds the file longer than the four
         * byte header and throws the frame away. The tree still has to reproduce it, so both directions
         * read a declared 0 with a payload behind it as 256.
         */
        const val WRAPPED_COUNT = 256
    }
}
