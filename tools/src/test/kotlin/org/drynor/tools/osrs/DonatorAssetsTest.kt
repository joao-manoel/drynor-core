package org.drynor.tools.osrs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic

class DonatorAssetsTest {
    @Test fun `appending donor icons preserves native colours and uses donor frames in rank order`() {
        val native = GraphicDefinition(1455, Array(8) { frame(0x123456) }, intArrayOf(0, 0x123456), width = 13, height = 11)
        val donor = GraphicDefinition(1455, Array(22) { frame(0x550000 + it) }, width = 13, height = 11)
        val merged = DonatorAssets.mergeIcons(native, donor)
        assertEquals(14, merged.graphics!!.size)
        for (i in 0 until 8) assertEquals(0x123456, colour(merged.graphics!![i]))
        DonatorAssets.sourceFrames.forEachIndexed { i, source -> assertEquals(0x550000 + source, colour(merged.graphics!![8 + i])) }
        assertEquals(0x123456, colour(native.graphics!![0]))
        assertContentEquals(byteArrayOf(1), native.graphics!![0].raster)
    }

    @Test fun `different canvas and palette overflow are rejected rather than changing native text metrics or quantizing`() {
        val native = GraphicDefinition(1455, Array(8) { frame(1) }, width = 13, height = 11)
        val donor = GraphicDefinition(1455, Array(22) { frame(2) }, width = 14, height = 11)
        assertFailsWith<IllegalArgumentException> { DonatorAssets.mergeIcons(native, donor) }
        val many = IndexedGraphic(width = 255, height = 1).apply { raster = ByteArray(255) { (it + 1).toByte() }; palette = IntArray(256) { it } }
        val full = native.copy(graphics = Array(8) { many }, width = 255)
        val extra = donor.copy(graphics = Array(22) { frame(1000) }, width = 255)
        assertFailsWith<IllegalArgumentException> { DonatorAssets.mergeIcons(full, extra) }
    }

    private fun frame(colour: Int) = IndexedGraphic(width = 1, height = 1).apply { raster = byteArrayOf(1); palette = intArrayOf(0, colour) }
    private fun colour(frame: IndexedGraphic) = frame.palette[frame.raster[0].toInt() and 255]
}
