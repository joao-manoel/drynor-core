package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.definition.data.WorldMapStaticElementDefinition

/**
 * Index 23's `<area>_staticelements` files: a coordinate, a map element id and the members flag.
 *
 * Not opcode keyed and only seven bytes long, so the whole read is [readLoop]. The archive an element
 * belongs to is the area's, and only the area's archive name says which, so this decoder is only ever
 * driven with bytes somebody else looked up - the source tree does exactly that.
 */
class WorldMapStaticElementDecoder : DefinitionDecoder<WorldMapStaticElementDefinition>(WORLD_MAP) {

    override fun create(size: Int) = Array(size) { WorldMapStaticElementDefinition(it) }

    override fun readLoop(definition: WorldMapStaticElementDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun WorldMapStaticElementDefinition.read(opcode: Int, buffer: Reader) {
        position = buffer.readInt()
        mapElement = buffer.readUnsignedShort()
        members = buffer.readUnsignedByte()
        check(buffer.readableBytes() == 0) {
            "World map static element $id has ${buffer.readableBytes()} bytes past its seven."
        }
    }
}
