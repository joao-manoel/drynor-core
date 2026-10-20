package org.drynor.tools.osrs

import kotlin.test.*

class WorldMapAreaRecordsTest {
    private fun record(region: Int,id: Int = 1): ByteArray = WorldMapAreaRecords.region(region,IntArray(4096){19},IntArray(4096),IntArray(4096),List(4096){if(it==0)listOf(id to 192)else emptyList()})
    @Test fun patchPreservesPalettesAndUntouchedChunkBytes() {
        val header = byteArrayOf(1,19,1,112)
        val chunk = byteArrayOf(1,34,48,2,3)+ByteArray(64){248.toByte()}
        val before = WorldMapAreaRecords.parse(header+record(8751)+chunk)
        val after = WorldMapAreaRecords.parse(before.patch(mapOf(8751 to record(8751,130000))))
        assertContentEquals(header,after.header)
        assertContentEquals(chunk,after.records.first().raw)
        assertEquals(listOf(8752,8751),after.records.map { it.region })
    }
    @Test fun truncatedBigSmartAndIllegalPaletteFailClosed() {
        val valid = byteArrayOf(0,0)+record(8751,130000)
        assertFailsWith<IllegalArgumentException> { WorldMapAreaRecords.parse(valid.copyOf(9)) }
        assertFailsWith<IllegalArgumentException> { WorldMapAreaRecords.parse(byteArrayOf(0,0,0,34,47,0)) }
    }
    @Test fun invalidTileLimitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { WorldMapAreaRecords.region(8751,IntArray(4096){256},IntArray(4096),IntArray(4096),List(4096){emptyList()}) }
    }
}
