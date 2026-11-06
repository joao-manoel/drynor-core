package org.drynor.tools.tarnish

import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class TarnishHouseMaterialsTest {
    @Test fun goldenAncientCrystalPreservesFramingAndOrdinaryBankNoteLink() {
        val bytes = HexFormat.of().parseHex("0c0003d0904b352701857507fff408ffe104051c0602c15f0237050460104102416e6369656e74206372797374616c0061552d94552e00")
        val item = TarnishStamina.parse(30308, bytes).definition
        assertEquals("Ancient crystal", item.name)
        assertEquals(34165, item.modelId)
        assertEquals(-12, item.graphicTranslateX); assertEquals(-31, item.graphicTranslateY)
        assertEquals(21805, item.noteId); assertEquals(-1, item.notedTemplateId)
    }

    @Test fun goldenFairyEnchantmentHasNoOrdinaryNoteAndKeepsItsRecolours() {
        val bytes = HexFormat.of().parseHex("010a7307000408000404034a0501794b00500c00000064102803286fa0de1b9e138a03c0e7ad02466169727920656e6368616e746d656e740094508200")
        val item = TarnishStamina.parse(30310, bytes).definition
        assertEquals("Fairy enchantment", item.name)
        assertEquals(2675, item.modelId); assertEquals(842, item.graphicScale)
        assertEquals(377, item.graphicPitch); assertEquals(4, item.graphicTranslateX)
        assertEquals(-1, item.noteId); assertEquals(-1, item.notedTemplateId)
        assertEquals(3, item.originalColours!!.size)
    }
}
