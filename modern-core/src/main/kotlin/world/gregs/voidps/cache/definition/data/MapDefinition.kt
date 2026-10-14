package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One map square's terrain, locations and - for `m<x>_<y>` - the environment block behind them.
 *
 * The five archives a map square is made of ([world.gregs.voidps.cache.MapSquare]) all decode into
 * one of these: [tiles] and [effects] come from a terrain archive, [objects] from a locations
 * archive, and the underwater pair fills the same fields with [levels] set to 1.
 *
 * [levels] and [effects] are what a lossless decode needs beyond the tiles and the objects, and
 * both are additive: a definition nobody set them on is a four level surface square with no
 * environment block, which is what every existing caller builds and reads.
 */
class MapDefinition(
    override var id: Int = -1,
    val tiles: LongArray = LongArray(64 * 64 * 4),
    val objects: MutableList<MapObject> = mutableListOf(),
    /**
     * How many levels of tile records the archive holds: 4 for a surface square, 1 for an
     * underwater one, and 0 for an archive with no tiles at all.
     *
     * The client decides the same way - it builds the underwater scene with a single plane - and
     * the count is not in the file, so it comes from which of the five archives is being read.
     */
    var levels: Int = LEVELS,
    /**
     * The environment records that follow the tiles of a terrain archive, in file order.
     *
     * Empty for every archive that has none, which is the other four shapes and 190 of the 2,407
     * surface squares. See [MapEffect].
     */
    val effects: MutableList<MapEffect> = mutableListOf(),
    /**
     * Bytes after the last record that no record accounts for, or null when the file ends where
     * its records do.
     *
     * 48 of the 727 cache's location archives are padded with zeroes to a multiple of 16 bytes -
     * the packer padded the plaintext before it encrypted it - and the padding is part of the file
     * the client checks the CRC of, so it has to come back. Every other archive of every other
     * shape ends exactly where its records do.
     */
    var trailing: ByteArray? = null
) : Definition {

    fun getTile(localX: Int, localY: Int, level: Int) = MapTile(tiles[index(localX, localY, level)])

    fun setTile(localX: Int, localY: Int, level: Int, tile: MapTile) {
        tiles[index(localX, localY, level)] = tile.packed
    }

    companion object {
        /** Levels a surface map square has. */
        const val LEVELS = 4

        /** Tiles per level, per axis. */
        const val SIZE = 64

        fun index(localX: Int, localY: Int, level: Int): Int {
            return (level shl 12) + (localX shl 6) + localY
        }
        fun localX(tile: Int) = tile shr 6 and 0x3f
        fun localY(tile: Int) = tile and 0x3f
        fun level(level: Int) = level shr 12 and 0x3
    }
}
