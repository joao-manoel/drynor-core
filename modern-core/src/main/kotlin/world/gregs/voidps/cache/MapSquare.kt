package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import world.gregs.voidps.type.Region

/**
 * The names of the five [Index.MAPS] archives that make up one map square (region).
 *
 * Index 5 is the one index whose archive names are built rather than catalogued: there is no
 * `gamevals/mapsquare.json` and there never will be, because the name is a pure function of the
 * region coordinates. The 727 spelling has no separator between the letter and the first
 * coordinate - `m50_50`, not `m_50_50` - and only the two coordinates are separated by `_`.
 *
 * The formula is the client's own: `MapRegion.getArchiveName(floorData, underwater, regionX,
 * regionY)` composes `"m"`/`"l"` + regionX + `'_'` + regionY and prefixes `"u"` when underwater,
 * and its callers ask for the npc spawn archive as `"n" + regionX + '_' + regionY`.
 *
 * Names resolve to archive ids through [Cache.archiveId], which hashes the name the same way the
 * reference table did. Not every region has all five: only 38 of the 2,407 surface squares carry
 * npc spawns, and 1,208 carry an underwater pair.
 */
object MapSquare {

    /** Terrain, height and overlay/underlay data: `m<regionX>_<regionY>`. */
    fun terrain(regionX: Int, regionY: Int): String = "m${regionX}_$regionY"

    /** Placed locations: `l<regionX>_<regionY>`. XTEA encrypted. */
    fun locations(regionX: Int, regionY: Int): String = "l${regionX}_$regionY"

    /** Npc spawns: `n<regionX>_<regionY>`. */
    fun npcs(regionX: Int, regionY: Int): String = "n${regionX}_$regionY"

    /** The underwater map's terrain: `um<regionX>_<regionY>`. */
    fun underwaterTerrain(regionX: Int, regionY: Int): String = "um${regionX}_$regionY"

    /** The underwater map's locations: `ul<regionX>_<regionY>`. */
    fun underwaterLocations(regionX: Int, regionY: Int): String = "ul${regionX}_$regionY"

    /** [terrain] of [region]. */
    fun terrain(region: Region): String = terrain(region.x, region.y)

    /** [locations] of [region]. */
    fun locations(region: Region): String = locations(region.x, region.y)

    /** [npcs] of [region]. */
    fun npcs(region: Region): String = npcs(region.x, region.y)

    /** [underwaterTerrain] of [region]. */
    fun underwaterTerrain(region: Region): String = underwaterTerrain(region.x, region.y)

    /** [underwaterLocations] of [region]. */
    fun underwaterLocations(region: Region): String = underwaterLocations(region.x, region.y)

    /**
     * The five kinds of archive a map square is made of, and what tells them apart.
     *
     * The prefix is the whole difference between them in the cache; everything else here is what a
     * reader has to know once it knows which one it is holding - how many levels of tiles the file
     * carries ([levels]), whether the container is XTEA encrypted ([encrypted]), and the name the
     * cache source tree stores it under ([file], see `docs/cache-source.md`).
     *
     * [TERRAIN] is the only one with an environment block after its tiles, and [UNDERWATER_TERRAIN]
     * the only one with fewer than four levels: the client builds the underwater scene with a single
     * plane (`SceneGraph(..., 1, ...)` in the bot's `MapRegion`), so its file holds 64 * 64 tile
     * records and not 4 * 64 * 64.
     */
    enum class Shape(
        /** The letter (or letters) the archive name starts with. */
        val prefix: String,
        /** How many levels of 64 * 64 tile records the file holds, or 0 when it holds none. */
        val levels: Int,
        /** Whether the container is XTEA encrypted. */
        val encrypted: Boolean,
        /** The file the cache source tree stores the archive as, extension excluded. */
        val file: String
    ) {
        TERRAIN("m", MapSquare.LEVELS, false, "terrain"),
        LOCATIONS("l", 0, true, "locs"),
        NPCS("n", 0, true, "npcs"),
        UNDERWATER_TERRAIN("um", 1, false, "underwater_terrain"),
        UNDERWATER_LOCATIONS("ul", 0, false, "underwater_locs");

        /** [prefix] with the region coordinates: the archive's name in the cache. */
        fun name(regionX: Int, regionY: Int): String = "$prefix${regionX}_$regionY"

        companion object {

            /** The shape whose [file] is [name], or null. */
            fun ofFile(name: String): Shape? = entries.firstOrNull { it.file == name }
        }
    }

    /** One archive of one map square: which of the five it is, and whose region. */
    data class Square(val shape: Shape, val regionX: Int, val regionY: Int) {

        /** The archive's name in the cache. */
        val name: String
            get() = shape.name(regionX, regionY)

        /** The region id the XTEA keys are filed under (`regionX shl 8 or regionY`). */
        val region: Int
            get() = (regionX shl 8) or regionY

        /** The region's directory in a cache source tree. */
        val directory: String
            get() = "${regionX}_$regionY"
    }

    /**
     * The archive [nameHash] names, or null when no map square is called that.
     *
     * Index 5's reference table carries name hashes and no names, and the names are a formula, so
     * the way back from a hash to a square is to hash all 5 * 256 * 256 of them once and look the
     * answer up. Every one of those names hashes to a different int - checked, not assumed - so the
     * answer is unambiguous.
     */
    fun of(nameHash: Int): Square? {
        val packed = hashes.get(nameHash)
        if (packed == 0) {
            return null
        }
        return Square(Shape.entries[(packed shr 16) - 1], (packed shr 8) and 0xff, packed and 0xff)
    }

    /** Region coordinates are one byte each, so this is every name index 5 can hold. */
    private const val REGIONS = 256

    /**
     * Name hash to `(shape + 1) shl 16 or regionX shl 8 or regionY`, for all 327,680 names.
     *
     * Built once, on the first [of]; 0 is the absent value, which is why the shape is stored one
     * higher than its ordinal. A collision would make [of] answer with the wrong square, so the
     * build refuses to finish on one rather than letting a map square be silently mis-filed.
     */
    private val hashes: Int2IntMap by lazy {
        val map = Int2IntOpenHashMap(Shape.entries.size * REGIONS * REGIONS)
        map.defaultReturnValue(0)
        for (shape in Shape.entries) {
            for (regionX in 0 until REGIONS) {
                for (regionY in 0 until REGIONS) {
                    val packed = ((shape.ordinal + 1) shl 16) or (regionX shl 8) or regionY
                    val previous = map.put(shape.name(regionX, regionY).hashCode(), packed)
                    check(previous == 0) {
                        "Map square names ${shape.name(regionX, regionY)} and " +
                            "${Shape.entries[(previous shr 16) - 1].name((previous shr 8) and 0xff, previous and 0xff)}" +
                            " share a name hash."
                    }
                }
            }
        }
        map
    }

    /** Every level of tiles a surface map square holds. */
    const val LEVELS = 4
}
