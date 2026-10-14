package world.gregs.voidps.cache.source.audio

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.Packer
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seven audio and native asset indices through the real framework: unpack them into a tree,
 * pack that tree into a cache, and demand that every container and every reference table comes out
 * byte for byte as it went in.
 *
 * `WholeCacheSourceTest` does this for all thirty-seven indices and is the guarantee; this is the
 * same run over these seven alone, so a regression in one of them is a one minute test rather than
 * a twenty minute one, and so the timings and sizes `docs/cache-source.md` publishes per index have
 * somewhere to come from.
 *
 * The one thing it cannot assert is the derived version table, which is a digest of *every* index's
 * reference table: a tree of seven indices has a different one from a cache of thirty-seven by
 * construction. Every reference table these seven own is compared, which is what parity means here.
 */
class AudioSourceTreeTest {

    @Test
    fun `the audio indices round trip through a source tree`() {
        val packed = directory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "audio-source").toFile()
        val tree = File(work, "tree")
        val output = File(work, "packed")
        try {
            val cache = FileCache.load(packed.absolutePath)
            val source = SourceTree.create(tree, SourceTree.REVISION, cache.indexCount())
            val unpacked = try {
                Unpacker.unpack(Subset(cache, INDICES), source) { println("  unpack $it") }
            } finally {
                cache.close()
            }
            println("unpack: $unpacked")
            assertEquals(INDICES.size, unpacked.indices)
            assertEquals(ARCHIVES, unpacked.archives)
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(0, unpacked.opaque, "none of these archives is encrypted")
            assertEquals(0, unpacked.pristine, "no file may need a pristine copy")
            assertEquals(0, unpacked.inexact, "no archive may need a pristine fallback")

            val size = size(tree.toPath())
            println("tree: ${size.first} files, ${size.second / 1_000_000} MB")
            for (index in INDICES) {
                val files = size(tree.toPath().resolve(IndexNames.name(index)))
                println("  %-18s %7d files %7d KB".format(IndexNames.name(index), files.first, files.second / 1000))
            }

            val result = Packer.pack(SourceTree.open(tree), output) { println("  pack $it") }
            println("pack: $result")
            assertEquals(unpacked.archives, result.archives, "everything unpacked should pack again")

            val report = Verifier.verify(SourceTree.open(tree), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(INDICES.size, report.tables, "every reference table must be identical")
            assertEquals(unpacked.archives, report.identical)

            val status = SourceTree.open(tree).status()
            println("status: $status")
            for (index in status.indices) {
                println("  $index")
            }
            assertEquals(0, status.pristine, "cache status must agree that nothing is pristine")
            assertEquals(emptyList(), status.stale, "no sidecar may be stale")
            assertEquals(INDICES.size, status.indices.size)

            // And the cache that was actually written holds those containers, read back off disk.
            assertEquals(0, differences(packed, output), "the packed output must match the cache it came from")

            val build = Builder.build(SourceTree.open(tree), output) { println("  build $it") }
            println("no-op build: $build")
            assertTrue(build.unchanged, "an unchanged tree must rebuild nothing")

            val again = FileCache.load(packed.absolutePath)
            val second = try {
                Unpacker.unpack(Subset(again, INDICES), SourceTree.open(tree))
            } finally {
                again.close()
            }
            println("re-unpack: $second")
            assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
            assertEquals(0, second.deleted)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Every container of the seven indices, in the built cache against the shipped one. */
    private fun differences(packed: File, output: File): Int {
        var different = 0
        val shipped = FileCache.load(packed.absolutePath)
        val built = FileCache.load(output.absolutePath)
        try {
            for (index in INDICES) {
                for (archive in shipped.archives(index)) {
                    val expected = shipped.container(index, archive)
                    val actual = built.container(index, archive)
                    if (expected == null || actual == null || !expected.contentEquals(actual)) {
                        different++
                    }
                }
                val expected = shipped.container(FileStore.REFERENCE_INDEX, index)
                val actual = built.container(FileStore.REFERENCE_INDEX, index)
                if (expected == null || actual == null || !expected.contentEquals(actual)) {
                    different++
                }
            }
        } finally {
            shipped.close()
            built.close()
        }
        return different
    }

    /**
     * A cache with only some of its indices, so the unpacker walks exactly those.
     *
     * It asks every index for its reference table and skips the ones that answer null, which is
     * how an index that does not exist is handled, so hiding a table hides the index.
     */
    private class Subset(private val cache: Cache, private val indices: Set<Int>) : Cache by cache {
        override fun referenceTable(index: Int): ReferenceTable? =
            if (indices.contains(index)) cache.referenceTable(index) else null
    }

    private fun size(root: Path): Pair<Int, Long> {
        if (!Files.exists(root)) {
            return 0 to 0L
        }
        var files = 0
        var bytes = 0L
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    files++
                    bytes += Files.size(path)
                }
            }
        }
        return files to bytes
    }

    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun directory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the audio source tree round trip; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        private val INDICES = linkedSetOf(
            Index.SYNTH_SOUNDS,
            Index.MIDI_SONGS,
            Index.MIDI_JINGLES,
            Index.VORBIS,
            Index.MIDI_INSTRUMENTS,
            Index.NATIVE_LIBRARIES,
            Index.SHADERS
        )

        /** 10,084 + 1,137 + 525 + 16,825 + 247 + 33 + 2. */
        private const val ARCHIVES = 28_853
    }
}
