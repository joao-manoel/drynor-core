package org.drynor.core.net

import org.drynor.core.net.prot.ProtSize
import kotlin.test.*

class ClientPacketFramerTest {
    private val seed = intArrayOf(1, 2, 3, 4)
    private val sizes = mapOf(0 to ProtSize.Fixed(0), 1 to ProtSize.Fixed(3), 2 to ProtSize.VarByte, 3 to ProtSize.VarShort)

    private fun wire(packets: List<Pair<Int, ByteArray>>): ByteArray {
        val cipher = Isaac(seed)
        val out = java.io.ByteArrayOutputStream()
        for ((opcode, body) in packets) {
            // TCPPacket.createPacket / ByteBuf.Bit.writeIsaacByte, followed by the client's size prefix.
            out.write((opcode + cipher.nextInt()) and 255)
            when (sizes.getValue(opcode)) {
                ProtSize.VarByte -> out.write(body.size)
                ProtSize.VarShort -> { out.write(body.size ushr 8); out.write(body.size and 255) }
                else -> Unit
            }
            out.write(body)
        }
        return out.toByteArray()
    }

    @Test fun `every split preserves opcode length and body including zero length packets`() {
        val expected = listOf(0 to byteArrayOf(), 1 to byteArrayOf(4, 5, 6), 2 to ByteArray(255) { it.toByte() },
            3 to ByteArray(300) { (it * 3).toByte() }, 0 to byteArrayOf())
        val encoded = wire(expected)
        for (split in 0..encoded.size) {
            val actual = mutableListOf<Pair<Int, ByteArray>>()
            val framer = ClientPacketFramer(Isaac(seed), sizes::get)
            val accept: (Int, ByteArray) -> Unit = { opcode, body -> actual += opcode to body }
            framer.feed(encoded, 0, split, accept)
            framer.feed(encoded, split, encoded.size - split, accept)
            assertEquals(expected.map { it.first }, actual.map { it.first })
            expected.zip(actual).forEach { (a, b) -> assertContentEquals(a.second, b.second) }
        }
    }

    @Test fun `single byte feeds retain varshort header and ISAAC beyond the first block`() {
        val expected = List(600) { if (it % 17 == 0) 3 to ByteArray(256) { it.toByte() } else 0 to byteArrayOf() }
        val actual = mutableListOf<Pair<Int, ByteArray>>()
        val framer = ClientPacketFramer(Isaac(seed), sizes::get)
        wire(expected).forEach { framer.feed(byteArrayOf(it)) { opcode, body -> actual += opcode to body } }
        assertEquals(expected.map { it.first }, actual.map { it.first })
        expected.zip(actual).forEach { (a, b) -> assertContentEquals(a.second, b.second) }
    }

    @Test fun `maximum unsigned length is retained and oversized configured bodies are terminal`() {
        val body = ByteArray(65535) { it.toByte() }
        var decoded: ByteArray? = null
        ClientPacketFramer(Isaac(seed), sizes::get).feed(wire(listOf(3 to body))) { _, value -> decoded = value }
        assertContentEquals(body, decoded)
        val limited = ClientPacketFramer(Isaac(seed), sizes::get, maxBodySize = 255)
        assertFails { limited.feed(wire(listOf(3 to ByteArray(256)))) { _, _ -> fail("Oversized frame dispatched") } }
        assertFails { limited.feed(byteArrayOf()) { _, _ -> fail("Closed frame dispatched") } }
    }

    @Test fun `unknown opcode dispatch error timeout and close all prevent later dispatch`() {
        val unknown = ClientPacketFramer(Isaac(seed), { null })
        assertFails { unknown.feed(wire(listOf(0 to byteArrayOf()))) { _, _ -> fail("Unknown frame") } }
        val broken = ClientPacketFramer(Isaac(seed), sizes::get)
        var received = 0
        assertFails { broken.feed(wire(List(2) { 0 to byteArrayOf() })) { _, _ -> received++; error("Queue full") } }
        assertEquals(1, received)
        assertFails { broken.feed(wire(listOf(0 to byteArrayOf()))) { _, _ -> received++ } }
        var now = 0L
        val slow = ClientPacketFramer(Isaac(seed), sizes::get, incompleteTimeoutNanos = 10, nanoTime = { now })
        val bytes = wire(listOf(3 to ByteArray(300)))
        slow.feed(bytes, 0, 2) { _, _ -> fail("Incomplete frame") }
        now = 11
        assertFails { slow.feed(bytes, 2, bytes.size - 2) { _, _ -> fail("Expired frame") } }
        val closed = ClientPacketFramer(Isaac(seed), sizes::get)
        closed.close()
        assertFails { closed.feed(bytes) { _, _ -> fail("Closed frame") } }
    }
}
