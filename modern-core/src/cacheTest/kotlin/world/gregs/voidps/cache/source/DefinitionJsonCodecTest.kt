package world.gregs.voidps.cache.source

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.ConfigCodec
import world.gregs.voidps.cache.source.codec.DefinitionCodecs
import world.gregs.voidps.cache.source.codec.DefinitionLayout
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every definition file of every JSON typed index, through the codec that stores it.
 *
 * The three encoder parity tests prove `encode(decode(bytes)) == bytes`; this proves the whole trip the
 * source tree actually makes - `encode(read(write(decode(bytes)))) == bytes` - so a field the JSON mapper
 * cannot spell, a map it reorders or a number it narrows shows up here rather than as a pristine sidecar
 * in a 2 GB tree. It is also where the per type counts in `docs/cache-source.md` come from.
 *
 * The codec is asked for exactly what an unpack asks it for, so a file it could not reproduce comes back
 * with its `.pristine` sidecar and is counted; the bar is zero.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class DefinitionJsonCodecTest {

    @Test
    fun `every definition round trips through its JSON file`() {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping definition JSON parity; no cache at ${directory.absolutePath}")
            return
        }
        val cache = Cache.get()
        var files = 0
        var pristine = 0
        println("%-11s %-6s %9s %9s %9s".format("type", "index", "files", "exact", "pristine"))
        for (type in DefinitionCodecs.types) {
            val counts = count(cache, type)
            files += counts.first
            pristine += counts.second
            println(
                "%-11s %-6s %9d %9d %9d".format(
                    type.name,
                    if (type.archive < 0) "${type.index}" else "${type.index}/${type.archive}",
                    counts.first, counts.first - counts.second, counts.second
                )
            )
            assertEquals(0, counts.second, "${type.name} files did not survive the JSON round trip")
        }
        println("%-11s %-6s %9d %9d %9d".format("total", "", files, files - pristine, pristine))
        assertTrue(files > 240_000, "only $files definition files were compared")
        assertEquals(0, pristine, "no definition may need a pristine copy")
    }

    /** [type]'s files and how many of them the codec had to keep the shipped bytes for. */
    private fun count(cache: Cache, type: DefinitionCodecs.Type): Pair<Int, Int> {
        var files = 0
        var pristine = 0
        val archives = if (type.archive < 0) cache.archiveIds(type.index) else intArrayOf(type.archive)
        for (archive in archives) {
            val ids = cache.files(type.index, archive)
            val data = ArrayList<ByteArray>(ids.size)
            for (file in ids) {
                data.add(cache.data(type.index, archive, file) ?: continue)
            }
            if (data.size != ids.size) {
                continue
            }
            files += ids.size
            val metadata = ArchiveMetadata()
            val source = SourceArchive(type.index, archive, null, ids, ByteArray(0), data, emptyList(), metadata)
            val unpacked = type.codec.unpack(Path.of("."), source)
            pristine += unpacked.pristine.size
            assertEquals(
                unpacked.pristine.size,
                unpacked.files.count { it.path.endsWith(PRISTINE) },
                "${type.name} archive $archive recorded a pristine hash without a sidecar, or the other way round"
            )
        }
        return files to pristine
    }

    @Test
    fun `a definition file is named by its id and maps back to its archive`() {
        val obj = DefinitionCodecs.types.first { it.name == "obj" }.codec
        assertEquals(16, obj.archiveOf("4151${DefinitionLayout.EXTENSION}"))
        assertEquals(16, obj.archiveOf("4151${DefinitionLayout.EXTENSION}.pristine"))
        assertEquals(null, obj.archiveOf("4151.dat"))
        assertEquals(null, obj.archiveOf("nested/4151${DefinitionLayout.EXTENSION}"))

        val component = DefinitionCodecs.types.first { it.name == "interface" }.codec
        assertEquals(1477, component.archiveOf("1477/32${DefinitionLayout.EXTENSION}"))
        assertEquals(1477, component.archiveOf("1477/32${DefinitionLayout.EXTENSION}.pristine"))
        assertEquals(null, component.archiveOf("1477${DefinitionLayout.EXTENSION}"))

        // Index 2's sub-codecs are inside a directory that is the archive, so only ConfigCodec knows which.
        val struct = DefinitionCodecs.types.first { it.name == "struct" }.codec
        assertEquals(null, struct.archiveOf("26${DefinitionLayout.EXTENSION}"))
    }

    @Test
    fun `every typed index has a codec registered`() {
        for (type in DefinitionCodecs.types) {
            val codec = if (type.archive < 0) {
                SourceCodecs.codec(type.index)
            } else {
                ConfigCodec.codec(type.archive)
            }
            assertEquals(type.codec, codec, "${type.name} is not the codec for its index")
        }
        assertEquals(
            DefinitionCodecs.types.count { it.archive < 0 },
            DefinitionCodecs.types.count { it.archive < 0 && it.index != Index.CONFIGS },
            "every whole index type is its own index"
        )
    }

    private companion object {
        private const val PRISTINE = ".pristine"
    }
}
