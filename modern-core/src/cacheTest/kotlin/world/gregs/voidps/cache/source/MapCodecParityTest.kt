package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapJson
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Index 5 through [MapCodec]: every one of the 7,268 map archives decoded, written as JSON, read
 * straight back and re-encoded, and the bytes compared against the ones the cache ships.
 *
 * This is the map half of the parity guarantee `docs/cache-source.md` makes. It is the same trip a
 * `cache unpack` makes and the same one `cache build` reverses, so a decoder that drops a byte, an
 * encoder that reorders a record or a JSON spelling that narrows a number shows up here as a
 * pristine sidecar rather than as a silently rewritten region in a 300 MB tree.
 *
 * Two tests, both skipped when there is no cache to read:
 *
 * 1. **Per archive**, reported per shape - files, byte identical, pristine - which is where the
 *    table in `docs/cache-source.md` comes from. The bar is every file identical and none pristine.
 * 2. **Tree level**: unpack index 5 into a real source tree and verify a fresh pack of it against
 *    the packed cache, container for container and reference table included.
 */
class MapCodecParityTest {

    @Test
    fun `every map archive re-encodes byte for byte`() {
        val cache = cache() ?: return
        SourceCodecs.reset()
        val keys = Unpacker.mapKeys()
        val table = cache.referenceTable(Index.MAPS) ?: error("The cache has no index ${Index.MAPS} reference table.")
        val work = Files.createTempDirectory(scratch(), "map-codec")
        val counts = LinkedHashMap<MapSquare.Shape, Counts>()
        for (shape in MapSquare.Shape.entries) {
            counts[shape] = Counts()
        }
        var opaque = 0
        val examples = StringBuilder()
        try {
            for (entry in table.archives) {
                val square = MapSquare.of(entry.nameHash)
                    ?: error("Map archive ${entry.id}'s name hash ${entry.nameHash} is no map square's.")
                val group = group(cache, entry.id, square, keys)
                if (group == null) {
                    // The eight `l<x>_<y>` archives nobody has a key for; the framework keeps their
                    // container bytes verbatim and the codec never sees them.
                    opaque++
                    continue
                }
                val count = counts.getValue(square.shape)
                count.files++
                val metadata = ArchiveMetadata(nameHash = entry.nameHash)
                metadata.fileIds(entry.fileIds)
                val source = SourceArchive(
                    Index.MAPS, entry.id, square.name, entry.fileIds, group,
                    ArchiveGroup.split(group, entry.fileIds).files, emptyList(), metadata
                )
                val unpacked = MapCodec.unpack(work, source)
                for (file in unpacked.files) {
                    SourceFiles.write(work.resolve(file.path), file.bytes)
                }
                if (unpacked.pristine.isNotEmpty()) {
                    metadata.pristine = unpacked.pristine
                    count.pristine++
                    if (count.pristine <= EXAMPLES) {
                        examples.append("\n  ${square.name} did not survive its JSON file")
                    }
                }
                val packed = MapCodec.pack(work, entry.id, metadata)
                if (packed.group.contentEquals(group)) {
                    count.identical++
                } else if (count.pristine == 0) {
                    examples.append("\n  ${square.name} packed ${packed.group.size} bytes, not ${group.size}")
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }

        var files = 0
        var identical = 0
        var pristine = 0
        println("%-22s %-24s %9s %9s %9s".format("shape", "file", "files", "identical", "pristine"))
        for ((shape, count) in counts) {
            files += count.files
            identical += count.identical
            pristine += count.pristine
            println(
                "%-22s %-24s %9d %9d %9d".format(
                    shape.name.lowercase(), "<x>_<y>/${shape.file}.json", count.files, count.identical, count.pristine
                )
            )
        }
        println("%-22s %-24s %9d %9d %9d".format("total", "", files, identical, pristine))
        println("opaque archives skipped: $opaque")

        assertEquals(OPAQUE, opaque, "only the unkeyed location archives should be opaque")
        assertEquals(ARCHIVES - OPAQUE, files, "every named map archive should have been compared")
        assertEquals(0, pristine, "no map archive may need a pristine copy$examples")
        assertEquals(files, identical, "every map archive must re-encode byte for byte$examples")
        assertEquals(TERRAIN, counts.getValue(MapSquare.Shape.TERRAIN).files)
        assertEquals(UNDERWATER, counts.getValue(MapSquare.Shape.UNDERWATER_TERRAIN).files)
        assertEquals(NPCS, counts.getValue(MapSquare.Shape.NPCS).files)
    }

    @Test
    fun `index 5 unpacks into a tree that packs back byte for byte`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "map-tree").toFile()
        try {
            val cache = FileCache.load(packed.absolutePath)
            val tree = SourceTree.create(work, SourceTree.REVISION, cache.indexCount())
            val unpacked = try {
                Unpacker.unpack(OnlyMaps(cache), tree, Unpacker.mapKeys()) { println("  unpack $it") }
            } finally {
                cache.close()
            }
            println("unpack: $unpacked")
            assertEquals(1, unpacked.indices, "only index ${Index.MAPS} should have been unpacked")
            assertEquals(ARCHIVES, unpacked.archives)
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(OPAQUE, unpacked.opaque, "only the unkeyed location archives should be opaque")
            assertEquals(0, unpacked.pristine, "no map file should have needed a pristine copy")
            assertEquals(0, unpacked.inexact, "no map archive should have needed a pristine fallback")

            val size = size(work.toPath())
            println("tree: ${size.first} files, ${size.second / 1_000_000} MB")

            val report = Verifier.verify(SourceTree.open(work), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(1, report.indices.size, "only index ${Index.MAPS} is in the tree")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(ARCHIVES, report.identical, "every container must come out identical")
            assertTrue(report.indices.single().tableIdentical, "index ${Index.MAPS}'s reference table must match")

            // Re-unpacking an unchanged cache has to leave the tree exactly as it is.
            val stamps = timestamps(work.toPath())
            val again = FileCache.load(packed.absolutePath)
            val second = try {
                Unpacker.unpack(OnlyMaps(again), SourceTree.open(work), Unpacker.mapKeys())
            } finally {
                again.close()
            }
            assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
            assertEquals(0, second.deleted)
            assertEquals(stamps, timestamps(work.toPath()), "not a single file may have been touched")

            edit(SourceTree.open(work))
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Edit one tile of one region and rebuild: exactly one archive may be repacked, and the built
     * cache has to carry the edit.
     *
     * This is the whole point of the codec - a map square is a file somebody changes - and it is
     * also the only end to end check of [MapCodec.archiveOf], because mapping the changed path back
     * onto an archive is what an incremental build does and nothing else asks for.
     */
    private fun edit(tree: SourceTree) {
        val output = File(tree.directory.parent.toFile(), "map-build")
        try {
            val directory = tree.indexDirectory(Index.MAPS)
            val square = MapSquare.Square(MapSquare.Shape.TERRAIN, EDITED_X, EDITED_Y)
            val archive = MapCodec.archiveOf(directory, MapCodec.path(square))
                ?: error("${square.name} is not in the tree.")
            assertEquals(1, Packer.pack(tree, output).indices, "the tree is index ${Index.MAPS} alone")

            val file = directory.resolve(MapCodec.path(square))
            val definition = MapJson.readTerrain(Files.readAllBytes(file), square)
            val before = definition.getTile(0, 0, 0)
            // Whatever the tile is now, this is not it: an edit that changes nothing would leave the
            // file untouched and the build with nothing to repack.
            val underlay = if (before.underlayId == EDITED_UNDERLAY) EDITED_UNDERLAY - 1 else EDITED_UNDERLAY
            val edited = MapTile(
                height = before.height,
                opcode = before.attrOpcode,
                overlay = before.overlayId,
                path = before.overlayPath,
                rotation = before.overlayRotation,
                settings = before.settings,
                underlay = underlay
            )
            definition.setTile(0, 0, 0, edited)
            Files.write(file, MapJson.writeTerrain(definition, square))

            val build = Builder.build(tree, output) { println("  edit build $it") }
            println("one file build: $build")
            assertTrue(!build.full, "an edited file must be patched, not repacked from scratch")
            assertEquals(1, build.changedFiles, "only the edited file changed")
            assertEquals(1, build.archives, "only ${square.name}'s archive may be repacked")

            val built = FileStore.open(output).use { store ->
                Container.decode(store.read(Index.MAPS, archive) ?: error("${square.name} is not in the built cache."))
                    .data()
            }
            val decoded = MapTileDecoder.decodeArchive(built, square.region).getTile(0, 0, 0)
            assertEquals(underlay, decoded.underlayId, "the built cache must carry the edited underlay")
            assertEquals(before.height, decoded.height, "nothing but the underlay may have changed")
        } finally {
            output.deleteRecursively()
        }
    }

    /** The layout is the codec's contract with the builder, so it is checked without a cache. */
    @Test
    fun `a map file names its square and maps back to its archive`() {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, 50, 50)
        assertEquals("50_50/terrain.json", MapCodec.path(square))
        assertEquals("50_50/locs.json", MapCodec.path(square.copy(shape = MapSquare.Shape.LOCATIONS)))
        assertEquals("50_50/npcs.json", MapCodec.path(square.copy(shape = MapSquare.Shape.NPCS)))
        assertEquals(
            "50_50/underwater_terrain.json",
            MapCodec.path(square.copy(shape = MapSquare.Shape.UNDERWATER_TERRAIN))
        )
        assertEquals(
            "50_50/underwater_locs.json",
            MapCodec.path(square.copy(shape = MapSquare.Shape.UNDERWATER_LOCATIONS))
        )

        // The archive id lives in index.json, so the path alone can never answer.
        assertNull(MapCodec.archiveOf("50_50/terrain.json"))

        val directory = Files.createTempDirectory(scratch(), "map-lookup")
        try {
            val metadata = IndexMetadata(Index.MAPS, named = true)
            metadata.archives[7] = ArchiveMetadata(nameHash = MapSquare.terrain(50, 50).hashCode())
            metadata.archives[9] = ArchiveMetadata(nameHash = MapSquare.underwaterLocations(50, 50).hashCode())
            Files.writeString(directory.resolve(SourceTree.INDEX_FILE), metadata.write())

            assertEquals(7, MapCodec.archiveOf(directory, "50_50/terrain.json"))
            assertEquals(7, MapCodec.archiveOf(directory, "50_50/terrain.json.pristine"))
            assertEquals(9, MapCodec.archiveOf(directory, "50_50/underwater_locs.json"))
            // A square the index does not hold, a shape that is not one of the five, and the
            // framework's own files, none of which are this codec's.
            assertNull(MapCodec.archiveOf(directory, "50_51/terrain.json"))
            assertNull(MapCodec.archiveOf(directory, "50_50/heights.json"))
            assertNull(MapCodec.archiveOf(directory, "50_50/terrain.dat"))
            assertNull(MapCodec.archiveOf(directory, "1234.container"))
            assertNull(MapCodec.archiveOf(directory, "terrain.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Counts {
        var files = 0
        var identical = 0
        var pristine = 0
    }

    /** [archive]'s decompressed group, or null when nothing can open it. */
    private fun group(cache: Cache, archive: Int, square: MapSquare.Square, keys: Map<Int, IntArray>): ByteArray? {
        val bytes = cache.container(Index.MAPS, archive) ?: return null
        return try {
            Container.decode(bytes, if (square.shape.encrypted) keys[square.region] else null).data()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The cache with every index but the maps hidden, so an unpack writes a tree of index 5 alone.
     *
     * [Unpacker] skips an index with no reference table, which is the whole trick; everything else
     * is the real cache.
     */
    private class OnlyMaps(private val cache: Cache) : Cache by cache {
        override fun referenceTable(index: Int): ReferenceTable? =
            if (index == Index.MAPS) cache.referenceTable(index) else null
    }

    private fun size(root: Path): Pair<Int, Long> {
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

    /** Hundreds of megabytes of tree do not belong in a tmpfs; the build directory is on disk. */
    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun cache(): Cache? {
        if (cacheDirectory() == null) {
            return null
        }
        return Cache.get()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping map codec parity; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        /** Index 5's archives in the 727 cache: 2,407 terrain, 2,407 locations, 38 npc spawns, 2,416 underwater. */
        private const val ARCHIVES = 7268

        /** The `l<x>_<y>` archives `data/map/xteaKeys.json` has no working key for. */
        private const val OPAQUE = 8

        private const val TERRAIN = 2407
        private const val UNDERWATER = 1208
        private const val NPCS = 38

        private const val EXAMPLES = 5

        /** Lumbridge, which is in every 727 cache, and the highest underlay an opcode can carry. */
        private const val EDITED_X = 50
        private const val EDITED_Y = 50
        private const val EDITED_UNDERLAY = 174
    }
}
