package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsSequenceDependenciesTest {
    @Test
    fun readsPackedFramesAndChatFramesWithoutArchiveOffsets() {
        val result = parse(
            1, 0, 2, 0, 10, 0, 20, 0, 3, 0, 4, 0, 5, 0, 6,
            12, 2, 0, 7, 255, 255, 0, 8, 255, 255, 0,
        )
        assertEquals(setOf((5 shl 16) or 3, (6 shl 16) or 4, (8 shl 16) or 7), result.frameIds)
    }

    @Test
    fun readsOsrsSoundListAndRawEquipmentOverrides() {
        val result = parse(
            13, 3, 1, 35, 69, 0, 0, 0, 1, 35, 70,
            6, 2, 42, 7, 255, 255, 0,
        )
        assertEquals(setOf(0x123), result.sounds)
        assertEquals(setOf(554), result.equipment)
    }

    @Test
    fun consumesGielenorExtensions() {
        val result = parse(
            2, 0, 1, 3, 2, 1, 2, 4, 5, 1, 8, 1, 9, 1, 10, 1, 11, 1,
            14, 15, 16, 17, 1, 18, 19, 0, 1, 119, 0, 0, 1,
            20, 0, 0, 1, 0, 2, 120, 0, 0, 0, 1, 0, 2,
            22, 1, 23, 0, 1, 24, 0, 1,
            249, 2, 1, 0, 0, 1, 65, 0, 0, 0, 0, 2, 0, 0, 0, 3, 0,
        )
        assertEquals(SequenceDependencies(emptySet(), emptySet(), emptySet()), result)
    }

    @Test
    fun rejectsUnknownTruncatedAndTrailingData() {
        listOf(
            intArrayOf(), intArrayOf(1, 0, 1, 0, 1, 0, 2),
            intArrayOf(12, 1, 0, 1), intArrayOf(13, 1, 0, 1),
            intArrayOf(21, 0), intArrayOf(0, 0), intArrayOf(249, 1, 2),
        ).forEach { bytes -> assertFailsWith<IllegalArgumentException> { parse(*bytes) } }
    }

    private fun parse(vararg bytes: Int): SequenceDependencies =
        OsrsSequenceDependencies.parse(ByteArray(bytes.size) { bytes[it].toByte() })
}
