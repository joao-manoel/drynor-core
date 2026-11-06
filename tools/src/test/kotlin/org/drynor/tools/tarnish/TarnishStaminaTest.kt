package org.drynor.tools.tarnish

import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals

class TarnishStaminaTest {
    @Test fun constructionCardParametersReadTypedKeysAndRejectUnknownTypes() {
        val item=TarnishStamina.parse(30313,HexFormat.of().parseHex("f9020000001700000055010009da546573740000")).definition
        assertEquals(mapOf(23 to 85,2522 to "Test"),item.params)
        assertFailsWith<IllegalArgumentException> { TarnishStamina.parse(30313,HexFormat.of().parseHex("f9010200001700")) }
    }
    private val fourDose=HexFormat.of().parseHex("234472696e6b000c00000190010ae508ffff0402260607cc0500544b00235e004526456d7074790010412801003d162f025374616d696e6120706f74696f6e28342900613152943bac00")
    @Test fun goldenTarnishDosePreservesFramingSignedOffsetAndOptions() {
        val parsed=TarnishStamina.parse(30300,fourDose)
        val item=parsed.definition
        assertEquals("Stamina potion(4)",item.name)
        assertEquals(2789,item.modelId);assertEquals(550,item.graphicScale)
        assertEquals(84,item.graphicPitch);assertEquals(1996,item.graphicCameraRoll)
        assertEquals(-1,item.graphicTranslateY)
        assertEquals("Drink",item.options[0]);assertEquals("Empty",item.options[3])
        assertEquals(12626,item.noteId);assertEquals(-1,item.notedTemplateId)
        assertContentEquals(shortArrayOf(61),item.originalColours)
        assertContentEquals(shortArrayOf(5679),item.modifiedColours)
        assertEquals(35,parsed.presentation["weight"]);assertEquals(69,parsed.presentation["category"])
        assertEquals(15276,parsed.presentation["bankPlaceholderId"])
    }
    @Test fun nativeNoteTranslationHasAnExplicitSourceTemplate() {
        val item=TarnishStamina.parse(30304,HexFormat.of().parseHex("61315162031f00")).definition
        assertEquals(12625,item.noteId);assertEquals(799,item.notedTemplateId)
    }
    @Test fun unknownOpcodeAndTrailingBytesFailClosed() {
        assertFailsWith<IllegalStateException> { TarnishStamina.parse(1,byteArrayOf(99,0)) }
        assertFailsWith<IllegalArgumentException> { TarnishStamina.parse(1,fourDose+byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { TarnishStamina.parse(1,fourDose.copyOf(fourDose.size-1)) }
    }
}
