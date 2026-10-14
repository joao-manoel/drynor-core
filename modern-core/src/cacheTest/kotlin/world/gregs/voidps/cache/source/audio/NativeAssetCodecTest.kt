package world.gregs.voidps.cache.source.audio

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.NativeLibrary
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.codec.AudioCodecs
import world.gregs.voidps.cache.source.codec.ShaderCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Indices 30 and 31 - the two indices whose archives and files have names - through their codecs.
 *
 * The point of both codecs is the name, so the test measures two things: that every archive and
 * every file gets one (no `<id>.dat` fallbacks left over), and that packing the named tree back
 * reproduces every archive byte for byte.
 */
class NativeAssetCodecTest {

    @Test
    fun `every native library is stored under its own name`() {
        val cache = AudioParity.cache() ?: return
        val directory = AudioParity.scratch("dlls")
        try {
            write(directory, cache, Index.NATIVE_LIBRARIES)
            val report = AudioParity.roundTrip(cache, Index.NATIVE_LIBRARIES, AudioCodecs.nativeLibraries, directory)
            println(report)
            val names = files(directory).map { directory.relativize(it).joinToString("/") { part -> part.name } }
            println("  ${names.size} files, e.g. ${names.sorted().take(3)}")
            assertEquals(LIBRARIES, report.archives)
            assertEquals(report.archives, report.identical, "every native library must pack back identically")
            assertEquals(0, report.pristine)
            assertEquals(LIBRARIES, names.count { it.count { char -> char == '/' } == 2 }, "every archive is named")
            assertTrue(names.contains("windows/x86/hw3d.dll"), "archive 1 is windows/x86/hw3d.dll: $names")
            assertTrue(names.contains("macos/universal/libhw3d.dylib"))
            assertTrue(names.contains("linux/x86_64/libjaggl.so"))
        } finally {
            cache.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the native library formula covers every named archive`() {
        val cache = AudioParity.cache() ?: return
        try {
            val table = cache.referenceTable(Index.NATIVE_LIBRARIES)!!
            val unnamed = table.archives.filter { NativeLibrary.name(it.nameHash) == null }
            assertEquals(emptyList(), unnamed.map { it.id }, "these archives' hashes match no name the formula builds")
            assertEquals(LIBRARIES, table.archives.size)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every shader file is stored under the name its archive spells out`() {
        val cache = AudioParity.cache() ?: return
        val directory = AudioParity.scratch("shaders")
        try {
            write(directory, cache, Index.SHADERS)
            val report = AudioParity.roundTrip(cache, Index.SHADERS, ShaderCodec, directory)
            println(report)
            val names = files(directory).map { directory.relativize(it).joinToString("/") { part -> part.name } }
            println("  ${names.size} files, e.g. ${names.sorted().take(4)}")
            assertEquals(ARCHIVES, report.archives)
            assertEquals(report.archives, report.identical, "both shader archives must pack back identically")
            assertEquals(0, report.pristine, "no program definition may need a pristine copy")
            assertEquals(FILES, names.size)
            assertEquals(emptyList(), names.filter { it.endsWith(".dat") }, "every file's name must be recovered")
            assertTrue(names.contains("gl/basic.vertex.glsl"), "gl file 7 is basic.vertex.glsl")
            assertTrue(names.contains("gl/basic.json"), "gl file 3 is the Basic program definition")
            assertTrue(names.contains("dx/basic.json"), "dx file 10 is the Basic program definition")
            assertTrue(names.any { it.startsWith("dx/") && it.contains(".vs_2_0") }, "dx holds vs_2_0 sources")
        } finally {
            cache.close()
            directory.toFile().deleteRecursively()
        }
    }

    /** The metadata the two codecs read back: which archive is named what, and which file is. */
    private fun write(directory: Path, cache: Cache, index: Int) {
        val table = cache.referenceTable(index)!!
        val metadata = IndexMetadata(index = index, format = table.format, revision = table.revision, named = true)
        for (entry in table.archives) {
            val archive = ArchiveMetadata(
                version = entry.version,
                nameHash = entry.nameHash
            )
            val fileNames = entry.files.filter { it.nameHash != 0 }
            if (fileNames.isNotEmpty()) {
                archive.fileNames = fileNames.associate { it.id to it.nameHash }
            }
            metadata.archives[entry.id] = archive
        }
        SourceFiles.write(
            directory.resolve(SourceTree.INDEX_FILE),
            metadata.write().toByteArray(Charsets.UTF_8)
        )
    }

    private fun files(directory: Path): List<Path> {
        Files.walk(directory).use { stream ->
            return stream.filter { Files.isRegularFile(it) && it.name != SourceTree.INDEX_FILE }.toList()
        }
    }

    private companion object {
        private const val LIBRARIES = 33
        private const val ARCHIVES = 2
        private const val FILES = 112
    }
}
