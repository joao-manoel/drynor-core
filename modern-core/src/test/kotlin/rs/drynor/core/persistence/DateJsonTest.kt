package rs.drynor.core.persistence

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DateJsonTest {
    private val gson = GsonBuilder().registerTypeAdapter(Date::class.java, DateJson(ZoneId.of("America/Cuiaba"))).create()
    @Test fun `reads the real saved player date and preserves its instant on write`() {
        val date = gson.fromJson("\"Oct 14, 2026, 8:05:22 AM\"", Date::class.java)
        assertEquals(Instant.parse("2026-10-14T12:05:22Z"), date.toInstant())
        assertEquals(date, gson.fromJson(gson.toJson(date), Date::class.java))
    }
    @Test fun `calendar year and non breaking spaces survive historic Gson formats`() {
        val date = gson.fromJson("\"Dec 31, 2025 11:59:59\u202fPM\"", Date::class.java)
        assertEquals(Instant.parse("2026-01-01T03:59:59Z"), date.toInstant())
    }
    @Test fun `invalid dates fail without silently deleting persisted data`() {
        assertFailsWith<JsonParseException> { gson.fromJson("\"Feb 30, 2026, 8:05:22 AM\"", Date::class.java) }
    }
}
