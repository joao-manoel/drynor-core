package world.gregs.voidps.cache.file

import io.ktor.utils.io.*
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.type.CacheFileProvider
import world.gregs.voidps.cache.file.type.MemoryFileProvider

/**
 * Provides cache sectors requested by the client
 */
interface FileProvider {

    fun data(index: Int, archive: Int): ByteArray?

    suspend fun encode(write: ByteWriteChannel, data: ByteArray)

    suspend fun serve(write: ByteWriteChannel, ref: Long, prefetch: Boolean): Boolean {
        val index = (ref ushr 32).toInt()
        val archive = (ref and 0xFFFFFFFFL).toInt()
        val data = data(index, archive)
        if (data == null || data.size < 4) {
            logWarn("Unable to fulfill request $index $archive $prefetch.")
            return false
        }
        val compression = data[0].toInt()
        val size = getInt(data[1], data[2], data[3], data[4])
        write.writeByte(index)
        write.writeInt(archive)
        write.writeByte(if (prefetch) compression or 0x80 else compression)
        write.writeInt(size)
        encode(write, data)
        //TODO if encryption ever gets set:
        //if (encryptionValue != 0)
        //  for (int i = 0; i < writeBuffer.length; i++)
        //    writeBuffer[i] = writeBuffer[i] ^ encryptionValue
        write.flush()
        return true
    }

    companion object {
        fun load(cache: Cache): FileProvider {
            val start = System.currentTimeMillis()
            val provider = if (EnvVars.memCache) MemoryFileProvider(cache) else CacheFileProvider(cache)
            logInfo("Loaded file provider in ${System.currentTimeMillis() - start}ms")
            return provider
        }

        internal fun getInt(b1: Byte, b2: Byte, b3: Byte, b4: Byte) = b1.toInt() shl 24 or (b2.toInt() and 0xff shl 16) or (b3.toInt() and 0xff shl 8) or (b4.toInt() and 0xff)

        internal const val SEPARATOR = 255
        internal const val JS5_HEADER_LEN = 10
        internal const val SPLIT = 512
        internal const val ARCHIVE_METADATA_HEADER_LEN = 5

        internal fun encode(data: ByteArray): ByteArray {
            val compression = data[0].toInt()
            val length = getInt(data[1], data[2], data[3], data[4])
            val fullLength = length + if (compression != 0) 4 else 0
            val write = BufferWriter(data.size + (fullLength / SPLIT) + ARCHIVE_METADATA_HEADER_LEN)
            write.writeByte(compression)
            write.writeInt(length)
            val realLength = if (compression != 0) length + 4 else length
            for (index in ARCHIVE_METADATA_HEADER_LEN..<realLength + ARCHIVE_METADATA_HEADER_LEN) {
                if ((write.position()+(JS5_HEADER_LEN-ARCHIVE_METADATA_HEADER_LEN)) % SPLIT == 0)
                    write.writeByte(SEPARATOR)
                write.writeByte(data[index].toInt())
            }
            return write.toArray()
        }
    }
}