package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.RawArchiveCodec
import world.gregs.voidps.cache.source.codec.RawFilesCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.source.codec.VerbatimGroupCodec
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The source tree against a cache small enough to reason about byte by byte.
 *
 * The fixture is built with the store API rather than taken from a file so it can hold one of
 * everything the real cache holds: a three chunk group, a sparse file id list, an archive with
 * file names, a whirlpool flagged table, none/gzip/bzip2 containers, an encrypted archive with a
 * key and an encrypted archive without one.
 */
class SourceTreeTest {

    private lateinit var root: File
    private lateinit var packed: File
    private lateinit var tree: File
    private lateinit var output: File

    @BeforeTest
    fun setup() {
        SourceCodecs.reset()
        // The fixture's archives are made up bytes: a chunk layout, a sparse file id list, names,
        // keys and an archive nothing can open, none of it a real 727 format. Every index now has a
        // typed codec, and those would (rightly) refuse to decode filler, so each index the fixture
        // fabricates is given back the raw codec this test was written against. The typed codecs
        // meet real data elsewhere - MapCodecParityTest, ModelCodecParityTest, DefinitionJsonCodecTest.
        SourceCodecs.register(Index.ANIMATION_FRAMES, VerbatimGroupCodec)
        SourceCodecs.register(Index.MAPS, RawArchiveCodec)
        SourceCodecs.register(Index.WORLD_MAP, RawFilesCodec.NESTED)
        val root = Files.createTempDirectory("cache-source").toFile()
        packed = File(root, "packed")
        tree = File(root, "tree")
        output = File(root, "out")
        this.root = root
        fixture(packed)
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
        SourceCodecs.reset()
    }

    @Test
    fun `a tree round trips a cache byte for byte`() {
        val source = unpack()

        // Every codec's layout, including the archive nothing could decrypt.
        for (path in listOf(
            "cache.json",
            "anims/0.grp", "anims/1.grp", "anims/3.grp",
            "maps/0.dat", "maps/1.dat", "maps/2.container",
            "worldmapdata/0/0.dat", "worldmapdata/0/1.dat", "worldmapdata/0/2.dat",
            "worldmapdata/1/0.dat", "worldmapdata/1/7.dat"
        )) {
            assertTrue(File(tree, path).isFile, "$path should exist")
        }
        assertFalse(File(tree, "maps/2.dat").exists(), "an opaque archive has no data file")
        assertEquals(1, source.opaque, "one archive has no key")
        assertEquals(0, source.missing)
        assertEquals(0, source.inexact)

        assertEquals(
            """
            {
              "index": 5,
              "format": 6,
              "revision": 5,
              "flags": ["names"],
              "compression": "gzip",
              "archives": {
                "0": {"version": 3, "compression": "gzip", "nameHash": ${MapSquare.terrain(50, 50).hashCode()}, "fileNames": {"0": ${"terrain".hashCode()}}},
                "1": {"version": 4, "compression": "gzip", "nameHash": ${MapSquare.locations(50, 50).hashCode()}, "xtea": [1, 2, 3, 4]},
                "2": {"version": 5, "compression": "gzip", "nameHash": ${MapSquare.locations(60, 60).hashCode()}, "opaque": true}
              }
            }
            """.trimIndent() + "\n",
            File(tree, "maps/index.json").readText()
        )
        assertTrue(
            File(tree, "anims/index.json").readText().contains(
                "\"3\": {\"version\": 7, \"compression\": \"bzip2\", \"fileCount\": 3, \"files\": [0, 2, 5]}"
            ),
            "a sparse file id list is recorded:\n${File(tree, "anims/index.json").readText()}"
        )

        // Re-unpacking an unchanged cache must leave every file alone.
        val again = unpack()
        assertEquals(0, again.written, "nothing should have been rewritten")
        assertEquals(0, again.deleted)

        val result = Packer.pack(SourceTree.open(tree), output)
        assertEquals(3, result.indices)
        assertEquals(8, result.archives)
        assertContainersMatch(packed, output)

        val report = Verifier.verify(SourceTree.open(tree), packed)
        assertTrue(report.parity, "the tree should reproduce the cache exactly: $report")
        assertEquals(0, report.different)
        assertEquals(0, report.recompiled, "nothing here is compiled rather than stored")
        assertEquals(8, report.identical)
        assertEquals(3, report.tables)
    }

    @Test
    fun `an incremental build repacks only what changed`() {
        unpack()
        val source = SourceTree.open(tree)
        Packer.pack(source, output)
        val before = containers(output)

        // One edited file and one bumped version, in two different indices.
        val file = File(tree, "worldmapdata/0/1.dat")
        file.writeBytes(byteArrayOf(9, 9, 9, 9))
        val anims = source.metadata(Index.ANIMATION_FRAMES)
        anims.archives.getValue(1).version = 12
        source.writeMetadata(anims)

        val first = Builder.build(source, output)
        assertFalse(first.full, "there is a manifest, so this is a patch")
        assertEquals(2, first.archives, "one file edit and one version bump: $first")
        assertEquals(2, first.indices, "two reference tables: $first")

        val after = containers(output)
        val changed = after.filter { before[it.key] == null || !before.getValue(it.key).contentEquals(it.value) }
        assertEquals(
            setOf(
                Index.ANIMATION_FRAMES to 1,
                Index.WORLD_MAP to 0,
                FileStore.REFERENCE_INDEX to Index.ANIMATION_FRAMES,
                FileStore.REFERENCE_INDEX to Index.WORLD_MAP
            ),
            changed.keys,
            "only the edited archives and their tables should have moved"
        )
        assertEquals(12, cache(output).archiveVersion(Index.ANIMATION_FRAMES, 1))
        assertContentEquals(byteArrayOf(9, 9, 9, 9), cache(output).data(Index.WORLD_MAP, 0, 1))

        // Nothing changed, so nothing may be touched.
        val stamps = timestamps(output)
        val second = Builder.build(source, output)
        assertTrue(second.unchanged, "a second build should be a no-op: $second")
        assertEquals(0, second.hashedFiles, "nothing should need rehashing")
        assertEquals(stamps, timestamps(output), "the output should not have been rewritten")
    }

    @Test
    fun `an incremental build adds and removes archives`() {
        unpack()
        val source = SourceTree.open(tree)
        Packer.pack(source, output)

        File(tree, "worldmapdata/0/2.dat").delete()
        File(tree, "worldmapdata/2").mkdirs()
        File(tree, "worldmapdata/2/0.dat").writeBytes(byteArrayOf(1, 2, 3))
        val items = source.metadata(Index.WORLD_MAP)
        items.archives[2] = ArchiveMetadata(version = 1, compression = Compression.NONE)
        source.writeMetadata(items)

        val result = Builder.build(source, output)
        assertEquals(2, result.archives, "the shrunken archive and the new one: $result")
        val built = cache(output)
        assertContentEquals(intArrayOf(0, 1), built.files(Index.WORLD_MAP, 0), "file 2 is gone")
        assertContentEquals(byteArrayOf(1, 2, 3), built.data(Index.WORLD_MAP, 2, 0), "archive 2 is new")
        assertEquals(1, built.archiveVersion(Index.WORLD_MAP, 2))

        // And back out again: dropping the archive from the metadata removes it from the cache.
        File(tree, "worldmapdata/2/0.dat").delete()
        val without = source.metadata(Index.WORLD_MAP)
        without.archives.remove(2)
        source.writeMetadata(without)
        val removed = Builder.build(source, output)
        assertEquals(1, removed.removed, "the archive should have been dropped: $removed")
        assertEquals(-1, cache(output).archiveVersion(Index.WORLD_MAP, 2))
    }

    @Test
    fun `a bumped index revision rewrites only the reference table`() {
        unpack()
        val source = SourceTree.open(tree)
        Packer.pack(source, output)
        val before = containers(output)

        val maps = source.metadata(Index.MAPS)
        maps.revision = 6
        source.writeMetadata(maps)

        val result = Builder.build(source, output)
        assertEquals(0, result.archives, "no archive's bytes changed: $result")
        assertEquals(1, result.indices, "but its table did: $result")
        val after = containers(output)
        assertEquals(
            setOf(FileStore.REFERENCE_INDEX to Index.MAPS),
            after.filter { !before.getValue(it.key).contentEquals(it.value) }.keys
        )
        val table = ReferenceTable.decode(
            Container.decode(after.getValue(FileStore.REFERENCE_INDEX to Index.MAPS)).data()
        )
        assertEquals(6, table.revision)
        assertTrue(Builder.build(source, output).unchanged, "and then nothing more")
    }

    @Test
    fun `a build with no manifest packs everything`() {
        unpack()
        val source = SourceTree.open(tree)
        val result = Builder.build(source, output)
        assertTrue(result.full, "an empty output directory has nothing to patch")
        assertContainersMatch(packed, output)
        assertTrue(Builder.build(source, output).unchanged, "the manifest it wrote makes the next build a no-op")
    }

    @Test
    fun `a codec's index level files are kept, tracked and reported`() {
        val codec = ProjectCodec()
        SourceCodecs.register(Index.WORLD_MAP, codec)

        unpack()
        val project = File(tree, "worldmapdata/${ProjectCodec.PROJECT}")
        assertTrue(project.isFile, "the codec's index level file should have been written")
        assertContentEquals(intArrayOf(0, 1), codec.unpacked, "unpackIndex is told what was unpacked")

        // The unpacker deletes everything no archive claimed, so a file only unpackIndex produced
        // is the case that proves the claim works.
        val again = unpack()
        assertEquals(0, again.deleted, "an index level file must survive the prune")
        assertEquals(0, again.written, "and must not be rewritten")

        val source = SourceTree.open(tree)
        codec.packs.clear()
        Packer.pack(source, output)
        assertEquals(
            listOf(listOf(0, 1) to emptyList<String>()),
            codec.packs,
            "a full pack sets the codec up once, with every archive"
        )

        // An edit to it repacks no archive; it is handed to the codec instead, and the manifest
        // records it like any other input, so the build after that is a no-op again.
        codec.packs.clear()
        project.writeText("edited\n")
        val build = Builder.build(source, output) { assertFalse(it.contains("belongs to no codec"), it) }
        assertEquals(0, build.archives, "an index level file repacks nothing on its own: $build")
        assertEquals(1, build.changedFiles)
        assertEquals(
            listOf(emptyList<Int>() to listOf(ProjectCodec.PROJECT)),
            codec.packs,
            "the codec is told which of its index level files moved"
        )

        codec.packs.clear()
        assertTrue(Builder.build(source, output).unchanged, "the manifest makes the next build a no-op")
        assertEquals(emptyList(), codec.packs, "and the codec is not woken for nothing")
    }

    @Test
    fun `status counts what the tree holds and which sidecars went stale`() {
        unpack()
        val source = SourceTree.open(tree)

        val clean = source.status()
        assertEquals(3, clean.indices.size)
        assertEquals(8, clean.archives)
        assertEquals(11, clean.files, "three groups, three map archives and five world map files")
        assertEquals(0, clean.pristine)
        assertEquals(1, clean.opaque)
        assertEquals(0, clean.stale.size)
        assertTrue(clean.gamevals > 0, "the unpack copied the catalogs in")

        // What every pristine fallback looks like: the original bytes beside the editable file,
        // guarded by that file's hash.
        val guarded = File(tree, "worldmapdata/0/1.dat")
        File(tree, "worldmapdata/0/1.dat${SourceFiles.PRISTINE_EXTENSION}").writeBytes(byteArrayOf(1, 2))
        val metadata = source.metadata(Index.WORLD_MAP)
        metadata.archives.getValue(0).pristine = mapOf(1 to SourceFiles.sha256(guarded.readBytes()))
        source.writeMetadata(metadata)

        val kept = source.status()
        assertEquals(1, kept.pristine)
        assertEquals(0, kept.stale.size, "the guard still holds, so the pack will emit the sidecar")

        guarded.writeBytes(byteArrayOf(9, 9, 9, 9))
        val stale = source.status().stale
        assertEquals(1, stale.size, "an edited file leaves its sidecar stale: $stale")
        assertEquals("worldmapdata/0/1.dat", stale.single().path)
    }

    /**
     * A codec with a file that is a property of the index rather than of any archive - what index
     * 12's `cs2.d.ts` and friends are - over the raw layout the fixture's bytes need.
     */
    private class ProjectCodec : SourceCodec by RawFilesCodec.NESTED {

        var unpacked: IntArray = IntArray(0)
        val packs = ArrayList<Pair<List<Int>, List<String>>>()

        override fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> {
            unpacked = archives
            return listOf(SourceFile(PROJECT, "the whole index\n".toByteArray()))
        }

        override fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {
            packs.add(archives.toList() to changedIndexFiles)
        }

        override fun owner(directory: Path, path: String): Owner =
            if (path == PROJECT) Owner.Index else super.owner(directory, path)

        override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive =
            RawFilesCodec.NESTED.unpack(directory, archive)

        override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
            RawFilesCodec.NESTED.pack(directory, archive, metadata)

        companion object {
            const val PROJECT = "project.txt"
        }
    }

    private fun unpack(): UnpackReport {
        val cache = FileCache.load(packed.absolutePath)
        try {
            return Unpacker.unpack(cache, SourceTree.create(tree, SourceTree.REVISION, cache.indexCount()), KEYS)
        } finally {
            cache.close()
        }
    }

    private fun cache(directory: File): Cache = FileCache.load(directory.absolutePath)

    /** Every container in [directory], by index and archive, reference tables included. */
    private fun containers(directory: File): Map<Pair<Int, Int>, ByteArray> {
        val containers = HashMap<Pair<Int, Int>, ByteArray>()
        FileStore.open(directory).use { store ->
            for (index in 0 until store.indexCount()) {
                val table = store.read(FileStore.REFERENCE_INDEX, index) ?: continue
                containers[FileStore.REFERENCE_INDEX to index] = table
                for (entry in ReferenceTable.decode(Container.decode(table).data()).archives) {
                    containers[index to entry.id] = store.read(index, entry.id) ?: continue
                }
            }
        }
        return containers
    }

    private fun assertContainersMatch(expected: File, actual: File) {
        val left = containers(expected)
        val right = containers(actual)
        assertEquals(left.keys, right.keys, "the same archives and tables should be there")
        for ((key, bytes) in left) {
            assertContentEquals(bytes, right.getValue(key), "index ${key.first} archive ${key.second}")
        }
    }

    private fun timestamps(directory: File): Map<String, Long> {
        val stamps = HashMap<String, Long>()
        val root: Path = directory.toPath()
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    stamps[root.relativize(path).toString()] = Files.getLastModifiedTime(path).toMillis()
                }
            }
        }
        return stamps
    }

    /**
     * Three indices' worth of archives, covering every shape the real cache has: `anims` keeps
     * whole groups, `maps` is named and partly encrypted, `worldmapdata` holds several files per
     * archive and a whirlpool flagged table. All three are raw-byte indices on purpose - the
     * typed JSON codecs decode what they are given, so a fixture of made up bytes has to stay out
     * of their indices; `DefinitionJsonCodecTest` is where they are exercised.
     */
    private fun fixture(directory: File) {
        FileStore.create(directory, INDEX_COUNT).use { store ->
            write(
                store,
                IndexSpec(
                    index = Index.ANIMATION_FRAMES,
                    format = 7,
                    revision = 11,
                    tableCompression = Compression.BZIP2,
                    archives = listOf(
                        ArchiveSpec(
                            id = 0,
                            fileIds = intArrayOf(0, 1),
                            files = listOf(ByteArray(9) { it.toByte() }, ByteArray(6) { (it * 3).toByte() }),
                            chunks = listOf(intArrayOf(3, 2), intArrayOf(3, 2), intArrayOf(3, 2)),
                            version = 1
                        ),
                        ArchiveSpec(
                            id = 1,
                            fileIds = intArrayOf(0),
                            files = listOf(ByteArray(5) { (it + 1).toByte() }),
                            compression = Compression.NONE,
                            version = 2
                        ),
                        ArchiveSpec(
                            id = 3,
                            fileIds = intArrayOf(0, 2, 5),
                            files = listOf(ByteArray(11) { it.toByte() }, ByteArray(4), ByteArray(2) { 7 }),
                            compression = Compression.BZIP2,
                            version = 7
                        )
                    )
                )
            )
            write(
                store,
                IndexSpec(
                    index = Index.MAPS,
                    format = 6,
                    revision = 5,
                    named = true,
                    archives = listOf(
                        ArchiveSpec(
                            id = 0,
                            fileIds = intArrayOf(0),
                            files = listOf(ByteArray(40) { it.toByte() }),
                            version = 3,
                            nameHash = MapSquare.terrain(50, 50).hashCode(),
                            fileNames = mapOf(0 to "terrain".hashCode())
                        ),
                        ArchiveSpec(
                            id = 1,
                            fileIds = intArrayOf(0),
                            files = listOf(ByteArray(64) { (it * 5).toByte() }),
                            version = 4,
                            nameHash = MapSquare.locations(50, 50).hashCode(),
                            keys = intArrayOf(1, 2, 3, 4)
                        ),
                        ArchiveSpec(
                            id = 2,
                            fileIds = intArrayOf(0),
                            files = listOf(ByteArray(48) { (it * 7).toByte() }),
                            version = 5,
                            nameHash = MapSquare.locations(60, 60).hashCode(),
                            keys = intArrayOf(9, 8, 7, 6)
                        )
                    )
                )
            )
            write(
                store,
                IndexSpec(
                    index = Index.WORLD_MAP,
                    format = 6,
                    revision = 9,
                    whirlpool = true,
                    archives = listOf(
                        ArchiveSpec(
                            id = 0,
                            fileIds = intArrayOf(0, 1, 2),
                            files = listOf(ByteArray(3) { 1 }, ByteArray(5) { 2 }, ByteArray(7) { 3 }),
                            version = 6
                        ),
                        ArchiveSpec(
                            id = 1,
                            fileIds = intArrayOf(0, 7),
                            files = listOf(ByteArray(2) { 4 }, ByteArray(6) { 5 }),
                            compression = Compression.NONE,
                            version = 0
                        )
                    )
                )
            )
        }
    }

    private fun write(store: FileStore, spec: IndexSpec) {
        val table = ReferenceTable(spec.format, spec.revision, spec.named, spec.whirlpool)
        for (archive in spec.archives) {
            val group = ArchiveGroup.join(archive.files, archive.chunks)
            val container = Container.compress(group, archive.compression, archive.version and 0xffff)
            val bytes = container.encode(archive.keys)
            store.write(spec.index, archive.id, bytes)
            val entry = ArchiveEntry(archive.id)
            entry.nameHash = archive.nameHash
            entry.version = archive.version
            entry.crc = CRC.calculate(bytes, 0, bytes.size - 2)
            if (spec.whirlpool) {
                entry.whirlpool = container.whirlpool(archive.keys)
            }
            for (file in archive.fileIds) {
                entry.files.add(FileEntry(file, archive.fileNames[file] ?: 0))
            }
            table.add(entry)
        }
        val bytes = Container.compress(table.encode(), spec.tableCompression, null).encode()
        store.write(FileStore.REFERENCE_INDEX, spec.index, bytes)
    }

    private class ArchiveSpec(
        val id: Int,
        val fileIds: IntArray,
        val files: List<ByteArray>,
        val chunks: List<IntArray>? = null,
        val compression: Compression = Compression.GZIP,
        val version: Int = 1,
        val nameHash: Int = 0,
        val fileNames: Map<Int, Int> = emptyMap(),
        val keys: IntArray? = null
    )

    private class IndexSpec(
        val index: Int,
        val format: Int,
        val revision: Int,
        val named: Boolean = false,
        val whirlpool: Boolean = false,
        val tableCompression: Compression = Compression.GZIP,
        val archives: List<ArchiveSpec>
    )

    private companion object {
        /** Enough idx files for the highest index the fixture uses. */
        private const val INDEX_COUNT = 24

        /** Only `l50_50`'s key; `l60_60`'s is the one nobody has. */
        private val KEYS = mapOf((50 shl 8 or 50) to intArrayOf(1, 2, 3, 4))
    }
}
