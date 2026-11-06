package org.drynor.tools.tarnish

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TarnishAnimationsTest {
    @Test fun readsActualLegacyRingSequenceWithoutDroppingFields() {
        val source = "0200100100100005000500050005000500050005000500050005000500050005000500050005000500070008000b0009000c000f0006000a00040001000d00000003000e00020d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d0d1d00".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val parsed = TarnishAnimations.sequence(source)
        assertEquals(16, parsed.loopOffset)
        assertContentEquals(IntArray(16) { 5 }, parsed.durations)
        assertEquals(setOf(3357), parsed.frames.map { it ushr 16 }.toSet())
        val converted = TarnishAnimations.convertSequence(30000, parsed, mapOf(3357 to 1234))
        assertContentEquals(parsed.frames.map { (1234 shl 16) or (it and 65535) }.toIntArray(), converted.frames)
    }

    @Test fun rejectsSkeletalAndUnreviewedSequencePayloads() {
        assertFailsWith<IllegalStateException> { TarnishAnimations.sequence(byteArrayOf(14,0,0,0,1,0)) }
    }

    @Test fun acceptsOnlyExplicitlyEmptySkeletalBaseTrailer() {
        val legacy = byteArrayOf(1,0,1,0)
        assertContentEquals(legacy, TarnishAnimations.legacyBase(legacy + byteArrayOf(0,0)))
        assertFailsWith<IllegalArgumentException> { TarnishAnimations.legacyBase(legacy + byteArrayOf(0,1)) }
    }

    @Test fun sequenceTableHeaderIsHighestIdWhileBaseTableHeaderIsCount() {
        val table = byteArrayOf(0,1, 0,0,0,1,42, 0,1,0,1,43)
        assertEquals(setOf(0,1), TarnishAnimations.records(table, highestIdHeader = true).keys)
        assertFailsWith<IllegalArgumentException> { TarnishAnimations.records(table) }
    }
}
