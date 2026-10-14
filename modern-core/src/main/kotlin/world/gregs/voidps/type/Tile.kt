package world.gregs.voidps.type

import world.gregs.voidps.type.area.Cuboid
import java.security.SecureRandom

class Tile(val id: Int) : Coordinate3D<Tile> {

    @JvmOverloads
    constructor(x: Int, y: Int, level: Int = 0) : this(id(x, y, level))

    override val x: Int
        get() = x(id)
    override val y: Int
        get() = y(id)
    override val level: Int
        get() = level(id)

    val zone: Zone
        get() = Zone(x shr 3, y shr 3, level)
    val region: Region
        get() = Region(x shr 6, y shr 6)
    val regionLevel: RegionLevel
        get() = RegionLevel(x shr 6, y shr 6, level)

    fun getCoordFaceX(sizeX: Int, sizeY: Int, rotation: Int) = x + ((if (rotation == 1 || rotation == 3) sizeY else sizeX) - 1) / 2
    fun getCoordFaceX(sizeX: Int) = getCoordFaceX(sizeX, -1, -1)
    fun getCoordFaceY(sizeX: Int, sizeY: Int, rotation: Int) = y + ((if (rotation == 1 || rotation == 3) sizeX else sizeY) - 1) / 2
    fun getCoordFaceY(sizeY: Int) = getCoordFaceY(-1, sizeY, -1)

    override fun copy(x: Int, y: Int, level: Int) = Tile(x, y, level)

    fun distanceTo(other: Tile, width: Int, height: Int) = distanceTo(Distance.getNearest(other, width, height, this))

    fun distanceTo(other: Tile): Int {
        if (level != other.level) {
            return -1
        }
        return Distance.chebyshev(x, y, other.x, other.y)
    }

    fun within(other: Tile, radius: Int): Boolean {
        return Distance.within(x, y, level, other.x, other.y, other.level, radius)
    }

    fun within(x: Int, y: Int, level: Int, radius: Int): Boolean {
        return Distance.within(this.x, this.y, this.level, x, y, level, radius)
    }

    fun toCuboid(width: Int = 1, height: Int = 1) = Cuboid(this, width, height, 1)
    fun toCuboid(radius: Int) = Cuboid(minus(radius, radius), radius * 2 + 1, radius * 2 + 1, 1)

    // Java interop helpers

    /** Alias for [add] — translates this tile by the given deltas. */
    fun transform(dx: Int, dy: Int, dl: Int): Tile = add(dx, dy, dl)

    /** Overload without level delta for Java callers. */
    fun transform(dx: Int, dy: Int): Tile = transform(dx, dy, 0)

    /** Alias for [within] with a default distance of 14 tiles. */
    fun withinDistance(other: Tile, distance: Int) = within(other, distance)

    /** Overload with default distance of 14 for Java callers. */
    fun withinDistance(other: Tile) = withinDistance(other, 14)

    /** Alias for [level] — backward compatibility with legacy code that uses 'plane'. */
    val plane: Int get() = level

    /** Returns the packed [Region.id] for this tile's region. */
    fun getRegionId() = region.id

    /** Returns the packed [Zone.id] for this tile's zone (chunk). */
    fun getChunkId() = zone.id

    /** Returns the packed tile hash (same as [id]). */
    fun getTileHash() = id

    /** Returns the local X coordinate within this tile's region (0-63). */
    fun getXInRegion() = x and 63

    /** Returns the local Y coordinate within this tile's region (0-63). */
    fun getYInRegion() = y and 63

    /** Returns the local X coordinate within this tile's chunk/zone (0-7). */
    fun getXInChunk() = x and 7

    /** Returns the local Y coordinate within this tile's chunk/zone (0-7). */
    fun getYInChunk() = y and 7

    /** Revision 727 zone coordinate: local X in the high nibble, local Y in the low nibble. */
    fun getChunkLocalHash() = ((x and 7) shl 4) or (y and 7)

    /** Equality check comparing packed ids. */
    fun matches(other: Tile) = id == other.id

    /** Returns the chunk X (x / 8). */
    fun getChunkX() = x shr 3

    /** Returns the chunk Y (y / 8). */
    fun getChunkY() = y shr 3

    /** Returns the X within scene for a given base chunk id. */
    fun getXInScene(baseChunkId: Int): Int = (getChunkXInScene(baseChunkId) shl 3) + (x and 7)

    /** Returns the Y within scene for a given base chunk id. */
    fun getYInScene(baseChunkId: Int): Int = (getChunkYInScene(baseChunkId) shl 3) + (y and 7)

    /** Returns the chunk X within scene for a given base chunk id. */
    fun getChunkXInScene(baseChunkId: Int): Int = (x shr 3) - (baseChunkId shr 11 and 0x7ff)

    /** Returns the chunk Y within scene for a given base chunk id. */
    fun getChunkYInScene(baseChunkId: Int): Int = (y shr 3) - (baseChunkId and 0x7ff)

    /** Returns the region X (x / 64). */
    fun getRegionX() = x shr 6

    /** Returns the region Y (y / 64). */
    fun getRegionY() = y shr 6

    /** Returns the 18-bit region hash (plane << 16 | regionX << 8 | regionY). */
    fun getRegionHash() = getRegionY() or (getRegionX() shl 8) or (plane shl 16)

    /** Returns the longest delta between this tile and another. */
    fun getLongestDelta(other: Tile): Int {
        val dx = Math.abs(x - other.x)
        val dy = Math.abs(y - other.y)
        return maxOf(dx, dy)
    }

    /**
     * Returns a Tile with region-local coordinates (x % 64, y % 64, same level).
     * Used for cutscene coordinate normalization.
     */
    fun localizeRegion(): Tile = Tile(x and 63, y and 63, level)

    /**
     * Checks if this tile is within the rectangular area defined by two corners (x1,y1) to (x2,y2).
     */
    fun withinArea(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        x >= x1 && x <= x2 && y >= y1 && y <= y2

    /** Check if this tile is at the given x, y position. */
    fun isAt(x: Int, y: Int) = this.x == x && this.y == y

    /** Check if this tile is at the given x, y, level position. */
    fun isAt(x: Int, y: Int, level: Int) = this.x == x && this.y == y && this.level == level

    override fun toString(): String {
        return "Tile($x, $y, $level)"
    }

    override fun hashCode(): Int = id

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tile) return false
        return id == other.id
    }

    companion object {
        @JvmStatic
        fun id(x: Int, y: Int, level: Int) = (y and 0x3fff) + ((x and 0x3fff) shl 14) + ((level and 0x3) shl 28)

        @JvmStatic
        fun id(x: Int, y: Int) = id(x, y, 0)

        @JvmStatic
        fun x(id: Int) = id shr 14 and 0x3fff

        @JvmStatic
        fun y(id: Int) = id and 0x3fff

        @JvmStatic
        fun level(id: Int) = id shr 28 and 0x3

        @JvmStatic
        val EMPTY = Tile(0)

        /** Java-friendly factory: create a Tile from x, y, level. */
        @JvmStatic
        fun of(x: Int, y: Int, level: Int) = Tile(x, y, level)

        /** Java-friendly factory: create a Tile from x, y (level defaults to 0). */
        @JvmStatic
        fun of(x: Int, y: Int) = of(x, y, 0)

        /** Legacy RS tile format: plane, regionX, regionY, localX, localY. */
        @JvmStatic
        fun of(plane: Int, regionX: Int, regionY: Int, localX: Int, localY: Int) =
            Tile(regionX * 64 + localX, regionY * 64 + localY, plane)

        /** Encodes x, y, level into a packed int representation. */
        @JvmStatic
        fun toInt(x: Int, y: Int, level: Int): Int = Tile(x, y, level).id

        /** Legacy overload with 4 args: x, y, level, sub (sub is ignored). */
        @JvmStatic
        fun of(x: Int, y: Int, level: Int, @Suppress("UNUSED_PARAMETER") sub: Int) = Tile(x, y, level)

        /** Java-friendly factory: create a Tile from a packed id. */
        @JvmStatic
        fun of(id: Int) = Tile(id)

        /** Copy constructor: create a new Tile with the same id. */
        @JvmStatic
        fun of(tile: Tile) = Tile(tile.id)

        private val tileRandom = SecureRandom()

        /** Creates a random tile within [randomize] distance of [tile]. */
        @JvmStatic
        fun of(tile: Tile, randomize: Int): Tile {
            val dx = tileRandom.nextInt(randomize * 2 + 1) - randomize
            val dy = tileRandom.nextInt(randomize * 2 + 1) - randomize
            return Tile(tile.x + dx, tile.y + dy, tile.level)
        }

        fun fromMap(map: Map<String, Any>) = Tile(map["x"] as Int, map["y"] as Int, map["level"] as? Int ?: 0)
        fun fromArray(array: IntArray) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)
        fun fromArray(array: List<Int>) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)

        /**
         * Index for a tile within a [Zone]
         * Used for indexing tiles in arrays
         */
        fun index(x: Int, y: Int): Int = (x and 0x7) or ((y and 0x7) shl 3)
        fun index(x: Int, y: Int, layer: Int): Int = index(x, y) or ((layer and 0x7) shl 6)
        fun indexX(index: Int) = index and 0x7
        fun indexY(index: Int) = index shr 3 and 0x7
        fun indexLayer(index: Int) = index shr 6 and 0x7
    }
}

fun Tile.equals(x: Int = this.x, y: Int = this.y, level: Int = this.level) = this.x == x && this.y == y && this.level == level
