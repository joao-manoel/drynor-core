package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.FontDecoder
import world.gregs.voidps.cache.definition.decoder.TextureDecoder
import world.gregs.voidps.cache.definition.encoder.FontEncoder
import world.gregs.voidps.cache.definition.encoder.TextureEncoder
import world.gregs.voidps.cache.source.IndexNames

/**
 * The five indices that hold the cache's 2D art and the definitions that describe it, and the
 * codecs that make them editable files rather than bytes.
 *
 * | index | directory | codec | layout |
 * |---|---|---|---|
 * | 8 | `graphics` | [GraphicCodec] | one RGBA PNG per frame, `<id>/<frame>.png` |
 * | 32 | `loading_graphics` | [GraphicCodec] | the same, or one `<id>.jpg` for a JPEG archive |
 * | 34 | `loading_graphics_raw` | [GraphicCodec] | the same; every archive here is a palette graphic |
 * | 9 | `textures` | [DefinitionJsonCodec] | `<id>.json`, the procedural operation graph |
 * | 13 | `fontmetrics` | [DefinitionJsonCodec] | `<id>.json`, the glyph advances and padding |
 * | 26 | `materials` | [MaterialCodec] | `<id>.json` per material of the index's single blob |
 *
 * Registered from [SourceCodecs.reset] as one call, so the built in registry stays one list of
 * one-line claims however many indices each group of codecs owns. See `docs/cache-source.md`,
 * "graphics", "textures", "materials", "fontmetrics" and "loading graphics".
 */
object ImageCodecs {

    /** Every index this claims, ascending, for a report that wants to enumerate them. */
    val indices: IntArray = intArrayOf(
        Index.GRAPHICS,
        Index.TEXTURES,
        Index.FONT_METRICS,
        Index.TEXTURE_DEFINITIONS,
        Index.LOADING_GRAPHICS,
        Index.LOADING_GRAPHICS_RAW
    )

    /** The texture graphs of index 9 as JSON; see [TextureDefinition]. */
    val textures: DefinitionJsonCodec<TextureDefinition> = definition("json-texture", TextureDecoder(), TextureEncoder())

    /** The font metrics of index 13 as JSON; see [FontDefinition]. */
    val fonts: DefinitionJsonCodec<FontDefinition> = definition("json-fontmetrics", FontDecoder(), FontEncoder())

    /** Claim all six indices. Called by [SourceCodecs.reset]. */
    fun register() {
        for (index in intArrayOf(Index.GRAPHICS, Index.LOADING_GRAPHICS, Index.LOADING_GRAPHICS_RAW)) {
            SourceCodecs.builtin(index, GraphicCodec)
        }
        SourceCodecs.builtin(Index.TEXTURES, textures)
        SourceCodecs.builtin(Index.FONT_METRICS, fonts)
        SourceCodecs.builtin(Index.TEXTURE_DEFINITIONS, MaterialCodec)
    }

    /** [index]'s directory relative to the tree, for a report. */
    fun directory(index: Int): String = IndexNames.name(index)

    /**
     * An index whose archives hold one definition each, named by the archive id.
     *
     * [DefinitionLayout.Packed] with no file bits is exactly that: the definition id *is* the
     * archive, the one file inside it is 0, and the file is `<archive>.json`.
     */
    private fun definition(
        id: String,
        decoder: TextureDecoder,
        encoder: TextureEncoder
    ): DefinitionJsonCodec<TextureDefinition> = DefinitionJsonCodec(
        id = id,
        layout = DefinitionLayout.Packed(0),
        create = { TextureDefinition(it) },
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    private fun definition(
        id: String,
        decoder: FontDecoder,
        encoder: FontEncoder
    ): DefinitionJsonCodec<FontDefinition> = DefinitionJsonCodec(
        id = id,
        layout = DefinitionLayout.Packed(0),
        create = { FontDefinition(it) },
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )
}
