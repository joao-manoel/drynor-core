package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZulrahNativeSurroundingsTest {
    @Test
    fun acceptsOnlyDocumentedCursorAndDynamicSlotMetadataDifferences() {
        val native = byteArrayOf(14, 2, 99, 0, 0, 59, 0)
        val source = byteArrayOf(44, 0, 39, 14, 2, 190.toByte(), 0, 59, 0)
        ZulrahNativeSurroundings.requireEquivalentNativeDefinition(1, source, native)
    }

    @Test
    fun refusesGeometryChangesAndUnknownTrailingPayloads() {
        val native = byteArrayOf(14, 2, 0)
        assertFailsWith<IllegalArgumentException> {
            ZulrahNativeSurroundings.requireEquivalentNativeDefinition(1, byteArrayOf(14, 3, 0), native)
        }
        assertFailsWith<IllegalArgumentException> {
            ZulrahNativeSurroundings.requireEquivalentNativeDefinition(1, byteArrayOf(14, 2, 0, 44), native)
        }
        assertFailsWith<IllegalArgumentException> {
            ZulrahNativeSurroundings.requireEquivalentNativeDefinition(1, byteArrayOf(44, 0), native)
        }
    }

    @Test
    fun nativeEnvironmentRoundTripAndUnknownOpcodeFailure() {
        val bytes = ByteArray(16384) + byteArrayOf(0, 0, 2, 1, 2, 3)
        val decoded = ZulrahNativeSurroundings.decodeTerrain(bytes, 8752)
        assertEquals(listOf(0, 2), decoded.effects.map { it.opcode })
        assertFailsWith<IllegalStateException> {
            ZulrahNativeSurroundings.decodeTerrain(ByteArray(16384) + byteArrayOf(127), 8752)
        }
    }

    @Test
    fun landscapeZeroPaddingIsAllowedButUnknownTailRejected() {
        assertEquals(emptyList(), ZulrahNativeSurroundings.decodeLocations(byteArrayOf(0, 0, 0), 9263))
        assertFailsWith<IllegalArgumentException> {
            ZulrahNativeSurroundings.decodeLocations(byteArrayOf(0, 1), 9263)
        }
    }
}
