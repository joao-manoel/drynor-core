package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

class ZulrahInventoryTest {
    @Test
    fun malformedItemBecomesExplicitAssetFailure() {
        val report = ZulrahInventory.asset(42926) {
            OsrsItemConverter.decode(42926, byteArrayOf(2, 65))
            error("Truncated string must fail before here")
        }
        assertEquals("error", report.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(report.getValue("error").jsonPrimitive.content.contains("EOF"))
    }

    @Test
    fun placeholderLinksAreRetainedWithoutPretendingNativeSupport() {
        val links = linkedMapOf<Int, Int>()
        OsrsItemConverter.decode(42926, byteArrayOf(148.toByte(), 0, 7, 149.toByte(), 0, 8, 0), links)
        assertEquals(mapOf(148 to 30007, 149 to 30008), links)
    }
    @Test
    fun offsetsOnlyConfirmedOsrsRegions() {
        for (region in listOf(8751, 9007, 9008)) assertEquals(100000, ZulrahInventory.objectOffset(region))
        for (region in listOf(259, 9263, 9264)) assertEquals(0, ZulrahInventory.objectOffset(region))
    }
}
