package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import world.gregs.voidps.cache.definition.data.MapTile

class LegacyTerrainConversionTest {
    @Test
    fun distinguishesExplicitFlatFromProceduralHeight() {
        val map = LegacyTerrainConversion.decode(byteArrayOf(1, 0) + ByteArray(16383), 9042)
        assertEquals(1, MapTile(map.tiles[0]).height)
        assertEquals(0, MapTile(map.tiles[1]).height)
    }

    @Test
    fun preservesFloorsFlagsAndExplicitNonzeroHeight() {
        val bytes = byteArrayOf(2, 72, 51, 82, 1, 7) + ByteArray(16383)
        val tile = MapTile(LegacyTerrainConversion.decode(bytes, 9042).tiles[0])
        assertEquals(72, tile.overlayId)
        assertEquals(1, tile.underlayId)
        assertEquals(2, tile.settings)
        assertEquals(7, tile.height)
    }
}
