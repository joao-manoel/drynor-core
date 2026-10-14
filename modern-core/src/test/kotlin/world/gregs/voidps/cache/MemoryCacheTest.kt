package world.gregs.voidps.cache

import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.ReferenceTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MemoryCacheTest {

    private val keys = intArrayOf(0x11112222, 0x33334444, 0x55556666, 0x77778888)

    private fun cache(archive: Int, encoded: ByteArray, loaded: ByteArray? = null): MemoryCache {
        val cache = MemoryCache(Index.MAPS + 1)
        val table = ReferenceTable()
        val entry = ArchiveEntry(archive)
        entry.files.add(FileEntry(0))
        table.add(entry)
        cache.indexTable(Index.MAPS, table)
        cache.sectors[Index.MAPS] = arrayOfNulls<ByteArray?>(archive + 1).also { it[archive] = encoded }
        cache.data[Index.MAPS] = arrayOfNulls<Array<ByteArray?>?>(archive + 1).also {
            it[archive] = if (loaded == null) null else arrayOf<ByteArray?>(loaded)
        }
        return cache
    }

    @Test
    fun `encrypted archive decodes with per call keys`() {
        val payload = ByteArray(2048) { (it * 7).toByte() }
        val encoded = Container.compress(payload, Compression.GZIP, 0).encode(keys)
        val cache = cache(100, encoded)

        assertNull(cache.data(Index.MAPS, 100, 0, null))
        assertContentEquals(payload, cache.data(Index.MAPS, 100, 0, keys))
        assertContentEquals(payload, cache.data(Index.MAPS, 100, 0, keys))
    }

    @Test
    fun `wrong keys leave the loaded sector intact`() {
        val payload = ByteArray(512) { it.toByte() }
        val encoded = Container.compress(payload, Compression.GZIP, 0).encode(keys)
        val cache = cache(100, encoded)

        assertNull(cache.data(Index.MAPS, 100, 0, intArrayOf(1, 2, 3, 4)))
        assertContentEquals(payload, cache.data(Index.MAPS, 100, 0, keys))
    }

    @Test
    fun `plain archive keeps its load time data`() {
        val payload = ByteArray(64) { it.toByte() }
        val encoded = Container.compress(payload, Compression.GZIP, 0).encode()
        val cache = cache(100, encoded, payload)

        assertContentEquals(payload, cache.data(Index.MAPS, 100, 0, null))
        assertContentEquals(payload, cache.data(Index.MAPS, 100, 0, keys))
    }
}
