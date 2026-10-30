package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZeahPlacementIdsTest {
    @Test fun `keeps free source ids and relocates conflicts and oversized ids`() {
        val plan = ZeahPlacementIds.plan(setOf(100_083, 100_084, 136_675), setOf(100_083, 131_071))
        assertEquals(100_084, plan.getValue(100_084))
        assertEquals(131_070, plan.getValue(100_083))
        assertEquals(131_069, plan.getValue(136_675))
        assertTrue(plan.values.all { it in 0..ZeahPlacementIds.MAX_ID })
    }

    @Test fun `fails if no representable id remains`() {
        assertFailsWith<IllegalArgumentException> {
            ZeahPlacementIds.plan(setOf(200_000), (0..ZeahPlacementIds.MAX_ID).toSet())
        }
    }
}
