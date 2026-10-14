package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One world map area: index 23, archive `details`, one file per area.
 *
 * A flat struct rather than an opcode list - the internal name, the shown name, the origin, the colour,
 * three bytes of display settings and then the sections that map game coordinates onto map coordinates.
 *
 * [rawZoom] and [mapSizeId] are here so the file survives a round trip: the client folds a zoom of 255
 * into 0, and reads the map size byte and throws it away.
 */
data class WorldMapDefinition(
    override var id: Int = -1,
    var map: String = "",
    var name: String = "",
    var position: Int = -1,
    var anInt9542: Int = -1,
    var static: Boolean = false,
    var anInt9547: Int = -1,
    /** The zoom byte as the file holds it; 255 is what [anInt9547] shows as 0. */
    var rawZoom: Int = -1,
    /** The map size the area renders at - 0 is 104 tiles, 1 is 120, 2 is 136, 3 is 168, 4 is 72. */
    var mapSizeId: Int = 0,
    var sections: MutableList<WorldMapSection>? = null,
    var minX: Int = 12800,
    var minY: Int = 12800,
    var maxX: Int = 0,
    var maxY: Int = 0
) : Definition
