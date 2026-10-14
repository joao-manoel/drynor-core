package world.gregs.voidps.cache.store

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("file-store-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a created store is empty`() {
        FileStore.create(directory, 5).use { store ->
            assertTrue(store.writable)
            assertEquals(5, store.indexCount())
            assertNull(store.read(0, 0))
            assertContentEquals(IntArray(0), store.archives(0))
            assertNull(store.read(9, 0), "an index with no idx file reads as absent")
        }
    }

    @Test
    fun `write and read back`() {
        FileStore.create(directory, 3).use { store ->
            val container = bytes(400, 1)
            store.write(1, 7, container)
            assertContentEquals(container, store.read(1, 7))
            assertContentEquals(intArrayOf(7), store.archives(1))
            assertNull(store.read(1, 6))
            assertNull(store.read(2, 7), "sector headers name the index they belong to")
        }
    }

    @Test
    fun `a container spanning many sectors round trips`() {
        FileStore.create(directory, 2).use { store ->
            val container = bytes(512 * 7 + 13, 2)
            store.write(0, 1, container)
            assertContentEquals(container, store.read(0, 1))
        }
    }

    @Test
    fun `an exact multiple of the sector payload round trips`() {
        FileStore.create(directory, 2).use { store ->
            val container = bytes(FileStore.SECTOR_DATA_SIZE_SMALL * 3, 3)
            store.write(0, 2, container)
            assertContentEquals(container, store.read(0, 2))
        }
    }

    @Test
    fun `a smaller rewrite reuses the chain and a larger one appends`() {
        FileStore.create(directory, 2).use { store ->
            val data = File(directory, "${FileStore.CACHE_FILE_NAME}.dat2")
            val large = bytes(512 * 4, 4)
            store.write(0, 0, large)
            val afterFirst = data.length()

            val small = bytes(512 * 2, 5)
            store.write(0, 0, small)
            assertContentEquals(small, store.read(0, 0))
            assertEquals(afterFirst, data.length(), "a shorter container reuses the existing sectors")

            val larger = bytes(512 * 6, 6)
            store.write(0, 0, larger)
            assertContentEquals(larger, store.read(0, 0))
            assertTrue(data.length() > afterFirst, "a longer container is appended")
        }
    }

    @Test
    fun `rewriting an archive leaves its neighbours alone`() {
        FileStore.create(directory, 2).use { store ->
            val first = bytes(1500, 7)
            val second = bytes(1500, 8)
            val third = bytes(1500, 9)
            store.write(0, 0, first)
            store.write(0, 1, second)
            store.write(0, 2, third)

            store.write(0, 1, bytes(400, 10))
            assertContentEquals(first, store.read(0, 0))
            assertContentEquals(third, store.read(0, 2))

            store.write(0, 1, bytes(9000, 11))
            assertContentEquals(first, store.read(0, 0))
            assertContentEquals(third, store.read(0, 2))
        }
    }

    @Test
    fun `big archive ids use the wider sector header`() {
        FileStore.create(directory, 2).use { store ->
            val big = 70000
            assertTrue(big > FileStore.MAX_SMALL_ARCHIVE)
            val container = bytes(FileStore.SECTOR_DATA_SIZE_BIG * 2 + 5, 12)
            store.write(0, big, container)
            store.write(0, 3, bytes(100, 13))
            assertContentEquals(container, store.read(0, big))
            assertContentEquals(intArrayOf(3, big), store.archives(0))
        }
    }

    @Test
    fun `remove clears the entry`() {
        FileStore.create(directory, 2).use { store ->
            store.write(0, 4, bytes(100, 14))
            store.remove(0, 4)
            assertNull(store.read(0, 4))
            assertContentEquals(IntArray(0), store.archives(0))
        }
    }

    @Test
    fun `index 255 holds the reference tables`() {
        FileStore.create(directory, 2).use { store ->
            store.write(FileStore.REFERENCE_INDEX, 0, bytes(60, 15))
            store.write(FileStore.REFERENCE_INDEX, 1, bytes(60, 16))
            assertEquals(2, store.indexCount())
            assertContentEquals(intArrayOf(0, 1), store.archives(FileStore.REFERENCE_INDEX))
        }
    }

    @Test
    fun `everything written survives a reopen`() {
        val archives = mapOf(0 to bytes(100, 20), 5 to bytes(2000, 21), 70000 to bytes(1100, 22))
        FileStore.create(directory, 3).use { store ->
            for ((archive, container) in archives) {
                store.write(2, archive, container)
            }
            store.write(FileStore.REFERENCE_INDEX, 2, bytes(80, 23))
        }
        FileStore.open(directory, writable = false).use { store ->
            assertTrue(!store.writable)
            for ((archive, container) in archives) {
                assertContentEquals(container, store.read(2, archive))
            }
            assertContentEquals(bytes(80, 23), store.read(FileStore.REFERENCE_INDEX, 2))
            assertContentEquals(intArrayOf(0, 5, 70000), store.archives(2))
            assertFailsWith<IllegalStateException> { store.write(2, 0, bytes(10, 24)) }
        }
    }

    @Test
    fun `empty and unaddressable containers are rejected`() {
        FileStore.create(directory, 1).use { store ->
            assertFailsWith<IllegalArgumentException> { store.write(0, 0, ByteArray(0)) }
            assertFailsWith<IllegalArgumentException> { store.write(0, -1, bytes(10, 25)) }
            assertFailsWith<FileNotFoundException> { store.write(1, 0, bytes(10, 26)) }
        }
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 7) + seed).toByte() }
}
