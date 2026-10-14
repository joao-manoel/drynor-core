package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.GAME_TIPS
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.GameTipElementRecord
import world.gregs.voidps.cache.definition.data.GameTipStageRecord
import world.gregs.voidps.cache.definition.data.GameTipUpdateRecord

/**
 * Index 33, the loading screens, from the client's `GameTipTypeList` and `GameTipType`.
 *
 * The index is not opcode keyed: every archive holds one file whose whole length is one fixed layout, so
 * [readLoop] does the entire read in one call and there is no terminator to stop on.
 *
 * Two layouts share the index and the archive id picks between them - archive [MASTER] is the stage table,
 * every other archive is one screen - which is why one definition type covers both; see
 * [GameTipDefinition].
 */
class GameTipDecoder : DefinitionDecoder<GameTipDefinition>(GAME_TIPS) {

    override fun create(size: Int) = Array(size) { GameTipDefinition(it) }

    /** One definition per archive, not per file: every archive of this index holds a single file 0. */
    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun getArchive(id: Int) = id

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: GameTipDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun GameTipDefinition.read(opcode: Int, buffer: Reader) {
        if (id == MASTER) {
            readStageTable(buffer)
        } else {
            readScreen(buffer)
        }
    }

    /**
     * The master stage table.
     *
     * The client's decode is defensive rather than a layout of its own: it bails out to an empty table if
     * the version is below 4 or if the render type signature disagrees with its own enum, and it reads on
     * regardless of which. Both checks are read here as plain fields, so a file that would make the client
     * give up still decodes and re-encodes unchanged.
     */
    private fun GameTipDefinition.readStageTable(buffer: Reader) {
        version = buffer.readUnsignedByte()
        val renderTypeCount = buffer.readUnsignedByte()
        renderTypes = IntArray(renderTypeCount) { buffer.readUnsignedByte() }
        val definedStages = buffer.readUnsignedByte()
        definedStageCount = definedStages
        totalStageCount = buffer.readUnsignedByte()
        tipsFileId = buffer.readShort()
        displayDuration = buffer.readUnsignedMedium()
        timeBetweenUpdates = buffer.readUnsignedShort()
        stages = List(definedStages) {
            val stage = buffer.readUnsignedByte()
            val hasUpdates = buffer.readUnsignedByte()
            val updateCount = buffer.readUnsignedShort()
            GameTipStageRecord(
                stage,
                hasUpdates,
                List(updateCount) {
                    GameTipUpdateRecord(
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedMedium(),
                        buffer.readUnsignedShort()
                    )
                }
            )
        }
    }

    /** One loading screen: a count and then that many elements, each tagged with its render type. */
    private fun GameTipDefinition.readScreen(buffer: Reader) {
        val count = buffer.readUnsignedByte()
        elements = List(count) { readElement(buffer) }
    }

    private fun readElement(buffer: Reader): GameTipElementRecord {
        val element = GameTipElementRecord(buffer.readUnsignedByte())
        val ids = BigSmarts(buffer)
        when (element.type) {
            BACKGROUND -> element.colour = buffer.readInt()
            COLOR_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.fillColour = buffer.readInt()
                element.outlineColour = buffer.readInt()
            }
            GRAPHIC_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.fillColour = buffer.readInt()
                element.backgroundColour = buffer.readInt()
                element.graphicFileId = ids.read()
            }
            MULTI_GRAPHIC_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.readBarGraphics(ids)
            }
            OUTLINED -> {
                element.newsItemId = buffer.readUnsignedByte()
                element.anchorX = buffer.readUnsignedByte()
                element.anchorY = buffer.readUnsignedByte()
                element.offsetX = buffer.readShort()
                element.offsetY = buffer.readShort()
                element.width = buffer.readUnsignedShort()
                element.height = buffer.readUnsignedShort()
                element.textColour = buffer.readInt()
                element.shadeColour = buffer.readInt()
                element.backgroundColour = buffer.readInt()
                element.drawOutline = buffer.readUnsignedByte()
            }
            GRAPHIC -> element.readGraphic(buffer, ids)
            ROTATED_GRAPHIC -> {
                element.readGraphic(buffer, ids)
                element.angle = buffer.readShort()
            }
            ANCHORED_TEXT -> {
                element.tipText = buffer.readString()
                element.anchorX = buffer.readUnsignedByte()
                element.anchorY = buffer.readUnsignedByte()
                element.offsetX = buffer.readShort()
                element.offsetY = buffer.readShort()
                element.textAlign = buffer.readUnsignedByte()
                element.textVAlign = buffer.readUnsignedByte()
                element.lineSpacing = buffer.readUnsignedByte()
                element.width = buffer.readUnsignedShort()
                element.height = buffer.readUnsignedShort()
                element.fileId = ids.read()
                element.textColour = buffer.readInt()
                element.textShadowColour = buffer.readInt()
            }
            TIPS_GRAPHIC -> element.graphicFileId = ids.read()
            ANIMATED_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.readBarGraphics(ids)
                element.scrollSpeed = buffer.readShort()
            }
            else -> throw IllegalArgumentException("Unknown game tip element type ${element.type}.")
        }
        element.wideFileIds = ids.wide()
        return element
    }

    /** The prefix types 1, 2, 3 and 9 share, the client's `decodeAnchored`. */
    private fun GameTipElementRecord.readAnchored(buffer: Reader, ids: BigSmarts) {
        anchorX = buffer.readUnsignedByte()
        anchorY = buffer.readUnsignedByte()
        offsetX = buffer.readShort()
        offsetY = buffer.readShort()
        width = buffer.readUnsignedShort()
        height = buffer.readUnsignedShort()
        textOffsetY = buffer.readShort()
        fileId = ids.read()
        textColour = buffer.readInt()
    }

    /** The six graphics of a multi graphic bar, the client's `decodeMultiSpriteLoadingBar`. */
    private fun GameTipElementRecord.readBarGraphics(ids: BigSmarts) {
        fillGraphic = ids.read()
        emptyGraphic = ids.read()
        leftCapGraphic = ids.read()
        rightCapGraphic = ids.read()
        topBorderGraphic = ids.read()
        bottomBorderGraphic = ids.read()
    }

    /** The client's `SpritePositioning.decode`, which types 5 and 6 share. */
    private fun GameTipElementRecord.readGraphic(buffer: Reader, ids: BigSmarts) {
        graphicId = ids.read()
        anchorX = buffer.readUnsignedByte()
        anchorY = buffer.readUnsignedByte()
        offsetX = buffer.readShort()
        offsetY = buffer.readShort()
    }

    /**
     * Reads one element's big smart ids, recording which of them the file packed wider than it had to.
     *
     * A big smart holds -1 and 0..32766 in two bytes and anything else in four, and
     * [world.gregs.voidps.buffer.write.Writer.writeBigSmart] always picks the narrower form, so a file that
     * spent four bytes on a value the two byte form would have held cannot be written back from the value
     * alone. The width of each id is recorded as a bit of [GameTipElementRecord.wideFileIds] instead, and
     * that field stays null - and out of the tree - for the overwhelmingly common case of a file that
     * packed everything as narrowly as it fits.
     */
    private class BigSmarts(private val buffer: Reader) {

        private var mask = 0

        private var index = 0

        fun read(): Int {
            val start = buffer.position()
            val value = buffer.readBigSmart()
            // Only a value the narrow form covers can have been packed wider than it had to be; a wide
            // read never yields -1, since the four byte form masks the sign bit off.
            if (buffer.position() - start == WIDE_BYTES && value in 0..NARROW_MAX) {
                mask = mask or (1 shl index)
            }
            index++
            return value
        }

        fun wide() = if (mask == 0) null else mask
    }

    private companion object {
        /** The archive that holds the master stage table rather than a screen. */
        const val MASTER = 0

        const val BACKGROUND = 0
        const val COLOR_LOADING_BAR = 1
        const val GRAPHIC_LOADING_BAR = 2
        const val MULTI_GRAPHIC_LOADING_BAR = 3
        const val OUTLINED = 4
        const val GRAPHIC = 5
        const val ROTATED_GRAPHIC = 6
        const val ANCHORED_TEXT = 7
        const val TIPS_GRAPHIC = 8
        const val ANIMATED_LOADING_BAR = 9

        const val WIDE_BYTES = 4
        const val NARROW_MAX = 32766
    }
}
