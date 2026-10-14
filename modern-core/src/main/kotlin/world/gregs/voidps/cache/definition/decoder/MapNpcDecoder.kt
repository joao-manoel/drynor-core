package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.definition.data.MapNpcSpawn

/**
 * Decodes a `n<x>_<y>` map archive: the npc spawns of one map square.
 *
 * Four bytes per record and nothing else in the file, so the record count is the file length over
 * four - see [MapNpcSpawn]. A file whose length is not a multiple of four is a file the client
 * would read one record too far into, so it is rejected rather than truncated.
 */
object MapNpcDecoder {

    /** Every spawn in [data], in file order. */
    fun decode(data: ByteArray, region: Int = -1): MutableList<MapNpcSpawn> {
        require(data.size % MapNpcSpawn.RECORD_SIZE == 0) {
            "Npc spawns of region $region are ${data.size} bytes, not a whole number of " +
                "${MapNpcSpawn.RECORD_SIZE} byte records."
        }
        val buffer = BufferReader(data)
        val spawns = ArrayList<MapNpcSpawn>(data.size / MapNpcSpawn.RECORD_SIZE)
        while (buffer.remaining > 0) {
            spawns.add(MapNpcSpawn.of(buffer.readUnsignedShort(), buffer.readUnsignedShort()))
        }
        return spawns
    }
}
