package world.gregs.voidps.cache.definition.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefinitionOptionsTest {

    private val obj = ObjectDefinition(id = 1, options = arrayOf("Open", "Search", null, null, null))
    private val npc = NPCDefinition(id = 1, options = arrayOf("Talk-to", "Trade", null, null, null, "Examine"))
    private val item = ItemDefinition(id = 1, options = arrayOf("Wear", "Rub", null, null, "Drop"))

    @Test
    fun `object option one is op1`() {
        assertEquals("Open", obj.getOption(1))
        assertEquals("Search", obj.getOption(2))
    }

    @Test
    fun `object option zero and out of range are null`() {
        assertNull(obj.getOption(0))
        assertNull(obj.getOption(-1))
        assertNull(obj.getOption(6))
        assertNull(obj.getOption(3))
    }

    @Test
    fun `object without options has no options`() {
        val empty = ObjectDefinition(id = 2)
        assertNull(empty.getOption(1))
        assertNull(empty.getFirstOption())
        assertFalse(empty.hasOption(1, "Open"))
        assertEquals(-1, empty.optionsIndex("Open"))
    }

    @Test
    fun `object has option matches the op number`() {
        assertTrue(obj.hasOption(1, "Open"))
        assertTrue(obj.hasOption(2, "Search"))
        assertFalse(obj.hasOption(2, "Open"))
        assertFalse(obj.hasOption(1, "Search"))
        assertFalse(obj.hasOption(1, "Climb-up"))
        assertFalse(obj.hasOption(0, "Open"))
    }

    @Test
    fun `object first option is op1`() {
        assertEquals(obj.getOption(1), obj.getFirstOption())
    }

    @Test
    fun `object options index is the op number`() {
        assertEquals(1, obj.optionsIndex("Open"))
        assertEquals(2, obj.optionsIndex("Search"))
        assertEquals(-1, obj.optionsIndex("Climb-up"))
    }

    @Test
    fun `npc option one is op1`() {
        assertEquals("Talk-to", npc.getOption(1))
        assertEquals("Trade", npc.getOption(2))
        assertEquals("Examine", npc.getOption(6))
    }

    @Test
    fun `npc option zero and out of range are null`() {
        assertNull(npc.getOption(0))
        assertNull(npc.getOption(-1))
        assertNull(npc.getOption(7))
        assertNull(npc.getOption(3))
    }

    @Test
    fun `item has option matches the op number`() {
        assertTrue(item.hasOption(1, "Wear"))
        assertTrue(item.hasOption(2, "Rub"))
        assertFalse(item.hasOption(2, "Wear"))
        assertFalse(item.hasOption(1, "Eat"))
        assertFalse(item.hasOption(0, "Wear"))
        assertFalse(item.hasOption(6, "Wear"))
    }

    @Test
    fun `item inventory option ignores case`() {
        assertTrue(item.hasInventoryOption(1, "wear"))
        assertTrue(item.hasInventoryOption(5, "DROP"))
        assertFalse(item.hasInventoryOption(2, "wear"))
        assertFalse(item.hasInventoryOption(0, "wear"))
        assertFalse(item.hasInventoryOption(1, "eat"))
    }
}
