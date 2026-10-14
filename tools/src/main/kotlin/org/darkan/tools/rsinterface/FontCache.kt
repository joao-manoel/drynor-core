package org.darkan.tools.rsinterface

import org.lwjgl.opengl.GL11.glDeleteTextures
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.decoder.FontDecoder

/**
 * Lazily builds [RsFont] atlases on demand.
 *
 * A font id addresses two indices at once: the metrics live in index 13
 * (`FONT_METRICS`) and the glyph bitmaps live in the graphic group of the same
 * id in index 8, which is why this shares the viewer's [GraphicCache] rather than
 * decoding graphics itself.
 */
class FontCache(
    private val cache: Cache,
    private val graphics: GraphicCache
) {
    private val decoder = FontDecoder()
    private val definitions: Array<FontDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val fonts = HashMap<Int, RsFont?>()

    /** One past the highest font id in the cache, for id pickers to bound. */
    val count: Int get() = definitions.size

    /** Returns the built font for [id], or null when it is missing from the cache. */
    fun font(id: Int): RsFont? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (fonts.containsKey(id)) {
            return fonts[id]
        }
        val built = build(id)
        fonts[id] = built
        return built
    }

    private fun build(id: Int): RsFont? {
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode font metrics $id: ${e.message}")
                return null
            }
        }
        val definition = definitions[id]
        if (definition.glyphWidths.isEmpty()) {
            return null
        }
        val glyphs = graphics.definition(id)?.graphics ?: return null
        return RsFont.buildAtlas(id, definition, glyphs)
    }

    fun cleanup() {
        fonts.values.forEach { font ->
            if (font != null) {
                glDeleteTextures(font.textureId)
            }
        }
        fonts.clear()
    }
}
