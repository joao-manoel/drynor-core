package org.darkan.tools.engine.render

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Represents a region within a texture atlas.
 * Contains UV coordinates for transforming mesh UVs to atlas space.
 *
 * The UV coordinates map to the INNER texture area, excluding any border pixels.
 * Shader code should use: atlasUV = vec2(u0, v0) + fract(localUV) * vec2(uScale, vScale)
 */
data class AtlasRegion(
    val atlasPageIndex: Int,
    val u0: Float,  // Left edge of inner texture in atlas [0, 1]
    val v0: Float,  // Top edge of inner texture in atlas [0, 1]
    val u1: Float,  // Right edge of inner texture in atlas [0, 1]
    val v1: Float,  // Bottom edge of inner texture in atlas [0, 1]
    val originalWidth: Int,
    val originalHeight: Int
) {
    /** Width of the inner region in atlas UV space */
    val uScale: Float get() = u1 - u0

    /** Height of the inner region in atlas UV space */
    val vScale: Float get() = v1 - v0

    /**
     * Transform a local UV coordinate to atlas UV coordinate.
     */
    fun transformUV(localU: Float, localV: Float): Pair<Float, Float> {
        return Pair(
            u0 + localU * uScale,
            v0 + localV * vScale
        )
    }

    /**
     * Get the atlas transform as a vec4 for shader use.
     * (u0, v0, uScale, vScale)
     */
    fun getTransformVec4(): FloatArray {
        return floatArrayOf(u0, v0, uScale, vScale)
    }
}

/**
 * Atlas page containing packed textures with optional border pixels.
 *
 * Each texture slot occupies (textureSize + 2*border) pixels in both dimensions.
 * Border pixels are filled with toroidal wrapping (opposite-edge pixels) to prevent
 * bilinear/mipmap bleeding across adjacent textures in the atlas.
 *
 * @param size Total atlas page size in pixels (e.g., 4096)
 * @param textureSize Size of individual textures in pixels (e.g., 128)
 * @param border Number of border pixels on each side (e.g., 8)
 */
private class AtlasPage(
    val size: Int,
    val textureSize: Int,
    val border: Int = 0
) {
    val glTextureId: Int = glGenTextures()
    val pixels: IntArray = IntArray(size * size)

    /** Total size of each slot including border on both sides */
    private val slotSize = textureSize + 2 * border

    // Packing state - simple row-based packing
    private var currentRow = 0
    private var currentColumn = 0

    // Textures per row (based on slot size which includes border)
    val texturesPerRow = size / slotSize

    init {
        // Initialize with transparent black
        pixels.fill(0)

        // Create the GL texture
        glBindTexture(GL_TEXTURE_2D, glTextureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        // Enable anisotropic filtering if available
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        // Allocate the texture
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * Try to add a texture to this page.
     *
     * The returned [AtlasRegion] UV coordinates map to the inner texture area
     * (excluding border pixels), so shaders sample correctly from the content region.
     *
     * @return The region if successful, null if no space
     */
    fun addTexture(texturePixels: IntArray, width: Int, height: Int): AtlasRegion? {
        // Check if we have space in current row
        if (currentColumn + 1 > texturesPerRow) {
            // Move to next row
            currentRow++
            currentColumn = 0
        }

        // Check if we have space for another row
        if (currentRow >= texturesPerRow) {
            return null
        }

        // Calculate slot position in atlas (slot = texture + border on each side)
        val slotX = currentColumn * slotSize
        val slotY = currentRow * slotSize

        // Copy pixels to atlas (inner area starts at slotX+border, slotY+border)
        copyPixels(texturePixels, width, height, slotX + border, slotY + border)

        // Fill border pixels with toroidal wrapping if border > 0
        if (border > 0) {
            fillBorderPixels(texturePixels, width, height, slotX, slotY)
        }

        // Calculate UV coordinates for the INNER texture area (excluding border).
        // No half-pixel inset is applied here: the 8-pixel toroidal border provides
        // sufficient bleed prevention for bilinear/mipmap filtering, and textureGrad()
        // in the terrain shader prevents mip level discontinuities at fract() seams.
        // Applying a half-pixel inset here would compress the UV range for objects
        // (which use linear UV remapping rather than fract()-based tiling).
        val innerX = slotX + border
        val innerY = slotY + border
        val u0 = innerX.toFloat() / size
        val v0 = innerY.toFloat() / size
        val u1 = (innerX + textureSize).toFloat() / size
        val v1 = (innerY + textureSize).toFloat() / size

        currentColumn++

        return AtlasRegion(
            atlasPageIndex = -1, // Will be set by TextureAtlas
            u0 = u0, v0 = v0,
            u1 = u1, v1 = v1,
            originalWidth = width,
            originalHeight = height
        )
    }

    /**
     * Copy texture pixels into the atlas at the specified position.
     * Handles scaling if the source is smaller than textureSize.
     *
     * @param src Source ARGB pixel data
     * @param srcWidth Source texture width
     * @param srcHeight Source texture height
     * @param destX Destination X in atlas pixels (inner area start, after border)
     * @param destY Destination Y in atlas pixels (inner area start, after border)
     */
    private fun copyPixels(src: IntArray, srcWidth: Int, srcHeight: Int, destX: Int, destY: Int) {
        val scaleX = srcWidth.toFloat() / textureSize
        val scaleY = srcHeight.toFloat() / textureSize

        for (dy in 0 until textureSize) {
            for (dx in 0 until textureSize) {
                val sx = (dx * scaleX).toInt().coerceIn(0, srcWidth - 1)
                // Flip source Y to match the vertical flip done by TextureLoader.createTextureFromArgb.
                // OpenGL has V=0 at bottom, RS textures have row 0 at top. Individual textures are
                // flipped during upload; atlas textures must be flipped here for consistency.
                val sy = ((textureSize - 1 - dy) * scaleY).toInt().coerceIn(0, srcHeight - 1)

                val srcIdx = sy * srcWidth + sx
                val destIdx = (destY + dy) * size + (destX + dx)

                if (srcIdx < src.size && destIdx < pixels.size) {
                    pixels[destIdx] = src[srcIdx]
                }
            }
        }
    }

    /**
     * Fill border pixels around the inner texture area with toroidal wrapping.
     *
     * Toroidal wrapping means border pixels sample from the opposite edge of the texture,
     * emulating GL_REPEAT behavior. This prevents bilinear/mipmap filtering from bleeding
     * adjacent atlas textures into each other.
     *
     * For a texture at inner position (innerX, innerY) with size textureSize:
     * - Left border: copies from right edge of texture (columns textureSize-border..textureSize-1)
     * - Right border: copies from left edge of texture (columns 0..border-1)
     * - Top border: copies from bottom edge of texture (rows textureSize-border..textureSize-1)
     * - Bottom border: copies from top edge of texture (rows 0..border-1)
     * - Corner borders: wrap both axes (e.g., top-left border = bottom-right corner of texture)
     *
     * The source pixels may have a different size than textureSize. We use the same
     * nearest-neighbor scaling as [copyPixels] to maintain consistency.
     *
     * @param src Source ARGB pixel data
     * @param srcWidth Source texture width
     * @param srcHeight Source texture height
     * @param slotX Slot X position in atlas (start of border area)
     * @param slotY Slot Y position in atlas (start of border area)
     */
    private fun fillBorderPixels(src: IntArray, srcWidth: Int, srcHeight: Int, slotX: Int, slotY: Int) {
        val scaleX = srcWidth.toFloat() / textureSize
        val scaleY = srcHeight.toFloat() / textureSize

        // Helper: sample a pixel from the source texture at logical coordinates (tx, ty)
        // in textureSize space, with toroidal wrapping applied.
        // The Y coordinate is flipped to match the V-flip applied in copyPixels, since
        // both read directly from the source array (not the already-flipped pixels[]).
        fun sampleWrapped(tx: Int, ty: Int): Int {
            // Apply toroidal wrapping: map any coordinate into [0, textureSize)
            val wrappedTx = ((tx % textureSize) + textureSize) % textureSize
            val wrappedTy = ((ty % textureSize) + textureSize) % textureSize

            val sx = (wrappedTx * scaleX).toInt().coerceIn(0, srcWidth - 1)
            // Flip Y to match copyPixels V-flip (read from bottom of source upward)
            val sy = ((textureSize - 1 - wrappedTy) * scaleY).toInt().coerceIn(0, srcHeight - 1)
            val srcIdx = sy * srcWidth + sx
            return if (srcIdx < src.size) src[srcIdx] else 0
        }

        // Fill the entire border region. The slot occupies slotSize x slotSize pixels.
        // Inner texture occupies [border, border+textureSize) within the slot.
        // We need to fill:
        //   - Top border rows: row 0..border-1 (entire width)
        //   - Bottom border rows: row border+textureSize..slotSize-1 (entire width)
        //   - Left border columns: column 0..border-1 (only inner rows, corners already done)
        //   - Right border columns: column border+textureSize..slotSize-1 (only inner rows)

        // Process ALL border pixels uniformly. For each pixel in the slot that is
        // outside the inner area, compute its wrapped source coordinate.
        for (dy in 0 until slotSize) {
            for (dx in 0 until slotSize) {
                // Skip inner texture area (already filled by copyPixels)
                if (dx in border until border + textureSize && dy in border until border + textureSize) {
                    continue
                }

                // Map slot-relative pixel to texture-relative coordinate.
                // The inner area starts at offset (border, border), so:
                //   textureX = dx - border
                //   textureY = dy - border
                // For border pixels this will be negative or >= textureSize, which
                // sampleWrapped handles via modular arithmetic.
                val tx = dx - border
                val ty = dy - border

                val pixel = sampleWrapped(tx, ty)
                val destIdx = (slotY + dy) * size + (slotX + dx)
                if (destIdx in pixels.indices) {
                    pixels[destIdx] = pixel
                }
            }
        }
    }

    /**
     * Upload the atlas to GPU.
     */
    fun upload() {
        val buffer = MemoryUtil.memAlloc(size * size * 4)
        try {
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
                buffer.put((pixel and 0xFF).toByte())          // B
                buffer.put(((pixel shr 24) and 0xFF).toByte()) // A
            }
            buffer.flip()

            glBindTexture(GL_TEXTURE_2D, glTextureId)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
            glBindTexture(GL_TEXTURE_2D, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    fun cleanup() {
        glDeleteTextures(glTextureId)
    }

    fun getRemainingSlots(): Int {
        val totalSlots = texturesPerRow * texturesPerRow
        val usedSlots = currentRow * texturesPerRow + currentColumn
        return totalSlots - usedSlots
    }
}

/**
 * Texture atlas for reducing texture bind calls.
 *
 * Packs multiple textures into large atlas pages. Each texture gets
 * a region with UV transformation data for shader use.
 *
 * When [border] > 0, each atlas slot is enlarged by 2*border pixels and the
 * border pixels are filled with toroidal wrapping (opposite-edge pixels).
 * This prevents bilinear/mipmap bleeding across adjacent textures in the atlas,
 * which is critical for terrain textures that tile via GL_REPEAT (emulated with
 * fract() in the shader).
 *
 * With 128px textures + 8px border on a 4096 atlas: floor(4096/144) = 28 tiles
 * per side = 784 textures/page, sufficient for all RS terrain textures.
 *
 * @param atlasSize Size of each atlas page (e.g., 4096 for 4096x4096)
 * @param textureSize Size of individual textures (e.g., 128 for 128x128)
 * @param border Number of border pixels on each side for bleed prevention (default 8)
 */
class TextureAtlas(
    private val atlasSize: Int = 4096,
    private val textureSize: Int = 128,
    private val border: Int = 8
) {
    private val pages = mutableListOf<AtlasPage>()

    /**
     * Texture id -> packed region.
     *
     * Concurrent because the region loading pipeline reads this from background
     * worker threads (batch geometry needs the atlas UV rect) while the GL thread
     * can still be packing new object textures into the atlas via [addTexture].
     * A plain HashMap resized underneath those readers would drop or corrupt
     * lookups. Only lookups happen off-thread; [addTexture] stays GL-thread only.
     */
    private val regions = ConcurrentHashMap<Int, AtlasRegion>()

    /** Size of each slot including border: textureSize + 2 * border */
    private val slotSize = textureSize + 2 * border

    // Textures per page = (atlasSize / slotSize)^2
    val texturesPerPage: Int = (atlasSize / slotSize) * (atlasSize / slotSize)

    /**
     * Add a texture to the atlas.
     *
     * @param textureId The original texture ID
     * @param pixels ARGB pixel data
     * @param width Texture width
     * @param height Texture height
     * @return The atlas region for this texture (UV coordinates map to inner area)
     */
    fun addTexture(textureId: Int, pixels: IntArray, width: Int = textureSize, height: Int = textureSize): AtlasRegion {
        // Check if already added
        regions[textureId]?.let { return it }

        // Find a page with space or create new
        var region: AtlasRegion? = null
        var pageIndex = 0

        for ((idx, page) in pages.withIndex()) {
            region = page.addTexture(pixels, width, height)
            if (region != null) {
                pageIndex = idx
                break
            }
        }

        // No space in existing pages, create new
        if (region == null) {
            val newPage = AtlasPage(atlasSize, textureSize, border)
            pages.add(newPage)
            pageIndex = pages.size - 1
            region = newPage.addTexture(pixels, width, height)!!
        }

        // Update with correct page index
        val finalRegion = region.copy(atlasPageIndex = pageIndex)
        regions[textureId] = finalRegion

        return finalRegion
    }

    /**
     * Get the atlas region for a texture.
     */
    fun getRegion(textureId: Int): AtlasRegion? = regions[textureId]

    /**
     * Check if a texture is in the atlas.
     */
    fun hasTexture(textureId: Int): Boolean = regions.containsKey(textureId)

    /**
     * Upload all atlas pages to GPU.
     * Call this after adding all textures.
     */
    fun upload() {
        for (page in pages) {
            page.upload()
        }
    }

    /**
     * Bind atlas pages to texture units starting at startUnit.
     */
    fun bindPages(startUnit: Int = 0) {
        for ((idx, page) in pages.withIndex()) {
            glActiveTexture(GL_TEXTURE0 + startUnit + idx)
            glBindTexture(GL_TEXTURE_2D, page.glTextureId)
        }
    }

    /**
     * Bind a specific atlas page.
     */
    fun bindPage(pageIndex: Int, unit: Int = 0) {
        if (pageIndex in pages.indices) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, pages[pageIndex].glTextureId)
        }
    }

    /**
     * Get the GL texture ID for a page.
     */
    fun getPageTextureId(pageIndex: Int): Int? {
        return pages.getOrNull(pageIndex)?.glTextureId
    }

    /**
     * Get number of atlas pages.
     */
    fun getPageCount(): Int = pages.size

    /**
     * Get total number of textures in the atlas.
     */
    fun getTextureCount(): Int = regions.size

    /**
     * Get the configured border size in pixels.
     */
    fun getBorder(): Int = border

    /**
     * Get statistics about atlas utilization.
     */
    fun getStats(): AtlasStats {
        val totalSlots = pages.size * texturesPerPage
        val usedSlots = regions.size
        val remainingSlots = pages.sumOf { it.getRemainingSlots() }

        return AtlasStats(
            pageCount = pages.size,
            textureCount = regions.size,
            totalSlots = totalSlots,
            usedSlots = usedSlots,
            utilization = if (totalSlots > 0) usedSlots.toFloat() / totalSlots else 0f
        )
    }

    /**
     * Clean up all GPU resources.
     */
    fun cleanup() {
        for (page in pages) {
            page.cleanup()
        }
        pages.clear()
        regions.clear()
    }

    /**
     * Clear all textures but keep the atlas structure.
     */
    fun clear() {
        cleanup()
    }
}

data class AtlasStats(
    val pageCount: Int,
    val textureCount: Int,
    val totalSlots: Int,
    val usedSlots: Int,
    val utilization: Float
)
