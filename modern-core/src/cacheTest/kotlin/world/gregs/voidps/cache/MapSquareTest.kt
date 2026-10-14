package world.gregs.voidps.cache

import org.darkan.core.EnvVars
import world.gregs.voidps.type.Region
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Index 5 archive names are built, not catalogued, so the formula itself is what needs a test:
 * the letter runs straight into the first coordinate and only the two coordinates are separated.
 */
class MapSquareTest {

    @Test
    fun `known good names`() {
        assertEquals("m50_50", MapSquare.terrain(50, 50))
        assertEquals("l50_50", MapSquare.locations(50, 50))
        assertEquals("n5_33", MapSquare.npcs(5, 33))
        assertEquals("um1_30", MapSquare.underwaterTerrain(1, 30))
        assertEquals("ul1_30", MapSquare.underwaterLocations(1, 30))
    }

    @Test
    fun `region overloads use the region coordinates`() {
        assertEquals("m50_50", MapSquare.terrain(Region(50, 50)))
        assertEquals("l50_50", MapSquare.locations(Region(12850)))
        assertEquals("n5_33", MapSquare.npcs(Region(5, 33)))
        assertEquals("um1_30", MapSquare.underwaterTerrain(Region(1, 30)))
        assertEquals("ul1_30", MapSquare.underwaterLocations(Region(1, 30)))
    }

    /**
     * The formula has to be exhaustive both ways: every one of the five shapes really occurs, and
     * between them they name every archive index 5 has - nothing is reachable only by a spelling
     * this helper cannot produce.
     */
    @Test
    fun `the five formulas name every map archive and nothing else`() {
        val cache = cache() ?: return
        val named = cache.archiveNameHashes(Index.MAPS).filterValues { it != 0 }
        assertTrue(named.isNotEmpty(), "expected named map archives")

        val built = HashMap<Int, String>(named.size * 2)
        for (regionX in 0..255) {
            for (regionY in 0..255) {
                for (name in listOf(
                    MapSquare.terrain(regionX, regionY),
                    MapSquare.locations(regionX, regionY),
                    MapSquare.npcs(regionX, regionY),
                    MapSquare.underwaterTerrain(regionX, regionY),
                    MapSquare.underwaterLocations(regionX, regionY),
                )) {
                    built[name.hashCode()] = name
                }
            }
        }

        val unaccounted = named.filterValues { it !in built }
        assertTrue(unaccounted.isEmpty(), "map archives that no formula names: ${unaccounted.keys.take(10)}")

        val counts = named.values
            .map { built.getValue(it).takeWhile { char -> !char.isDigit() } }
            .groupingBy { it }
            .eachCount()
        assertEquals(mapOf("m" to 2407, "l" to 2407, "um" to 1208, "ul" to 1208, "n" to 38), counts)

        // And a built name resolves to the archive that carries it.
        assertEquals(1585, cache.archiveId(Index.MAPS, MapSquare.terrain(50, 50)))
        assertEquals(1586, cache.archiveId(Index.MAPS, MapSquare.locations(50, 50)))
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping map square cache test; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }
}
