package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.WorldMapDefinition

/**
 * Index 23's `details` files, byte for byte.
 *
 * A flat struct, so there is nothing to decide: the two strings, the origin, the colour, the render
 * boolean, the raw zoom byte, the map size byte, the section count and 17 bytes per section.
 */
class WorldMapDetailsEncoder : DefinitionEncoder<WorldMapDefinition> {

    override fun Writer.encode(definition: WorldMapDefinition) {
        writeString(definition.map)
        writeString(definition.name)
        writeInt(definition.position)
        writeInt(definition.anInt9542)
        writeByte(if (definition.static) 1 else 0)
        writeByte(definition.rawZoom)
        writeByte(definition.mapSizeId)
        val sections = definition.sections ?: emptyList()
        writeByte(sections.size)
        for (section in sections) {
            writeByte(section.level)
            writeShort(section.minX)
            writeShort(section.minY)
            writeShort(section.maxX)
            writeShort(section.maxY)
            writeShort(section.startX)
            writeShort(section.startY)
            writeShort(section.endX)
            writeShort(section.endY)
        }
    }
}
