package world.gregs.voidps.cache.definition.data

/**
 * One npc the client spawns from a `n<x>_<y>` map archive.
 *
 * The archive is the simplest of the five a map square is made of: a bare list of four byte
 * records, no header and no terminator, read until the file runs out (the bot's
 * `MapRegion.decodeNpcSpawns`). Each record is the packed position and the npc id:
 *
 * ```
 * u16  level shl 14 or localX shl 7 or localY
 * u16  npc id
 * ```
 *
 * Only 38 of the 727 cache's 2,407 map squares carry one, eight of those are empty, and the client
 * stops after 511 records - which nothing in 727 comes close to.
 */
class MapNpcSpawn(
    val npc: Int,
    /** 0 to 63, local to the map square. */
    val x: Int,
    /** 0 to 63, local to the map square. */
    val y: Int,
    val level: Int
) {

    /** The position as the file packs it. */
    fun packed(): Int = (level shl 14) or (x shl 7) or y

    override fun toString(): String = "MapNpcSpawn(npc=$npc, x=$x, y=$y, level=$level)"

    companion object {

        /** The record a packed position and an npc id make. */
        fun of(packed: Int, npc: Int): MapNpcSpawn = MapNpcSpawn(
            npc = npc,
            x = (packed shr 7) and 0x3f,
            y = packed and 0x3f,
            level = packed shr 14
        )

        /** Every record is a position and an id, two bytes each. */
        const val RECORD_SIZE = 4
    }
}
