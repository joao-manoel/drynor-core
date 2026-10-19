package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OsrsObjectConverterTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun convert(data: ByteArray, preview: Boolean = false) = OsrsObjectConverter.convert(
        500, data, mapOf(200012 to 700, 200034 to 701), mapOf(20056 to 800), emptyMap(), preview
    )

    @Test fun typedModelsAndAnimationUsePhysicalMappings() {
        val result = convert(bytes(1, 2, 0, 12, 10, 0, 34, 22, 24, 0, 56, 0))
        assertContentEquals(byteArrayOf(10, 22), result.modelTypes)
        assertContentEquals(intArrayOf(700), result.modelIds!![0])
        assertContentEquals(intArrayOf(701), result.modelIds!![1])
        assertEquals(800, result.singleAnimation)
    }

    @Test fun untypedModelsFollowGielenorAllShapesPolicy() {
        val result = convert(bytes(5, 2, 0, 12, 0, 34, 0))
        assertEquals(23, result.modelIds!!.size)
        assertContentEquals(intArrayOf(700, 701), result.modelIds!![22])
    }

    @Test fun preservesCollisionAndSignedOffsetsInNativeUnits() {
        val result = convert(bytes(17, 27, 74, 69, 9, 70, 255, 254, 71, 0, 3, 72, 128, 0, 28, 10, 29, 254, 39, 253, 0))
        assertEquals(1, result.solid)
        assertFalse(result.blocksSky)
        assertFalse(result.blocksProjectiles)
        assertEquals(0, result.block)
        assertEquals(9, result.blockFlag)
        assertEquals(-8, result.offsetX)
        assertEquals(12, result.offsetZ)
        assertEquals(-131072, result.offsetY)
        assertEquals(40, result.offsetMultiplier)
        assertEquals(-2, result.brightness)
        assertEquals(-15, result.contrast)
    }

    @Test fun previewOmissionsAreExplicitAndStillBoundsChecked() {
        val data = bytes(24, 0, 56, 68, 0, 12, 82, 0, 8, 78, 0, 5, 3, 79, 0, 1, 0, 2, 3, 1, 0, 4, 0)
        assertFailsWith<IllegalStateException> { convert(data) }
        val result = convert(data, true)
        assertEquals(-1, result.singleAnimation)
        assertEquals(-1, result.mapscene)
        assertEquals(-1, result.mapDefinitionId)
        assertEquals(-1, result.anInt3015)
        assertFailsWith<IllegalArgumentException> { convert(bytes(79, 0, 1), true) }
    }

    @Test fun materialsAndColoursUseEffectiveClientValues() {
        val result = convert(bytes(40, 1, 255, 255, 0, 2, 41, 2, 0, 10, 0, 7, 0, 5, 0, 6, 0))
        assertContentEquals(shortArrayOf(-1), result.originalColours)
        assertContentEquals(shortArrayOf(8, 5), result.originalTextureColours)
        assertContentEquals(shortArrayOf(42, 6), result.modifiedTextureColours)
        assertFailsWith<IllegalArgumentException> { convert(bytes(41, 1, 0, 59, 0, 1, 0)) }
    }

    @Test fun rejectsMorphUnknownMissingMappingAndMalformedPayloads() {
        assertFailsWith<IllegalStateException> { convert(bytes(77, 0), true) }
        assertFailsWith<IllegalStateException> { convert(bytes(250, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(5, 1, 0, 99, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(1, 1, 0)) }
        assertFailsWith<IllegalArgumentException> { convert(bytes(0, 0)) }
    }

    @Test fun keepsDuplicateParameterRecords() {
        val result = convert(bytes(249, 2, 0, 0, 0, 1, 255, 255, 255, 255, 1, 0, 0, 1, 65, 0, 0))
        assertEquals(2, result.paramRecords!!.size)
        assertEquals("A", result.params!![1])
    }
}
