package org.drynor.tools.tarnish

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.zip.GZIPInputStream

/** Read-only reader for Tarnish's 317 sector cache; no source server classes are loaded. */
internal class TarnishCache(private val root: Path) : Closeable {
    private val data = FileChannel.open(root.resolve("main_file_cache.dat"), READ)

    fun file(index: Int, id: Int): ByteArray {
        require(index in 0..254 && id in 0..65535)
        FileChannel.open(root.resolve("main_file_cache.idx$index"), READ).use { table ->
            val entry = read(table, id.toLong() * 6, 6)
            val length = medium(entry)
            var sector = medium(entry)
            require(length in 1..16_777_215 && sector > 0) { "Missing Tarnish file $index/$id" }
            val result = ByteArray(length)
            val visited = HashSet<Int>()
            var offset = 0
            var chunk = 0
            while (offset < length) {
                require(sector > 0 && visited.add(sector)) { "Invalid sector chain $index/$id/$sector" }
                val size = minOf(512, length - offset)
                val bytes = read(data, sector.toLong() * 520, size + 8)
                require((bytes.short.toInt() and 65535) == id && (bytes.short.toInt() and 65535) == chunk) { "Sector owner/chunk mismatch $index/$id/$chunk" }
                sector = medium(bytes)
                require((bytes.get().toInt() and 255) == index + 1) { "Sector index mismatch $index/$id" }
                bytes.get(result, offset, size)
                offset += size
                chunk++
            }
            require(sector == 0) { "Trailing sector chain $index/$id" }
            return result
        }
    }

    fun model(id: Int): ByteArray = GZIPInputStream(ByteArrayInputStream(file(1, id))).use { bounded(it, MAX_UNCOMPRESSED) }

    fun archive(id: Int): Map<Int, ByteArray> {
        val encoded = file(0, id)
        val header = ByteBuffer.wrap(encoded)
        val size = medium(header)
        val packed = medium(header)
        require(packed == encoded.size - 6 && size <= MAX_UNCOMPRESSED)
        val compressed = size != packed
        val bytes = if (compressed) unbzip(encoded.copyOfRange(6, encoded.size), size) else encoded
        val reader = ByteBuffer.wrap(bytes).apply { position(if (compressed) 0 else 6) }
        val count = reader.short.toInt() and 65535
        require(count <= reader.remaining() / 10) { "Truncated Tarnish archive table" }
        var offset = reader.position() + count * 10
        val result = LinkedHashMap<Int, ByteArray>()
        repeat(count) {
            val hash = reader.int
            val unpackedSize = medium(reader)
            val packedSize = medium(reader)
            require(unpackedSize <= MAX_UNCOMPRESSED && packedSize <= bytes.size - offset)
            val payload = bytes.copyOfRange(offset, offset + packedSize)
            result[hash] = if (compressed || packedSize == unpackedSize) payload else unbzip(payload, unpackedSize)
            require(result.getValue(hash).size == unpackedSize)
            offset += packedSize
        }
        require(offset == bytes.size)
        return result
    }

    fun objects(): List<ByteArray> {
        val files = archive(2)
        val dat = requireNotNull(files[hash("loc.dat")])
        val idx = ByteBuffer.wrap(requireNotNull(files[hash("loc.idx")]))
        val count = idx.short.toInt() and 65535
        require(idx.remaining() >= count * 2)
        var offset = 0
        val records = ArrayList<ByteArray>(count + 1)
        repeat(count + 1) {
            if (idx.remaining() < 2) return@repeat
            val size = idx.short.toInt() and 65535
            if (size == 65535) return@repeat
            require(size <= dat.size - offset)
            records.add(dat.copyOfRange(offset, offset + size))
            offset += size
        }
        require(offset == dat.size) { "Unindexed object bytes: $offset/${dat.size}, highest=$count" }
        return records
    }

    override fun close() = data.close()

    companion object {
        private const val MAX_UNCOMPRESSED = 64 * 1024 * 1024
        private fun medium(bytes: ByteBuffer) = ((bytes.get().toInt() and 255) shl 16) or ((bytes.get().toInt() and 255) shl 8) or (bytes.get().toInt() and 255)
        private fun read(channel: FileChannel, position: Long, length: Int): ByteBuffer {
            require(position >= 0 && length >= 0 && position + length <= channel.size()) { "Truncated Tarnish cache" }
            val buffer = ByteBuffer.allocate(length)
            var offset = position
            while (buffer.hasRemaining()) {
                val amount = channel.read(buffer, offset)
                check(amount > 0) { "Unexpected EOF" }
                offset += amount
            }
            return buffer.flip() as ByteBuffer
        }
        private fun bounded(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximum) { "Tarnish decompression bound exceeded" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        private fun unbzip(bytes: ByteArray, size: Int): ByteArray =
            BZip2CompressorInputStream(ByteArrayInputStream(byteArrayOf(66, 90, 104, 49) + bytes)).use { bounded(it, size) }.also { require(it.size == size) }
        fun hash(name: String): Int = name.uppercase(java.util.Locale.ROOT).fold(0) { value, char -> value * 61 + char.code - 32 }
    }
}
