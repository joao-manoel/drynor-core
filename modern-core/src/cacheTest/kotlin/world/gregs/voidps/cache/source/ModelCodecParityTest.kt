package world.gregs.voidps.cache.source

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.source.codec.ModelCodec
import world.gregs.voidps.cache.source.codec.ModelCodecs
import world.gregs.voidps.cache.source.codec.ModelGltf
import world.gregs.voidps.cache.source.codec.ParticleCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every archive of indices 7, 27 and 29 through the codec that stores it: models as glTF binaries,
 * particles and billboards as JSON.
 *
 * [world.gregs.voidps.cache.definition.ModelEncoderParityTest] proves `encode(decode(bytes)) ==
 * bytes`; this proves the whole trip the source tree actually makes -
 * `encode(read(write(decode(bytes)))) == bytes` - so a field the JSON cannot spell, an array it
 * reorders or a number it narrows shows up here rather than as a pristine sidecar in a 2 GB tree.
 *
 * The codec is asked for exactly what an unpack asks it for, so a file it could not reproduce
 * comes back with its `.pristine` sidecar and is counted; the bar is zero. It also reports what
 * the JSON costs, which is the size `docs/cache-source.md` quotes.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class ModelCodecParityTest {

    @Test
    fun `every model, particle and billboard round trips through its file`() {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping model JSON parity; no cache at ${directory.absolutePath}")
            return
        }
        SourceCodecs.reset()
        val cache = Cache.get()
        println("%-24s %-6s %9s %9s %9s %9s %9s".format("codec", "index", "files", "exact", "pristine", "raw MB", "tree MB"))
        val models = count(cache, Index.MODELS, ModelCodec, ModelCodec.id, VANILLA_MODELS)
        val producers = count(cache, Index.PARTICLES, ParticleCodec, ModelCodecs.producers.id, VANILLA_PRODUCERS, archives = intArrayOf(0))
        val particles = count(cache, Index.PARTICLES, ParticleCodec, ModelCodecs.particles.id, VANILLA_PARTICLES, archives = intArrayOf(1))
        val billboards = count(cache, Index.BILLBOARDS, ModelCodecs.billboards, ModelCodecs.billboards.id, VANILLA_BILLBOARDS)
        val all = listOf(models, producers, particles, billboards)
        println(
            "%-24s %-6s %9d %9d %9d %9d %9d".format(
                "total", "", all.sumOf { it.files }, all.sumOf { it.files - it.pristine },
                all.sumOf { it.pristine }, all.sumOf { it.raw } / 1_000_000, all.sumOf { it.tree } / 1_000_000
            )
        )
        for (result in all) {
            assertTrue(
                result.files >= result.vanilla,
                "${result.name}: ${result.files} files is fewer than the ${result.vanilla} of vanilla 727"
            )
            assertEquals(0, result.pristine, "${result.name} files did not survive the JSON round trip")
        }
        assertTrue(models.tree > models.raw, "the editable form is expected to be larger than the bytes it replaces")
    }

    /**
     * One model's `.glb`, reported: its size, its two chunks and the start of its glTF JSON.
     *
     * A worked example belongs in a test rather than only in a document, because a test is what
     * prints it again when the spelling changes: `docs/cache-source.md`'s *models* excerpt is the
     * head of this output.
     */
    @Test
    fun `a model reads as a whole file`() {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping the model JSON example; no cache at ${directory.absolutePath}")
            return
        }
        SourceCodecs.reset()
        val cache = Cache.get()
        val data = assertNotNull(cache.data(Index.MODELS, EXAMPLE, 0), "model $EXAMPLE is not in the cache")
        val metadata = ArchiveMetadata()
        metadata.fileIds(intArrayOf(0))
        val source = SourceArchive(Index.MODELS, EXAMPLE, null, intArrayOf(0), data, listOf(data), emptyList(), metadata)
        val unpacked = ModelCodec.unpack(Path.of("."), source)
        assertEquals(1, unpacked.files.size, "model $EXAMPLE needed a pristine sidecar")
        val glb = unpacked.files[0].bytes
        val asset = ModelGltf.json(glb)
        println("models/$EXAMPLE${ModelCodec.EXTENSION}: ${glb.size} bytes, ${data.size} in the cache")
        println("  glTF JSON chunk ${asset.length} bytes before padding")
        println("  ${asset.take(HEAD)}")
        println("  ...")
        assertTrue(asset.startsWith("{\"asset\":"), "the JSON chunk must be a glTF asset")
        assertEquals(ModelGltf.read(glb, EXAMPLE).vertexCount, ModelDecoder.read(EXAMPLE, data).vertexCount)
    }

    @Test
    fun `a model file is named by its id and maps back to its archive`() {
        assertEquals(1234, ModelCodec.archiveOf("1234${ModelCodec.EXTENSION}"))
        assertEquals(1234, ModelCodec.archiveOf("1234${ModelCodec.EXTENSION}${SourceFiles.PRISTINE_EXTENSION}"))
        assertEquals(null, ModelCodec.archiveOf("1234.dat"))
        assertEquals(null, ModelCodec.archiveOf("nested/1234${ModelCodec.EXTENSION}"))

        assertEquals(0, ParticleCodec.archiveOf("${ModelCodecs.PRODUCER_DIRECTORY}/12.json"))
        assertEquals(1, ParticleCodec.archiveOf("${ModelCodecs.PARTICLE_DIRECTORY}/12.json"))
        assertEquals(null, ParticleCodec.archiveOf("12.json"))

        assertEquals(0, ModelCodecs.billboards.archiveOf("359.json"))
    }

    @Test
    fun `the three indices claim their codecs`() {
        SourceCodecs.reset()
        assertEquals(ModelCodec, SourceCodecs.codec(Index.MODELS))
        assertEquals(ParticleCodec, SourceCodecs.codec(Index.PARTICLES))
        assertEquals(ModelCodecs.billboards, SourceCodecs.codec(Index.BILLBOARDS))
        assertEquals(ModelCodecs.producers, ParticleCodec.codec(0))
        assertEquals(ModelCodecs.particles, ParticleCodec.codec(1))
    }

    /** One codec's files, how many needed the shipped bytes beside them, and what they cost. */
    private class Result(
        val name: String,
        val vanilla: Int,
        val files: Int,
        val pristine: Int,
        val raw: Long,
        val tree: Long
    )

    /**
     * Run [codec] over every archive of [index] exactly as an unpack does, and count.
     *
     * [archives] narrows it to one archive of an index whose archives are different types, which
     * is how index 27's two are reported separately.
     */
    private fun count(
        cache: Cache,
        index: Int,
        codec: SourceCodec,
        name: String,
        vanilla: Int,
        archives: IntArray? = null
    ): Result {
        var files = 0
        var pristine = 0
        var raw = 0L
        var tree = 0L
        val start = System.nanoTime()
        for (archive in archives ?: cache.archiveIds(index)) {
            val ids = cache.files(index, archive)
            val data = ArrayList<ByteArray>(ids.size)
            for (file in ids) {
                data.add(assertNotNull(cache.data(index, archive, file), "$name archive $archive has no file $file"))
            }
            files += ids.size
            raw += data.sumOf { it.size.toLong() }
            val metadata = ArchiveMetadata()
            metadata.fileIds(ids)
            val group = if (data.size == 1) data[0] else ArchiveGroup.join(data)
            val source = SourceArchive(index, archive, null, ids, group, data, emptyList(), metadata)
            val unpacked = codec.unpack(Path.of("."), source)
            pristine += unpacked.pristine.size
            tree += unpacked.files.filter { !SourceFiles.isPristine(it.path) }.sumOf { it.bytes.size.toLong() }
            assertEquals(
                unpacked.pristine.size,
                unpacked.files.count { SourceFiles.isPristine(it.path) },
                "$name archive $archive recorded a pristine hash without a sidecar, or the other way round"
            )
        }
        println(
            "%-24s %-6d %9d %9d %9d %9d %9d  (%d ms)".format(
                name, index, files, files - pristine, pristine, raw / 1_000_000, tree / 1_000_000,
                (System.nanoTime() - start) / 1_000_000
            )
        )
        return Result(name, vanilla, files, pristine, raw, tree)
    }

    private companion object {
        /** What vanilla 727 shipped; the tree only ever grows, so these are floors. */
        private const val VANILLA_MODELS = 73_417
        private const val VANILLA_PRODUCERS = 741
        private const val VANILLA_PARTICLES = 18
        private const val VANILLA_BILLBOARDS = 360

        /** The model `docs/cache-source.md`'s *models* excerpt quotes. */
        private const val EXAMPLE = 9808

        /** How much of its glTF JSON to print: enough to show the asset and the start of the extras. */
        private const val HEAD = 640
    }
}
