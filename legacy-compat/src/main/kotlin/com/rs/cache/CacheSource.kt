package com.rs.cache

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.SourceTree

/** Builds through the modern core and CS2 provider; preserves gameplay's Store contract. */
object CacheSource {
    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun ensureBuilt(packedPath: String): String {
        if (System.getenv("DRYNOR_CACHE_BUILD") == "false") return packedPath
        val configuredSource = System.getenv("CACHE_SOURCE_PATH")?.takeIf { it.isNotBlank() }
        val source = Path.of(configuredSource ?: "../cache-unpacked").toAbsolutePath().normalize()
        if (!Files.isRegularFile(source.resolve("cache.json"))) {
            if (configuredSource != null) throw IOException("Missing cache.json in CACHE_SOURCE_PATH: $source")
            return packedPath
        }
        val output = Path.of(System.getenv("CACHE_BUILD_PATH")?.takeIf { it.isNotBlank() } ?: packedPath)
            .toAbsolutePath().normalize()
        if (source == output || output.startsWith(source) || source.startsWith(output))
            throw IOException("Cache source and build output must be separate directories")
        Files.createDirectories(output.parent)
        val lockPath = output.resolveSibling(output.fileName.toString() + ".build.lock")
        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            channel.lock().use {
                Builder.build(SourceTree.open(source.toFile()), output.toFile()) { println("[cache] $it") }
                if (!Files.isRegularFile(output.resolve("main_file_cache.dat2")))
                    throw IOException("Cache builder did not produce main_file_cache.dat2")
            }
        }
        // Legacy Store concatenates filenames directly to its path.
        return output.toString() + java.io.File.separator
    }
}
