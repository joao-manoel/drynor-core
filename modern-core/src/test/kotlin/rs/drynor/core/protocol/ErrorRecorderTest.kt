package rs.drynor.core.protocol

import org.drynor.core.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ErrorRecorderTest {
    @Test fun `native logger keeps the application error persistence callback`() {
        val entries = mutableListOf<Triple<String, String?, Throwable?>>()
        Logger.setErrorRecorder { origin, message, throwable -> entries += Triple(origin, message, throwable) }
        try {
            val failure = IllegalStateException("test failure")
            Logger.handle(javaClass, "save-player", "Could not save", failure)
            assertEquals("save-player", entries.single().first)
            assertEquals("Could not save", entries.single().second)
            assertSame(failure, entries.single().third)
            Logger.handleNoRecord(javaClass, "transport", "Retrying", failure)
            assertEquals(1, entries.size)
        } finally { Logger.setErrorRecorder(null) }
    }
}
