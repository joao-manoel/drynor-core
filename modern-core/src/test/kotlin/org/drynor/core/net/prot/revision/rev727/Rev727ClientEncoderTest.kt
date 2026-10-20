package org.drynor.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.drynor.core.clientwatch.MouseTrailStep
import org.drynor.core.clientwatch.MouseTrailStep.Type
import org.drynor.core.fixChatMessage
import org.drynor.core.model.Preference
import org.drynor.core.net.prot.ClientProt
import org.drynor.core.net.prot.assertClientRoundTrip
import org.drynor.core.net.prot.clientBytes
import org.drynor.core.net.prot.decodeClient
import org.drynor.core.net.prot.packet.*
import world.gregs.voidps.cache.secure.Huffman
import world.gregs.voidps.type.Tile
import org.drynor.core.net.prot.ChatHuffman
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Rev727ClientEncoderTest {
    private val codec = register727()
    private val huffman = ChatHuffman(Huffman().load(syntheticHuffmanTable()))

    private fun syntheticHuffmanTable() = ByteArray(256) { symbol ->
        when (symbol) {
            in 'a'.code..'h'.code -> 4
            in 0..7 -> 8
            else -> 9
        }.toByte()
    }

    private fun opcodeOf(prot: ClientProt) = codec.clientProts.getValue(prot::class).opcodeFor(prot)

    private fun decodeAs(opcode: Int, bytes: ByteArray): ClientProt? = runBlocking {
        codec.decode(codec.clientProtsByOpcode.getValue(opcode), opcode, Buffer().apply { write(bytes) })
    }

    @Test
    fun `keyboard batch matches client golden bytes including signed keys and unsigned elapsed time`() {
        // One opcode per batch; each event is an untransformed signed byte and unsigned BE medium.
        val golden = byteArrayOf(82, 0, 0, 1, 81, 0, 0, 2, -1, -1, -1, -1, 13, 0, 0, 0)
        val batch = KeyPress(82, 1, listOf(KeyPressEvent(81, 2), KeyPressEvent(-1, 0xffffff), KeyPressEvent(13, 0)))
        assertEquals(28, opcodeOf(batch))
        assertContentEquals(golden, codec.clientBytes(batch))
        assertEquals(batch, decodeAs(28, golden))
        codec.assertClientRoundTrip(batch)
    }

    @Test
    fun `keyboard batch rejects empty and partial event bodies`() {
        for (length in listOf(0, 1, 2, 3, 5, 6, 7, 9, 65536)) {
            assertFailsWith<IllegalArgumentException>("length=$length") {
                decodeAs(28, ByteArray(length))
            }
        }
    }

    @Test
    fun `payload-less prots round trip`() {
        codec.assertClientRoundTrip(Ping())
        codec.assertClientRoundTrip(CloseInterface())
        codec.assertClientRoundTrip(RegionLoadedConfirm())
    }

    @Test
    fun `input and telemetry prots round trip`() {
        codec.assertClientRoundTrip(UnusedClanOp(pid = 65535, username = "Zezima"))
        codec.assertClientRoundTrip(ReflectionResponse(id = -123456789, data = byteArrayOf(0, -1, 127, -128)))
        codec.assertClientRoundTrip(ReflectionResponse(id = 0, data = byteArrayOf()))
        codec.assertClientRoundTrip(ClientFocus(true))
        codec.assertClientRoundTrip(ClientFocus(false))
        codec.assertClientRoundTrip(ClientScreenSize(displayMode = 255, width = 1920, height = 65535, switchDisplayMode = true))
        codec.assertClientRoundTrip(MoveCamera(angleX = 2047, angleY = 65535))
        codec.assertClientRoundTrip(SendFps(key1 = -123456789, key2 = Int.MIN_VALUE, fps = 60))
        codec.assertClientRoundTrip(SendFps(key1 = 0, key2 = -1, fps = -1))
        codec.assertClientRoundTrip(MouseClick(mouseButton = 1, time = 0x7FFF, x = 65535, y = 65535))
        codec.assertClientRoundTrip(MouseClick(mouseButton = 0, time = 0, x = 0, y = 0))
        codec.assertClientRoundTrip(MouseButtonClick(mouseButton = 2, time = 65535, x = 765, y = 65535, hw = true))
        codec.assertClientRoundTrip(
            MouseButtonClick(mouseButton = 127, time = 0, x = 1, y = 2, hw = false),
            expected = MouseButtonClick(mouseButton = 127, time = 0, x = 1, y = 2, hw = true)
        )
        codec.assertClientRoundTrip(KeyPress(keyCode = 85, time = 0xFFFFFF))
        codec.assertClientRoundTrip(KeyPress(keyCode = -1, time = 0))
        codec.assertClientRoundTrip(ClientCheat(client = true, save = false, command = "tele 3200 3200"))
        codec.assertClientRoundTrip(ClientCheat(client = false, save = true, command = ""))
    }

    @Test
    fun `mouse trail goes out on the native opcode with its trailing byte when every step is hardware`() {
        val steps = arrayOf(
            MouseTrailStep(Type.MOVE_OFFSET, frames = 7, x = 63, y = 0),
            MouseTrailStep(Type.MOVE_OFFSET, frames = 31, x = 255, y = 200),
            MouseTrailStep(Type.MOVE_OFFSET, frames = 3, x = 64, y = 5),
            MouseTrailStep(Type.SET_POSITION, frames = 31, x = 65535, y = -1),
            MouseTrailStep(Type.SET_POSITION, frames = 0, x = 0, y = -32768),
            MouseTrailStep(Type.SET_POSITION, frames = 8191, x = 800, y = 600),
        )
        val trail = MoveMouseData(frameCount = 0, frameSteps = 255, steps = steps)
        assertEquals(40, opcodeOf(trail))
        assertEquals(2 + 3 + 4 + 4 + 6 + 6 + 7, codec.clientBytes(trail).size)
        codec.assertClientRoundTrip(trail)
    }

    @Test
    fun `mouse trail goes out on the java opcode when its steps are software`() {
        val steps = arrayOf(
            MouseTrailStep(Type.MOVE_OFFSET, frames = 0, x = 32, y = 32, hardware = false),
            MouseTrailStep(Type.MOVE_OFFSET, frames = 8, x = 10, y = 10, hardware = false),
            MouseTrailStep(Type.SET_POSITION, frames = 40, x = 1234, y = 32767, hardware = false),
        )
        val trail = MoveMouseData(frameCount = 3, frameSteps = 12, steps = steps)
        assertEquals(78, opcodeOf(trail))
        assertEquals(2 + 2 + 3 + 6, codec.clientBytes(trail).size)
        codec.assertClientRoundTrip(trail)
        codec.assertClientRoundTrip(MoveMouseData(frameCount = 0, frameSteps = 0, steps = emptyArray()))
    }

    @Test
    fun `entity ops round trip on every opcode`() {
        val playerOps = intArrayOf(66, 6, 31, 89, 103, 1, 51, 94, 53, 70)
        for (opNum in 1..10) {
            val op = OpPlayer(opNum = opNum, playerIndex = 2047, forceRun = opNum % 2 == 0)
            assertEquals(playerOps[opNum - 1], opcodeOf(op))
            codec.assertClientRoundTrip(op)
        }
        val npcOps = intArrayOf(65, 16, 50, 77, 95, 3)
        for (opNum in 1..6) {
            val op = OpNpc(opNum = opNum, npcIndex = 65535, forceRun = opNum % 2 == 1)
            assertEquals(npcOps[opNum - 1], opcodeOf(op))
            codec.assertClientRoundTrip(op)
        }
        val objOps = intArrayOf(75, 93, 38, 32, 48, 73)
        for (opNum in 1..6) {
            val op = OpObj(opNum = opNum, objectId = if (opNum == 1) -1 else 131072 + opNum, x = 65535, y = 3200, forceRun = opNum % 2 == 0)
            assertEquals(objOps[opNum - 1], opcodeOf(op))
            codec.assertClientRoundTrip(op)
        }
        val groundItemOps = intArrayOf(24, 25, 54, 8, 43, 61)
        for (opNum in 1..6) {
            val op = OpGroundItem(opNum = opNum, itemId = 65535 - opNum, x = 3222, y = 65535, forceRun = opNum % 2 == 1)
            assertEquals(groundItemOps[opNum - 1], opcodeOf(op))
            codec.assertClientRoundTrip(op)
        }
    }

    @Test
    fun `minimap walk round trips on opcode 42`() {
        val walk = Walk(x = 65535, y = 3200, forceRun = true, minimap = true)
        assertEquals(42, opcodeOf(walk))
        codec.assertClientRoundTrip(walk)
    }

    @Test
    fun `plain walk goes out on opcode 33 and decodes back through it`() {
        val walk = Walk(x = 3200, y = 65535, forceRun = false, minimap = false)
        assertEquals(33, opcodeOf(walk))
        val bytes = codec.clientBytes(walk)
        assertEquals(5, bytes.size)
        assertEquals(walk, decodeAs(33, bytes))
    }

    @Test
    fun `interface button round trips on every opcode with none sentinels`() {
        val ifButtonOps = intArrayOf(96, 27, 68, 9, 72, 19, 23, 21, 22, 81)
        for (opNum in 1..10) {
            val button = IfButton(opNum = opNum, interfaceId = 1477, componentId = 65534, slotId = 27, itemId = 40000)
            assertEquals(ifButtonOps[opNum - 1], opcodeOf(button))
            codec.assertClientRoundTrip(button)
            codec.assertClientRoundTrip(button.copy(componentId = -1, slotId = -1, itemId = -1))
        }
    }

    @Test
    fun `interface targeting prots round trip`() {
        codec.assertClientRoundTrip(IfOnIf(fromInter = 1473, fromComp = 34, fromSlot = 27, fromItemId = 40000, toInter = 32767, toComp = -1, toSlot = -1, toItemId = -1))
        codec.assertClientRoundTrip(IfOnIf(fromInter = 0, fromComp = -1, fromSlot = -1, fromItemId = -1, toInter = 1473, toComp = 65534, toSlot = 65534, toItemId = 65534))
        codec.assertClientRoundTrip(IfOnObject(interfaceId = 1473, componentId = -1, slotId = -1, itemId = -1, objectId = 131072, x = 65535, y = 3200, forceRun = true))
        codec.assertClientRoundTrip(IfOnObject(interfaceId = 192, componentId = 60, slotId = 5, itemId = 40000, objectId = -1, x = 0, y = 65535, forceRun = false))
        codec.assertClientRoundTrip(IfOnGroundItem(interfaceId = 1473, componentId = 34, slotId = -1, itemIdContainer = -1, itemId = -1, x = 65535, y = 65535, forceRun = true))
        codec.assertClientRoundTrip(IfOnGroundItem(interfaceId = 192, componentId = -1, slotId = 27, itemIdContainer = 40000, itemId = 32767, x = 3200, y = 3201, forceRun = false))
        codec.assertClientRoundTrip(IfOnNpc(interfaceId = 1473, componentId = -1, slotId = -1, itemId = -1, npcIndex = 65535, forceRun = true))
        codec.assertClientRoundTrip(IfOnNpc(interfaceId = 192, componentId = 60, slotId = 27, itemId = 40000, npcIndex = 0, forceRun = false))
        codec.assertClientRoundTrip(IfOnPlayer(interfaceId = 1473, componentId = -1, slotId = -1, itemId = -1, playerIndex = 65535, forceRun = true))
        codec.assertClientRoundTrip(IfOnPlayer(interfaceId = 192, componentId = 60, slotId = 27, itemId = 40000, playerIndex = 2047, forceRun = false))
        codec.assertClientRoundTrip(IfOnTile(interfaceId = 1473, componentId = -1, slotId = -1, itemId = -1, x = 65535, y = 0))
        codec.assertClientRoundTrip(IfOnTile(interfaceId = 192, componentId = 60, slotId = 27, itemId = 40000, x = 3200, y = 65535))
        codec.assertClientRoundTrip(IfContinue(interfaceId = 1184, componentId = 15, slotId = -1))
        codec.assertClientRoundTrip(IfContinue(interfaceId = 32767, componentId = -1, slotId = 65534))
        codec.assertClientRoundTrip(IfDragOntoIf(fromInter = 762, fromComp = 95, fromSlot = 12, fromItemId = 40000, toInter = 762, toComp = 44, toSlot = -1, toItemId = -1))
        codec.assertClientRoundTrip(IfDragOntoIf(fromInter = 32767, fromComp = -1, fromSlot = -1, fromItemId = -1, toInter = 0, toComp = 65534, toSlot = 65534, toItemId = 65534))
    }

    @Test
    fun `dialog prots round trip`() {
        codec.assertClientRoundTrip(ResumeTextDialog("Some text"))
        codec.assertClientRoundTrip(ResumeTextDialog(""))
        codec.assertClientRoundTrip(ResumeNameDialog("Zezima"))
        codec.assertClientRoundTrip(ResumeClanForumQFCDialog("1-2-3-4"))
        codec.assertClientRoundTrip(ResumeHSLDialog(65535))
        codec.assertClientRoundTrip(ResumeCountDialog(Int.MAX_VALUE))
        codec.assertClientRoundTrip(ResumeCountDialog(-1))
        codec.assertClientRoundTrip(ResumeItemSelect(-1))
        codec.assertClientRoundTrip(ResumeItemSelect(32767))
    }

    @Test
    fun `chat is huffman compressed after its colour and effect`() {
        val chat = Chat(color = 11, effect = 5, message = "Hello world. Buying gf 5k ea! Zzz?")
        val bytes = codec.clientBytes(chat, huffman)
        assertEquals(11, bytes[0].toInt())
        assertEquals(5, bytes[1].toInt())
        assertEquals(chat.message.length, bytes[2].toInt())
        codec.assertClientRoundTrip(chat, context = huffman)
        codec.assertClientRoundTrip(Chat(color = 0, effect = 0, message = "Abcdefgh"), context = huffman)
        codec.assertClientRoundTrip(Chat(color = 0, effect = 0, message = ""), context = huffman)
    }

    @Test
    fun `chat decodes to the clamped colour and fixed casing the server applies`() {
        val shouted = "hELLO THERE. hOW aRE yOU"
        codec.assertClientRoundTrip(
            Chat(color = 20, effect = 9, message = shouted),
            expected = Chat(color = 12, effect = 5, message = shouted.fixChatMessage()),
            context = huffman
        )
    }

    @Test
    fun `chat longer than a one byte smart round trips`() {
        codec.assertClientRoundTrip(Chat(color = 1, effect = 2, message = "A" + "bcdefgh ".repeat(20)), context = huffman)
    }

    @Test
    fun `private message is huffman compressed after its recipient`() {
        codec.assertClientRoundTrip(PrivateMessage(toDisplayName = "Mod Ash", message = "Hey there, got a sec?"), context = huffman)
        codec.assertClientRoundTrip(PrivateMessage(toDisplayName = "", message = ""), context = huffman)
    }

    @Test
    fun `quick chat round trips with and without dynamic values`() {
        codec.assertClientRoundTrip(QuickChatPublic(chatType = 2, qcId = 65535, messageData = byteArrayOf(1, -1, 0)))
        codec.assertClientRoundTrip(QuickChatPublic(chatType = -1, qcId = 0, messageData = null))
        codec.assertClientRoundTrip(
            QuickChatPublic(chatType = 0, qcId = 1, messageData = byteArrayOf()),
            expected = QuickChatPublic(chatType = 0, qcId = 1, messageData = null)
        )
        codec.assertClientRoundTrip(QuickChatPrivate(toUsername = "Zezima", qcId = 40000, messageData = byteArrayOf(-128, 127)))
        codec.assertClientRoundTrip(QuickChatPrivate(toUsername = "", qcId = 0, messageData = null))
    }

    @Test
    fun `chat settings and social prots round trip`() {
        codec.assertClientRoundTrip(ChatType(255))
        codec.assertClientRoundTrip(ChatSetFilter(publicFilter = 0, privateFilter = 2, tradeFilter = 255))
        codec.assertClientRoundTrip(AddFriend("Zezima"))
        codec.assertClientRoundTrip(RemoveFriend("Zezima"))
        codec.assertClientRoundTrip(AddIgnoreReq(username = "Spammer", temporary = true))
        codec.assertClientRoundTrip(AddIgnoreReq(username = "Spammer", temporary = false))
        codec.assertClientRoundTrip(RemoveIgnore("Spammer"))
        codec.assertClientRoundTrip(FcJoin("Drynor"))
        codec.assertClientRoundTrip(FcJoin(""))
        codec.assertClientRoundTrip(FcJoin(null))
        codec.assertClientRoundTrip(FcKick("Spammer"))
        codec.assertClientRoundTrip(FcSetRank(username = "Friend", rank = 7))
        codec.assertClientRoundTrip(FcSetRank(username = "Friend", rank = -1))
        codec.assertClientRoundTrip(ClanChannelKickUser(guest = true, pid = 65535, username = "Guest"))
        codec.assertClientRoundTrip(ClanChannelKickUser(guest = false, pid = 0, username = "Member"))
        codec.assertClientRoundTrip(ReportAbuse(username = "Spammer", type = 255, mute = true, reason = "Spam"))
        codec.assertClientRoundTrip(BugReport(category = 3, body = "It broke", reproSteps = ""))
    }

    @Test
    fun `game state prots round trip`() {
        codec.assertClientRoundTrip(SoundEffectMusicEnded(-1))
        codec.assertClientRoundTrip(SongLoaded(Int.MAX_VALUE))
        codec.assertClientRoundTrip(CutsceneFinished(true))
        codec.assertClientRoundTrip(WritePing(65535))
        codec.assertClientRoundTrip(WorldMapClick(Tile(16383, 16383, 3)))
        codec.assertClientRoundTrip(WorldMapClick(Tile(3200, 3200)))
        codec.assertClientRoundTrip(TransmitvarVerifyId(-1))
        codec.assertClientRoundTrip(RequestWorldList(Int.MIN_VALUE))
    }

    @Test
    fun `preferences round trip every preference`() {
        codec.assertClientRoundTrip(SendPreferences(Preference.entries.associateWith { (it.ordinal * 7 + 3) and 0xFF }))
        codec.assertClientRoundTrip(SendPreferences(Preference.entries.associateWith { 255 }))
    }

    @Test
    fun `preferences left out go out as zero`() {
        val partial = mapOf(Preference.BRIGHTNESS to 4, Preference.MUSIC_VOL to 255)
        codec.assertClientRoundTrip(
            SendPreferences(partial),
            expected = SendPreferences(Preference.entries.associateWith { partial[it] ?: 0 })
        )
    }

    @Test
    fun `account prots round trip`() {
        codec.assertClientRoundTrip(EmailValidationSubmitCode("ABC123"))
        codec.assertClientRoundTrip(EmailValidationAddNewAddress(email = "a@b.c", flags = -1))
        codec.assertClientRoundTrip(EmailValidationAddNewAddress(email = "", flags = 3))
        codec.assertClientRoundTrip(EmailValidationChangeAddress(email = "a@b.c", email2 = "d@e.f"))
        codec.assertClientRoundTrip(AccountCreationStage(255))
        codec.assertClientRoundTrip(LobbyHyperlink(service = "https://drynor.org", page = "Drynor", query = "", flags = 3))
        val encrypted = byteArrayOf(0, -1, 42, -128, 127)
        assertContentEquals(encrypted, (codec.decodeClient(CheckEmailValidity(encrypted), codec.clientBytes(CheckEmailValidity(encrypted))) as CheckEmailValidity).encryptedData)
        assertContentEquals(encrypted, (codec.decodeClient(SendSignUpForm(encrypted), codec.clientBytes(SendSignUpForm(encrypted))) as SendSignUpForm).encryptedData)
    }

    @Test
    fun `misc prots round trip`() {
        codec.assertClientRoundTrip(PlayVorbis(-1))
        codec.assertClientRoundTrip(PlayVorbis(32767))
        codec.assertClientRoundTrip(AltWalk(x = 65535, y = 3200))
        codec.assertClientRoundTrip(AppletLoadingPleaseWait(1057001181))
        codec.assertClientRoundTrip(UnkCs2StringResponse("Zezima"))
        codec.assertClientRoundTrip(UnkCs2StringResponse(""))
    }
}
