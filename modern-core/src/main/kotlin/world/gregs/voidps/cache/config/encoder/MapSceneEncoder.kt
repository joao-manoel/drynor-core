package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.MapSceneDefinition

/**
 * Writes a map scene (`mapscene`, index 2 archive 34) file.
 *
 * Opcode 4 is the bare "no graphic" marker; opcode 1 carries a graphic as a big smart. A definition
 * with no graphic is written as opcode 4 unless its file used opcode 1 with the -1 sentinel, which
 * the recorded opcode order preserves.
 */
class MapSceneEncoder : OpcodeEncoder<MapSceneDefinition>() {

    override fun opcodes(definition: MapSceneDefinition): IntArray {
        val opcodes = ArrayList<Int>(4)
        if (definition.graphic > 0) {
            opcodes.add(1)
        }
        if (definition.colour != 0) {
            opcodes.add(2)
        }
        if (definition.aBoolean1741) {
            opcodes.add(3)
        }
        if (definition.graphic == -1) {
            opcodes.add(4)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: MapSceneDefinition, members: MapSceneDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphic)
            2 -> writeMedium(definition.colour)
            3, 4 -> {}
            else -> error("Unhandled map scene opcode $opcode for definition ${definition.id}")
        }
    }
}
