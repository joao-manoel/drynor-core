package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeterministicIdsTest {
    @Test
    fun preservesFreeIdsAndRelocatesConflictsWithoutStealingOtherSourceIds() {
        assertEquals(mapOf(2 to 5, 3 to 3, 4 to 4), DeterministicIds.plan(setOf(4, 2, 3), setOf(0, 1, 2)))
    }

    @Test
    fun planningIsIndependentOfInputIterationOrder() {
        val occupied = setOf(1, 3)
        assertEquals(DeterministicIds.plan(linkedSetOf(1, 2, 3), occupied), DeterministicIds.plan(linkedSetOf(3, 2, 1), occupied))
    }

    @Test
    fun rejectsOverflowInsteadOfProposingNegativeIds() {
        assertFailsWith<IllegalArgumentException> { DeterministicIds.plan(setOf(Int.MAX_VALUE), setOf(Int.MAX_VALUE)) }
    }
}
