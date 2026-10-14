package world.gregs.voidps.cache.file.type

import io.ktor.utils.io.*
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.FileProvider
import world.gregs.voidps.cache.file.FileProvider.Companion.ARCHIVE_METADATA_HEADER_LEN
import world.gregs.voidps.cache.file.FileProvider.Companion.JS5_HEADER_LEN
import world.gregs.voidps.cache.file.FileProvider.Companion.SEPARATOR
import world.gregs.voidps.cache.file.FileProvider.Companion.SPLIT
import world.gregs.voidps.cache.file.FileProvider.Companion.getInt

/**
 * Reads sectors directly from the [cache]
 * Average read speeds, low memory usage
 */
class CacheFileProvider(private val cache: Cache) : FileProvider {

    override fun data(index: Int, archive: Int): ByteArray? {
        if (index == 255 && archive == 255)
            return cache.versionTable
        return cache.sector(index, archive)
    }

    override suspend fun encode(write: ByteWriteChannel, data: ByteArray) {
        val compression = data[0].toInt()
        val length = getInt(data[1], data[2], data[3], data[4])
        val realLength = if (compression != 0) length + 4 else length
        var writePosition = JS5_HEADER_LEN
        for (index in ARCHIVE_METADATA_HEADER_LEN..< realLength+ARCHIVE_METADATA_HEADER_LEN) {
            if (writePosition % SPLIT == 0) {
                write.writeByte(SEPARATOR)
                writePosition++
            }
            write.writeByte(data[index].toInt())
            writePosition++
        }
    }
}