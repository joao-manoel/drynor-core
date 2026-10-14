package world.gregs.voidps.cache.store

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchiveGroupTest {

    @Test
    fun `a single file group is the file itself`() {
        val file = ByteArray(50) { it.toByte() }
        val split = ArchiveGroup.split(file, intArrayOf(3))
        assertEquals(1, split.files.size)
        assertContentEquals(file, split.files[0])
        assertContentEquals(file, ArchiveGroup.join(split.files, split.chunks))
        assertContentEquals(file, ArchiveGroup.join(split.files))
    }

    @Test
    fun `a one chunk group round trips`() {
        val files = listOf(bytes(10, 1), bytes(0, 2), bytes(33, 3))
        val group = ArchiveGroup.join(files)
        // 43 bytes of data, three int deltas and the chunk count.
        assertEquals(43 + 3 * 4 + 1, group.size)
        assertEquals(1, group[group.size - 1].toInt())

        val split = ArchiveGroup.split(group, intArrayOf(0, 1, 2))
        assertEquals(1, split.chunks.size)
        assertContentEquals(intArrayOf(10, 0, 33), split.chunks[0])
        for (i in files.indices) {
            assertContentEquals(files[i], split.files[i])
        }
        assertContentEquals(group, ArchiveGroup.join(split.files, split.chunks))
    }

    @Test
    fun `a three chunk group keeps its layout byte for byte`() {
        // The shape index 0 uses: a small first chunk of every file, then a chunk that skips
        // file 0 entirely, then the remainder.
        val chunks = listOf(
            intArrayOf(4, 4, 4),
            intArrayOf(0, 20, 20),
            intArrayOf(6, 1, 0)
        )
        val files = listOf(bytes(10, 7), bytes(25, 8), bytes(24, 9))
        val group = ArchiveGroup.join(files, chunks)
        assertEquals(59 + 3 * 3 * 4 + 1, group.size)
        assertEquals(3, group[group.size - 1].toInt())

        val split = ArchiveGroup.split(group, intArrayOf(0, 1, 2))
        assertEquals(3, split.chunks.size)
        for (i in chunks.indices) {
            assertContentEquals(chunks[i], split.chunks[i])
        }
        for (i in files.indices) {
            assertContentEquals(files[i], split.files[i])
        }
        assertContentEquals(group, ArchiveGroup.join(split.files, split.chunks))
    }

    @Test
    fun `chunk deltas are cumulative within a chunk`() {
        val chunks = listOf(intArrayOf(5, 2, 9))
        val group = ArchiveGroup.join(listOf(bytes(5, 1), bytes(2, 2), bytes(9, 3)), chunks)
        val table = group.size - 1 - 3 * 4
        assertEquals(5, int(group, table))
        assertEquals(-3, int(group, table + 4))
        assertEquals(7, int(group, table + 8))
    }

    @Test
    fun `joining rejects a layout that does not add up`() {
        assertFailsWith<IllegalArgumentException> {
            ArchiveGroup.join(listOf(bytes(4, 1), bytes(4, 2)), listOf(intArrayOf(4, 5)))
        }
        assertFailsWith<IllegalArgumentException> {
            ArchiveGroup.join(listOf(bytes(4, 1), bytes(4, 2)), listOf(intArrayOf(4)))
        }
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it + seed).toByte() }

    private fun int(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
}
