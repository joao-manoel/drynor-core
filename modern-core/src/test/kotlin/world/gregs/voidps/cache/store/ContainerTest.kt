package world.gregs.voidps.cache.store

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerTest {

    @Test
    fun `uncompressed container round trips`() {
        val data = payload(300)
        val container = Container.compress(data, Compression.NONE, version = 1234)
        assertEquals(Compression.NONE, container.compression)
        assertEquals(data.size, container.decompressedSize)
        assertContentEquals(data, container.data())

        val encoded = container.encode()
        assertEquals(5 + data.size + 2, encoded.size)
        assertEquals(0, encoded[0].toInt())

        val decoded = Container.decode(encoded)
        assertEquals(Compression.NONE, decoded.compression)
        assertEquals(1234, decoded.version)
        assertContentEquals(data, decoded.data())
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `gzip container round trips`() {
        val data = payload(5000)
        val container = Container.compress(data, Compression.GZIP, version = 7)
        assertTrue(container.payload.size < data.size, "Random-ish data should still deflate")
        assertContentEquals(data, container.data())

        val encoded = container.encode()
        val decoded = Container.decode(encoded)
        assertEquals(Compression.GZIP, decoded.compression)
        assertEquals(data.size, decoded.decompressedSize)
        assertEquals(7, decoded.version)
        assertEquals(0, decoded.gzipOs)
        assertContentEquals(data, decoded.data())
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `gzip header is the ten byte form the cache uses`() {
        val encoded = Container.compress(payload(64), Compression.GZIP, version = 0).encode()
        val header = encoded.copyOfRange(9, 19)
        assertContentEquals(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0), header)
    }

    @Test
    fun `gzip os byte survives a round trip`() {
        val data = payload(120)
        val container = Container.compress(data, Compression.GZIP, version = 1, gzipOs = 0xff)
        val decoded = Container.decode(container.encode())
        assertEquals(0xff, decoded.gzipOs)
        assertContentEquals(data, decoded.data())
        assertContentEquals(container.encode(), decoded.encode())
    }

    @Test
    fun `container without a trailer has a null version`() {
        val encoded = Container.compress(payload(40), Compression.GZIP, version = null).encode()
        val decoded = Container.decode(encoded)
        assertNull(decoded.version)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun `xtea encrypted container round trips and leaves the caller's array alone`() {
        val keys = intArrayOf(1, -2, 3, -4)
        val data = payload(777)
        val container = Container.compress(data, Compression.GZIP, version = 9)
        val plain = container.encode()
        val encrypted = container.encode(keys)

        assertEquals(plain.size, encrypted.size)
        // The type byte and compressed size are outside the encrypted region.
        assertContentEquals(plain.copyOfRange(0, 5), encrypted.copyOfRange(0, 5))
        assertTrue(!plain.contentEquals(encrypted), "Keys should have changed the payload")
        // The last two bytes are the version trailer, which is outside the encrypted region.
        assertContentEquals(plain.copyOfRange(plain.size - 2, plain.size), encrypted.copyOfRange(plain.size - 2, plain.size))

        val untouched = encrypted.copyOf()
        val decoded = Container.decode(encrypted, keys)
        assertContentEquals(untouched, encrypted, "decode must not modify its input")
        assertContentEquals(data, decoded.data())
        assertEquals(9, decoded.version)
        assertContentEquals(encrypted, decoded.encode(keys))
    }

    @Test
    fun `all zero keys mean unencrypted`() {
        val data = payload(64)
        val container = Container.compress(data, Compression.NONE, version = 1)
        assertContentEquals(container.encode(), container.encode(intArrayOf(0, 0, 0, 0)))
        assertContentEquals(data, Container.decode(container.encode(), intArrayOf(0, 0, 0, 0)).data())
    }

    @Test
    fun `crc and whirlpool cover the container without its trailer`() {
        val container = Container.compress(payload(500), Compression.GZIP, version = 5)
        val withTrailer = Container(container.compression, container.payload, container.decompressedSize, 5)
        val withoutTrailer = Container(container.compression, container.payload, container.decompressedSize, null)
        assertEquals(withoutTrailer.crc(), withTrailer.crc())
        assertContentEquals(withoutTrailer.whirlpool(), withTrailer.whirlpool())
        assertEquals(64, withTrailer.whirlpool().size)
    }

    @Test
    fun `a truncated container is rejected`() {
        assertFailsWith<IllegalArgumentException> { Container.decode(byteArrayOf(0, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { Container.decode(byteArrayOf(0, 0, 0, 0, 100, 1, 2)) }
    }

    @Test
    fun `lzma is not a 727 compression type`() {
        assertFailsWith<IllegalArgumentException> { Compression.of(3) }
    }

    private fun payload(size: Int): ByteArray {
        // Deterministic but compressible, like a real definition archive.
        val random = Random(size.toLong())
        return ByteArray(size) { (random.nextInt(16) + it % 7).toByte() }
    }
}
