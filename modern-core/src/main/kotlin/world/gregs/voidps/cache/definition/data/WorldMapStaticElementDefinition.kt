package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One placed world map icon: index 23, an `<area>_staticelements` archive, one file per icon.
 *
 * Seven bytes and no opcodes. All 1,091 static element files in the 727 cache are exactly this.
 *
 * @param position the bitpacked coordinate - level `ushr 28`, x `(ushr 14) and 0x3fff`, y `and 0x3fff`
 * @param mapElement the `mapelement` id (index 2 archive 36) that says what to draw and what it is called
 * @param members 1 for an icon a free world hides, 0 otherwise
 */
data class WorldMapStaticElementDefinition(
    override var id: Int = -1,
    var position: Int = 0,
    var mapElement: Int = -1,
    var members: Int = 0
) : Definition
