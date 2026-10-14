package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord

/**
 * One archive of index 33, the loading screens the client shows while a stage of startup runs.
 *
 * The index holds two unrelated file formats and the codec addresses it one definition per archive, so
 * both live here and are told apart by which half is non-null:
 *
 * - **Archive 0** is the master stage table ([version] .. [stages]), the client's `GameTipTypeList`: the
 *   render type signature it validates itself against, the fallback tip file and timings, and for every
 *   defined startup stage the tip files that stage may show.
 * - **Every other archive** is one loading screen ([elements]), the client's `GameTipType`: a flat list of
 *   draw elements, each one of the ten [world.gregs.voidps.cache.definition.data.GameTipElementRecord]
 *   shapes.
 *
 * Nothing the client derives after decoding is here - the default stages it fills the table out with, the
 * shuffle it applies when picking a tip, the `AnchorX`/`AnchorY` enum constants it turns the anchor bytes
 * into - because a definition re-encoded from derived state no longer matches its file. Anchors, alignments
 * and boolean-ish bytes are kept as the raw values the file holds.
 */
data class GameTipDefinition(
    override var id: Int = -1,
    /** Format version; the client refuses anything below 4, and 727 holds 4. */
    var version: Int? = null,
    /**
     * The transform type of each [world.gregs.voidps.cache.definition.data.GameTipElementRecord] type, in
     * ordinal order - the client's `TipRenderDetails.transformType` - which it compares against its own
     * table and gives up on the whole index if they disagree. 727 holds 1, 2, 2, 2, 1, 1, 1, 2, 1, 2.
     */
    var renderTypes: IntArray? = null,
    /** How many stage records follow. Not the length of [stages] when a stage is listed twice. */
    var definedStageCount: Int? = null,
    /** The highest startup stage the client builds a slot for; not derivable from [stages]. */
    var totalStageCount: Int? = null,
    /** The tip file every stage falls back to, or -1 for none. Signed. */
    var tipsFileId: Int? = null,
    /** How long the fallback tip is shown, in milliseconds. */
    var displayDuration: Int? = null,
    /** How long between fallback tip updates, in milliseconds. */
    var timeBetweenUpdates: Int? = null,
    var stages: List<GameTipStageRecord>? = null,
    /** The draw elements of one loading screen, in the order the client draws them. */
    var elements: List<GameTipElementRecord>? = null
) : Definition

/**
 * One startup stage of the master stage table and the tips it may show.
 *
 * @param stage the startup stage this record configures; the file may list one stage more than once, and
 *        the client lets the last record win, so the id stays on the record rather than becoming a key.
 * @param hasUpdates the raw byte the client tests for `== 1`, kept raw so any other value survives.
 */
data class GameTipStageRecord(
    var stage: Int,
    var hasUpdates: Int,
    var updates: List<GameTipUpdateRecord>
) : DefinitionRecord

/** One tip a stage may show, and how long for. */
data class GameTipUpdateRecord(
    var tipFileId: Int,
    var displayDuration: Int,
    var timeBetweenUpdates: Int
) : DefinitionRecord

/**
 * One draw element of a loading screen.
 *
 * The ten element shapes share one flat record so the list needs no polymorphism: [type] says which shape
 * this is and only that shape's fields are set, the rest staying null and being left out of the file.
 *
 * [type] is the ordinal of the client's `TipRenderDetails`:
 *
 * | type | shape | fields |
 * |---|---|---|
 * | 0 | `BACKGROUND` | [colour] |
 * | 1 | `COLOR_LOADING_BAR` | the anchored fields, [fillColour], [outlineColour] |
 * | 2 | `GRAPHIC_LOADING_BAR` | the anchored fields, [fillColour], [backgroundColour], [graphicFileId] |
 * | 3 | `MULTI_GRAPHIC_LOADING_BAR` | the anchored fields, the six bar graphics |
 * | 4 | `OUTLINED` | [newsItemId], [anchorX], [anchorY], [offsetX], [offsetY], [width], [height], [textColour], [shadeColour], [backgroundColour], [drawOutline] |
 * | 5 | `GRAPHIC` | [graphicId], [anchorX], [anchorY], [offsetX], [offsetY] |
 * | 6 | `ROTATED_GRAPHIC` | the `GRAPHIC` fields and [angle] |
 * | 7 | `ANCHORED_TEXT` | [tipText], [anchorX], [anchorY], [offsetX], [offsetY], [textAlign], [textVAlign], [lineSpacing], [width], [height], [fileId], [textColour], [textShadowColour] |
 * | 8 | `TIPS_GRAPHIC` | [graphicFileId] |
 * | 9 | `ANIMATED_LOADING_BAR` | the `MULTI_GRAPHIC_LOADING_BAR` fields and [scrollSpeed] |
 *
 * The anchored fields, the prefix types 1, 2, 3 and 9 share, are [anchorX], [anchorY], [offsetX],
 * [offsetY], [width], [height], [textOffsetY], [fileId] and [textColour].
 *
 * @param anchorX `AnchorX` ordinal: 0 left, 1 centre, 2 right.
 * @param anchorY `AnchorY` ordinal: 0 top, 1 centre, 2 bottom.
 * @param fileId the font, or graphic, the element's text is drawn with.
 * @param drawOutline the raw byte the client tests for `== 1`, kept raw so any other value survives.
 * @param wideFileIds the ids of this element that the file packed in the four byte form of a big smart
 *        although the two byte form would have held them, low bit first in the order the element reads
 *        them. Null - and left out of the file - when every id was packed as narrowly as it fits, which is
 *        every element of the 727 cache; see the encoder.
 */
data class GameTipElementRecord(
    var type: Int,
    var colour: Int? = null,
    var newsItemId: Int? = null,
    var graphicId: Int? = null,
    var tipText: String? = null,
    var anchorX: Int? = null,
    var anchorY: Int? = null,
    var offsetX: Int? = null,
    var offsetY: Int? = null,
    var width: Int? = null,
    var height: Int? = null,
    var textOffsetY: Int? = null,
    var textAlign: Int? = null,
    var textVAlign: Int? = null,
    var lineSpacing: Int? = null,
    var fileId: Int? = null,
    var textColour: Int? = null,
    var shadeColour: Int? = null,
    var textShadowColour: Int? = null,
    var fillColour: Int? = null,
    var outlineColour: Int? = null,
    var backgroundColour: Int? = null,
    var drawOutline: Int? = null,
    var graphicFileId: Int? = null,
    var fillGraphic: Int? = null,
    var emptyGraphic: Int? = null,
    var leftCapGraphic: Int? = null,
    var rightCapGraphic: Int? = null,
    var topBorderGraphic: Int? = null,
    var bottomBorderGraphic: Int? = null,
    var angle: Int? = null,
    var scrollSpeed: Int? = null,
    var wideFileIds: Int? = null
) : DefinitionRecord
