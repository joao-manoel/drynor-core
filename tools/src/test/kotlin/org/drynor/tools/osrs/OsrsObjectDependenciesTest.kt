package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsObjectDependenciesTest {
    @Test
    fun readsEveryTypedModelInsteadOfRepeatingFirst() {
        val result = OsrsObjectDependencies.parse(bytes(1, 2, 0, 12, 10, 0, 34, 22, 24, 0, 56, 0))
        assertEquals(setOf(12, 34), result.models)
        assertEquals(setOf(56), result.animations)
    }

    @Test
    fun followsDefaultMorphAndOmitsSentinels() {
        val result = OsrsObjectDependencies.parse(bytes(92, 0, 7, 255, 255, 0, 99, 1, 0, 42, 255, 255, 0))
        assertEquals(setOf(7), result.varbits)
        assertEquals(emptySet(), result.varps)
        assertEquals(setOf(42, 99), result.morphs)
    }

    @Test
    fun extractsRetextureAndSounds() {
        val result = OsrsObjectDependencies.parse(bytes(41, 1, 0, 5, 0, 6, 79, 0, 1, 0, 2, 3, 1, 0, 44, 0))
        assertEquals(setOf(5, 6), result.textures)
        assertEquals(setOf(44), result.sounds)
    }

    @Test
    fun doesNotInventMissingSentinelForModelReferences() {
        assertEquals(setOf(65535), OsrsObjectDependencies.parse(bytes(5, 1, 255, 255, 0)).models)
    }

    @Test
    fun rejectsUnknownTruncatedAndTrailingPayloads() {
        assertFailsWith<IllegalStateException> { OsrsObjectDependencies.parse(bytes(250, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsObjectDependencies.parse(bytes(1, 1, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsObjectDependencies.parse(bytes(2, 65)) }
        assertFailsWith<IllegalArgumentException> { OsrsObjectDependencies.parse(bytes(0, 0)) }
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
