package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.MapNpcSpawn

/**
 * Writes a `n<x>_<y>` map archive, the exact inverse of
 * [world.gregs.voidps.cache.definition.decoder.MapNpcDecoder].
 *
 * Records go out in list order - the file has no ordering rule of its own and the client reads them
 * in order - and the coordinates have to be local to the map square, because the position field has
 * six bits for each and would silently wrap a world coordinate into the wrong tile.
 */
object MapNpcEncoder {

    /** Write [spawns] at [Writer]'s current position. */
    fun Writer.writeSpawns(spawns: List<MapNpcSpawn>) {
        for (spawn in spawns) {
            require(spawn.x in 0..63 && spawn.y in 0..63) {
                "Npc ${spawn.npc} at (${spawn.x}, ${spawn.y}) is not a map square local coordinate, " +
                    "expected 0..63 - world coordinates cannot be encoded."
            }
            require(spawn.level in 0..3) { "Npc ${spawn.npc} is on level ${spawn.level}, outside 0..3." }
            require(spawn.npc in 0..0xffff) { "Npc id ${spawn.npc} does not fit in a short." }
            writeShort(spawn.packed())
            writeShort(spawn.npc)
        }
    }
}
