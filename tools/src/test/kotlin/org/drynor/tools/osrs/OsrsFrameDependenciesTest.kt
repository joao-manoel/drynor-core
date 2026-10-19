package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsFrameDependenciesTest {
    @Test
    fun readsLegacyFrameHeaderAndBothSignedSmartWidths() {
        val data = bytes(1, 2, 3, 0, 7, 2, 0, 127, 128, 0, 255, 255)
        assertEquals(OsrsFrameInfo(258, 3), OsrsFrameDependencies.parseFrame(data))
        assertEquals(OsrsFrameInfo(0, 0), OsrsFrameDependencies.parseFrame(bytes(0, 0, 0)))
    }

    @Test
    fun readsLegacyBaseTypesLengthsAndLabels() {
        assertEquals(
            OsrsBaseInfo(3),
            OsrsFrameDependencies.parseBase(bytes(3, 0, 3, 5, 0, 2, 1, 7, 255, 42)),
        )
        assertEquals(OsrsBaseInfo(0), OsrsFrameDependencies.parseBase(bytes(0)))
    }

    @Test
    fun rejectsTruncatedUnknownFlagsAndTrailingFrameBytes() {
        listOf(
            bytes(), bytes(0, 1), bytes(0, 1, 2, 0), bytes(0, 1, 1, 8),
            bytes(0, 1, 1, 1), bytes(0, 1, 1, 1, 128), bytes(0, 1, 0, 0),
        ).forEach { data ->
            assertFailsWith<IllegalArgumentException> { OsrsFrameDependencies.parseFrame(data) }
        }
    }

    @Test
    fun rejectsTruncatedExtendedAndTrailingBaseBytes() {
        listOf(bytes(), bytes(1), bytes(1, 0), bytes(1, 0, 1), bytes(1, 6, 0), bytes(0, 0))
            .forEach { data ->
                assertFailsWith<IllegalArgumentException> { OsrsFrameDependencies.parseBase(data) }
            }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
