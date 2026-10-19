package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsNpcDependenciesTest {
    @Test
    fun readsRawBodyHeadAnimationAndTextureReferences() {
        val result = parse(
            2, 90, 117, 107, 0,
            1, 2, 0, 7, 255, 255,
            60, 1, 0, 9,
            13, 0, 10,
            17, 0, 11, 255, 255, 0, 12, 0, 10,
            41, 2, 0, 3, 0, 4, 255, 255, 0, 4,
            102, 0, 8,
            0,
        )
        assertEquals("Zuk", result.name)
        assertEquals(setOf(7, 9), result.models)
        assertEquals(setOf(10, 11, 12), result.animations)
        assertEquals(setOf(3, 4), result.textures)
        assertEquals(setOf(8), result.headIcons)
    }

    @Test
    fun readsBothMorphFormsAndOmitsSentinels() {
        val result = parse(
            106, 0, 1, 0, 2, 1, 0, 5, 255, 255,
            118, 255, 255, 255, 255, 0, 6, 1, 0, 7, 255, 255,
            0,
        )
        assertEquals(setOf(5, 6, 7), result.morphs)
        assertEquals(setOf(1), result.varbits)
        assertEquals(setOf(2), result.varps)
    }

    @Test
    fun consumesNonDependencyPayloadsAndParameters() {
        val result = parse(
            12, 1, 30, 65, 0, 40, 1, 0, 1, 0, 2, 42, 2, 1, 2,
            93, 95, 0, 1, 97, 0, 1, 98, 0, 1, 99, 100, 255, 101, 255,
            102, 255, 255, 103, 0, 1, 107, 109, 111,
            249, 2, 1, 0, 0, 1, 65, 0, 0, 0, 0, 2, 0, 0, 0, 3,
            0,
        )
        assertEquals(NpcDependencies("null", emptySet(), emptySet(), emptySet(), emptySet()), result)
    }

    @Test
    fun rejectsUnknownTruncatedAndTrailingData() {
        listOf(
            intArrayOf(), intArrayOf(1, 1, 0), intArrayOf(2, 65),
            intArrayOf(106, 0, 0, 0, 0, 0), intArrayOf(18, 0),
            intArrayOf(0, 0), intArrayOf(249, 1, 2),
        ).forEach { bytes ->
            assertFailsWith<IllegalArgumentException> { parse(*bytes) }
        }
    }

    private fun parse(vararg bytes: Int): NpcDependencies =
        OsrsNpcDependencies.parse(ByteArray(bytes.size) { bytes[it].toByte() })
}
