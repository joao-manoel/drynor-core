package rs.drynor.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.darkan.core.model.Clan
import org.darkan.core.model.ClanMemberData
import org.darkan.core.model.ClanRank
import org.darkan.core.net.prot.packet.ClanSettingsFull
import org.darkan.core.net.prot.revision.rev727.register727
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClanWireTest {
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
