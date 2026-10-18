package rs.drynor.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.drynor.core.model.Clan
import org.drynor.core.model.ClanMemberData
import org.drynor.core.model.ClanRank
import org.drynor.core.net.prot.packet.ClanSettingsFull
import org.drynor.core.net.prot.revision.rev727.register727
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.drynor.core.net.prot.dto.ClanSettingsMember


class ClanWireTest {
    @Test fun `settings member count and names match the revision 727 client bytes`() {
        // PacketDecoder reads main, then ClanSettings.decode reads version 3 / names attribute 2.
        // This fixture is independent of the paired server decoder, including all rank/flag bytes.
        val codec = register727()
        val expected = byteArrayOf(
            1, 3, 2, 0, 0, 0, 7, 0, 0, 0, 0, 0, 1, 0,
            68, 114, 121, 110, 111, 114, 0, 1, -1, 100, 0, 0,
            80, 101, 113, 117, 105, 0, 126, 1, 2, 3, 4, 0, 0,
        )
        val packet = ClanSettingsFull(true, "Drynor", 7, true, -1, 100,
            arrayOf(ClanSettingsMember("Pequi", 126, 0x01020304)))
        val body = PacketBody.encode(codec, packet)
        assertEquals(137, body.opcode)
        kotlin.test.assertContentEquals(expected, body.bytes)
        for (count in listOf(0, 1, 5, 6)) {
            val bytes = PacketBody.encode(codec, packet.copy(members = Array(count) {
                ClanSettingsMember("Member$it", if (it == 0) 126 else 0)
            })).bytes
            assertEquals(count, ((bytes[11].toInt() and 255) shl 8) or (bytes[12].toInt() and 255))
            assertTrue(!bytes.toString(Charsets.ISO_8859_1).contains("FILLER_CHARACTER"))
        }
    }

    @Test fun `packed bit flags and 64 bit settings survive the actual client codec`() {
        val clan = Clan(name = "Drynor", members = mutableMapOf(
            "late" to ClanMemberData(ClanRank.ADMIN, joinDate = 2),
            "owner" to ClanMemberData(ClanRank.OWNER, joinDate = 1)
        ))
        val packet = ClanWire.settings(clan, false, displayName = { "Custom $it" },
            packedSettings = mapOf(24 to 5, 35 to 9007199254740993L, 36 to "Drynor motto"))
        val codec = register727()
        val encoded = PacketBody.encode(codec, packet)
        val source = Buffer().apply { write(encoded.bytes) }
        val decoded = runBlocking { codec.decodeServerProt(encoded.opcode, source) } as ClanSettingsFull
        val settings = assertNotNull(decoded.settings).associateBy { it.key }
        assertEquals(5, settings.getValue(24).intValue)
        assertEquals(9007199254740993L, settings.getValue(35).longValue)
        assertEquals("Drynor motto", settings.getValue(36).stringValue)
        assertEquals(listOf("Custom owner", "Custom late"), decoded.members!!.take(2).map { it.displayName })
        assertEquals(listOf("owner", "late"), ClanWire.memberUsernames(clan))
        assertTrue(source.exhausted())
    }
}
