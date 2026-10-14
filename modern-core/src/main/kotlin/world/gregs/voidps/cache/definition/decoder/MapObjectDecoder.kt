package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.cache.definition.data.MapDefinition

/**
 * Decodes all objects in a map.
 *
 * Objects are loaded at their ORIGINAL plane as stored in the map data.
 * The bridge tile flag (0x2 on plane 1) only affects collision plane calculation,
 * NOT the object's stored plane. Rendering transformations like "linkBelow" that
 * shift objects for display purposes happen separately after loading.
 *
 * Reference: darkan-bot SceneGraph.decodeLocMapClient - objects are always added
 * at their original plane, with only the collision map reference adjusted for bridges.
 */
abstract class MapObjectDecoder {

    /**
     * Decodes object information and calls [add] for each object.
     * Objects are stored at their original plane from the map data.
     *
     * Returns the offset the records ended at, which is the file's length for all but the 48
     * location archives that carry trailing padding - see [MapDefinition.trailing].
     */
    fun decode(buffer: ByteArray, settings: ByteArray, regionTileX: Int, regionTileY: Int): Int {
        var position = 0
        var objectId = -1
        while (true) {
            // Client `Packet.readSum`: unsigned smarts accumulate for as long as each one is 32767, so a
            // single delta can be built from any number of parts. Chaining only once (as this used to)
            // mis-reads every archive whose id delta needs two or more parts - the leftover part is then
            // consumed as a position offset and the rest of the archive decodes as garbage.
            var skip = 0
            var part: Int
            do {
                val peek = buffer[position++].toInt() and 0xff
                part = if (peek < 128) peek else (peek shl 8 or (buffer[position++].toInt() and 0xff)) - 32768
                skip += part
            } while (part == 32767)
            // A sum of 0 (which can only come from a leading 0 byte) ends the archive.
            if (skip == 0) {
                break
            }
            objectId += skip
            var tile = 0
            while (true) {
                /*
                val loc = reader.readSmart()
                if (loc == 0) {
                    break
                }
                tile += loc - 1
                */
                // Decomposed for early exit
                val loc = buffer[position++].toInt() and 0xff
                tile += when {
                    loc == 0 -> break
                    loc >= 128 -> (loc shl 8 or (buffer[position++].toInt() and 0xff)) - 32769
                    else -> loc - 1
                }

                // Extract the original plane from the tile data - do NOT adjust for bridges here.
                // Bridge adjustments only affect collision plane, not the object's stored plane.
                val level = tile shr 12

                // Data
                val data = buffer[position++].toInt()
                val shape = data shr 2
                val rotation = data and 0x3
                val localX = MapDefinition.localX(tile)
                val localY = MapDefinition.localY(tile)

                // Add object at its original plane
                add(objectId, localX, localY, level, shape, rotation, regionTileX, regionTileY)
            }
        }
        return position
    }

    abstract fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int)
}
