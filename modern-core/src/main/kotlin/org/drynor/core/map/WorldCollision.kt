package org.drynor.core.map

import org.drynor.core.map.pathfinder.getIndexInZone
import org.drynor.core.map.pathfinder.getZoneIndex
import org.drynor.core.model.ObjectShape
import world.gregs.voidps.type.Tile

fun interface CollisionWriter {
    fun add(x: Int, y: Int, level: Int, flag: Int)
}

private fun CollisionWriter.add(tile: Tile, flag: Int) = add(tile.x, tile.y, tile.level, flag)

/**
 * Global collision flag map.
 * Stores collision flags per-zone in a flat array indexed by zone coordinate.
 * The pathfinder reads from [allFlags] directly.
 */
object WorldCollision {
    @JvmField
    val allFlags: Array<IntArray?> = arrayOfNulls(2048 * 2048 * 4)

    @JvmField
    val live = CollisionWriter(::addFlag)

    @JvmStatic
    fun getFlags(x: Int, y: Int, level: Int): Int {
        val zoneId = getZoneIndex(x, y, level)
        if (zoneId < 0 || zoneId >= allFlags.size) return -1
        val zone = allFlags[zoneId] ?: return -1
        return zone[getIndexInZone(x, y)]
    }

    @JvmStatic
    fun getFlags(tile: Tile): Int = getFlags(tile.x, tile.y, tile.level)

    @JvmStatic
    fun addFlag(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
        val zone = allFlags[zoneId]!!
        val idx = getIndexInZone(x, y)
        zone[idx] = zone[idx] or flag
    }

    @JvmStatic
    fun addFlag(tile: Tile, flag: Int) = addFlag(tile.x, tile.y, tile.level, flag)

    @JvmStatic
    fun removeFlag(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        val zone = allFlags[zoneId] ?: return
        val idx = getIndexInZone(x, y)
        zone[idx] = zone[idx] and flag.inv()
    }

    @JvmStatic
    fun removeFlag(tile: Tile, flag: Int) = removeFlag(tile.x, tile.y, tile.level, flag)

    @JvmStatic
    fun setFlags(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
        allFlags[zoneId]!![getIndexInZone(x, y)] = flag
    }

    @JvmStatic
    fun clearZone(zoneId: Int) {
        allFlags[zoneId] = null
    }

    @JvmStatic
    fun initZone(zoneId: Int) {
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
    }

    /** Alias for [clearZone] used by legacy chunk-based callers. */
    @JvmStatic
    fun clearChunk(chunkId: Int) = clearZone(chunkId)

    /** Alias for [initZone] used by legacy chunk-based callers. */
    @JvmStatic
    fun initChunk(chunkId: Int) = initZone(chunkId)

    // --- Convenience flag operations ---

    @JvmStatic
    fun addBlockedTile(tile: Tile) = addFlag(tile, ClipFlag.PFBW_FLOOR.flag)
    @JvmStatic
    fun removeBlockedTile(tile: Tile) = removeFlag(tile, ClipFlag.PFBW_FLOOR.flag)

    @JvmStatic
    fun addBlockWalkAndProj(tile: Tile) = addFlag(tile, ClipFlag.PFBW_GROUND_DECO.flag)
    @JvmStatic
    fun removeBlockWalkAndProj(tile: Tile) = removeFlag(tile, ClipFlag.PFBW_GROUND_DECO.flag)

    @JvmStatic
    fun addClipNPC(tile: Tile) = addFlag(tile, ClipFlag.BW_NPC.flag)
    @JvmStatic
    fun removeClipNPC(tile: Tile) = removeFlag(tile, ClipFlag.BW_NPC.flag)

    @JvmStatic
    fun addClipPlayer(tile: Tile) = addFlag(tile, ClipFlag.BW_PLAYER.flag)
    @JvmStatic
    fun removeClipPlayer(tile: Tile) = removeFlag(tile, ClipFlag.BW_PLAYER.flag)

    @JvmStatic
    fun getClipNPC(tile: Tile): Boolean = ClipFlag.flagged(getFlags(tile), ClipFlag.BW_NPC)

    // --- Object clipping ---

    fun addObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean, writer: CollisionWriter = live) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX)
            for (tileY in tile.y until tile.y + sizeY)
                writer.add(tileX, tileY, tile.level, flag)
    }

    fun removeObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX)
            for (tileY in tile.y until tile.y + sizeY)
                removeFlag(tileX, tileY, tile.level, flag)
    }

    fun addWall(tile: Tile, type: ObjectShape, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean, writer: CollisionWriter = live) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> when (rotation) {
                0 -> { writer.add(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
                1 -> { writer.add(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                2 -> { writer.add(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); writer.add(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                3 -> { writer.add(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> when (rotation) {
                0 -> { writer.add(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(-1, 1), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)) }
                1 -> { writer.add(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)); writer.add(tile.add(1, 1), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)) }
                2 -> { writer.add(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)); writer.add(tile.add(1, -1), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)) }
                3 -> { writer.add(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(-1, -1), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_WHOLE_CORNER -> when (rotation) {
                0 -> { writer.add(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                1 -> { writer.add(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); writer.add(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                2 -> { writer.add(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); writer.add(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
                3 -> { writer.add(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); writer.add(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); writer.add(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
            }
            else -> {}
        }
    }

    fun removeWall(tile: Tile, type: ObjectShape, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 1), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 1), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, -1), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, -1), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_WHOLE_CORNER -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
            }
            else -> {}
        }
    }

    /**
     * Clip an object into the collision map based on its shape, rotation, and definition properties.
     */
    fun clipObject(tile: Tile, id: Int, shape: ObjectShape, rotation: Int, clipType: Int, blocksProjectiles: Boolean, ignoresPathfinder: Boolean, sizeX: Int, sizeY: Int, writer: CollisionWriter = live) {
        if (id == -1 || clipType == 0) return
        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                addWall(tile, shape, rotation, blocksProjectiles, !ignoresPathfinder, writer)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sx = if (rotation == 1 || rotation == 3) sizeY else sizeX
                val sy = if (rotation == 1 || rotation == 3) sizeX else sizeY
                addObject(tile, sx, sy, blocksProjectiles, !ignoresPathfinder, writer)
            }
            ObjectShape.GROUND_DECORATION -> if (clipType == 1) writer.add(tile, ClipFlag.PFBW_GROUND_DECO.flag)
            else -> {}
        }
    }

    fun unclipObject(tile: Tile, id: Int, shape: ObjectShape, rotation: Int, clipType: Int, blocksProjectiles: Boolean, ignoresPathfinder: Boolean, sizeX: Int, sizeY: Int) {
        if (id == -1 || clipType == 0) return
        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                removeWall(tile, shape, rotation, blocksProjectiles, !ignoresPathfinder)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sx = if (rotation == 1 || rotation == 3) sizeY else sizeX
                val sy = if (rotation == 1 || rotation == 3) sizeX else sizeY
                removeObject(tile, sx, sy, blocksProjectiles, !ignoresPathfinder)
            }
            ObjectShape.GROUND_DECORATION -> if (clipType == 1) removeBlockWalkAndProj(tile)
            else -> {}
        }
    }
}
