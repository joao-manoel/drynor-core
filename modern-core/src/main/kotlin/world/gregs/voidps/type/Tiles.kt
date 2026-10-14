package world.gregs.voidps.type

/**
 * Java-friendly factory and utility methods for [Tile].
 *
 * The [Tile] inline value class has mangled JVM method names for any function
 * that takes or returns [Tile]. This object provides non-mangled static methods
 * by accepting/returning boxed [Any] so Java code can call them with a cast.
 *
 * Usage from Java:
 * ```java
 * Tile tile = Tiles.of(3200, 3200, 0);
 * Tile copy = Tiles.of(existingTile);
 * Tile moved = Tiles.transform(tile, 1, 0, 0);
 * ```
 */
@Suppress("NOTHING_TO_INLINE")
object Tiles {

    // ---- Factory methods ----

    /** Create a Tile from x, y, and level coordinates. */
    @JvmStatic
    inline fun of(x: Int, y: Int, level: Int): Any = Tile(x, y, level)

    /** Create a Tile from x and y (level = 0). */
    @JvmStatic
    inline fun of(x: Int, y: Int): Any = Tile(x, y, 0)

    /** Create a Tile from a packed tile id. */
    @JvmStatic
    inline fun ofId(id: Int): Any = Tile(id)

    /** Create a copy of the given tile. */
    @JvmStatic
    fun copy(tile: Any): Any {
        val t = tile as Tile
        return Tile(t.id)
    }

    /** Create a tile randomized within [randomize] distance of [tile]. */
    @JvmStatic
    fun ofRandom(tile: Any, randomize: Int): Any {
        return Tile.of(tile as Tile, randomize)
    }

    // ---- Transform methods ----

    /** Transform (translate) a tile by the given deltas. */
    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int, dl: Int): Any {
        return (tile as Tile).add(dx, dy, dl)
    }

    /** Transform (translate) a tile by the given deltas (no level change). */
    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int): Any {
        return (tile as Tile).add(dx, dy, 0)
    }

    // ---- Accessors ----

    /** Get the X coordinate within the scene for the given tile and base chunk id. */
    @JvmStatic
    fun getXInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getXInScene(baseChunkId)

    /** Get the Y coordinate within the scene for the given tile and base chunk id. */
    @JvmStatic
    fun getYInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getYInScene(baseChunkId)

    /** Get the chunk X within the scene for the given tile and base chunk id. */
    @JvmStatic
    fun getChunkXInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getChunkXInScene(baseChunkId)

    /** Get the chunk Y within the scene for the given tile and base chunk id. */
    @JvmStatic
    fun getChunkYInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getChunkYInScene(baseChunkId)

    /** Get the chunk X for a tile (x / 8). */
    @JvmStatic
    fun getChunkX(tile: Any): Int = (tile as Tile).x shr 3

    /** Get the chunk Y for a tile (y / 8). */
    @JvmStatic
    fun getChunkY(tile: Any): Int = (tile as Tile).y shr 3

    /** Check if the given tile is at the specified x, y position. */
    @JvmStatic
    fun isAt(tile: Any, x: Int, y: Int): Boolean {
        val t = tile as Tile
        return t.x == x && t.y == y
    }
}
