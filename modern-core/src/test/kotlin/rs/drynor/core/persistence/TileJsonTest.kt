package rs.drynor.core.persistence

import com.google.gson.GsonBuilder
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals

class TileJsonTest {
    private val gson = GsonBuilder().registerTypeAdapter(Tile::class.java, TileJson()).create()
    @Test fun `world content coordinates and current packed tiles share the same location`() {
        for (plane in 0..3) {
            val tile = Tile(3202, 3205, plane)
            for (input in listOf(
                """{"x":3202,"y":3205,"plane":$plane}""",
                """{"x":3202,"y":3205,"level":$plane}""",
                """{"id":${tile.id}}""", tile.id.toString()
            )) assertEquals(tile, gson.fromJson(input, Tile::class.java))
            assertEquals(tile, gson.fromJson(gson.toJson(tile), Tile::class.java))
        }
    }
    @Test fun `coordinates with no plane default to ground level`() {
        assertEquals(Tile(3202, 3205), gson.fromJson("""{"x":3202,"y":3205}""", Tile::class.java))
    }
}
