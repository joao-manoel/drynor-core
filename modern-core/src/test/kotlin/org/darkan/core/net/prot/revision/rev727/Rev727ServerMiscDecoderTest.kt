package org.darkan.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.darkan.core.clientwatch.ReflectionCheck
import org.darkan.core.clientwatch.ReflectionCheckType
import org.darkan.core.model.RegionSize
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ProtDecodeContext
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.assertServerRoundTrip
import org.darkan.core.net.prot.decodeServer
import org.darkan.core.net.prot.dto.CheckEmailReplyOpcode
import org.darkan.core.net.prot.dto.CreateAccountReplyOpcode
import org.darkan.core.net.prot.dto.InstanceLoadType
import org.darkan.core.net.prot.dto.WorldListEntry
import org.darkan.core.net.prot.dto.WorldListSnapshot
import org.darkan.core.net.prot.packet.*
import org.darkan.core.net.prot.serverBytes
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rev727ServerMiscDecoderTest {
    private val codec: Codec = register727()

    private val localPlayerIndex = 37
    private val localPlayerTile = Tile(3222, 3218, 1)

    private fun decodeAs(localPlayerIndex: Int, prot: ServerProt): ServerProt = runBlocking(ProtDecodeContext(localPlayerIndex)) {
        val entry = codec.serverProts.getValue(prot::class)
        val source = Buffer().apply { write(codec.serverBytes(prot)) }
        val decoded = codec.decode(entry, entry.opcode, source) ?: error("Could not decode ${prot::class.simpleName}")
        assertTrue(source.exhausted(), "${prot::class.simpleName} left ${source.size} unread bytes")
        decoded
    }

    private fun keyOf(regionId: Int) = intArrayOf(regionId, regionId * 31 + 7, regionId.inv(), regionId xor 0x5a5a5a5a)

    private fun loginPlayerRegionIds(): IntArray = IntArray(2048) { index ->
        when (index) {
            0 -> 0
            localPlayerIndex -> localPlayerTile.getRegionHash()
            else -> (index * 7919) and 0x3ffff
        }
    }

    private fun standardRegion(mapSize: RegionSize, chunkX: Int, chunkY: Int, atLogin: Boolean): BuildRegion {
        val regionIds = BuildRegion.regionIds(chunkX, chunkY, mapSize)
        return BuildRegion(
            regionIds = regionIds,
            mapSize = mapSize,
            xteas = Array(regionIds.size) { keyOf(regionIds[it]) },
            chunkX = chunkX,
            chunkY = chunkY,
            forceMapRefresh = chunkX % 2 == 0,
            localPlayerPid = if (atLogin) localPlayerIndex else null,
            localPlayerBaseTileHash = if (atLogin) localPlayerTile.id else null,
            otherPlayerRegionIds = if (atLogin) loginPlayerRegionIds() else null,
        )
    }

    private fun instancedRegion(mapSize: RegionSize, atLogin: Boolean, loadType: InstanceLoadType = InstanceLoadType.LARGE): BuildInstancedRegion {
        val layout = mapOf(
            Triple(600, 600, 0) to BuildInstancedRegion.chunkDescriptor(416, 392, 0, 0),
            Triple(600, 601, 0) to BuildInstancedRegion.chunkDescriptor(392, 400, 1, 1),
            Triple(601, 598, 0) to BuildInstancedRegion.chunkDescriptor(417, 393, 0, 2),
            Triple(597, 600, 1) to BuildInstancedRegion.chunkDescriptor(400, 424, 2, 3),
            Triple(600, 600, 3) to BuildInstancedRegion.chunkDescriptor(1023, 2047, 3, 0),
        )
        val chunks = BuildInstancedRegion.chunks(600, 600, mapSize) { x, y, plane -> layout[Triple(x, y, plane)] ?: BuildInstancedRegion.ABSENT_CHUNK }
        val regionIds = BuildInstancedRegion.regionIds(chunks)
        return BuildInstancedRegion(
            mapSize = mapSize,
            chunks = chunks,
            xteas = Array(regionIds.size) { keyOf(regionIds[it]) },
            chunkX = 600,
            chunkY = 600,
            forceMapRefresh = true,
            instanceLoadType = loadType,
            localPlayerPid = if (atLogin) localPlayerIndex else null,
            localPlayerBaseTileHash = if (atLogin) localPlayerTile.id else null,
            otherPlayerRegionIds = if (atLogin) loginPlayerRegionIds() else null,
        )
    }

    @Test
    fun `in-game standard region rebuilds decode for every map size and position`() {
        for (mapSize in RegionSize.entries)
            for (chunkX in listOf(0, 9, 400, 1599))
                codec.assertServerRoundTrip(standardRegion(mapSize, chunkX, 402, atLogin = false))
    }

    @Test
    fun `login standard region rebuild decodes its player block with the local index from context`() {
        for (mapSize in RegionSize.entries) {
            val prot = standardRegion(mapSize, 400, 402, atLogin = true)
            assertEquals(prot, decodeAs(localPlayerIndex, prot))
        }
    }

    @Test
    fun `reconnect details carry exactly the login player block the client decodes on reconnect`() {
        val prot = WorldReconnectDetails(localPlayerIndex, localPlayerTile.id, loginPlayerRegionIds())
        assertEquals((30 + 18 * 2046 + 7) / 8, codec.serverBytes(prot).size)
        assertEquals(prot, decodeAs(localPlayerIndex, prot))
    }

    @Test
    fun `login region block recovers the local slot from the base tile and leaves slot zero empty`() {
        val sent = standardRegion(RegionSize.SIZE_104, 400, 402, atLogin = true)
            .copy(otherPlayerRegionIds = IntArray(2048) { 0x2abcd })
        val decoded = decodeAs(localPlayerIndex, sent) as BuildRegion
        val expected = IntArray(2048) { 0x2abcd }.also {
            it[0] = 0
            it[localPlayerIndex] = localPlayerTile.getRegionHash()
        }
        assertContentEquals(expected, decoded.otherPlayerRegionIds)
    }

    @Test
    fun `a login region rebuild without a local player index in context fails clearly`() {
        val prot = standardRegion(RegionSize.SIZE_104, 400, 402, atLogin = true)
        val error = assertFailsWith<IllegalStateException> { codec.decodeServer(prot, codec.serverBytes(prot)) }
        assertTrue(error.message!!.contains("local player index"))
    }

    @Test
    fun `in-game instanced region rebuilds decode for every map size and load type`() {
        for (mapSize in RegionSize.entries)
            for (loadType in InstanceLoadType.entries)
                codec.assertServerRoundTrip(instancedRegion(mapSize, atLogin = false, loadType))
    }

    @Test
    fun `an in-game instanced rebuild with no chunks decodes`() {
        val span = RegionSize.SIZE_104.size shr 3
        codec.assertServerRoundTrip(BuildInstancedRegion(chunks = IntArray(4 * span * span) { BuildInstancedRegion.ABSENT_CHUNK }, xteas = emptyArray(), chunkX = 600, chunkY = 600))
    }

    @Test
    fun `login instanced region rebuild decodes its player block with the local index from context`() {
        for (mapSize in RegionSize.entries) {
            val prot = instancedRegion(mapSize, atLogin = true)
            assertEquals(prot, decodeAs(localPlayerIndex, prot))
        }
    }

    @Test
    fun `a login instanced rebuild without a local player index in context fails clearly`() {
        val prot = instancedRegion(RegionSize.SIZE_104, atLogin = true)
        assertFailsWith<IllegalStateException> { codec.decodeServer(prot, codec.serverBytes(prot)) }
    }

    @Test
    fun `reflection request decodes every check type`() {
        val prot = ReflectionRequest(
            -123456,
            arrayOf(
                ReflectionCheck(ReflectionCheckType.GET_INT, "client", "fieldA"),
                ReflectionCheck(ReflectionCheckType.SET_INT, "client", "fieldB", fieldValue = -9),
                ReflectionCheck(ReflectionCheckType.GET_FIELD_MODIFIERS, "Packet", ""),
                ReflectionCheck(
                    ReflectionCheckType.GET_METHOD_RETURN_VALUE, "Utils", "hash", "int",
                    arrayOf("java.lang.String", "int", "long"), arrayOf("abc", 70000, Long.MIN_VALUE),
                ),
                ReflectionCheck(ReflectionCheckType.GET_METHOD_MODIFIERS, "Utils", "hash", "void", arrayOf("int")),
            ),
        )
        codec.assertServerRoundTrip(prot)
    }

    @Test
    fun `reflection request fields the encoder drops or defaults decode to what the client sees`() {
        val prot = ReflectionRequest(
            1,
            arrayOf(
                ReflectionCheck(ReflectionCheckType.GET_INT, "client", "f", returnType = "int", fieldValue = 5),
                ReflectionCheck(ReflectionCheckType.SET_INT, "client", "g"),
                ReflectionCheck(ReflectionCheckType.GET_METHOD_MODIFIERS, "client", "m", null, arrayOf("int"), arrayOf(3)),
            ),
        )
        val expected = ReflectionRequest(
            1,
            arrayOf(
                ReflectionCheck(ReflectionCheckType.GET_INT, "client", "f"),
                ReflectionCheck(ReflectionCheckType.SET_INT, "client", "g", fieldValue = 0),
                ReflectionCheck(ReflectionCheckType.GET_METHOD_MODIFIERS, "client", "m", "void", arrayOf("int")),
            ),
        )
        codec.assertServerRoundTrip(prot, expected)
        codec.assertServerRoundTrip(ReflectionRequest(0, emptyArray()))
    }

    @Test
    fun `reflection params outside the allowed value types stay raw bytes`() {
        val prot = ReflectionRequest(
            2,
            arrayOf(ReflectionCheck(ReflectionCheckType.GET_METHOD_RETURN_VALUE, "a", "b", "int", arrayOf("java.util.ArrayList"), arrayOf(arrayListOf(1, 2)))),
        )
        val decoded = codec.decodeServer(prot, codec.serverBytes(prot)) as ReflectionRequest
        assertIs<ByteArray>(decoded.checks.single().paramValues.single())
    }

    @Test
    fun `camera packets decode`() {
        codec.assertServerRoundTrip(CamLookAt(viewLocalX = 255, viewLocalY = 200, viewZ = 4 * 65535, speed1 = 255, speed2 = 128))
        codec.assertServerRoundTrip(CamLookAt(0, 0, 0, 0, 0))
        codec.assertServerRoundTrip(CamLookAt(10, 20, 401, 3, 4), CamLookAt(10, 20, 400, 3, 4))
        codec.assertServerRoundTrip(CamMoveTo(moveLocalX = 250, moveLocalY = 3, moveZ = 40000 * 4, speed1 = 255, speed2 = 129))
        codec.assertServerRoundTrip(CamMoveTo(1, 2, 7, 3, 4), CamMoveTo(1, 2, 4, 3, 4))
        codec.assertServerRoundTrip(CamRemoveRoof(Tile(3222, 3218, 3)))
        codec.assertServerRoundTrip(CamShake(slotId = 4, v1 = 255, v2 = 130, v3 = 0, v4 = 65535))
        codec.assertServerRoundTrip(CamForceAngle(angleX = 40000, angleY = 16383))
    }

    @Test
    fun `url and window call packets decode`() {
        codec.assertServerRoundTrip(OpenUrl("https://darkan.org/vote"))
        codec.assertServerRoundTrip(OpenUrl("https://darkan.org/a", "https://darkan.org/b"))
        codec.assertServerRoundTrip(OpenUrl("https://darkan.org/a", ""), OpenUrl("https://darkan.org/a", "https://darkan.org/a"))
        codec.assertServerRoundTrip(OpenSocialNetworkUrl("https://darkan.org/€"))
        codec.assertServerRoundTrip(OpenSocialNetworkUrl(""))
        codec.assertServerRoundTrip(WindowCallWithArgs("fn(1, 'x')"))
        codec.assertServerRoundTrip(WindowCallWithArgs(""))
    }

    @Test
    fun `world list decodes every entry field and player count`() {
        val entries = listOf(
            WorldListEntry(1, 0, 225, "Home", "127.0.0.1", 43595, members = true, quickChat = false, botsAllowed = true, lootShare = false, highlighted = true, playersOnline = 40000),
            WorldListEntry(200, 1, 0, "", "world2.darkan.org", 43596, members = false, quickChat = true, botsAllowed = false, lootShare = true, highlighted = false, playersOnline = -1, customCache = true),
            WorldListEntry(3, 7, 161, "PvP £", "w3", 60000, members = true, quickChat = true, botsAllowed = true, lootShare = true, highlighted = true, playersOnline = 0),
        )
        codec.assertServerRoundTrip(WorldListPacket(WorldListSnapshot(entries, maxWorlds = 250, revision = -7), refreshOnClient = true, refreshOnlyPlayerCounts = false))
        codec.assertServerRoundTrip(WorldListPacket(WorldListSnapshot(emptyList(), maxWorlds = 0, revision = 3), refreshOnClient = false, refreshOnlyPlayerCounts = false))
    }

    @Test
    fun `a player-count-only world list decodes to numbered placeholder entries`() {
        val entries = listOf(
            WorldListEntry(1, 0, 225, "Home", "127.0.0.1", 43595, members = true, quickChat = false, botsAllowed = true, lootShare = false, highlighted = true, playersOnline = 12),
            WorldListEntry(300, 1, 1, "Two", "w2", 43596, members = false, quickChat = true, botsAllowed = false, lootShare = true, highlighted = false, playersOnline = -1, customCache = true),
        )
        val prot = WorldListPacket(WorldListSnapshot(entries, maxWorlds = 250, revision = 9), refreshOnClient = true, refreshOnlyPlayerCounts = true)
        val placeholders = listOf(
            WorldListEntry(1, 0, 0, "", "", 43595, members = false, quickChat = false, botsAllowed = false, lootShare = false, highlighted = false, playersOnline = 12),
            WorldListEntry(300, 0, 0, "", "", 43595, members = false, quickChat = false, botsAllowed = false, lootShare = false, highlighted = false, playersOnline = -1),
        )
        codec.assertServerRoundTrip(prot, WorldListPacket(WorldListSnapshot(placeholders, maxWorlds = 0, revision = 0), refreshOnClient = true, refreshOnlyPlayerCounts = true))
    }

    @Test
    fun `session and account packets decode`() {
        codec.assertServerRoundTrip(DisplayNameChangeState(1))
        codec.assertServerRoundTrip(DisplayNameChangeState(-1))
        codec.assertServerRoundTrip(IdentifyHostName(-1062731519))
        codec.assertServerRoundTrip(UpdateRebootTimer(65535))
        codec.assertServerRoundTrip(RequestFps(Int.MIN_VALUE, -1))
        codec.assertServerRoundTrip(UpdateSitesettingsCookie())
        CheckEmailReplyOpcode.entries.forEach { codec.assertServerRoundTrip(CreateCheckEmailReply(it)) }
        CreateAccountReplyOpcode.entries.forEach { codec.assertServerRoundTrip(CreateAccountReply(it)) }
        codec.assertServerRoundTrip(LoyaltyUpdate(Int.MAX_VALUE, true))
        codec.assertServerRoundTrip(LoyaltyUpdate(-5, false))
        codec.assertServerRoundTrip(JCoinsUpdate(-123456789))
        codec.assertServerRoundTrip(QuickHopWorlds("world2.darkan.org", 65535, reconnecting = true))
        codec.assertServerRoundTrip(QuickHopWorlds("", 43595, reconnecting = false))
    }

    @Test
    fun `client control packets decode`() {
        codec.assertServerRoundTrip(ProcessDevConsoleCommand("tele 3222 3218"))
        codec.assertServerRoundTrip(ProcessDevConsoleCommand(""))
        codec.assertServerRoundTrip(SetCursor())
        codec.assertServerRoundTrip(SetCursor(cursorId = 40000))
        codec.assertServerRoundTrip(SetCursor(cursorId = 0, uptext = ""))
        codec.assertServerRoundTrip(SetCursor(cursorId = -1, uptext = "Attack"))
        codec.assertServerRoundTrip(Cutscene(65535, arrayOf(intArrayOf(1, -2, Int.MAX_VALUE, Int.MIN_VALUE), IntArray(4)), ByteArray(255) { it.toByte() }))
        codec.assertServerRoundTrip(Cutscene(0, emptyArray(), ByteArray(0)))
        codec.assertServerRoundTrip(ApplyDebug(65535))
        codec.assertServerRoundTrip(DebugServerTriggers(1477, 3, 6, intArrayOf(0, 0xffffff, 0x800000)))
        codec.assertServerRoundTrip(DebugServerTriggers(0, 5, 5, IntArray(0)))
        codec.assertServerRoundTrip(ShowFaceHere(true))
        codec.assertServerRoundTrip(SetDrawOrder(true))
        codec.assertServerRoundTrip(SetDrawOrder(false))
        codec.assertServerRoundTrip(BlockMinimapState(255))
    }

    @Test
    fun `run script decodes int and string params in order`() {
        val params = arrayOf<Any>(1, "two", -3, "", Int.MIN_VALUE, "€")
        val decoded = codec.decodeServer(RunScript(5000, params), codec.serverBytes(RunScript(5000, params))) as RunScript
        assertEquals(5000, decoded.scriptId)
        assertContentEquals(params, decoded.params)
        val empty = codec.decodeServer(RunScript(-1, emptyArray()), codec.serverBytes(RunScript(-1, emptyArray()))) as RunScript
        assertEquals(-1, empty.scriptId)
        assertContentEquals(emptyArray(), empty.params)
    }

    @Test
    fun `minimap flag and hint packets decode`() {
        codec.assertServerRoundTrip(MinimapFlag(0, 103))
        codec.assertServerRoundTrip(MinimapFlag(-1, -1))
        codec.assertServerRoundTrip(MinimapFlag(-1, 20), MinimapFlag(-1, -1))
        codec.assertServerRoundTrip(HintArrow(iconIndex = 7, targetType = 0))
        codec.assertServerRoundTrip(HintArrow(iconIndex = 2, targetType = 0, arrowGraphic = 5, x = 3), HintArrow(iconIndex = 2, targetType = 0))
        codec.assertServerRoundTrip(HintArrow(iconIndex = 1, targetType = 1, arrowGraphic = 255, targetIndex = 2047, denominator = 65535, modelId = -1))
        codec.assertServerRoundTrip(HintArrow(iconIndex = 3, targetType = 10, arrowGraphic = 0, targetIndex = 32767, denominator = 1, modelId = 90000))
        codec.assertServerRoundTrip(HintArrow(iconIndex = 4, targetType = 2, arrowGraphic = 1, plane = 3, x = 3222, y = 65535, height = 1020, distance = 40000, modelId = 5))
        codec.assertServerRoundTrip(
            HintArrow(iconIndex = 0, targetType = 6, plane = 1, x = 1, y = 2, height = 7, distance = 3),
            HintArrow(iconIndex = 0, targetType = 6, plane = 1, x = 1, y = 2, height = 4, distance = 3),
        )
        codec.assertServerRoundTrip(HintTrail(255, -1, ByteArray(0)))
        codec.assertServerRoundTrip(HintTrail(0, 40000, byteArrayOf(1, -1, 127, -128)))
        codec.assertServerRoundTrip(HintTrail(9, 32766, byteArrayOf(5)))
    }

    @Test
    fun `sound and music packets decode`() {
        codec.assertServerRoundTrip(VorbisSound(-1, 255, 65535, 255, 44100))
        codec.assertServerRoundTrip(VorbisSound(40000, 0, 0, 0, 0))
        codec.assertServerRoundTrip(VorbisSpeechSound(-1, 1, 2, 3))
        codec.assertServerRoundTrip(VorbisSpeechSound(65534, 255, 65535, 255))
        codec.assertServerRoundTrip(SoundSynth(-1, 3, 40000, 200, 22050))
        codec.assertServerRoundTrip(SoundSynth(0, 0, 0, 0, 65535))
        codec.assertServerRoundTrip(MusicEffect(0x123456, 255, -1))
        codec.assertServerRoundTrip(MusicEffect(0xffffff, 0, 40000))
        codec.assertServerRoundTrip(MusicTrack(255, 128, -1))
        codec.assertServerRoundTrip(MusicTrack(0, 0, 40000))
        codec.assertServerRoundTrip(PreloadSong(-1))
        codec.assertServerRoundTrip(PreloadSong(40000))
        codec.assertServerRoundTrip(StopVorbisStream(65535))
        codec.assertServerRoundTrip(VorbisPreloadSound(soundId = 40000, priority = 255))
        codec.assertServerRoundTrip(SetVorbisSound(65535))
        codec.assertServerRoundTrip(SoundMixbussSetLevel(index = 15, level = 255))
        codec.assertServerRoundTrip(SoundMixbussSetLevel(index = -1, level = 0))
    }

    @Test
    fun `lobby login details decode the real fields around the constants`() {
        codec.assertServerRoundTrip(LobbyLoginDetails(crown = 2, displayName = "Trent", lastIpHash = -1062731519, worldLoginToken = "abc-123", defaultWorldId = 65534, defaultWorldAddress = "world1.darkan.org"))
        codec.assertServerRoundTrip(LobbyLoginDetails(crown = 0, displayName = "", lastIpHash = 0, worldLoginToken = ""))
        codec.assertServerRoundTrip(LobbyLoginDetails(crown = 255, displayName = "x", lastIpHash = 1, worldLoginToken = "t", defaultWorldId = -1))
    }

    @Test
    fun `world login details decode`() {
        codec.assertServerRoundTrip(WorldLoginDetails(2, 1, true, false, true, false, 2047, true, 0x7fffff, false, "World 1"))
        codec.assertServerRoundTrip(WorldLoginDetails(0, 0, false, true, false, true, 1, false, -1, true, ""))
    }
}
