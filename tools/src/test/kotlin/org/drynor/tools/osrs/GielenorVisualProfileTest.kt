package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GielenorVisualProfileTest {
    @Test
    fun resolvesClientOnlyMaterialAliases() {
        assertEquals(8, GielenorVisualProfile.runtimeMaterial(10))
        assertEquals(42, GielenorVisualProfile.runtimeMaterial(7))
        assertEquals(2710, GielenorVisualProfile.runtimeMaterial(59))
        assertEquals(12, GielenorVisualProfile.runtimeMaterial(12))
    }

    @Test
    fun keepsNativeBaseZeroUnlessSequenceForcesOsrs() {
        assertEquals(0, GielenorVisualProfile.sourceBaseId(0, false))
        assertEquals(5000, GielenorVisualProfile.sourceBaseId(0, true))
        assertEquals(5123, GielenorVisualProfile.sourceBaseId(123, false))
        assertEquals(true, GielenorVisualProfile.forceOsrsSkeleton(26657))
        assertEquals(false, GielenorVisualProfile.forceOsrsSkeleton(27567))
    }
    @Test
    fun resolvesNativeUnderlaysExceptExplicitOsrsException() {
        assertEquals(0, GielenorVisualProfile.effectiveUnderlayFile(1))
        assertEquals(145, GielenorVisualProfile.effectiveUnderlayFile(146))
        assertEquals(1026, GielenorVisualProfile.effectiveUnderlayFile(27))
    }

    @Test
    fun keepsVerifiedInfernoOverlayExceptions() {
        assertEquals(1071, GielenorVisualProfile.effectiveInfernoOverlayFile(72))
        assertEquals(1151, GielenorVisualProfile.effectiveInfernoOverlayFile(152))
        assertFailsWith<IllegalArgumentException> { GielenorVisualProfile.effectiveInfernoOverlayFile(6) }
    }
}
