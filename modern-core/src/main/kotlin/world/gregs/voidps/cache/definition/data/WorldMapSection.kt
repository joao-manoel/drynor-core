package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.definition.DefinitionRecord

/**
 * One rectangle of a world map area, 17 bytes: a level and eight shorts.
 *
 * [minX] to [maxY] are the game coordinates the rectangle covers and [startX] to [endY] the world map
 * coordinates it is drawn at, which is the pairing the client offsets between.
 */
data class WorldMapSection(
    val level: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    var startX: Int,
    var startY: Int,
    var endX: Int,
    var endY: Int
) : DefinitionRecord
