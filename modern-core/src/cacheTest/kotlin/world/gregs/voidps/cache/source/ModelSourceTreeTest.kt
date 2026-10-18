package world.gregs.voidps.cache.source

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.source.codec.ModelCodec
import world.gregs.voidps.cache.source.codec.ModelGltf
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Indices 7, 27 and 29 through a real source tree: unpack them, pack the tree back, and demand
 * that every container and every reference table comes out byte for byte as it went in.
 *
 * Heavy: it writes every model in the cache to disk as a `.glb`. Run it deliberately, not as part
 * of a routine `:core:test`.
 *
 * [ModelCodecParityTest] proves the codecs reproduce every archive in memory; this proves the tree
 * on disk does - the file names map back to their archives, `index.json` carries what the layout
 * cannot, the group is rebuilt from the files and the container recompresses to the same bytes -
 * which is the guarantee `docs/cache-source.md` makes and the only place it can be shown. It also
 * reports what unpacking and packing index 7 costs and how big the tree is, which are the numbers
 * that document quotes.
 *
 * Only three indices, so the derived version table is not compared: it is a function of all
 * thirty-seven reference tables and [WholeCacheSourceTest] is where the whole cache is checked.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class ModelSourceTreeTest {

    @Test
    fun `models, particles and billboards round trip through a source tree`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "model-source").toFile()
        val tree = File(work, "tree")
        val output = File(work, "packed")
        try {
            val source = SourceTree.create(tree, SourceTree.REVISION, INDICES_IN_CACHE)
            val unpackTimes = LinkedHashMap<String, Long>()
            val unpacked = FileCache.load(packed.absolutePath).use { cache ->
                var last = System.nanoTime()
                Unpacker.unpack(PartialCache(cache, INDICES), source) { line ->
                    val now = System.nanoTime()
                    unpackTimes[line] = (now - last) / 1_000_000
                    last = now
                    println("  unpack $line")
                }
            }
            println("unpack: $unpacked")
            for ((line, millis) in unpackTimes) {
                println("  ${line.substringBefore(':')}: ${millis}ms")
            }
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(0, unpacked.opaque, "none of these indices is encrypted")
            assertEquals(0, unpacked.pristine, "no model, particle or billboard may need a pristine copy")
            assertEquals(0, unpacked.inexact, "no archive should have needed a pristine fallback")
            val archives = unpacked.archives
            assertTrue(
                archives >= VANILLA_ARCHIVES,
                "$archives archives unpacked, fewer than the $VANILLA_ARCHIVES of vanilla 727"
            )

            for (index in INDICES) {
                val size = size(tree.toPath().resolve(IndexNames.name(index)))
                println("  ${IndexNames.name(index)}: ${size.first} files, ${size.second / 1_000_000} MB")
            }

            val packTimes = LinkedHashMap<String, Long>()
            var last = System.nanoTime()
            val result = Packer.pack(SourceTree.open(tree), output) { line ->
                val now = System.nanoTime()
                packTimes[line] = (now - last) / 1_000_000
                last = now
            }
            println("pack: $result")
            for ((line, millis) in packTimes) {
                println("  ${line.substringBefore(':')}: ${millis}ms")
            }
            assertEquals(archives, result.archives, "everything unpacked should pack again")

            val report = Verifier.verify(SourceTree.open(tree), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(INDICES.size, report.indices.size, "all three indices must be verified")
            assertEquals(INDICES.size, report.tables, "every reference table must be identical")
            assertEquals(archives, report.identical, "every unpacked archive must verify identical")

                val stamps = timestamps(tree.toPath())
            val second = FileCache.load(packed.absolutePath).use { cache ->
                Unpacker.unpack(PartialCache(cache, INDICES), SourceTree.open(tree))
            }
            println("re-unpack: $second")
            assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
            assertEquals(0, second.deleted)
            assertEquals(stamps, timestamps(tree.toPath()), "not a single file may have been touched")

            edit(SourceTree.open(tree), output)
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Move one vertex of one model and rebuild: exactly one archive may be repacked, and the built
     * cache must decode the moved vertex.
     *
     * This is the whole point of the codec - a model is a file somebody edits, in a format Blender
     * opens - and the incremental build is what makes editing one of tens of thousands of them cheap, so both
     * are checked in one go. The edit goes through [ModelGltf] rather than through a text
     * substitution, which is what an editor would do to a `.glb` too.
     */
    private fun edit(tree: SourceTree, output: File) {
        val file = tree.indexDirectory(Index.MODELS).resolve("$EDITED${ModelCodec.EXTENSION}")
        val model = ModelGltf.read(Files.readAllBytes(file), EDITED)
        assertTrue(model.vertexCount > 0, "model $EDITED has no vertices to move")
        val moved = model.vertexX.copyOf()
        moved[0] = EDITED_X
        Files.write(file, ModelGltf.write(model.copy(vertexX = moved)))

        val build = Builder.build(tree, output) { println("  edit build $it") }
        println("one file build: $build")
        assertTrue(!build.full, "an edited file must be patched, not repacked from scratch")
        assertEquals(1, build.changedFiles, "only the edited file changed")
        assertEquals(1, build.archives, "only model $EDITED's archive may be repacked")
        assertEquals(1, build.indices, "only index ${Index.MODELS}'s table may be rewritten")

        FileCache.load(output.absolutePath).use { cache ->
            val model = assertNotNull(ModelDecoder.decode(cache, EDITED), "model $EDITED is not in the built cache")
            assertEquals(EDITED_X, model.vertexX[0], "the built cache must carry the edited vertex")
        }
    }

    /**
     * A cache that admits to only some of its indices, so an unpack of three of them writes three
     * directories and a `verify` compares three reference tables.
     *
     * [Unpacker] asks a cache for each index's reference table and skips an index that has none,
     * which is the one seam a partial unpack needs; everything else is the cache's own.
     */
    private class PartialCache(private val cache: Cache, indices: IntArray) : Cache by cache {
        private val kept = indices.toSet()

        override fun referenceTable(index: Int): ReferenceTable? =
            if (kept.contains(index)) cache.referenceTable(index) else null
    }

    private fun size(root: Path): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        if (!Files.isDirectory(root)) {
            return files to bytes
        }
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

    private fun timestamps(root: Path): Map<String, Long> {
        val stamps = HashMap<String, Long>()
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    stamps[root.relativize(path).toString()] = Files.getLastModifiedTime(path).toMillis()
                }
            }
        }
        return stamps
    }

    /** A gigabyte of model JSON does not belong in a tmpfs; the build directory is on disk. */
    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the model source tree round trip; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        /** Models, the particle configs their footers emit, and the billboard configs they pin. */
        private val INDICES = intArrayOf(Index.MODELS, Index.PARTICLES, Index.BILLBOARDS)

        /** What vanilla 727 shipped: 73,417 models, 2 particle archives and 1 billboard archive. */
        private const val VANILLA_ARCHIVES = 73_420

        /** How many indices the tree describes, so `cache.json` says what the real cache says. */
        private const val INDICES_IN_CACHE = IndexNames.COUNT

        /** A model whose first vertex the edit test moves: 3253 is a plain untextured mesh. */
        private const val EDITED = 3253

        /**
         * A multiple of four, because a model older than version 13 is stored at quarter scale and
         * a value that is not a multiple of four could not come back out of one.
         */
        private const val EDITED_X = 1232
    }
}
