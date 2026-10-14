package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.AnimationGltf
import world.gregs.voidps.cache.source.codec.Gltf
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every frame set of index 0 out through its `.glb` and back, with nothing on disk.
 *
 * [world.gregs.voidps.cache.definition.MiscEncoderParityTest] proves `encode(decode(bytes)) ==
 * bytes` frame by frame and [MiscCodecTest] proves the whole trip through a real directory; this is
 * the one that proves the *file format* - `encode(read(write(decode(group)))) == group` for every
 * archive, chunk table and all - and it is where the numbers `docs/cache-source.md`'s *anims*
 * section quotes are measured: what a `.glb` costs against the group it replaces, and what the two
 * directions cost in time.
 *
 * Two checks per archive, both on every one of them:
 *
 * - **the group comes back byte for byte**, which is the parity bar (zero pristine sidecars), and
 * - **every frame comes back field for field**, so a value the extras dropped or reordered is named
 *   as a frame rather than as a byte offset.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class AnimationGltfParityTest {

    @Test
    fun `every frame set round trips through its glb`() {
        val cache = open() ?: return
        val decoder = AnimationFrameDecoder()
        var archives = 0
        var frames = 0
        var identical = 0
        var multi = 0
        var single = 0
        var raw = 0L
        var tree = 0L
        var extras = 0L
        var samplers = 0L
        var unpackNanos = 0L
        var packNanos = 0L
        try {
            val ids = cache.archiveIds(Index.ANIMATION_FRAMES)
            for (archive in ids) {
                val source = assertNotNull(source(cache, archive), "frame set $archive is not in the cache")
                archives++
                frames += source.fileIds.size
                raw += source.group.size
                if (source.fileIds.size == 1) {
                    single++
                } else {
                    multi++
                }

                var start = System.nanoTime()
                val glb = AnimationFrameCodec.glb(source)
                unpackNanos += System.nanoTime() - start
                tree += glb.size
                val chunks = Gltf.chunks(glb)
                extras += chunks.first.size
                samplers += chunks.second.size

                start = System.nanoTime()
                val set = AnimationGltf.read(glb, archive)
                val rebuilt = AnimationFrameCodec.encode(set)
                packNanos += System.nanoTime() - start

                if (rebuilt.contentEquals(source.group)) {
                    identical++
                } else {
                    val offset = (0 until minOf(source.group.size, rebuilt.size))
                        .firstOrNull { source.group[it] != rebuilt[it] } ?: -1
                    println(
                        "  frame set $archive differs: ${source.group.size} bytes became " +
                            "${rebuilt.size}, first difference at $offset"
                    )
                }

                assertEquals(
                    source.fileIds.size,
                    set.frames.size,
                    "frame set $archive lost frames on the way through its glb"
                )
                assertTrue(
                    set.fileIds().contentEquals(source.fileIds),
                    "frame set $archive came back with the file ids ${set.fileIds().toList()} " +
                        "rather than ${source.fileIds.toList()}"
                )
                for ((position, file) in source.fileIds.withIndex()) {
                    val expected = AnimationFrameDefinition((archive shl AnimationFrameDecoder.BITS) or file)
                    decoder.readLoop(expected, BufferReader(source.files[position]))
                    assertEquals(
                        expected,
                        set.frames[position],
                        "frame set $archive frame $file changed on the way through its glb"
                    )
                }
            }
            println("%-14s %9s %9s %9s %9s %9s".format("codec", "archives", "frames", "identical", "raw MB", "glb MB"))
            println(
                "%-14s %9d %9d %9d %9d %9d".format(
                    AnimationFrameCodec.id, archives, frames, identical, raw / 1_000_000, tree / 1_000_000
                )
            )
            println(
                "  $multi three chunk archives, $single single frame archives, " +
                    "${tree / archives} bytes a file on average"
            )
            println(
                "  ${extras / 1_000_000} MB of glTF JSON (the nodes and the extras) and " +
                    "${samplers / 1_000_000} MB of samplers"
            )
            println("  unpack ${unpackNanos / 1_000_000} ms, pack ${packNanos / 1_000_000} ms, single threaded")
            assertEquals(ids.size, archives, "every frame set index 0 holds has to be round tripped")
            assertEquals(archives, multi + single, "every frame set is either a three chunk group or a single frame")
            assertTrue(
                archives >= VANILLA_ARCHIVES,
                "$archives frame sets, fewer than the $VANILLA_ARCHIVES of vanilla 727"
            )
            assertTrue(frames >= VANILLA_FRAMES, "$frames frames, fewer than the $VANILLA_FRAMES of vanilla 727")
            assertEquals(archives, identical, "every frame set has to pack back byte for byte")
        } finally {
            close(cache)
        }
    }

    /**
     * One frame set's `.glb`, reported: its size, its chunks and its whole glTF JSON.
     *
     * A worked example belongs in a test rather than only in a document, because a test is what
     * prints it again when the spelling changes: `docs/cache-source.md`'s *anims* excerpt is this
     * output. Archive 99 is two frames over a six transform skeleton of origins and rotations, which
     * is small enough to read whole and still shows a channel, a shared keyframe time accessor and
     * the extras.
     */
    @Test
    fun `a frame set reads as a whole file`() {
        val cache = open() ?: return
        try {
            val source = assertNotNull(source(cache, EXAMPLE), "frame set $EXAMPLE is not in the cache")
            val glb = AnimationFrameCodec.glb(source)
            val asset = AnimationGltf.json(glb)
            println("anims/$EXAMPLE${AnimationFrameCodec.EXTENSION}: ${glb.size} bytes, ${source.group.size} in the cache")
            println("  glTF JSON chunk ${asset.length} bytes before padding")
            println("  $asset")
            assertTrue(asset.startsWith("{\"asset\":"), "the JSON chunk must be a glTF asset")
            // The nodes are the base's label groups, and the animation is the frames.
            assertTrue(asset.contains("\"animations\":[{\"name\":\"frameset_$EXAMPLE\""), asset)
            assertTrue(asset.contains("\"path\":\"rotation\""), asset)
            val set = AnimationGltf.read(glb, EXAMPLE)
            assertEquals(source.fileIds.size, set.frames.size)
            assertEquals(AnimationGltf.CHUNKS, set.chunks, "a two frame group is three chunks")
            assertEquals(AnimationGltf.HEADER, set.header)
            assertTrue(
                AnimationFrameCodec.encode(set).contentEquals(source.group),
                "the example has to pack back byte for byte too"
            )
        } finally {
            close(cache)
        }
    }

    /** Re-unpacking an unchanged cache must rewrite nothing, which means the same bytes twice. */
    @Test
    fun `a frame set glb is deterministic`() {
        val cache = open() ?: return
        try {
            for (archive in DETERMINISTIC) {
                val source = assertNotNull(source(cache, archive), "frame set $archive is not in the cache")
                val first = AnimationFrameCodec.glb(source)
                val second = AnimationFrameCodec.glb(source)
                assertTrue(first.contentEquals(second), "frame set $archive wrote two different glb files")
            }
        } finally {
            close(cache)
        }
    }

    /**
     * The unpack itself: one file per archive, no sidecar, and the name the layout says.
     *
     * The round trip above is what proves no archive *needs* a sidecar; this is the check that the
     * codec writes what it says it writes.
     */
    @Test
    fun `an unpacked frame set is one file with no sidecar`() {
        val cache = open() ?: return
        try {
            for (archive in DETERMINISTIC) {
                val source = assertNotNull(source(cache, archive), "frame set $archive is not in the cache")
                val unpacked = AnimationFrameCodec.unpack(Path.of("."), source)
                assertEquals(
                    listOf("$archive${AnimationFrameCodec.EXTENSION}"),
                    unpacked.files.map { it.path },
                    "frame set $archive did not unpack to one glb"
                )
                assertTrue(unpacked.pristine.isEmpty(), "frame set $archive needed a pristine sidecar")
            }
        } finally {
            close(cache)
        }
    }

    /** One archive exactly as the unpacker hands it to a codec. */
    private fun source(cache: Cache, archive: Int): SourceArchive? {
        val container = cache.container(Index.ANIMATION_FRAMES, archive) ?: return null
        val ids = cache.files(Index.ANIMATION_FRAMES, archive)
        val group = Container.decode(container).data()
        val split = ArchiveGroup.split(group, ids)
        val metadata = ArchiveMetadata()
        return SourceArchive(
            index = Index.ANIMATION_FRAMES,
            archive = archive,
            name = null,
            fileIds = ids,
            group = group,
            files = split.files,
            chunks = split.chunks,
            metadata = metadata
        )
    }

    /**
     * The cache, with the codec pointed at it.
     *
     * Index 0's codec reads index 1 to lay a file's nodes out, and it would otherwise open a second
     * cache of its own through `Cache.get`.
     */
    private fun open(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the animation glTF parity check; no cache at ${directory.absolutePath}")
            return null
        }
        SourceCodecs.reset()
        val cache = FileCache.load(directory.absolutePath)
        AnimationFrameCodec.cache = { cache }
        return cache
    }

    private fun close(cache: Cache) {
        AnimationFrameCodec.reset()
        cache.close()
    }

    private companion object {
        /** What vanilla 727 shipped; index 0 only ever grows, so these are floors. */
        private const val VANILLA_ARCHIVES = 4_085
        private const val VANILLA_FRAMES = 490_049

        /** The frame set `docs/cache-source.md`'s *anims* excerpt quotes. */
        private const val EXAMPLE = 99

        /**
         * A handful of frame sets to check the cheap properties on: index 0's first archive, one
         * with sparse file ids (1), the example, the archive whose frames declare 0 transforms and
         * carry 256 flag bytes (162), and one whose skeleton has a transform of every kind (381).
         */
        private val DETERMINISTIC = intArrayOf(0, 1, 99, 162, 381)
    }
}
