package org.drynor.core.util

import world.gregs.voidps.type.Tile
import org.drynor.core.Utils

enum class Structure(
    private val parent: Structure?,
    private val childWidth: Int,
    private val childHeight: Int,
    private val encoder: ((Int, Int, Int) -> Int)?,
    private val decoder: ((Int) -> IntArray)?
) {
    TILE(
        null, 1, 1,
        encoder = { x, y, plane -> y or (x shl 14) or (plane shl 28) },
        decoder = { id -> intArrayOf((id shr 14) and 16383, id and 16383, (id shr 28) and 3) }
    ),
    CHUNK(
        TILE, 8, 8,
        encoder = { x, y, plane -> (x shl 11) or y or (plane shl 22) },
        decoder = { id -> intArrayOf((id shr 11) and 2047, id and 2047, (id shr 22) and 3) }
    ),
    REGION(
        CHUNK, 8, 8,
        encoder = { x, y, plane -> (x shl 8) or y or (plane shl 16) },
        decoder = { id -> intArrayOf((id shr 8) and 255, id and 255, (id shr 24) and 3) }
    ),
    MAP(
        REGION, 256, 256,
        encoder = null,
        decoder = null
    );

    fun getWidth(): Int {
        var w = childWidth
        var p = parent
        while (p != null) {
            w *= p.childWidth
            p = p.parent
        }
        return w
    }

    fun getChildWidth(): Int = childWidth

    fun getHeight(): Int {
        var h = childHeight
        var p = parent
        while (p != null) {
            h *= p.childHeight
            p = p.parent
        }
        return h
    }

    fun getChildHeight(): Int = childWidth // Note: matches legacy bug (returns width, not height)

    fun encode(x: Int, y: Int): Int = encode(x, y, 0)

    fun encode(x: Int, y: Int, plane: Int): Int =
        encoder?.invoke(x, y, plane) ?: throw UnsupportedOperationException("$name does not support encoding")

    fun decode(id: Int): IntArray =
        decoder?.invoke(id) ?: throw UnsupportedOperationException("$name does not support decoding")

    override fun toString(): String = Utils.formatPlayerNameForDisplay(name)
}

class Area(
    val structure: Structure,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val mapX: Int get() = x * structure.getWidth()
    val mapY: Int get() = y * structure.getHeight()
    val mapWidth: Int get() = width * structure.getWidth()
    val mapHeight: Int get() = height * structure.getHeight()

    fun getRandomTile(): Tile = Tile(
        mapX + Utils.random(mapWidth),
        mapY + Utils.random(mapHeight),
        0
    )

    fun getRandomTile(fromCenter: Int): Tile {
        val centerX = mapX + mapWidth / 2
        val centerY = mapY + mapHeight / 2
        return Tile(
            centerX - fromCenter + Utils.random(fromCenter * 2),
            centerY - fromCenter + Utils.random(fromCenter * 2),
            0
        )
    }

    fun within(tile: Tile): Boolean {
        return tile.x >= mapX && tile.x < mapX + mapWidth && tile.y >= mapY && tile.y < mapY + mapHeight
    }

    override fun hashCode(): Int = structure.encode(x, y, 0)

    override fun toString(): String = "Area(${structure.name}, $x, $y, ${width}x${height})"
}

object MapUtils {

    @JvmStatic
    fun getArea(min: Tile, max: Tile): Area = getArea(Structure.TILE, min.x, min.y, max.x, max.y)

    @JvmStatic
    fun getArea(minX: Int, minY: Int, maxX: Int, maxY: Int): Area = getArea(Structure.TILE, minX, minY, maxX, maxY)

    @JvmStatic
    fun getArea(structure: Structure, minX: Int, minY: Int, maxX: Int, maxY: Int): Area =
        Area(structure, minX, minY, maxX - minX + 1, maxY - minY + 1)

    @JvmStatic
    fun within(area: Area, tile: Tile): Boolean = area.within(tile)

    @JvmStatic
    fun encode(structure: Structure, vararg xyp: Int): Int {
        val plane = if (xyp.size > 2) xyp[2] else 0
        return structure.encode(xyp[0], xyp[1], plane)
    }

    @JvmStatic
    fun decode(structure: Structure, id: Int): IntArray = structure.decode(id)

    @JvmStatic
    fun chunkToRegionId(chunkId: Int): Int {
        val decoded = Structure.CHUNK.decode(chunkId)
        val tile = Tile(decoded[0] shl 3, decoded[1] shl 3, 0)
        return tile.region.id
    }

    @JvmStatic
    fun convert(from: Structure, to: Structure, vararg xy: Int): IntArray {
        val fromWidth = from.getWidth()
        val fromHeight = from.getHeight()
        val toWidth = to.getWidth()
        val toHeight = to.getHeight()
        return intArrayOf(xy[0] * fromWidth / toWidth, xy[1] * fromHeight / toHeight)
    }

    @JvmStatic
    fun convert(to: Structure, area: Area): Area {
        val converted = convert(area.structure, to, area.x, area.y)
        val convertedW = convert(area.structure, to, area.width, area.height)
        return Area(to, converted[0], converted[1], convertedW[0], convertedW[1])
    }
}
