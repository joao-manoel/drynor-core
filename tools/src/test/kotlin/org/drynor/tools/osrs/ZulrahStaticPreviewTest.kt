package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZulrahStaticPreviewTest {
    @Test
    fun waterFloorsUseActualNativeClientOverrides() {
        assertEquals(111, ZulrahStaticPreview.overlayFile(6))
        assertEquals(113, ZulrahStaticPreview.overlayFile(85))
        assertEquals(160, ZulrahStaticPreview.overlayFile(161))
        assertEquals(21, ZulrahStaticPreview.overlayFile(22))
        assertEquals(110, ZulrahStaticPreview.underlayFile(111))
    }

    @Test
    fun unverifiedExceptionIsRejectedInsteadOfInventingFloorMapping() {
        assertFailsWith<IllegalArgumentException> { ZulrahStaticPreview.overlayFile(72) }
        assertFailsWith<IllegalArgumentException> { ZulrahStaticPreview.underlayFile(27) }
    }
}
