package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyTerrainScanTest {
    @Test
    fun acceptsAllTilesAndLeavesEnvironmentUnread() {
        assertEquals(16384, LegacyTerrainScan.validate(ByteArray(16384) + byteArrayOf(99, 42)))
    }

    @Test
    fun rejectsIncompleteTileSequence() {
        assertFailsWith<IllegalArgumentException> { LegacyTerrainScan.validate(ByteArray(16383)) }
    }

    @Test
    fun rejectsMissingHeightPayload() {
        assertFailsWith<IllegalArgumentException> { LegacyTerrainScan.validate(ByteArray(16383) + byteArrayOf(1)) }
    }

    @Test
    fun acceptsOverlaySettingsUnderlayAndExplicitHeight() {
        val tile = byteArrayOf(2, 7, 50, 82, 1, 12)
        assertEquals(16383 + tile.size, LegacyTerrainScan.validate(ByteArray(16383) + tile))
    }
}
