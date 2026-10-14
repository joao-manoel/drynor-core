package world.gregs.voidps.cache.definition.data

import java.awt.image.BufferedImage

data class IndexedGraphic(
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
    var deltaHeight: Int = 0,
    var deltaWidth: Int = 0,
    var alpha: ByteArray? = null,
    /**
     * The frame's format byte: bit 0 stores the pixels column major rather than row major, bit 1
     * says an alpha plane follows them.
     *
     * The client reads it and keeps nothing, and it is not derivable from what it does keep: 302
     * of the 727 cache's frames carry an alpha plane that is entirely opaque, which the client
     * (and [alpha]) drops, so only this says the plane was there.
     */
    var flags: Int = 0
) {
    lateinit var raster: ByteArray
    lateinit var palette: IntArray

    fun scaleWidth() = offsetX + width + deltaWidth

    fun toBufferedImage(): BufferedImage {
        val bi = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val i = x + y * width
                if (alpha == null) {
                    val colour = palette[raster[i].toInt() and 255]
                    if (colour != 0) {
                        bi.setRGB(x, y, -16777216 or colour)
                    }
                } else {
                    bi.setRGB(x, y, palette[raster[i].toInt() and 255] or (alpha!![i].toInt() shl 24))
                }
            }
        }
        return bi
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IndexedGraphic

        if (offsetX != other.offsetX) return false
        if (offsetY != other.offsetY) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (deltaHeight != other.deltaHeight) return false
        if (deltaWidth != other.deltaWidth) return false
        if (flags != other.flags) return false
        if (alpha != null) {
            if (other.alpha == null) return false
            if (!alpha.contentEquals(other.alpha)) return false
        } else if (other.alpha != null) return false
        if (!raster.contentEquals(other.raster)) return false
        if (!palette.contentEquals(other.palette)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offsetX
        result = 31 * result + offsetY
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + deltaHeight
        result = 31 * result + deltaWidth
        result = 31 * result + flags
        result = 31 * result + (alpha?.contentHashCode() ?: 0)
        result = 31 * result + raster.contentHashCode()
        result = 31 * result + palette.contentHashCode()
        return result
    }

}