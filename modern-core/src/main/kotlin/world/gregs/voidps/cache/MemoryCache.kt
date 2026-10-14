package world.gregs.voidps.cache

import kotlinx.coroutines.*
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * [Cache] that holds all data in memory
 * Read speeds are as fast, loading is slow and memory usage is high but stable.
 * Loading is done in parallel as it is much slower to load than [FileCache]
 *
 * Not much benefit of using this in the live game as file providers cache the
 * sector data independently for the file server; so the only use after startup is
 * reading dynamic map regions in MapDefinitions.
 * It is however very useful for integration tests to speed world resetting.
 */
class MemoryCache(indexCount: Int) : ReadOnlyCache(indexCount) {

    val data: Array<Array<Array<ByteArray?>?>?> = arrayOfNulls(indexCount)
    val sectors: Array<Array<ByteArray?>?> = arrayOfNulls(indexCount)
    val index255: Array<ByteArray?> = arrayOfNulls(indexCount)

    private val keyed = ConcurrentHashMap<Int, Array<ByteArray?>>()
    private val context = ThreadLocal.withInitial { DecompressionContext() }

    private val indexCrcs by lazy {
        indices.map {
            val data = sector(255, it) ?: return@map 0
            CRC.calculate(data, 0, data.size)
        }.toIntArray()
    }

    override fun sector(index: Int, archive: Int): ByteArray? {
        if (index == 255) {
            if (archive >= index255.size) {
                return null
            }
            return index255[archive]
        }
        if (index >= sectors.size) {
            return null
        }
        val archives = sectors[index]
        if (archives == null || archive >= archives.size) {
            return null
        }
        return archives[archive]
    }

    override fun indexCrcs() = indexCrcs

    override fun data(index: Int, archive: Int, file: Int, xtea: IntArray?): ByteArray? {
        if (index >= data.size) {
            return null
        }
        val archives = data[index]
        if (archives == null || archive >= archives.size) {
            return null
        }
        val loaded = archives[archive]
        if (xtea != null && index == Index.MAPS) {
            decipher(index, archive, xtea)?.let { return it.getOrNull(file) }
        }
        if (loaded == null || file >= loaded.size) {
            return null
        }
        return loaded[file]
    }

    private fun decipher(index: Int, archive: Int, xtea: IntArray): Array<ByteArray?>? {
        val key = index + (archive shl 6) + xtea.contentHashCode()
        keyed[key]?.let { return it }
        val fileIds = files.getOrNull(index)?.getOrNull(archive) ?: return null
        if (fileIds.isEmpty()) {
            return null
        }
        val sector = sector(index, archive) ?: return null
        val result = try {
            // Deciphering is in place, so the loaded sector is copied rather than consumed.
            val decompressed = context.get().decompress(sector.copyOf(), xtea) ?: return null
            val files = arrayOfNulls<ByteArray>(fileIds.last() + 1)
            if (fileIds.size == 1) {
                files[fileIds.last()] = decompressed
            } else {
                val group = ArchiveGroup.split(decompressed, fileIds)
                for ((position, id) in fileIds.withIndex()) {
                    files[id] = group.files[position]
                }
            }
            files
        } catch (e: Exception) {
            logWarn("Failed to decrypt index $index archive $archive.", e)
            return null
        }
        keyed[key] = result
        return result
    }

    companion object : CacheLoader {
        operator fun invoke(path: String, threadUsage: Double = 1.0, exponent: BigInteger? = null, modulus: BigInteger? = null, xteas: Map<Int, IntArray>? = null): Cache {
            return load(path, exponent, modulus, xteas, threadUsage) as ReadOnlyCache
        }

        /**
         * Load each index in parallel using a percentage of cpu cores
         */
        @OptIn(DelicateCoroutinesApi::class)
        override fun load(
            path: String,
            mainFile: File,
            main: RandomAccessFile,
            index255File: File,
            index255: RandomAccessFile,
            indexCount: Int,
            versionTable: VersionTableBuilder?,
            xteas: Map<Int, IntArray>?,
            threadUsage: Double,
            writable: Boolean,
            exponent: BigInteger?,
            modulus: BigInteger?
        ): Cache {
            require(!writable) { "An in-memory cache cannot be opened writable." }
            val cache = MemoryCache(indexCount)
            val processors = (Runtime.getRuntime().availableProcessors() * threadUsage).toInt().coerceAtLeast(1)
            newFixedThreadPoolContext(processors, "cache-loader").use { dispatcher ->
                runBlocking(dispatcher) {
                    supervisorScope {
                        val fileLength = mainFile.length()
                        for (indexId in 0 until indexCount) {
                            launch {
                                loadIndex(path, indexId, mainFile, fileLength, index255File, xteas, processors, cache, versionTable)
                            }
                        }
                    }
                }
            }
            cache.versionTable = versionTable?.build() ?: ByteArray(0)
            return cache
        }

        /**
         * Reads an indexes archive information before reading each archive in parallel
         */
        private suspend fun loadIndex(
            path: String,
            indexId: Int,
            mainFile: File,
            mainFileLength: Long,
            index255File: File,
            xteas: Map<Int, IntArray>?,
            processors: Int,
            cache: MemoryCache,
            versionTable: VersionTableBuilder?
        ) {
            val file = File(path, "${FileCache.CACHE_FILE_NAME}.idx$indexId")
            if (!file.exists()) {
                logTrace("No index $indexId file found.")
                return
            }
            try {
                val start = System.currentTimeMillis()
                val main = withContext(Dispatchers.IO) {
                    RandomAccessFile(mainFile, "r")
                }
                val index255 = withContext(Dispatchers.IO) {
                    RandomAccessFile(index255File, "r")
                }
                val context = DecompressionContext()
                val whirlpool = Whirlpool()
                val highest = cache.archiveData(context, main, mainFileLength, index255, indexId, versionTable, whirlpool, cache.index255)
                if (highest == -1) {
                    return
                }
                cache.sectors[indexId] = arrayOfNulls(highest + 1)
                cache.data[indexId] = arrayOfNulls<Array<ByteArray?>?>(highest + 1)
                coroutineScope {
                    if (processors in 2 until highest) {
                        for (list in (0..highest).chunked(highest / processors)) {
                            launch {
                                loadArchives(cache, list, file, mainFile, mainFileLength, indexId, xteas)
                            }
                        }
                    } else {
                        loadArchives(cache, (0..highest).toList(), file, mainFile, mainFileLength, indexId, xteas)
                    }
                }
                logTrace("Loaded ${cache.data[indexId]!!.size} index $indexId archives in ${System.currentTimeMillis() - start}ms.")
            } catch (e: Exception) {
                logWarn("Failed to load index $indexId.", e)
            }
            return
        }

        /**
         * Reads data for every file in each [archives] into the [cache]
         */
        private fun loadArchives(
            cache: MemoryCache,
            archives: List<Int>,
            file: File,
            mainFile: File,
            mainFileLength: Long,
            indexId: Int,
            xteas: Map<Int, IntArray>?
        ) {
            val context = DecompressionContext()
            val raf = RandomAccessFile(file, "r")
            val main = RandomAccessFile(mainFile, "r")
            for (archiveId in archives) {
                val archiveFiles = cache.fileData(
                    context, main, mainFileLength, raf, indexId, archiveId, xteas, cache.sectors
                ) ?: continue
                val archiveFileIds = cache.files[indexId]?.get(archiveId) ?: continue
                val fileId = archiveFileIds.last()
                val fileCount = cache.fileCounts[indexId]?.getOrNull(archiveId) ?: continue
                val archiveData: Array<ByteArray?> = arrayOfNulls(fileId + 1)
                for (fileIndex in 0 until fileCount) {
                    val data = archiveFiles[fileIndex]
                    archiveData[archiveFileIds[fileIndex]] = data
                }
                cache.data[indexId]!![archiveId] = archiveData
            }
        }
    }
}