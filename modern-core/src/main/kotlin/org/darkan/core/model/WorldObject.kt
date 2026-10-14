package org.darkan.core.model

import org.darkan.core.cache.ObjectDefinitions
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.Tile

open class WorldObject(
    open var id: Int,
    open var type: ObjectShape,
    open var rotation: Int,
    open var tile: Tile
) {

    constructor(id: Int, rotation: Int, x: Int, y: Int, plane: Int) : this(
        id = id,
        type = ObjectDefinitions.getDefs(id).modelTypes?.let { ObjectShape.forId(it[0].toInt()) ?: ObjectShape.SCENERY_INTERACT } ?: ObjectShape.SCENERY_INTERACT,
        rotation = rotation,
        tile = Tile(x, y, plane)
    )

    constructor(id: Int, type: ObjectShape, rotation: Int, x: Int, y: Int, plane: Int) : this(
        id = id,
        type = type,
        rotation = rotation,
        tile = Tile(x, y, plane)
    )

    constructor(other: WorldObject) : this(
        id = other.id,
        type = other.type,
        rotation = other.rotation,
        tile = other.tile
    )

    val definitions
        get() = ObjectDefinitions.getDefs(id)

    val coordFaceX: Int
        get() = tile.getCoordFaceX(definitions.sizeX, definitions.sizeY, rotation)

    val coordFaceY: Int
        get() = tile.getCoordFaceY(definitions.sizeX, definitions.sizeY, rotation)

    val coordFace: Tile
        get() = Tile(coordFaceX, coordFaceY, tile.level)

    fun getRotation(turn: Int): Int = (rotation + turn) and 0x3

    val slot: Int
        get() = type.slot

    val x: Int
        get() = tile.x

    val y: Int
        get() = tile.y

    val level: Int
        get() = tile.level

    /** Legacy alias for [level]. */
    val plane: Int
        get() = tile.level

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorldObject) return false
        return id == other.id && type == other.type && rotation == other.rotation && tile == other.tile
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + type.hashCode()
        result = 31 * result + rotation
        result = 31 * result + tile.hashCode()
        return result
    }

    override fun toString(): String = "WorldObject(id=$id, type=$type, rotation=$rotation, tile=$tile)"
}