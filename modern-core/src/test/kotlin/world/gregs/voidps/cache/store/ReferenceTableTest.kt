package world.gregs.voidps.cache.store

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceTableTest {

    @Test
    fun `a format 7 named whirlpool table round trips`() {
        val table = ReferenceTable(format = 7, revision = 1005, named = true, whirlpool = true)
        table.add(entry(0, nameHash = -12345, crc = 0x1234, version = 3, files = intArrayOf(0)))
        table.add(entry(1, nameHash = 99, crc = -1, version = 70000, files = intArrayOf(0, 1, 5)))
        // Above 0x7fff an id delta has to use the four byte big-smart form.
        table.add(entry(40000, nameHash = 0, crc = 7, version = 0, files = intArrayOf(2)))

        val encoded = table.encode()
        val decoded = ReferenceTable.decode(encoded)
        assertContentEquals(encoded, decoded.encode())
        assertEquals(7, decoded.format)
        assertEquals(1005, decoded.revision)
        assertTrue(decoded.named)
        assertTrue(decoded.whirlpool)
        assertEquals(3, decoded.archiveCount)
        assertContentEquals(intArrayOf(0, 1, 40000), IntArray(3) { decoded.archives[it].id })

        val second = decoded.archive(1)!!
        assertEquals(99, second.nameHash)
        assertEquals(-1, second.crc)
        assertEquals(70000, second.version, "versions are full ints, not the container's 16 bits")
        assertContentEquals(intArrayOf(0, 1, 5), second.fileIds)
        assertContentEquals(whirlpool(1), second.whirlpool)
        assertEquals(101, second.file(1)!!.nameHash)
    }

    @Test
    fun `a format 6 unnamed table round trips`() {
        val table = ReferenceTable(format = 6, revision = 42, named = false, whirlpool = false)
        table.add(entry(5, crc = 1, version = 1, files = intArrayOf(0, 1)))
        table.add(entry(9, crc = 2, version = 2, files = intArrayOf(0)))
        val encoded = table.encode()
        // format, revision, flags, count, two deltas, two crcs, two versions, two counts, three file deltas
        assertEquals(1 + 4 + 1 + 2 + 2 * 2 + 2 * 4 + 2 * 4 + 2 * 2 + 3 * 2, encoded.size)
        val decoded = ReferenceTable.decode(encoded)
        assertContentEquals(encoded, decoded.encode())
        assertEquals(6, decoded.format)
        assertEquals(42, decoded.revision)
        assertNull(decoded.archive(5)!!.whirlpool)
        assertEquals(0, decoded.archive(5)!!.nameHash)
    }

    @Test
    fun `a format 5 table has no revision`() {
        val table = ReferenceTable(format = 5, revision = 0, named = false, whirlpool = false)
        table.add(entry(0, crc = 3, version = 4, files = intArrayOf(0)))
        val encoded = table.encode()
        assertEquals(5, encoded[0].toInt())
        assertEquals(0, encoded[1].toInt(), "flags follow the format byte directly")
        assertContentEquals(encoded, ReferenceTable.decode(encoded).encode())
    }

    @Test
    fun `archives stay in id order however they are added`() {
        val table = ReferenceTable(format = 7)
        table.archiveOrCreate(9)
        table.archiveOrCreate(2)
        table.archiveOrCreate(30)
        table.archiveOrCreate(2)
        assertContentEquals(intArrayOf(2, 9, 30), IntArray(table.archiveCount) { table.archives[it].id })
        assertEquals(3, table.archiveCount)
        table.remove(9)
        assertContentEquals(intArrayOf(2, 30), IntArray(table.archiveCount) { table.archives[it].id })
    }

    @Test
    fun `a table needing a whirlpool it does not have will not encode`() {
        val table = ReferenceTable(format = 7, whirlpool = true)
        table.archiveOrCreate(0).files.add(FileEntry(0))
        assertFailsWith<IllegalStateException> { table.encode() }
    }

    @Test
    fun `an unknown format is rejected`() {
        assertFailsWith<IllegalArgumentException> { ReferenceTable.decode(byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0)) }
    }

    private fun entry(id: Int, nameHash: Int = 0, crc: Int = 0, version: Int = 0, files: IntArray = intArrayOf(0)) =
        ArchiveEntry(
            id = id,
            nameHash = nameHash,
            crc = crc,
            whirlpool = whirlpool(id),
            version = version,
            files = files.mapTo(ArrayList()) { FileEntry(it, if (nameHash == 0) 0 else nameHash + it + 1) }
        )

    private fun whirlpool(seed: Int) = ByteArray(64) { (it * 31 + seed).toByte() }
}
