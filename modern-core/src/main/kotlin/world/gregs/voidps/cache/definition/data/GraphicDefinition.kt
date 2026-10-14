package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One graphic group of index 8 (or of the loading graphic indices 32 and 34): a shared palette and
 * one to 256 frames indexed into it.
 *
 * The fields after [graphics] are what the client throws away and a byte exact encoder needs back -
 * see `docs/cache-source.md`, "graphics". [rawPalette] is the palette exactly as the file carries
 * it, beside the normalised copy every frame holds in [IndexedGraphic.palette]; the client rewrites
 * a palette entry of 0 to 1 because 0 is its transparent sentinel, and 5,264 of the 727 cache's
 * groups have at least one such entry, so the normalised palette cannot be written back.
 */
data class GraphicDefinition(
    override var id: Int = -1,
    var graphics: Array<IndexedGraphic>? = null,
    /**
     * The palette as the file holds it: entry 0 first (always 0, the transparent index) and the
     * rest the 3 byte colours, unmodified. [IndexedGraphic.palette] is the client's normalised
     * view of the same array.
     */
    var rawPalette: IntArray = IntArray(0),
    /**
     * Whether the file writes an explicit `000000` for palette entry 0 before entry 1.
     *
     * The client never reads it - it seeks past it to entry 1 - so it is three bytes of padding
     * that only 29 of the 727 cache's 12,071 groups carry, and they are the last 29 ids, packed by
     * a different tool than the rest.
     */
    var paletteZeroEntry: Boolean = false,
    /** The group canvas width every frame is padded into; the client's `biggestWidth`. */
    var width: Int = 0,
    /** The group canvas height every frame is padded into; the client's `biggestHeight`. */
    var height: Int = 0
) : Definition {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphicDefinition

        if (id != other.id) return false
        if (graphics != null) {
            if (other.graphics == null) return false
            if (!graphics.contentEquals(other.graphics)) return false
        } else if (other.graphics != null) return false
        if (!rawPalette.contentEquals(other.rawPalette)) return false
        if (paletteZeroEntry != other.paletteZeroEntry) return false
        if (width != other.width) return false
        if (height != other.height) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (graphics?.contentHashCode() ?: 0)
        result = 31 * result + rawPalette.contentHashCode()
        result = 31 * result + paletteZeroEntry.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
