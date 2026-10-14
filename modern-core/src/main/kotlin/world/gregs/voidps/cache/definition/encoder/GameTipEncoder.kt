package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.GameTipElementRecord

/**
 * Index 33, the loading screens, written back exactly as
 * [world.gregs.voidps.cache.definition.decoder.GameTipDecoder] read them.
 *
 * The index carries no opcodes, so there is no record order to preserve and nothing to terminate: the
 * whole file is one fixed layout and the encoder is the decoder read backwards. The archive id picks the
 * layout, exactly as it does on the way in.
 *
 * The only thing the file holds that the client's decoded state does not is how widely a big smart id was
 * packed; [GameTipElementRecord.wideFileIds] carries it, and this writes it back.
 */
class GameTipEncoder : DefinitionEncoder<GameTipDefinition> {

    override fun Writer.encode(definition: GameTipDefinition) {
        if (definition.id == MASTER) {
            writeStageTable(definition)
        } else {
            writeScreen(definition)
        }
    }

    private fun Writer.writeStageTable(definition: GameTipDefinition) {
        val renderTypes = definition.renderTypes
            ?: throw IllegalArgumentException("Game tip ${definition.id} is the stage table but has no render types.")
        val stages = definition.stages
            ?: throw IllegalArgumentException("Game tip ${definition.id} is the stage table but has no stages.")
        writeByte(definition.field("version", definition.version))
        writeByte(renderTypes.size)
        for (renderType in renderTypes) {
            writeByte(renderType)
        }
        writeByte(definition.field("definedStageCount", definition.definedStageCount))
        writeByte(definition.field("totalStageCount", definition.totalStageCount))
        writeShort(definition.field("tipsFileId", definition.tipsFileId))
        writeMedium(definition.field("displayDuration", definition.displayDuration))
        writeShort(definition.field("timeBetweenUpdates", definition.timeBetweenUpdates))
        for (stage in stages) {
            writeByte(stage.stage)
            writeByte(stage.hasUpdates)
            writeShort(stage.updates.size)
            for (update in stage.updates) {
                writeShort(update.tipFileId)
                writeMedium(update.displayDuration)
                writeShort(update.timeBetweenUpdates)
            }
        }
    }

    private fun Writer.writeScreen(definition: GameTipDefinition) {
        val elements = definition.elements
            ?: throw IllegalArgumentException("Game tip ${definition.id} is a screen but has no elements.")
        writeByte(elements.size)
        for (element in elements) {
            writeElement(element)
        }
    }

    private fun Writer.writeElement(element: GameTipElementRecord) {
        writeByte(element.type)
        val ids = BigSmarts(this, element.wideFileIds ?: 0)
        when (element.type) {
            BACKGROUND -> writeInt(element.field("colour", element.colour))
            COLOR_LOADING_BAR -> {
                writeAnchored(element, ids)
                writeInt(element.field("fillColour", element.fillColour))
                writeInt(element.field("outlineColour", element.outlineColour))
            }
            GRAPHIC_LOADING_BAR -> {
                writeAnchored(element, ids)
                writeInt(element.field("fillColour", element.fillColour))
                writeInt(element.field("backgroundColour", element.backgroundColour))
                ids.write(element.field("graphicFileId", element.graphicFileId))
            }
            MULTI_GRAPHIC_LOADING_BAR -> {
                writeAnchored(element, ids)
                writeBarGraphics(element, ids)
            }
            OUTLINED -> {
                writeByte(element.field("newsItemId", element.newsItemId))
                writeByte(element.field("anchorX", element.anchorX))
                writeByte(element.field("anchorY", element.anchorY))
                writeShort(element.field("offsetX", element.offsetX))
                writeShort(element.field("offsetY", element.offsetY))
                writeShort(element.field("width", element.width))
                writeShort(element.field("height", element.height))
                writeInt(element.field("textColour", element.textColour))
                writeInt(element.field("shadeColour", element.shadeColour))
                writeInt(element.field("backgroundColour", element.backgroundColour))
                writeByte(element.field("drawOutline", element.drawOutline))
            }
            GRAPHIC -> writeGraphic(element, ids)
            ROTATED_GRAPHIC -> {
                writeGraphic(element, ids)
                writeShort(element.field("angle", element.angle))
            }
            ANCHORED_TEXT -> {
                writeString(element.tipText)
                writeByte(element.field("anchorX", element.anchorX))
                writeByte(element.field("anchorY", element.anchorY))
                writeShort(element.field("offsetX", element.offsetX))
                writeShort(element.field("offsetY", element.offsetY))
                writeByte(element.field("textAlign", element.textAlign))
                writeByte(element.field("textVAlign", element.textVAlign))
                writeByte(element.field("lineSpacing", element.lineSpacing))
                writeShort(element.field("width", element.width))
                writeShort(element.field("height", element.height))
                ids.write(element.field("fileId", element.fileId))
                writeInt(element.field("textColour", element.textColour))
                writeInt(element.field("textShadowColour", element.textShadowColour))
            }
            TIPS_GRAPHIC -> ids.write(element.field("graphicFileId", element.graphicFileId))
            ANIMATED_LOADING_BAR -> {
                writeAnchored(element, ids)
                writeBarGraphics(element, ids)
                writeShort(element.field("scrollSpeed", element.scrollSpeed))
            }
            else -> throw IllegalArgumentException("Unknown game tip element type ${element.type}.")
        }
    }

    private fun Writer.writeAnchored(element: GameTipElementRecord, ids: BigSmarts) {
        writeByte(element.field("anchorX", element.anchorX))
        writeByte(element.field("anchorY", element.anchorY))
        writeShort(element.field("offsetX", element.offsetX))
        writeShort(element.field("offsetY", element.offsetY))
        writeShort(element.field("width", element.width))
        writeShort(element.field("height", element.height))
        writeShort(element.field("textOffsetY", element.textOffsetY))
        ids.write(element.field("fileId", element.fileId))
        writeInt(element.field("textColour", element.textColour))
    }

    private fun writeBarGraphics(element: GameTipElementRecord, ids: BigSmarts) {
        ids.write(element.field("fillGraphic", element.fillGraphic))
        ids.write(element.field("emptyGraphic", element.emptyGraphic))
        ids.write(element.field("leftCapGraphic", element.leftCapGraphic))
        ids.write(element.field("rightCapGraphic", element.rightCapGraphic))
        ids.write(element.field("topBorderGraphic", element.topBorderGraphic))
        ids.write(element.field("bottomBorderGraphic", element.bottomBorderGraphic))
    }

    private fun Writer.writeGraphic(element: GameTipElementRecord, ids: BigSmarts) {
        ids.write(element.field("graphicId", element.graphicId))
        writeByte(element.field("anchorX", element.anchorX))
        writeByte(element.field("anchorY", element.anchorY))
        writeShort(element.field("offsetX", element.offsetX))
        writeShort(element.field("offsetY", element.offsetY))
    }

    private fun GameTipDefinition.field(name: String, value: Int?): Int =
        value ?: throw IllegalArgumentException("Game tip $id has no $name.")

    private fun GameTipElementRecord.field(name: String, value: Int?): Int =
        value ?: throw IllegalArgumentException("Game tip element of type $type has no $name.")

    /**
     * Writes one element's big smart ids at the widths the file used, from
     * [GameTipElementRecord.wideFileIds].
     *
     * [world.gregs.voidps.buffer.write.Writer.writeBigSmart] always picks the narrowest form the value
     * fits, so an id the file spent four bytes on although two would have done is written wide by hand
     * here; the four byte form is the value with the top bit set, which the reader masks back off.
     */
    private class BigSmarts(private val writer: Writer, private val wide: Int) {

        private var index = 0

        fun write(value: Int) {
            val forced = ((wide shr index) and 1) == 1
            index++
            if (forced) {
                writer.writeInt(value or WIDE_FLAG)
            } else {
                writer.writeBigSmart(value)
            }
        }
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

        val WIDE_FLAG = 0x80000000.toInt()
    }
}
