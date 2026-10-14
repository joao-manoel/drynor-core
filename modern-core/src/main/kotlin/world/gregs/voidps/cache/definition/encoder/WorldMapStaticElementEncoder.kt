package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.WorldMapStaticElementDefinition

/** Index 23's static element files: the seven bytes, in order. */
class WorldMapStaticElementEncoder : DefinitionEncoder<WorldMapStaticElementDefinition> {

    override fun Writer.encode(definition: WorldMapStaticElementDefinition) {
        writeInt(definition.position)
        writeShort(definition.mapElement)
        writeByte(definition.members)
    }
}
