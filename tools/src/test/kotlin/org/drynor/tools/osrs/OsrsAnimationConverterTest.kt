package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsAnimationConverterTest {
    @Test
    fun baseAddsNativeColumnsAndPreservesLabelOrder() {
        val base = OsrsAnimationConverter.convertBase(6000, bytes(3, 0, 1, 3, 1, 2, 0, 9, 255, 7))
        assertContentEquals(intArrayOf(0, 1, 3), base.types)
        assertContentEquals(intArrayOf(0, 0, 0), base.shadowed)
        assertContentEquals(intArrayOf(65535, 65535, 65535), base.submeshes)
        assertContentEquals(intArrayOf(255, 7), base.labels[1])
    }

    @Test
    fun sparseFrameKeepsTransformIndicesDefaultsAndSignedSmartWidths() {
        val base = OsrsAnimationConverter.convertBase(6000, bytes(4, 0, 1, 2, 3, 0, 0, 0, 0))
        val frame = OsrsAnimationConverter.convertFrame(0x7fffFFFF, bytes(0, 42, 4, 0, 1, 2, 4, 63, 192, 1, 127), base)
        assertEquals(6000, frame.baseId)
        assertEquals(0x7fffFFFF, frame.id)
        assertContentEquals(intArrayOf(0, 1, 2, 4), frame.flags)
        assertContentEquals(intArrayOf(-4, 16, 63), frame.values)
        assertContentEquals(intArrayOf(1), frame.wideValues)
    }

    @Test
    fun rotationNormalizationMatchesGielenorForEverySignedSmart() {
        val base = OsrsAnimationConverter.convertBase(5000, bytes(1, 2, 0))
        for (raw in -16384..16383) {
            val encoded = raw + 49152
            val frame = OsrsAnimationConverter.convertFrame(1, bytes(0, 0, 1, 1, encoded ushr 8, encoded), base)
            val source = ((raw * 16).toShort().toInt() shl 2) and 16383
            assertEquals(source, (frame.values.single() shl 2) and 16383)
        }
    }

    @Test
    fun translationOverflowFailsRatherThanChangingMovement() {
        val base = OsrsAnimationConverter.convertBase(1, bytes(1, 1, 0))
        assertFailsWith<IllegalArgumentException> {
            OsrsAnimationConverter.convertFrame(1, bytes(0, 0, 1, 1, 208, 0), base)
        }
        val wrapped = OsrsAnimationConverter.convertFrame(1, bytes(0, 0, 1, 1, 255, 255), base)
        assertContentEquals(intArrayOf(-4), wrapped.values)
    }

    @Test
    fun sequenceRemapsPhysicalFrameSetsAndUnsignedFiles() {
        val seq = OsrsAnimationConverter.convertSequence(20000, bytes(1, 0, 2, 0, 5, 0, 9, 255, 254, 128, 1, 0, 42, 0, 43, 2, 0, 2, 8, 3, 11, 1, 0), mapOf(5042 to 6000, 5043 to 6001))
        assertContentEquals(intArrayOf(5, 9), seq.durations)
        assertContentEquals(intArrayOf((6000 shl 16) or 65534, (6001 shl 16) or 32769), seq.frames)
        assertEquals(2, seq.loopOffset)
        assertEquals(3, seq.maxLoops)
        assertEquals(1, seq.replayMode)
        assertEquals(0, seq.animatingPrecedence)
    }

    @Test
    fun sequenceRequiresExplicitPreviewToDiscardForeignDependencies() {
        val data = bytes(6, 2, 1, 13, 1, 1, 2, 3, 0)
        assertFailsWith<IllegalArgumentException> { OsrsAnimationConverter.convertSequence(1, data, emptyMap()) }
        val converted = OsrsAnimationConverter.convertSequence(1, data, emptyMap(), true)
        assertEquals(-1, converted.leftHandItem)
        assertEquals(null, converted.sounds)
    }

    @Test
    fun malformedAndUnsupportedDataFailsClosed() {
        for (data in listOf(bytes(), bytes(1), bytes(1, 6, 0), bytes(0, 0))) {
            assertFailsWith<IllegalArgumentException> { OsrsAnimationConverter.convertBase(1, data) }
        }
        val base = OsrsAnimationConverter.convertBase(1, bytes(1, 0, 0))
        for (data in listOf(bytes(0, 0, 1, 8), bytes(0, 0, 1, 1), bytes(0, 0, 0, 0), bytes(0, 0, 2, 0, 0))) {
            assertFailsWith<IllegalArgumentException> { OsrsAnimationConverter.convertFrame(1, data, base) }
        }
        for (data in listOf(bytes(), bytes(14, 0), bytes(0, 0), bytes(5), bytes(5, 1, 5, 2, 0))) {
            assertFailsWith<IllegalArgumentException> { OsrsAnimationConverter.convertSequence(1, data, emptyMap()) }
        }
    }

    @Test
    fun sourceOpcodeFourHasNoRetainedStateInGielenorClient() {
        val withFlag = OsrsAnimationConverter.convertSequence(20000, bytes(4, 5, 7, 0), emptyMap())
        val withoutFlag = OsrsAnimationConverter.convertSequence(20000, bytes(5, 7, 0), emptyMap())
        assertEquals(withoutFlag, withFlag)
        assertEquals(7, withFlag.priority)
    }
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
