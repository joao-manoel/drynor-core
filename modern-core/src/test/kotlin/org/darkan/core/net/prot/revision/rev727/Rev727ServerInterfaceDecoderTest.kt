package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Rev727ServerInterfaceDecoderTest {
    private val codec = register727()
    private val xteas = intArrayOf(0x12345678, -1, Int.MIN_VALUE, -0x7edcba99)

    private inline fun <reified T : ServerProt> decodeWithXteas(prot: T, xteas: (T) -> IntArray): T {
        val bytes = codec.serverBytes(prot)
        assertEquals((codec.serverProts.getValue(T::class).size as ProtSize.Fixed).length, bytes.size)
        val decoded = codec.decodeServer(prot, bytes) as T
        assertContentEquals(xteas(prot), xteas(decoded))
        return decoded
    }

    @Test
    fun `component-only prots split the hash back into interface and component`() {
        codec.assertServerRoundTrip(IfSetPlayerHead(1477, 65535))
        codec.assertServerRoundTrip(IfSetPlayerModel(0, 0))
        codec.assertServerRoundTrip(IfCloseSub(65535, 32768))
        codec.assertServerRoundTrip(IfMoveSub(548, 12, 746, 40000))
    }

    @Test
    fun `boolean component flags round trip both states`() {
        for (flag in listOf(true, false)) {
            codec.assertServerRoundTrip(IfSetHide(1477, 7, flag))
            codec.assertServerRoundTrip(IfSetTextAntiMacro(1477, 7, flag))
            codec.assertServerRoundTrip(IfSetClickMask(1477, 7, flag))
        }
    }

    @Test
    fun `int valued component prots keep negative and large values`() {
        codec.assertServerRoundTrip(IfSetTextFont(1477, 3, 495))
        codec.assertServerRoundTrip(IfSetGraphic(1477, 3, -1))
        codec.assertServerRoundTrip(IfSetGraphic(1477, 3, 100000))
        codec.assertServerRoundTrip(IfSetAnim(1477, 3, -1))
        codec.assertServerRoundTrip(IfSetAnim(1477, 3, 21000))
        codec.assertServerRoundTrip(IfSetModel(1477, 3, -1))
        codec.assertServerRoundTrip(IfSetModel(1477, 3, 90000))
        codec.assertServerRoundTrip(IfSetNpcHead(1477, 3, -1))
        codec.assertServerRoundTrip(IfSetNpcHead(1477, 3, 16000))
        codec.assertServerRoundTrip(IfSetPlayerHeadOther(1477, 3, Int.MIN_VALUE, 65535))
        codec.assertServerRoundTrip(IfSetPlayerModelOther(1477, 3, 40000, -1))
        codec.assertServerRoundTrip(IfSetPlayerModelOther(1477, 3, -1, 123456))
    }

    @Test
    fun `short valued component prots keep unsigned range and -1 sentinels`() {
        codec.assertServerRoundTrip(IfSetPlayerHeadIgnoreWorn(1477, 3, 0, 40000, -1))
        codec.assertServerRoundTrip(IfSetAngle(1477, 3, 65535, 0, 40000))
        codec.assertServerRoundTrip(IfSetScrollPos(1477, 3, 65535))
        codec.assertServerRoundTrip(IfSetColor(1477, 3, 0xFFFF))
        codec.assertServerRoundTrip(IfSetReCol(1477, 3, 255, 65535, 1))
        codec.assertServerRoundTrip(IfSetReTex(1477, 3, 200, -1, 40000))
        codec.assertServerRoundTrip(OutdatedSetTheoraString(1477, 3, 65535))
    }

    @Test
    fun `positions keep signed offsets`() {
        codec.assertServerRoundTrip(IfSetPosition(1477, 3, -32768, 32767))
        codec.assertServerRoundTrip(IfSetPosition(1477, 3, 32767, -1))
        codec.assertServerRoundTrip(IfSetPosition(1477, 3, 0, 0))
    }

    @Test
    fun `pulse event reassembles the full int from both halves`() {
        codec.assertServerRoundTrip(OutdatedInterfacePulseEvent(1477, 3, -1))
        codec.assertServerRoundTrip(OutdatedInterfacePulseEvent(1477, 3, 0x12348765))
        codec.assertServerRoundTrip(OutdatedInterfacePulseEvent(1477, 3, Int.MIN_VALUE))
    }

    @Test
    fun `item and text prots round trip`() {
        codec.assertServerRoundTrip(IfSetItem(1477, 3, -1, 0))
        codec.assertServerRoundTrip(IfSetItem(1477, 3, 60000, Int.MAX_VALUE))
        codec.assertServerRoundTrip(IfSetText(1477, 3, ""))
        codec.assertServerRoundTrip(IfSetText(1477, 3, "Coins: 1,000 £"))
    }

    @Test
    fun `slot range prots map 65535 back to -1`() {
        codec.assertServerRoundTrip(IfSetEvents(1477, 3, -1, -1, -1))
        codec.assertServerRoundTrip(IfSetEvents(1477, 3, 0, 65534, 0x7FFFFFFE))
        codec.assertServerRoundTrip(IfSetTargetParam(1477, 3, 65535, -1, -1))
        codec.assertServerRoundTrip(IfSetTargetParam(1477, 3, 0, 27, 40000))
    }

    @Test
    fun `top level open keeps xteas and type`() {
        val prot = IfOpenTop(1477, 255, xteas)
        assertEquals(prot, decodeWithXteas(prot) { it.xteas }.copy(xteas = xteas))
    }

    @Test
    fun `sub interface opens keep xteas overlay and target`() {
        for (overlay in listOf(true, false)) {
            val sub = IfOpenSub(1477, 40000, 65535, overlay, xteas)
            assertEquals(sub, decodeWithXteas(sub) { it.xteas }.copy(xteas = xteas))

            val player = IfOpenSubActivePlayer(1477, 3, 60000, overlay, 2047, xteas)
            assertEquals(player, decodeWithXteas(player) { it.xteas }.copy(xteas = xteas))

            val npc = IfOpenSubActiveNpc(1477, 3, 60000, overlay, 32767, xteas)
            assertEquals(npc, decodeWithXteas(npc) { it.xteas }.copy(xteas = xteas))
        }
    }

    @Test
    fun `active object open recovers tile shape and rotation`() {
        for ((tile, overlay) in listOf(Tile(3222, 3218, 3) to true, Tile(0, 0, 0) to false, Tile(16383, 16383, 2) to true)) {
            val prot = IfOpenSubActiveObject(1477, 3, 60000, overlay, xteas, -1, tile, 22, 3)
            assertEquals(prot, decodeWithXteas(prot) { it.xteas }.copy(xteas = xteas))
        }
        val large = IfOpenSubActiveObject(1477, 3, 1, false, xteas, 120000, Tile(3000, 3000, 1), 63, 0)
        assertEquals(large, decodeWithXteas(large) { it.xteas }.copy(xteas = xteas))
    }

    @Test
    fun `active ground item open recovers tile and item`() {
        for ((tile, itemId) in listOf(Tile(3222, 3218, 3) to 995, Tile(16383, 0, 1) to -1, Tile(0, 16383, 0) to 60000)) {
            val prot = IfOpenSubActiveGroundItem(1477, 3, 60000, itemId != 995, tile, itemId, xteas)
            assertEquals(prot, decodeWithXteas(prot) { it.xteas }.copy(xteas = xteas))
        }
    }
}
