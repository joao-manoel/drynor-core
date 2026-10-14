package org.darkan.tools.rsinterface

import org.lwjgl.opengl.GL11.glDeleteTextures
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder

/**
 * A graphic uploaded to the GPU, ready for [org.darkan.tools.engine.render.Canvas2D].
 *
 * [width] and [height] are the *group canvas* size, not the trimmed size: the
 * client's `NativeSprite.getSpriteWidth()` reports the padded size and component
 * layout compares it against the component's own width/height, so the trimmed
 * pixels are re-inset into a full-size canvas at upload time.
 */
class LoadedGraphic(
    val textureId: Int,
    val width: Int,
    val height: Int
)

/**
 * Lazily decodes graphic groups from the cache and uploads them as GL textures.
 *
 * Graphics are keyed by id plus the flip flags, because the client flips pixels
 * in the decoded graphic rather than at blit time, and a flipped graphic is a
 * different image as far as the GPU is concerned.
 */
class GraphicCache(private val cache: Cache) {

    private val decoder = GraphicDecoder()
    private val definitions: Array<GraphicDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val textures = HashMap<Long, LoadedGraphic?>()

    /** One past the highest graphic group id in the cache, for id pickers to bound. */
    val count: Int get() = definitions.size

    /** Decodes (once) and returns the raw graphic group for [id]. */
    fun definition(id: Int): GraphicDefinition? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode graphic $id: ${e.message}")
            }
        }
        return if (definitions[id].graphics == null) null else definitions[id]
    }

    /**
     * Returns the uploaded graphic for [id], or null when the group is missing.
     *
     * @param frame index within the graphic group; interface components always
     *   use frame 0, matching the client's `SpriteType.listSingle(index, id, 0)`.
     */
    fun graphic(id: Int, flipVertical: Boolean = false, flipHorizontal: Boolean = false, frame: Int = 0): LoadedGraphic? {
        val key = (id.toLong() shl 24) or
            (frame.toLong() shl 2) or
            (if (flipVertical) 2L else 0L) or
            (if (flipHorizontal) 1L else 0L)
        // Misses are cached as nulls too: interfaces routinely reference graphics
        // that are absent from the cache, and retrying every frame would stall.
        if (textures.containsKey(key)) {
            return textures[key]
        }
        val uploaded = upload(id, frame, flipVertical, flipHorizontal)
        textures[key] = uploaded
        return uploaded
    }

    private fun upload(id: Int, frame: Int, flipVertical: Boolean, flipHorizontal: Boolean): LoadedGraphic? {
        val graphics = definition(id)?.graphics ?: return null
        val graphic = graphics.getOrNull(frame) ?: return null
        val width = graphic.offsetX + graphic.width + graphic.deltaWidth
        val height = graphic.offsetY + graphic.height + graphic.deltaHeight
        if (width <= 0 || height <= 0) {
            return null
        }

        val pixels = expand(graphic, width, height, flipVertical, flipHorizontal)
        return LoadedGraphic(UiTextures.createArgb(pixels, width, height, repeat = true), width, height)
    }

    /**
     * Expands a trimmed, palette-indexed graphic into a full-canvas ARGB image.
     * Palette index 0 is transparent; when the group carries no alpha plane every
     * other index is fully opaque.
     */
    private fun expand(
        graphic: IndexedGraphic,
        canvasWidth: Int,
        canvasHeight: Int,
        flipVertical: Boolean,
        flipHorizontal: Boolean
    ): IntArray {
        val pixels = IntArray(canvasWidth * canvasHeight)
        val palette = graphic.palette
        val raster = graphic.raster
        val alpha = graphic.alpha
        for (y in 0 until graphic.height) {
            for (x in 0 until graphic.width) {
                val source = x + y * graphic.width
                val index = raster[source].toInt() and 0xff
                val opacity = if (alpha != null) alpha[source].toInt() and 0xff else if (index == 0) 0 else 255
                if (opacity == 0) {
                    continue
                }
                var destX = x + graphic.offsetX
                var destY = y + graphic.offsetY
                if (flipHorizontal) {
                    destX = canvasWidth - 1 - destX
                }
                if (flipVertical) {
                    destY = canvasHeight - 1 - destY
                }
                pixels[destX + destY * canvasWidth] = (opacity shl 24) or palette[index]
            }
        }
        return pixels
    }

    fun cleanup() {
        textures.values.forEach { graphic ->
            if (graphic != null) {
                glDeleteTextures(graphic.textureId)
            }
        }
        textures.clear()
    }
}
