package org.darkan.core.net.js5

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.Closeable
import java.io.File

class Js5Cache private constructor(
    val directory: File,
    private val store: FileStore,
    val versionTable: Js5VersionTable,
    private var client: Js5Client?,
    private val connect: suspend () -> Js5Client,
) : Closeable {
    private val lock = Mutex()
    private val tables = arrayOfNulls<ReferenceTable>(versionTable.crcs.size)
    private val current = HashSet<Long>()

    val cache: Cache by lazy { FileCache(directory.path) }

    val indexCrcs: IntArray get() = versionTable.crcs

    fun referenceTable(index: Int): ReferenceTable? {
        tables.getOrNull(index)?.let { return it }
        val container = store.read(FileStore.REFERENCE_INDEX, index) ?: return null
        val decompressed = DecompressionContext().decompress(container) ?: return null
        return ReferenceTable.decode(decompressed).also { tables[index] = it }
    }

    suspend fun sync(index: Int, archives: Collection<Int>? = null): Int = lock.withLock {
        val table = referenceTable(index) ?: return@withLock 0
        val wanted = (archives ?: table.archives.map { it.id }).filter { archive ->
            val key = key(index, archive)
            if (key in current) return@filter false
            val entry = table.archive(archive) ?: return@filter false
            val stored = store.read(index, archive)
            if (stored != null && Js5Client.crc(stored) == entry.crc) {
                current.add(key)
                false
            } else true
        }
        if (wanted.isEmpty()) return@withLock 0
        logInfo("Fetching ${wanted.size} archive(s) of index $index over JS5")
        fetch(wanted.map { index to it }) { i, archive, container ->
            store.write(i, archive, container)
            current.add(key(i, archive))
        }
        wanted.size
    }

    private suspend fun fetch(requests: List<Pair<Int, Int>>, onReceived: (Int, Int, ByteArray) -> Unit) {
        try {
            (client ?: connect().also { client = it }).fetchAll(requests, onReceived = onReceived)
        } catch (e: Exception) {
            logWarn("JS5 connection lost (${e.message}); reconnecting")
            client?.close()
            client = connect()
            client!!.fetchAll(requests, onReceived = onReceived)
        }
    }

    override fun close() {
        client?.close()
        store.close()
    }

    companion object {
        private fun key(index: Int, archive: Int) = (index.toLong() shl 32) or (archive.toLong() and 0xffffffffL)

        suspend fun open(directory: File, connect: suspend () -> Js5Client): Js5Cache {
            val client = connect()
            val versionTable = client.versionTable()
            val indexCount = versionTable.crcs.size
            val store = if (File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
                for (index in 0 until indexCount) File(directory, "${FileStore.CACHE_FILE_NAME}.idx$index").createNewFile()
                FileStore.open(directory, writable = true)
            } else FileStore.create(directory, indexCount)
            val stale = (0 until indexCount).filter { index ->
                val crc = versionTable.crcs[index]
                crc != 0 && store.read(FileStore.REFERENCE_INDEX, index)?.let(Js5Client::crc) != crc
            }
            if (stale.isNotEmpty()) {
                logInfo("Fetching ${stale.size} reference table(s) over JS5")
                client.fetchAll(stale.map { FileStore.REFERENCE_INDEX to it }) { _, index, container ->
                    store.write(FileStore.REFERENCE_INDEX, index, container)
                }
            }
            return Js5Cache(directory, store, versionTable, client, connect)
        }
    }
}
