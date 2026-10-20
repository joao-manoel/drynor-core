package rs.drynor.core.cache

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import world.gregs.voidps.cache.definition.data.ItemDefinition

class ItemSpecialAttackParameterTest {
    @Test fun `native special bar is independent of attack style category`() {
        // Native whip4151 uses style11 and special_attack1; CS2_1136 reads param687.
        val weapon = ItemDefinition(4151).apply { params = mutableMapOf(686 to 11, 687 to 1) }
        assertTrue(weapon.hasSpecialBar())
        weapon.params = mutableMapOf(686 to 1)
        assertFalse(weapon.hasSpecialBar())
        weapon.params = mutableMapOf(686 to 13, 687 to 0)
        assertFalse(weapon.hasSpecialBar())
        assertFalse(ItemDefinition().hasSpecialBar())
    }
}
