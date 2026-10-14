package world.gregs.voidps.cache.compress

import lzma.sdk.lzma.Decoder
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logWarn
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.secure.Xtea
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

/**
 * Context per thread for decompressing data in parallel
 */
internal class DecompressionContext {
    private val gzipInflater = Inflater(true)
    private val bzip2Compressor: BZIP2Compressor by lazy { BZIP2Compressor() }
    private val lzmaDecoder: Decoder by lazy { Decoder() }

    fun decompress(data: ByteArray, keys: IntArray? = null): ByteArray? {
        val buffer = BufferReader(data)
        val type = buffer.readUnsignedByte()
        val compressedSize = buffer.readInt() and 0xFFFFFF
        if (keys != null && (keys[0] != 0 || keys[1] != 0 || keys[2] != 0 || 0 != keys[3])) {
            // The encrypted region is the container and nothing else: from offset 5 to the end of
            // the payload, so the two byte version trailer stays outside it. Deciphering to the end
            // of the array instead adds a ninth block whenever the trailer completes one, which
            // corrupts the last eight bytes of the payload - 2,033 of the 727 cache's 2,407 `l`
            // archives failed to decompress because of it. The client's own
            // `decryptXtea(keys, containerLength)` uses the same bound, and so does
            // [world.gregs.voidps.cache.store.Container].
            val end = (if (type == NONE) HEADER_SIZE_NONE else HEADER_SIZE_SIZED) + compressedSize
            Xtea.decipher(data, keys, XTEA_OFFSET, minOf(end, data.size))
        }
        var decompressedSize = 0
        if (type != 0) {
            decompressedSize = buffer.readInt() and 0xFFFFFF
        }
        when (type) {
            NONE -> {
                val decompressed = ByteArray(compressedSize)
                buffer.readBytes(decompressed, 0, compressedSize)
                return decompressed
            }
            BZIP2 -> {
                if (!warned.get()) {
                    logWarn("BZIP2 Compression found - replace to improve read performance.")
                    warned.set(true)
                }
                val decompressed = ByteArray(decompressedSize)
                bzip2Compressor.decompress(decompressed, decompressedSize, data, 9)
                return decompressed
            }
            GZIP -> {
                val offset = buffer.position()
                if (buffer.readByte() != 31 || buffer.readByte() != -117) {
                    return null
                }
                return try {
                    val decompressed = ByteArray(decompressedSize)
                    gzipInflater.setInput(data, offset + 10, data.size - (offset + 18))
                    gzipInflater.finished()
                    gzipInflater.inflate(decompressed)
                    decompressed
                } catch (exception: Exception) {
                    logWarn("Error decompressing gzip data.")
                    null
                } finally {
                    gzipInflater.reset()
                }
            }
            LZMA -> {
                val decompressed = ByteArray(decompressedSize)
                decompress(data, buffer.position(), decompressed, decompressedSize)
                return decompressed
            }
        }
        return null
    }

    private fun decompress(compressed: ByteArray, offset: Int, decompressed: ByteArray, decompressedLength: Int) {
        if (!lzmaDecoder.setDecoderProperties(compressed)) {
            logError("LZMA: Bad properties.")
            return
        }
        val input = ByteArrayInputStream(compressed)
        input.skip(offset.toLong())
        val output = ByteArrayWrapperOutputStream(decompressed)
        lzmaDecoder.code(input, output, decompressedLength.toLong())
    }

    private class ByteArrayWrapperOutputStream(private val byteArray: ByteArray) : OutputStream() {
        private var position = 0

        override fun write(b: Int) {
            byteArray[position++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            System.arraycopy(b, off, byteArray, position, len)
            position += len
        }

        override fun flush() {
        }

        override fun close() {
        }
    }

    companion object {
        /** Everything but the compression byte and the compressed size is encrypted. */
        private const val XTEA_OFFSET = 5

        /** Bytes before the payload of an uncompressed container. */
        private const val HEADER_SIZE_NONE = 5

        /** Bytes before the payload of a compressed one: the decompressed size as well. */
        private const val HEADER_SIZE_SIZED = 9

        private const val NONE = 0
        private const val BZIP2 = 1
        private const val GZIP = 2
        private const val LZMA = 3
        private val warned = AtomicBoolean()
    }
}