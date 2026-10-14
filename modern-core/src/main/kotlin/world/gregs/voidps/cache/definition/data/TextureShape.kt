package world.gregs.voidps.cache.definition.data

/**
 * One shape a rasterizer operation (type 29) draws: a line, a cubic bezier, a rectangle or an
 * ellipse, in the fixed point 0..4096 coordinate space textures are generated in.
 *
 * [shape] decides the record's whole shape - four coordinates or eight, one colour or two - so it
 * is what both the decoder and the encoder read the rest from; see
 * [world.gregs.voidps.cache.definition.TextureOpFormat].
 */
data class TextureShape(
    /** `0` line, `1` bezier, `2` rectangle, `3` ellipse. */
    var shape: Int = 0,
    /** Four coordinates (`x1, y1, x2, y2`), or eight for a bezier's two control points. */
    var coordinates: IntArray = IntArray(0),
    /** The fill colour, only for a rectangle or an ellipse. */
    var fill: Int? = null,
    /** The stroke colour. */
    var stroke: Int = 0,
    var strokeWidth: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureShape

        if (shape != other.shape) return false
        if (!coordinates.contentEquals(other.coordinates)) return false
        if (fill != other.fill) return false
        if (stroke != other.stroke) return false
        if (strokeWidth != other.strokeWidth) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shape
        result = 31 * result + coordinates.contentHashCode()
        result = 31 * result + (fill ?: 0)
        result = 31 * result + stroke
        result = 31 * result + strokeWidth
        return result
    }
}
