package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZeahNpcIdPlanTest {
    @Test fun `keeps free NPC IDs and moves collisions without reusing an occupied ID`() {
        val occupied = setOf(20_001, 65_535)
        val plan = ZeahNpcIdPlan.allocate(setOf(20_001, 20_002, 28_975), occupied)
        assertEquals(65_534, plan.getValue(20_001))
        assertEquals(20_002, plan.getValue(20_002))
        assertEquals(28_975, plan.getValue(28_975))
        assertEquals(plan, ZeahNpcIdPlan.allocate(setOf(28_975, 20_002, 20_001), occupied))
    }

    @Test fun `rejects IDs outside the 16 bit NPC range`() {
        assertFailsWith<IllegalArgumentException> { ZeahNpcIdPlan.allocate(setOf(65_536), emptySet()) }
    }
}
