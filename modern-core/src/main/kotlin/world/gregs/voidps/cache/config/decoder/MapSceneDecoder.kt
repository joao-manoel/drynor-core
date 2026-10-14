package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.MAP_SCENES
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.MapSceneDefinition
import world.gregs.voidps.cache.config.encoder.MapSceneEncoder

class MapSceneDecoder : ConfigDecoder<MapSceneDefinition>(MAP_SCENES) {

    private val encoder = MapSceneEncoder()

    override fun create(size: Int) = Array(size) { MapSceneDefinition(it) }

    override fun canonicalOpcodes(definition: MapSceneDefinition): IntArray = encoder.opcodes(definition)

    override fun MapSceneDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> graphic = buffer.readBigSmart()
            2 -> colour = buffer.readUnsignedMedium()
            3 -> aBoolean1741 = true
            4 -> graphic = -1
            else -> error("Unhandled map scene definition opcode: $opcode for def $id")
        }
    }

}