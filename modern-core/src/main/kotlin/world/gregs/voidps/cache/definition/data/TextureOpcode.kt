package world.gregs.voidps.cache.definition.data

/**
 * One configuration record of a texture operation: the opcode byte and whatever payload its
 * operation type gives that opcode.
 *
 * The payload is kept raw - the unsigned byte, short or 3 byte colour the file holds, before any
 * of the scaling the client does on the way into its own fields - because that is the only form
 * that writes back to the same bytes. Which of the three lists is populated is a function of the
 * operation type and the opcode, not of this record: see
 * [world.gregs.voidps.cache.definition.TextureOpFormat].
 *
 * - a scalar opcode has [value] and nothing else;
 * - an opcode its type ignores has neither, and is still a byte in the file;
 * - a curve has [value] (the interpolation mode) and [values] as `x, y` pairs;
 * - a colour gradient has [value] (the preset, `0` for custom) and, when custom, [values] as
 *   `position, r, g, b` quads;
 * - perlin noise's opcode 2 has [value] (the amplitude) and, when it is negative, [values] as one
 *   multiplier per noise step;
 * - a rasterizer has [shapes].
 */
data class TextureOpcode(
    var opcode: Int = 0,
    var value: Int? = null,
    var values: IntArray? = null,
    var shapes: List<TextureShape>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureOpcode

        if (opcode != other.opcode) return false
        if (value != other.value) return false
        if (!values.contentEquals(other.values)) return false
        if (shapes != other.shapes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + (value ?: 0)
        result = 31 * result + (values?.contentHashCode() ?: 0)
        result = 31 * result + (shapes?.hashCode() ?: 0)
        return result
    }
}
