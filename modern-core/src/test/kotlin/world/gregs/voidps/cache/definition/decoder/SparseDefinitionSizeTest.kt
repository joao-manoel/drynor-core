package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MemoryCache
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.ReferenceTable
import kotlin.test.Test
import kotlin.test.assertEquals

class SparseDefinitionSizeTest {
    @Test fun `sparse imported ids remain within loaded table bounds`() {
        val cache = MemoryCache(22)
        for (index in listOf(Index.NPCS, Index.ANIMATIONS, Index.OBJECTS, Index.SPOTANIMS, Index.ITEMS)) {
            val table = ReferenceTable()
            table.add(ArchiveEntry(3).also { archive ->
                archive.files.add(FileEntry(0))
                archive.files.add(FileEntry(117))
            })
            cache.indexTable(index, table)
        }
        assertEquals(3 * 128 + 117, NPCDecoder().size(cache))
        assertEquals(3 * 128 + 117, AnimationDecoder().size(cache))
        assertEquals(3 * 256 + 117, ObjectDecoder().size(cache))
        assertEquals(3 * 256 + 117, SpotAnimDecoder().size(cache))
        assertEquals(3 * 256 + 117, ItemDecoder().size(cache))
    }
    @Test fun `absent indexes have no definitions`() {
        val cache = MemoryCache(22)
        assertEquals(-1, NPCDecoder().size(cache))
        assertEquals(-1, AnimationDecoder().size(cache))
        assertEquals(-1, ObjectDecoder().size(cache))
        assertEquals(-1, SpotAnimDecoder().size(cache))
        assertEquals(-1, ItemDecoder().size(cache))
    }

    @Test fun `sparse item beyond previous dense range loads its name and cape slot`() {
        val cache = MemoryCache(22)
        cache.indexTable(Index.ITEMS, ReferenceTable().also { table ->
            table.add(ArchiveEntry(117).also { it.files.add(FileEntry(48)) })
        })
        cache.data[Index.ITEMS] = arrayOfNulls(118)
        cache.data[Index.ITEMS]!![117] = arrayOfNulls(49)
        cache.data[Index.ITEMS]!![117]!![48] = byteArrayOf(2) + "Infernal cape".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 13, 1, 0)
        val items = ItemDecoder().load(cache)
        assertEquals(30001, items.size)
        assertEquals("Infernal cape", items[30000].name)
        assertEquals(1, items[30000].wearPos)
        assertEquals("null", items[29999].name)
    }
}
