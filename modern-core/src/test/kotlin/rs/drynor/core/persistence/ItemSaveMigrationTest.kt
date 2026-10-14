package rs.drynor.core.persistence

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.darkan.core.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemSaveMigrationTest {
    private val gson = Gson()

    @Test fun `historical and current metadata names preserve item properties through save`() {
        for (field in listOf("metaData", "metadata")) {
            val item = gson.fromJson(
                """{"id":4151,"amount":2,"$field":{"charges":137,"owner":"Pequi","custom":{"bound":true}}}""",
                Item::class.java
            )
            assertEquals(4151, item.id)
            assertEquals(2, item.amount)
            assertEquals(137, item.getMetaDataI("charges"))
            assertEquals("Pequi", item.getMetaString("owner"))
            val saved = JsonParser.parseString(gson.toJson(item)).asJsonObject
            assertTrue(saved.has("metaData"))
            assertFalse(saved.has("metadata"))
            assertTrue(saved.getAsJsonObject("metaData").getAsJsonObject("custom").get("bound").asBoolean)
            val restored = gson.fromJson(saved, Item::class.java)
            assertEquals(137, restored.getMetaDataI("charges"))
            assertEquals("Pequi", restored.getMetaString("owner"))
        }
    }

    @Test fun `runtime inventory slot is excluded from saved item data`() {
        val item = Item(995, 100000).withSlot(27)
        val saved = JsonParser.parseString(gson.toJson(item)).asJsonObject
        assertFalse(saved.has("slot"))
        assertFalse(saved.has("owner"))
        assertEquals(0, gson.fromJson(saved, Item::class.java).slot)
    }

    @Test fun `absent and empty metadata retain distinct stack semantics`() {
        val plain = gson.fromJson("""{"id":995,"amount":100000}""", Item::class.java)
        assertNull(plain.getMetaData())
        assertFalse(plain.containsMetaData())
        val empty = gson.fromJson("""{"id":995,"amount":100000,"metaData":{}}""", Item::class.java)
        assertTrue(empty.containsMetaData())
        assertTrue(empty.getMetaData()?.isEmpty() == true)
        assertEquals(-1, empty.getMetaDataI("charges"))
        assertTrue(gson.fromJson(gson.toJson(empty), Item::class.java).containsMetaData())
    }
}
