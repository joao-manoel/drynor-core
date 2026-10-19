package org.drynor.tools.osrs

import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.store.*
import java.nio.file.Files
import kotlin.test.*

class InfernoAssetWriterTest {
    @Test fun `batch keeps existing files names and checksums and preserves frame section layout`() {
        val directory = Files.createTempDirectory("inferno-assets-")
        try {
            val original = byteArrayOf(11, 22)
            FileStore.create(directory.toFile(), 2).use { store ->
                val container = Container.compress(original, Compression.GZIP, 7)
                val bytes = container.encode()
                store.write(1, 10, bytes)
                val table = ReferenceTable(named = true, whirlpool = true)
                table.archiveOrCreate(10).also {
                    it.nameHash = 123; it.version = 7; it.crc = CRC.calculate(bytes, 0, bytes.size - 2)
                    it.whirlpool = container.whirlpool(); it.files.add(FileEntry(17, 456))
                }
                store.write(255, 1, Container.compress(table.encode(), Compression.GZIP, null).encode())
                store.write(255, 0, Container.compress(ReferenceTable().encode(), Compression.GZIP, null).encode())
            }
            val frames = listOf(byteArrayOf(0, 0, 1, 1, 7, 9), byteArrayOf(0, 0, 1, 2, 8, 7, 5))
            InfernoAssetWriter.write(directory, listOf(
                InfernoAssetWriter.Asset(1, 10, 19, byteArrayOf(33)),
                InfernoAssetWriter.Asset(0, 20, 0, frames[0]), InfernoAssetWriter.Asset(0, 20, 2, frames[1])))
            FileCache(directory.toString(), writable = false).use { cache ->
                assertContentEquals(original, cache.data(1, 10, 17))
                assertContentEquals(byteArrayOf(33), cache.data(1, 10, 19))
                val entry = cache.referenceTable(1)!!.archive(10)!!
                assertEquals(123, entry.nameHash); assertEquals(456, entry.file(17)!!.nameHash)
                assertEquals(8, entry.version)
                val bytes = cache.container(1, 10)!!
                assertEquals(CRC.calculate(bytes, 0, bytes.size - 2), entry.crc)
                assertContentEquals(Container.decode(bytes).whirlpool(), entry.whirlpool)
                val group = ArchiveGroup.split(Container.decode(cache.container(0, 20)!!).data(), intArrayOf(0, 2))
                assertEquals(3, group.chunks.size)
                assertContentEquals(frames[0], cache.data(0, 20, 0))
                assertContentEquals(frames[1], cache.data(0, 20, 2))
            }
            assertFailsWith<IllegalArgumentException> {
                InfernoAssetWriter.write(directory, listOf(InfernoAssetWriter.Asset(1, 10, 17, byteArrayOf(99))))
            }
            InfernoAssetWriter.write(directory, listOf(InfernoAssetWriter.Asset(1, 10, 17, byteArrayOf(99), replaceExisting = true)))
            FileCache(directory.toString(), writable = false).use { cache ->
                assertContentEquals(byteArrayOf(99), cache.data(1, 10, 17))
                assertContentEquals(byteArrayOf(33), cache.data(1, 10, 19))
            }
            assertFailsWith<IllegalArgumentException> {
                InfernoAssetWriter.write(directory, listOf(InfernoAssetWriter.Asset(1, 10, 123, byteArrayOf(99), replaceExisting = true)))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
