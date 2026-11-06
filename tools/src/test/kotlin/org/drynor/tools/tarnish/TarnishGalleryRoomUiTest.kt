package org.drynor.tools.tarnish

import org.drynor.cs2.Cs2Codec
import org.drynor.cs2.Cs2Instruction
import org.drynor.cs2.Cs2Op
import org.drynor.cs2.Cs2Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TarnishGalleryRoomUiTest {
    private fun script(height:Int=1468,parent:Int=(402 shl 16) or 12)=Cs2Script("room_scroll",
        listOf(Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT,332),Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT,height),
            Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT,parent),Cs2Instruction(Cs2Op.IF_SETSCROLLSIZE))+
            List(10) { Cs2Instruction(Cs2Op.RETURN) },1,2,3,0,0,0,emptyList())

    @Test fun expandsOnlyTheAuditedHeightAndPreservesTheOtherBytecodeAndStacks() {
        val before=script()
        val after=TarnishGalleryRoomUi.patchScroll(before)
        assertEquals(1532,after.instructions[1].intOperand)
        assertEquals(before.instructions.filterIndexed { index,_->index!=1 },after.instructions.filterIndexed { index,_->index!=1 })
        assertEquals(before.intLocalsCount,after.intLocalsCount)
        assertEquals(before.stringLocalsCount,after.stringLocalsCount)
        assertEquals(before.longLocalsCount,after.longLocalsCount)
        assertEquals(before.switchTables,after.switchTables)
        val decoded=Cs2Codec.decode(Cs2Codec.encode(after))
        assertEquals(after.instructions,decoded.instructions)
    }

    @Test fun refusesAnotherMenuLayoutOrRepeatedPatching() {
        assertFailsWith<IllegalArgumentException> { TarnishGalleryRoomUi.patchScroll(script(1404)) }
        assertFailsWith<IllegalArgumentException> { TarnishGalleryRoomUi.patchScroll(script(parent=123)) }
        assertFailsWith<IllegalArgumentException> { TarnishGalleryRoomUi.patchScroll(TarnishGalleryRoomUi.patchScroll(script())) }
    }
}
