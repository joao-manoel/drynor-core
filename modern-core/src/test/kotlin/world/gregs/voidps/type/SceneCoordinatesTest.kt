package world.gregs.voidps.type

import org.drynor.core.util.Structure
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneCoordinatesTest {

    private val baseChunkId = Structure.CHUNK.encode(394, 400, 0)
    private val tile = Tile(3222, 3218)

    @Test
    fun `scene coordinates are relative to the base chunk`() {
        assertEquals(70, tile.getXInScene(baseChunkId))
        assertEquals(18, tile.getYInScene(baseChunkId))
    }

    @Test
    fun `scene chunk coordinates are relative to the base chunk`() {
        assertEquals(8, tile.getChunkXInScene(baseChunkId))
        assertEquals(2, tile.getChunkYInScene(baseChunkId))
    }

    @Test
    fun `base chunk plane does not shift the scene coordinates`() {
        val raised = Structure.CHUNK.encode(394, 400, 2)
        assertEquals(tile.getXInScene(baseChunkId), tile.getXInScene(raised))
        assertEquals(tile.getYInScene(baseChunkId), tile.getYInScene(raised))
    }

    @Test
    fun `tile on the base chunk origin is at the scene origin`() {
        val origin = Tile(394 shl 3, 400 shl 3)
        assertEquals(0, origin.getXInScene(baseChunkId))
        assertEquals(0, origin.getYInScene(baseChunkId))
    }

    @Test
    fun `tiles statics match the tile members`() {
        assertEquals(tile.getXInScene(baseChunkId), Tiles.getXInScene(tile, baseChunkId))
        assertEquals(tile.getYInScene(baseChunkId), Tiles.getYInScene(tile, baseChunkId))
        assertEquals(tile.getChunkXInScene(baseChunkId), Tiles.getChunkXInScene(tile, baseChunkId))
        assertEquals(tile.getChunkYInScene(baseChunkId), Tiles.getChunkYInScene(tile, baseChunkId))
    }
}
