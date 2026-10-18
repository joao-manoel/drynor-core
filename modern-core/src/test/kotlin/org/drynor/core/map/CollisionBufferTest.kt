package org.drynor.core.map

import org.drynor.core.map.pathfinder.getIndexInZone
import org.drynor.core.map.pathfinder.getZoneIndex
import org.drynor.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CollisionBufferTest {

    @Test
    fun `a wall on a zone edge keeps the flag it sets across the edge`() {
        val zone = getZoneIndex(3200, 3200, 0)
        val buffer = CollisionBuffer(listOf(zone))
        WorldCollision.addWall(Tile(3200, 3203, 0), ObjectShape.WALL_STRAIGHT, 0, blocksProjectiles = true, pathfinder = true, writer = buffer)

        val written = HashMap<Int, Int>()
        buffer.writeOverflow { x, y, level, flag -> written.merge(getZoneIndex(x, y, level) * 64 + getIndexInZone(x, y), flag, Int::or) }

        val across = assertNotNull(written[getZoneIndex(3199, 3203, 0) * 64 + getIndexInZone(3199, 3203)])
        assertEquals(ClipFlag.blockEast(true, true, true), across)
        assertEquals(ClipFlag.blockWest(true, true, true), buffer.flags(zone)!![getIndexInZone(3200, 3203)])
    }
}
