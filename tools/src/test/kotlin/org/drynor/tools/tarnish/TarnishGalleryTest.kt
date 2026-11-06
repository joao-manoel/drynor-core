package org.drynor.tools.tarnish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder

class TarnishGalleryTest {
    @Test fun nativeFurnitureRecipeUsesMappedMaterialsAndSurvivesNativeEncoding() {
        val card=TarnishGallery.Card(20617,30322,"Ancient altar",80,
            listOf(3420 to 10,8788 to 1,30330 to 1,9044 to 1),"Limestone, magic stone, Ancient signet, sceptre")
        val params=TarnishGallery.cardParams(card)
        assertEquals(80,params[23])
        assertEquals(30330,params[215])
        assertEquals(1,params[216])
        assertFalse(params.values.contains(20611))
        val original=ItemDefinition(card.target).apply { this.params=params }
        val bytes=BufferWriter(4096).apply { with(ItemEncoder()) { encode(original) } }.toArray()
        val decoded=ItemDefinition(card.target).also { ItemDecoder().readLoop(it,BufferReader(bytes)) }
        assertEquals(params,decoded.params)
    }
}
