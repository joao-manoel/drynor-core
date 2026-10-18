package org.drynor.tools.cache.texture

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable, shareable rasterisation constants for one texture size.
 *
 * Everything here is derived purely from `width`/`height`, so an instance is safe to
 * publish to any number of threads. [TextureRaster.context] interns one instance per
 * size, which is why generating a 64x64 material on one thread can no longer disturb a
 * 128x128 material on another: they simply hold different contexts.
 *
 * Operations copy these values into their own fields (see [TextureOperation.bindRaster])
 * rather than dereferencing the context per pixel, so a lookup in a hot loop stays a
 * single field read.
 */
class RasterContext(
    @JvmField val currentWidth: Int,
    @JvmField val currentHeight: Int
) {
    /** Precomputed horizontal texture coordinate mapping table */
    @JvmField
    val horizontalTable: IntArray = IntArray(currentWidth) { (it shl 12) / currentWidth }

    /** Precomputed vertical texture coordinate mapping table (shared for square textures) */
    @JvmField
    val verticalTable: IntArray = if (currentHeight == currentWidth) {
        horizontalTable
    } else {
        IntArray(currentHeight) { (it shl 12) / currentHeight }
    }

    /** Width - 1, used for wrapping */
    @JvmField
    val rowEnd: Int = currentWidth - 1

    /** Height - 1, used for wrapping */
    @JvmField
    val columnEnd: Int = currentHeight - 1

    /** Width * 32, used for emboss calculations */
    @JvmField
    val widthScale: Int = currentWidth * 32
}

/**
 * Process-wide, read-only tables for texture rasterization.
 *
 * This object holds only immutable state. The per-generation width/height and the
 * coordinate tables derived from them used to live here as reassignable singleton
 * fields, which meant two concurrent generations at different sizes reallocated the
 * tables underneath each other. They now live in [RasterContext] instances that are
 * bound to the (per-generation) operation DAG.
 */
object TextureRaster {
    private const val TWO_PI = 6.283185307179586

    /** Sine lookup table (256 entries, values scaled to 12-bit fixed point) */
    @JvmField
    val PALETTE_SIN: IntArray = IntArray(256) { (sin(TWO_PI * (it / 255.0)) * 4096.0).toInt() }

    /** Cosine lookup table (256 entries, values scaled to 12-bit fixed point) */
    @JvmField
    val PALETTE_COS: IntArray = IntArray(256) { (cos(TWO_PI * (it / 255.0)) * 4096.0).toInt() }

    /** Wall-clock budget for a single top-level texture generation, including its nested
     *  materials. Callers turn this into an absolute deadline and pass it down; a value of
     *  0 disables the check. */
    const val MAX_GENERATION_NANOS: Long = 5_000_000_000L

    /** Interned contexts keyed by (width, height). Contexts are immutable, so sharing one
     *  across threads and generations is safe and keeps generation allocation-free. */
    private val contexts = ConcurrentHashMap<Long, RasterContext>()

    /**
     * Get the shared [RasterContext] for a texture size, creating it on first use.
     */
    fun context(width: Int, height: Int): RasterContext {
        val key = (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)
        contexts[key]?.let { return it }
        // putIfAbsent rather than computeIfAbsent: a duplicate construction is cheap and
        // the first publication wins, so every caller at a given size sees one instance.
        val created = RasterContext(width, height)
        return contexts.putIfAbsent(key, created) ?: created
    }

    /**
     * Retained for callers that used to prime the sine/cosine tables. The tables are now
     * built during object initialisation, which the JVM already makes thread-safe, so this
     * only forces that initialisation to happen.
     */
    fun calculateTable() {
        // PALETTE_SIN/PALETTE_COS are initialised with the object; nothing to do.
    }
}
