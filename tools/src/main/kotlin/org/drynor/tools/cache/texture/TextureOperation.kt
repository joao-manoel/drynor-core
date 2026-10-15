package org.drynor.tools.cache.texture

import org.drynor.tools.cache.texture.cache.ColorImageCache
import org.drynor.tools.cache.texture.cache.MonochromeImageCache
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Abstract base class for texture operations.
 *
 * Texture operations form a DAG (Directed Acyclic Graph) where each operation
 * can reference child operations for input. Operations generate texture data
 * row by row, using LRU caching to avoid redundant computation.
 *
 * All color values use 12-bit fixed-point arithmetic (0-4096 instead of 0-255).
 *
 * @param childCount The number of child operations this operation can reference
 * @param monochrome True if this operation outputs grayscale, false for RGB
 */
abstract class TextureOperation(
    childCount: Int,
    var monochrome: Boolean
) {
    /** Color image cache for RGB output (used when monochrome=false) */
    lateinit var colorImageCache: ColorImageCache

    /** Monochrome image cache for grayscale output (used when monochrome=true) */
    lateinit var monochromeImageCache: MonochromeImageCache

    /** Number of rows to cache (255 = cache all rows) */
    var imageCacheCapacity: Int = 0

    /** Child operations that provide input to this operation */
    val operations: Array<TextureOperation?> = arrayOfNulls(childCount)

    // ---------------------------------------------------------------------------------
    // Per-generation raster state.
    //
    // These used to be singleton fields on TextureRaster, reassigned for every texture
    // generation. A TextureType (and therefore its whole operation DAG) is constructed
    // fresh per generation, so holding the state here confines it to the generation that
    // owns it: two threads rasterising different sizes can no longer clobber each other.
    //
    // They are flat copies of the values in the bound RasterContext rather than a
    // reference to it, so a read inside a per-pixel loop remains a single field access -
    // exactly what it cost when these were statics.
    // ---------------------------------------------------------------------------------

    /** Current texture width */
    @JvmField
    protected var currentWidth: Int = 0

    /** Current texture height */
    @JvmField
    protected var currentHeight: Int = 0

    /** Width - 1, used for wrapping */
    @JvmField
    protected var rowEnd: Int = 0

    /** Height - 1, used for wrapping */
    @JvmField
    protected var columnEnd: Int = 0

    /** Width * 32, used for emboss calculations */
    @JvmField
    protected var widthScale: Int = 0

    /** Precomputed horizontal texture coordinate mapping table */
    @JvmField
    protected var horizontalTable: IntArray = EMPTY_TABLE

    /** Precomputed vertical texture coordinate mapping table */
    @JvmField
    protected var verticalTable: IntArray = EMPTY_TABLE

    /** Absolute [System.nanoTime] deadline for the enclosing generation, or 0 for none. */
    @JvmField
    protected var deadlineNanos: Long = 0L

    /**
     * Bind the raster dimensions and generation deadline for the generation this
     * operation is about to take part in. Must be called before [createImageCache],
     * which propagates the deadline into the row caches.
     */
    fun bindRaster(raster: RasterContext, deadlineNanos: Long) {
        currentWidth = raster.currentWidth
        currentHeight = raster.currentHeight
        rowEnd = raster.rowEnd
        columnEnd = raster.columnEnd
        widthScale = raster.widthScale
        horizontalTable = raster.horizontalTable
        verticalTable = raster.verticalTable
        this.deadlineNanos = deadlineNanos
    }

    /**
     * Creates the image cache with the specified dimensions.
     *
     * @param width The texture width
     * @param height The texture height
     */
    open fun createImageCache(width: Int, height: Int) {
        val capacity = if (imageCacheCapacity == 255) height else imageCacheCapacity
        if (monochrome) {
            monochromeImageCache = MonochromeImageCache(capacity, height, width, deadlineNanos)
        } else {
            colorImageCache = ColorImageCache(capacity, height, width, deadlineNanos)
        }
    }

    /**
     * Gets the monochrome output for this operation at the specified row.
     *
     * @param y The row index
     * @return The grayscale values for this row (12-bit fixed-point)
     */
    open fun getMonochromeOutput(y: Int): IntArray {
        throw IllegalStateException("Operation does not support monochrome output")
    }

    /**
     * Gets the color output for this operation at the specified row.
     *
     * @param y The row index
     * @return Array of 3 IntArrays: [red, green, blue] (12-bit fixed-point)
     */
    open fun getColorOutput(y: Int): Array<IntArray> {
        throw IllegalStateException("Operation does not support color output")
    }

    /**
     * Gets the monochrome output from a child operation.
     * If the child outputs color, uses the red channel.
     *
     * @param operationIndex The child operation index
     * @param y The row index
     * @return The grayscale values for this row
     */
    protected fun getChildMonochromeOutput(operationIndex: Int, y: Int): IntArray {
        val child = operations[operationIndex]!!
        return if (!child.monochrome) {
            child.getColorOutput(y)[0]
        } else {
            child.getMonochromeOutput(y)
        }
    }

    /**
     * Gets the color output from a child operation.
     * If the child outputs monochrome, duplicates it to all channels.
     *
     * @param operationIndex The child operation index
     * @param y The row index
     * @return The RGB values for this row
     */
    protected fun getChildColorOutput(operationIndex: Int, y: Int): Array<IntArray> {
        val child = operations[operationIndex]!!
        return if (child.monochrome) {
            val monochromeRow = child.getMonochromeOutput(y)
            arrayOf(monochromeRow, monochromeRow, monochromeRow)
        } else {
            child.getColorOutput(y)
        }
    }

    /**
     * Decodes an opcode from the packet buffer.
     * Each operation type has its own set of opcodes for configuration.
     *
     * @param opcode The opcode to decode
     * @param buffer The packet buffer
     */
    open fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        // Default: no opcodes to decode
    }

    /**
     * Post-processing after decoding is complete.
     * Called after all opcodes have been decoded.
     */
    open fun postDecode() {
        // Default: no post-processing
    }

    /**
     * Gets the texture/graphic ID for this operation.
     *
     * @return The texture ID, or -1 if not applicable
     */
    open fun getTextureId(): Int = -1

    /**
     * Gets the material ID for this operation.
     *
     * @return The material ID, or -1 if not applicable
     */
    open fun getMaterialId(): Int = -1

    /**
     * Clears and releases the image cache.
     */
    open fun clearImageCache() {
        if (monochrome) {
            monochromeImageCache.reset()
        } else {
            colorImageCache.clear()
        }
    }

    companion object {
        /** Placeholder for the coordinate tables before a raster context is bound. */
        private val EMPTY_TABLE = IntArray(0)
    }
}

/**
 * Thrown when texture generation exceeds the operation access limit,
 * indicating a likely infinite loop in the operation pipeline.
 */
class TextureGenerationException(message: String) : RuntimeException(message)
