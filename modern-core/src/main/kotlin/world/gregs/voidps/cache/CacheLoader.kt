package world.gregs.voidps.cache

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.math.BigInteger

interface CacheLoader {

    fun load(xteas: Map<Int, IntArray>? = null): Cache {
        return load(EnvVars.cachePath, BigInteger(EnvVars.js5RsaExponent), BigInteger(EnvVars.js5RsaModulus), threadUsage = EnvVars.cacheThreadUsage)
    }

    /**
     * @param writable open the underlying store read-write, so [Cache.write] works. Only
     *   [FileCache] supports it; an in-memory cache has nothing to write to.
     */
    fun load(
        path: String,
        exponent: BigInteger? = null,
        modulus: BigInteger? = null,
        xteas: Map<Int, IntArray>? = null,
        threadUsage: Double = 1.0,
        writable: Boolean = false
    ): Cache {
        val mainFile = File(path, "${FileStore.CACHE_FILE_NAME}.dat2")
        if (!mainFile.exists()) {
            throw FileNotFoundException("Main file not found at '${mainFile.absolutePath}'.")
        }
        val main = RandomAccessFile(mainFile, "r")
        val index255File = File(path, "${FileStore.CACHE_FILE_NAME}.idx255")
        if (!index255File.exists()) {
            throw FileNotFoundException("Checksum file not found at '${index255File.absolutePath}'.")
        }
        val index255 = RandomAccessFile(index255File, "r")
        val indexCount = index255.length().toInt() / FileStore.INDEX_ENTRY_SIZE
        val versionTable = if (exponent != null && modulus != null) VersionTableBuilder(exponent, modulus, indexCount) else null
        return load(path, mainFile, main, index255File, index255, indexCount, versionTable, xteas, threadUsage, writable, exponent, modulus)
    }

    /**
     * [main] and [index255] are opened read-only by the caller purely so the index count can be
     * read; an implementation that keeps its own handles (as [FileCache] does through
     * [FileStore]) is expected to close them.
     */
    fun load(
        path: String,
        mainFile: File,
        main: RandomAccessFile,
        index255File: File,
        index255: RandomAccessFile,
        indexCount: Int,
        versionTable: VersionTableBuilder? = null,
        xteas: Map<Int, IntArray>? = null,
        threadUsage: Double = 1.0,
        writable: Boolean = false,
        exponent: BigInteger? = null,
        modulus: BigInteger? = null
    ): Cache
}
