package rs.drynor.core.protocol

import org.drynor.core.net.prot.packet.ClanSettingsFullRaw
import org.drynor.core.net.prot.packet.PlayerUpdate
import org.drynor.core.net.prot.revision.rev727.register727
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PacketBodyTest {
    @Test fun `clan snapshot body preserves main flag and exact payload`() {
        val body = PacketBody.encode(register727(), ClanSettingsFullRaw(false, byteArrayOf(3, 2, 0, 1)))
        assertEquals(137, body.opcode)
        assertEquals(-2, body.size)
        assertContentEquals(byteArrayOf(0, 3, 2, 0, 1), body.bytes)
    }

    @Test fun `large update body drains concurrently without changing bytes`() {
        val bytes = ByteArray(65535) { (it % 251).toByte() }
        assertContentEquals(bytes, PacketBody.encode(register727(), PlayerUpdate(bytes)).bytes)
    }

    @Test fun `oversize body is rejected before framing`() {
        assertFailsWith<IllegalArgumentException> {
            PacketBody.encode(register727(), PlayerUpdate(ByteArray(65536)))
        }
    }
}
