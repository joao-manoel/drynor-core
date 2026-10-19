package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OsrsNpcConverterTest {
    private fun bytes(vararg ints: Int) = ints.map(Int::toByte).toByteArray()
    private fun convert(data: ByteArray, preview: Boolean = true) = OsrsNpcConverter.convert(
        500, data, mapOf(200012 to 700, 200034 to 701), (1..8).associate { (20000 + it) to (800 + it) }, 900, preview
    )

    @Test fun mapsBodyHeadAndExplicitBasReferences() {
        val result = convert(bytes(1, 1, 0, 12, 60, 1, 0, 34, 13, 0, 1, 14, 0, 2,
            15, 0, 3, 16, 0, 4, 17, 0, 5, 0, 6, 0, 7, 0, 8, 0))
        assertContentEquals(intArrayOf(700), result.npc.modelIds)
        assertContentEquals(intArrayOf(701), result.npc.dialogueModels)
        assertEquals(900, result.npc.renderEmote)
        assertEquals(900, result.bas.id)
        assertEquals(801, result.bas.standAnimation)
        assertEquals(805, result.bas.walkAnimation)
        assertEquals(803, result.bas.standTurn1)
        assertEquals(804, result.bas.standTurn2)
        assertEquals(806, result.bas.walkDir3)
        assertEquals(807, result.bas.walkDir2)
        assertEquals(808, result.bas.walkDir1)
        assertEquals(-1, result.bas.runAnimation)
    }

    @Test fun preservesAppearanceAndPhysicalProperties() {
        val npc = convert(bytes(2, 90, 117, 107, 0, 12, 3, 40, 1, 255, 255, 128, 0,
            41, 1, 0, 10, 0, 7, 95, 0, 99, 97, 0, 150, 98, 0, 160,
            100, 254, 101, 253, 103, 0, 64, 107, 109, 93, 0)).npc
        assertEquals("Zuk", npc.name)
        assertEquals(3, npc.size)
        assertContentEquals(shortArrayOf(-1), npc.originalColours)
        assertContentEquals(shortArrayOf(-32768), npc.modifiedColours)
        assertContentEquals(shortArrayOf(8), npc.originalTextureColours)
        assertContentEquals(shortArrayOf(42), npc.modifiedTextureColours)
        assertEquals(99, npc.combat)
        assertEquals(150, npc.scaleXY)
        assertEquals(160, npc.scaleZ)
        assertEquals(-2, npc.lightModifier)
        assertEquals(-15, npc.shadowModifier)
        assertEquals(64, npc.rotation)
        assertFalse(npc.clickable)
        assertFalse(npc.slowWalk)
        assertFalse(npc.drawMinimapDot)
    }

    @Test fun missingAnimationsAndAmbiguousAssetsAreExplicit() {
        assertEquals(-1, convert(bytes(13, 255, 255, 0)).bas.standAnimation)
        val preview = convert(bytes(102, 0, 4, 111, 0))
        assertEquals(-1, preview.npc.headIcon)
        assertTrue(preview.warnings.any { "head icon 4" in it })
        assertTrue(preview.warnings.any { "opcode 111" in it })
        assertFailsWith<IllegalArgumentException> { convert(bytes(102, 0, 4, 0), false) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(41, 1, 0, 59, 0, 1, 0)) }
    }

    @Test fun retainsDuplicateParametersAndHiddenOptions() {
        val npc = convert(bytes(30, 104, 105, 100, 100, 101, 110, 0,
            249, 2, 0, 0, 0, 1, 255, 255, 255, 255, 1, 0, 0, 1, 65, 0, 0)).npc
        assertEquals(null, npc.options[0])
        assertEquals(2, npc.paramRecords!!.size)
        assertEquals(-1, npc.paramRecords!![0].value)
        assertEquals("A", npc.params!![1])
    }

    @Test fun rejectsUnsupportedAndMalformedDefinitions() {
        assertFailsWith<IllegalStateException> { convert(bytes(106, 0)) }
        assertFailsWith<IllegalStateException> { convert(bytes(250, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(1, 1, 0, 99, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(13, 0, 99, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(60, 1, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(2, 65)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(0, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(12, 0, 0)) }
    }
}
