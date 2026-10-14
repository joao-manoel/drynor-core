package world.gregs.voidps.type

import kotlin.test.Test
import kotlin.test.assertEquals

class TileTest {

    private val tile = Tile(3200, 3200)

    @Test
    fun `single size face coord is the tile itself`() {
        assertEquals(3200, tile.getCoordFaceX(1))
        assertEquals(3200, tile.getCoordFaceY(1))
    }

    @Test
    fun `three by three face coord is the centre`() {
        assertEquals(3201, tile.getCoordFaceX(3))
        assertEquals(3201, tile.getCoordFaceY(3))
    }

    @Test
    fun `unrotated face coord uses width for x and height for y`() {
        assertEquals(3200, tile.getCoordFaceX(2, 5, 0))
        assertEquals(3202, tile.getCoordFaceY(2, 5, 0))
    }

    @Test
    fun `rotated face coord swaps width and height`() {
        assertEquals(3202, tile.getCoordFaceX(2, 5, 1))
        assertEquals(3200, tile.getCoordFaceY(2, 5, 1))
        assertEquals(3202, tile.getCoordFaceX(2, 5, 3))
        assertEquals(3200, tile.getCoordFaceY(2, 5, 3))
    }
}
