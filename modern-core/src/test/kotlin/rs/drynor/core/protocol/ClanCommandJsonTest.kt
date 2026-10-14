package rs.drynor.core.protocol

import org.darkan.core.clan.ClanAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClanCommandJsonTest {
    @Test fun `name check uses the closed current core action discriminator`() {
        val request = ClanCommandRequest("owner", ClanAction.CheckName("Drynor"))
        assertEquals("{\"username\":\"owner\",\"action\":{\"_type\":\"CheckName\",\"name\":\"Drynor\"}}", ClanCommandJson.encode(request))
        assertEquals(request, ClanCommandJson.decode(ClanCommandJson.encode(request)))
    }
    @Test fun `untrusted class names cannot become a command`() {
        assertFailsWith<IllegalArgumentException> {
            ClanCommandJson.decode("{\"username\":\"owner\",\"action\":{\"_type\":\"java.lang.Runtime\"}}")
        }
    }
}
