package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrsSpotConverterTest {
    @Test fun `physical references and presentation survive remapping`() {
        val raw = OsrsSpotConverter.parse(6382, byteArrayOf(1, 0, 12, 2, 0, 34, 4, 0, 64, 6, 0, 90, 0))
        val converted = OsrsSpotConverter.convert(9000, raw, mapOf(200012 to 123), mapOf(20034 to 456))
        assertEquals(123, converted.modelId)
        assertEquals(456, converted.animationId)
        assertEquals(64, converted.sizeXY)
        assertEquals(90, converted.rotation)
    }
    @Test fun `missing references and unsupported dialect are not silently accepted`() {
        assertFailsWith<IllegalStateException> { OsrsSpotConverter.parse(6382, byteArrayOf(99, 0)) }
        assertFailsWith<IllegalArgumentException> { OsrsSpotConverter.parse(6382, byteArrayOf(0)) }
        val raw = OsrsSpotConverter.parse(6382, byteArrayOf(1, 0, 12, 0))
        assertFailsWith<NoSuchElementException> { OsrsSpotConverter.convert(9000, raw, emptyMap(), emptyMap()) }
    }
}
