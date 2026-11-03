package org.drynor.tools.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfernoTicketAssetsTest {
    @Test fun `authored definition retains Destroy and camera fields after encoding`() {
        val item = InfernoTicketAssets.decode(InfernoTicketAssets.itemBytes())
        assertEquals("Unlock", item.options[0])
        assertEquals("Destroy", item.options[4])
        assertFalse(item.options.any { it.equals("Drop", true) })
        assertTrue(item.isDestroyItem())
        assertEquals(InfernoTicketAssets.MODEL_ID, item.modelId)
        assertEquals(1000, item.graphicScale)
        assertEquals(480, item.graphicPitch)
        assertEquals(192, item.graphicCameraRoll)
        assertEquals(0, item.graphicCameraYaw)
        assertEquals(0, item.stackable)
        assertEquals(-1, item.notedTemplateId)
        assertFalse(item.exchangeable)
    }
}
