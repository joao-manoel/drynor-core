package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.cache.source.BuildResult
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.SourceTree
import java.io.File

/**
 * The server's switch between a prepacked cache and a cache source tree (`docs/cache-source.md`).
 *
 * With `CACHE_SOURCE_PATH` unset nothing happens and [EnvVars.cachePath] is the prepacked cache.
 * With it set, [ensureBuilt] builds the tree into `CACHE_BUILD_PATH` - a full pack the first
 * time, afterwards only the archives whose inputs changed, a stat walk when nothing did - and
 * [EnvVars.cachePath] is that build directory. `Cache.get()` calls [ensureBuilt] before it opens
 * anything, so no caller has to remember to; calling it early (a server's `main`) only moves the
 * log lines to where they are expected.
 */
object CacheSource {

    @Volatile
    private var result: BuildResult? = null

    /** The tree the server serves from, or null when it serves a prepacked cache. */
    val tree: File? = EnvVars.cacheSourcePath?.let { File(it) }

    /** Whether the server is configured to build from a source tree. */
    val enabled: Boolean get() = tree != null

    /** What the last [ensureBuilt] did, once it has run. */
    val lastBuild: BuildResult? get() = result

    /**
     * Builds the configured tree into [EnvVars.cacheBuildPath] if it has not been built by this
     * process yet. Idempotent and thread-safe; a no-op without `CACHE_SOURCE_PATH`.
     */
    @Synchronized
    fun ensureBuilt(): BuildResult? {
        val directory = tree ?: return null
        result?.let { return it }
        check(directory.isDirectory) { "CACHE_SOURCE_PATH '${directory.absolutePath}' is not a directory" }
        val output = File(EnvVars.cacheBuildPath)
        logInfo("Building cache from source tree ${directory.absolutePath} into ${output.absolutePath}")
        val built = Builder.build(SourceTree.open(directory), output) { line -> logInfo("cache build: $line") }
        logInfo("Cache build ${if (built.unchanged) "up to date" else "done"}: $built")
        result = built
        return built
    }
}
