package world.gregs.voidps.cache.source

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.ArchiveDirectoryCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.WorldMapCodec
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The eight indices `MiscCodecs` claims, each unpacked and packed back through its own codec.
 *
 * The strongest form of the check there is short of a whole tree: every archive of every one of these
 * indices is decoded, written to disk exactly as an unpack would write it, read back off disk and packed,
 * and the group that comes out has to be the group that went in - **byte for byte, chunk table and all**.
 * So it proves the decoders, the encoders, the JSON mapper, the file layout and, for index 0, the three
 * chunk split, all at once, and it reports the per index numbers `docs/cache-source.md` quotes.
 *
 * The bar is every archive identical and no pristine sidecar. Both are asserted, so a residual cannot
 * appear without the build going red, and neither can grow.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class MiscCodecTest {

    @Test
    fun `every archive of every misc index packs back byte for byte`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val cache = FileCache.load(packed.absolutePath)
        // Index 0's codec reads the skeletons to lay its nodes out; point it at this cache rather
        // than letting it open a second one through Cache.get.
        AnimationFrameCodec.cache = { cache }
        val work = Files.createTempDirectory(scratch(), "misc-codec")
        var archives = 0
        var files = 0
        var pristine = 0
        var bytes = 0L
        try {
            println("%-20s %-6s %9s %9s %9s %9s %9s".format("index", "id", "archives", "files", "identical", "pristine", "tree KB"))
            for (index in INDICES) {
                val counts = index(cache, index, work.resolve(IndexNames.name(index)))
                archives += counts.archives
                files += counts.files
                pristine += counts.pristine
                bytes += counts.bytes
                println(
                    "%-20s %-6d %9d %9d %9d %9d %9d".format(
                        IndexNames.name(index), index, counts.archives, counts.files,
                        counts.identical, counts.pristine, counts.bytes / 1024
                    )
                )
                assertEquals(
                    cache.archiveIds(index).size,
                    counts.archives,
                    "${IndexNames.name(index)} left archives out of the round trip"
                )
                assertEquals(
                    counts.archives,
                    counts.identical,
                    "${IndexNames.name(index)} did not pack back byte for byte"
                )
                assertEquals(0, counts.pristine, "${IndexNames.name(index)} needed a pristine sidecar")
            }
            println("%-20s %-6s %9d %9d %9d %9d %9d".format("total", "", archives, files, archives, pristine, bytes / 1024))
            assertTrue(
                archives >= VANILLA_ARCHIVES,
                "$archives archives, fewer than the $VANILLA_ARCHIVES of vanilla 727"
            )
            assertTrue(files >= VANILLA_FILES, "$files files, fewer than the $VANILLA_FILES of vanilla 727")
            assertEquals(0, pristine, "no file of any of these indices may need a pristine copy")
        } finally {
            AnimationFrameCodec.reset()
            cache.close()
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Index 0's groups are stored in three chunks and the codec derives the split from the frames rather
     * than carrying it in `index.json`, so it is worth saying out loud what the split is: the four byte
     * header, the flag byte per transform, and the transform values.
     */
    @Test
    fun `an animation frame group keeps its three chunks`() {
        val packed = cacheDirectory() ?: return
        val cache = FileCache.load(packed.absolutePath)
        try {
            var multi = 0
            var singleFile = 0
            val ids = cache.archiveIds(Index.ANIMATION_FRAMES)
            for (archive in ids) {
                val ids = cache.files(Index.ANIMATION_FRAMES, archive)
                val group = Container.decode(cache.container(Index.ANIMATION_FRAMES, archive)!!).data()
                val split = ArchiveGroup.split(group, ids)
                if (ids.size == 1) {
                    singleFile++
                    assertEquals(1, split.chunks.size, "a one file group has no chunk table")
                    continue
                }
                multi++
                assertEquals(FRAME_CHUNKS, split.chunks.size, "frame set $archive is not three chunks")
                for (position in ids.indices) {
                    assertEquals(
                        FRAME_HEADER,
                        split.chunks[0][position],
                        "frame set $archive frame ${ids[position]} has a header of another length"
                    )
                }
            }
            println("index 0: $multi three chunk archives, $singleFile single file archives")
            assertEquals(ids.size, multi + singleFile, "every frame set is either three chunks or one file")
            assertTrue(
                multi >= VANILLA_MULTI_CHUNK_ARCHIVES,
                "$multi three chunk frame sets, fewer than the $VANILLA_MULTI_CHUNK_ARCHIVES of vanilla 727"
            )
        } finally {
            cache.close()
        }
    }

    /**
     * Index 23's directory names are the archives' recovered names, and a name is only a name because it
     * hashes to what the reference table holds. That is checked on every unpack; this is the check that
     * the table covers the cache rather than only agreeing with itself.
     */
    @Test
    fun `every world map archive name hashes to the cache's own`() {
        val packed = cacheDirectory() ?: return
        val cache = FileCache.load(packed.absolutePath)
        try {
            val hashes = cache.archiveNameHashes(Index.WORLD_MAP)
            assertTrue(
                hashes.size >= VANILLA_WORLD_MAP_ARCHIVES,
                "${hashes.size} named archives, fewer than the $VANILLA_WORLD_MAP_ARCHIVES of vanilla 727"
            )
            for ((archive, hash) in hashes) {
                val name = WorldMapCodec.name(archive)
                assertEquals(hash, name.hashCode(), "index 23 archive $archive is not '$name'")
            }
            assertEquals("details", WorldMapCodec.name(cache.archiveId(Index.WORLD_MAP, "details")))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every misc index has its codec`() {
        SourceCodecs.reset()
        assertEquals(AnimationFrameCodec, SourceCodecs.codec(Index.ANIMATION_FRAMES))
        assertEquals(WorldMapCodec, SourceCodecs.codec(Index.WORLD_MAP))
        assertEquals(
            SourceCodecs.codec(Index.QUICK_CHAT_MESSAGES),
            SourceCodecs.codec(Index.QUICK_CHAT_MENUS),
            "both quick chat indices hold the same two archives"
        )
        for (index in INDICES) {
            assertTrue(
                SourceCodecs.codec(index).id != "raw-archive",
                "${IndexNames.name(index)} still has the fallback codec"
            )
        }
        // The layouts, spelled out so a rename shows up here rather than as a rebuilt tree.
        assertEquals(12, AnimationFrameCodec.archiveOf("12${AnimationFrameCodec.EXTENSION}"))
        assertEquals(
            12,
            AnimationFrameCodec.archiveOf("12${AnimationFrameCodec.EXTENSION}${SourceFiles.PRISTINE_EXTENSION}")
        )
        assertEquals(null, AnimationFrameCodec.archiveOf("12.json"))
        assertEquals(null, AnimationFrameCodec.archiveOf("12/5${AnimationFrameCodec.EXTENSION}"))
        assertEquals(1, WorldMapCodec.archiveOf("details/29.json"))
        assertEquals(83, WorldMapCodec.archiveOf("main_staticelements/0.json"))
        assertEquals(29, WorldMapCodec.archiveOf("main/4.dat"))
        val defaults = SourceCodecs.codec(Index.DEFAULTS) as ArchiveDirectoryCodec
        assertEquals("equipment", defaults.name(6))
        assertEquals(6, defaults.archiveOf("equipment.json"))
        assertEquals(6, defaults.archiveOf("equipment.json.pristine"))
        val quickChat = SourceCodecs.codec(Index.QUICK_CHAT_MESSAGES) as ArchiveDirectoryCodec
        assertEquals(0, quickChat.archiveOf("quickchatoption/12.json"))
        assertEquals(1, quickChat.archiveOf("quickchatphrase/12.json"))
        val huffman = SourceCodecs.codec(Index.HUFFMAN) as ArchiveDirectoryCodec
        assertEquals(1, huffman.archiveOf("huffman.json"))
    }

    /** One index, archive by archive, through its own codec and a real directory. */
    private fun index(cache: Cache, index: Int, directory: Path): Counts {
        val codec = SourceCodecs.codec(index)
        Files.createDirectories(directory)
        val counts = Counts()
        for (archive in cache.archiveIds(index)) {
            val container = cache.container(index, archive) ?: continue
            val ids = cache.files(index, archive)
            val group = Container.decode(container).data()
            val split = ArchiveGroup.split(group, ids)
            val metadata = ArchiveMetadata(nameHash = cache.archiveNameHashes(index)[archive] ?: 0)
            if (codec.fileIdsFromMetadata(archive)) {
                metadata.fileIds(ids)
            }
            val source = SourceArchive(index, archive, null, ids, group, split.files, split.chunks, metadata)
            val unpacked = codec.unpack(directory, source)
            metadata.pristine = unpacked.pristine
            counts.archives++
            counts.pristine += unpacked.pristine.size
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
                if (!SourceFiles.isPristine(file.path)) {
                    counts.files++
                    counts.bytes += file.bytes.size
                }
            }
            if (codec.pack(directory, archive, metadata).group.contentEquals(group)) {
                counts.identical++
            } else {
                report(index, archive, codec, directory, metadata, group)
            }
        }
        return counts
    }

    /** Say which archive differed and where, so a failure names the byte rather than the index. */
    private fun report(
        index: Int,
        archive: Int,
        codec: SourceCodec,
        directory: Path,
        metadata: ArchiveMetadata,
        group: ByteArray
    ) {
        val rebuilt = codec.pack(directory, archive, metadata).group
        val offset = (0 until minOf(group.size, rebuilt.size)).firstOrNull { group[it] != rebuilt[it] } ?: -1
        println(
            "  ${IndexNames.name(index)} archive $archive differs: ${group.size} bytes became " +
                "${rebuilt.size}, first difference at $offset"
        )
    }

    private class Counts {
        var archives = 0
        var identical = 0
        var files = 0
        var pristine = 0
        var bytes = 0L
    }

    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the misc codec parity check; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {

        /** The indices `MiscCodecs` claims, ascending. */
        private val INDICES = intArrayOf(
            Index.ANIMATION_FRAMES,
            Index.ANIMATION_SKELETONS,
            Index.HUFFMAN,
            Index.WORLD_MAP,
            Index.QUICK_CHAT_MESSAGES,
            Index.QUICK_CHAT_MENUS,
            Index.DEFAULTS,
            Index.GAME_TIPS,
            Index.CUTSCENES
        )

        /** What vanilla 727 shipped: 4,085 + 3,535 + 1 + 86 + 2 + 2 + 6 + 31 + 16. */
        private const val VANILLA_ARCHIVES = 7764

        /**
         * What vanilla 727 shipped: 4,085 + 3,535 + 1 + 1,181 + 1,372 + 86 + 6 + 31 + 16.
         *
         * Index 0 is 4,085 files rather than 490,049 because a frame set is one glTF animation
         * (`anims/<archive>.glb`) rather than a JSON file per frame - see *anims*.
         */
        private const val VANILLA_FILES = 10313

        private const val FRAME_CHUNKS = 3
        private const val FRAME_HEADER = 4
        private const val VANILLA_MULTI_CHUNK_ARCHIVES = 4074
        private const val VANILLA_WORLD_MAP_ARCHIVES = 86
    }
}
