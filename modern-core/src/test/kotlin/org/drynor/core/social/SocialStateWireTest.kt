package org.drynor.core.social

import org.drynor.core.net.prot.packet.FriendsChatChannel
import org.drynor.core.net.prot.revision.rev727.register727
import rs.drynor.core.protocol.PacketBody
import kotlin.test.*

class SocialStateWireTest {
    private val codec = register727()
    @Test fun `ignore wire uses display and previous names and never account email`() {
        val packet = SocialPackets.ignoreList(listOf(IgnoredPlayer("private@example.com", "Alice", "Old Alice")))
        val body = PacketBody.encode(codec, packet)
        assertContentEquals(byteArrayOf(1) + "Alice\u0000Old Alice\u0000".toByteArray(), body.bytes)
    }
    @Test fun `temporary ignore and partial bootstrap preserve client state semantics`() {
        val packet = SocialPackets.render("viewer", SocialEvent.TemporaryIgnoreAdded(IgnoredPlayer("private@example.com", "Alice", "Old"))).single()
        assertContentEquals(byteArrayOf(2) + "Alice\u0000Old\u0000".toByteArray(), PacketBody.encode(codec, packet).bytes)
        val partial = SocialPackets.render("viewer", SocialEvent.Snapshot(emptyList(), emptyList(), 0, includeFriendsChat = false))
        assertEquals(4, partial.size)
        assertTrue(partial.none { it is FriendsChatChannel })
        val clear = SocialPackets.render("viewer", SocialEvent.Snapshot(emptyList(), emptyList(), 0))
        assertTrue((clear.last() as FriendsChatChannel).clear)
    }
}
