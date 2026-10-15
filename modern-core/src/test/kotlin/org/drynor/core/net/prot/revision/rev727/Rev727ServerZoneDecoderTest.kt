package org.drynor.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.drynor.core.net.prot.LocCustomisationSizes
import org.drynor.core.net.prot.LocCustomisationSizing
import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.ZonePacketDecoder
import org.drynor.core.net.prot.ZonePacketEncoder
import org.drynor.core.net.prot.assertServerRoundTrip
import org.drynor.core.net.prot.decodeServer
import org.drynor.core.net.prot.packet.*
import org.drynor.core.net.prot.serverBytes
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Rev727ServerZoneDecoderTest {
    private val codec = register727()

    private val sizing = LocCustomisationSizing { objectId, shape ->
        if (objectId == 70000 && shape == 10) LocCustomisationSizes(models = 2, recolors = 3, retextures = 1) else LocCustomisationSizes(0, 0, 0)
    }

    private fun decodeBatch(data: ByteArray): List<ServerProt> = runBlocking { ZonePacketDecoder.decodeZoneSubPackets(codec, data) }

    private fun assertCustomizeEquals(expected: CustomizeObject, actual: ServerProt) {
        actual as CustomizeObject
        assertEquals(expected.copy(modelIds = null, recolors = null, retextures = null), actual.copy(modelIds = null, recolors = null, retextures = null))
        assertContentEquals(expected.modelIds, actual.modelIds)
        assertContentEquals(expected.recolors, actual.recolors)
        assertContentEquals(expected.retextures, actual.retextures)
    }

    @Test
    fun `zone headers round trip`() {
        codec.assertServerRoundTrip(UpdateZoneFullFollows(chunkX = 12, chunkY = 0, plane = 3))
        codec.assertServerRoundTrip(UpdateZonePartialFollows(chunkX = 0, chunkY = 12, plane = 1))
        codec.assertServerRoundTrip(UpdateZonePartialEnclosed(chunkX = 6, chunkY = 13, plane = 2, data = byteArrayOf(9, -1, 0, 127)))
        codec.assertServerRoundTrip(UpdateZonePartialEnclosed(chunkX = 0, chunkY = 0, plane = 0, data = ByteArray(0)))
    }

    @Test
    fun `ground item prots round trip at the zone corners`() {
        codec.assertServerRoundTrip(GroundItemCount(localX = 7, localY = 0, itemId = 65535, oldAmount = 40000, newAmount = 0))
        codec.assertServerRoundTrip(CreateGroundItem(localX = 0, localY = 7, itemId = 40000, amount = 65535))
        codec.assertServerRoundTrip(GroundItemReveal(playerId = 2047, localX = 7, localY = 7, itemId = 995, amount = 50000))
        codec.assertServerRoundTrip(RemoveGroundItem(localX = 3, localY = 5, itemId = 60000))
    }

    @Test
    fun `object prots round trip`() {
        codec.assertServerRoundTrip(DestroyObject(localX = 7, localY = 7, shape = 22, rotation = 3))
        codec.assertServerRoundTrip(CreateObject(localX = 0, localY = 1, objectId = 131072, shape = 10, rotation = 2))
        codec.assertServerRoundTrip(ObjectPrefetch(objectId = -1, typeIndex = 255))
        codec.assertServerRoundTrip(ObjAnim(localX = 4, localY = 6, shape = 11, rotation = 1, animationId = -1))
        codec.assertServerRoundTrip(ObjAnimSpecific(tile = Tile(3222, 3218, 3), shape = 0, rotation = 3, animationId = 100000))
    }

    @Test
    fun `customize object clear round trips without arrays`() {
        codec.assertServerRoundTrip(CustomizeObject(localX = 2, localY = 5, objectId = 70000, shape = 10, rotation = 1, clear = true))
    }

    @Test
    fun `customize object with a single array sizes it from the payload`() {
        val prot = CustomizeObject(localX = 7, localY = 0, objectId = 70000, shape = 10, rotation = 3, recolors = shortArrayOf(-1, 0, 32767))
        assertCustomizeEquals(prot, codec.decodeServer(prot, codec.serverBytes(prot)))
    }

    @Test
    fun `customize object with several arrays sizes them from the loc`() {
        val prot = CustomizeObject(1, 6, 70000, 10, 2, modelIds = intArrayOf(-1, 90000), recolors = shortArrayOf(1, -2, 3), retextures = shortArrayOf(-32768))
        val entry = codec.serverProts.getValue(CustomizeObject::class)
        val source = Buffer().apply { write(codec.serverBytes(prot)) }
        val decoded = runBlocking(sizing) { codec.decode(entry, entry.opcode, source) }!!
        assertTrue(source.exhausted())
        assertCustomizeEquals(prot, decoded)
    }

    @Test
    fun `spot anim prots round trip including the removal sentinel`() {
        codec.assertServerRoundTrip(SpotAnim(localX = 7, localY = 3, spotAnimId = -1, height = 65535, speed = 0, rotation = 255))
        codec.assertServerRoundTrip(SpotAnim(localX = 0, localY = 0, spotAnimId = 40000, height = 100, speed = 30, rotation = 7))
        codec.assertServerRoundTrip(SpotAnimSpecific(slot = 255, spotAnimId = -1, settings = 0xFF, speed = 65535, targetHash = -123456789, height = 40000))
        codec.assertServerRoundTrip(SpotAnimSpecific(slot = 0, spotAnimId = 2000, settings = 0, speed = 0, targetHash = 0x7FFFFFFF, height = 0))
    }

    @Test
    fun `map projectiles round trip with signed offsets and scaled heights`() {
        codec.assertServerRoundTrip(MapProjAnim(7, 0, -128, 127, -32768, 65535, 1020, 0, 65535, 0, -1, 65535, true))
        codec.assertServerRoundTrip(MapProjAnim(0, 7, 3, -3, 1234, 500, 40, 36, 30, 60, 16, 64, false))
        codec.assertServerRoundTrip(MapProjAnimHalfSq(15, 0, -1, 1, -32768, 32767, 60000, 1020, 1020, 0, 65535, -1, 0))
        codec.assertServerRoundTrip(MapProjAnimHalfSq(0, 15, 2, -2, 5, -5, 100, -20, 4, 10, 20, 32, 64, basOffIdx = 63, useFloorHeight = true))
    }

    @Test
    fun `map projectile heights lose their low two bits`() {
        codec.assertServerRoundTrip(
            MapProjAnim(1, 1, 0, 0, 0, 1, 43, 41, 0, 0, 0, 0, false),
            expected = MapProjAnim(1, 1, 0, 0, 0, 1, 40, 40, 0, 0, 0, 0, false),
        )
        codec.assertServerRoundTrip(
            ProjAnimSpecific(43, 1, 2, 3, 0, 4, 5, 6, 7, 8, 9, 10, 11, 23),
            expected = ProjAnimSpecific(40, 1, 2, 3, 0, 4, 5, 6, 7, 8, 9, 10, 11, 20),
        )
    }

    @Test
    fun `specific projectile round trips with and without a bas offset`() {
        codec.assertServerRoundTrip(ProjAnimSpecific(1020, -32768, 65535, -1, 0x1, 65535, 65535, 0, 65535, -1, -128, 127, 40000, 1020))
        codec.assertServerRoundTrip(ProjAnimSpecific(0, 32767, 0, 32767, 0x2 or (5 shl 2), 0, 0, 65535, 0, 255 - 1, 0, -1, 0, -100))
    }

    @Test
    fun `sound, music and message prots round trip`() {
        codec.assertServerRoundTrip(SoundEffectTile(localX = 7, localY = 1, soundId = -1, range = 15, mode = 7, delay = 255, volume = 255, sampleRate = 65535))
        codec.assertServerRoundTrip(SoundEffectTile(localX = 0, localY = 0, soundId = 4000, range = 0, mode = 0, delay = 0, volume = 0, sampleRate = 0))
        codec.assertServerRoundTrip(MusicTrackTile(plane = 3, x = 16383, y = 0, near = 255, far = 0, volume = 128, musicArchive = -1))
        codec.assertServerRoundTrip(MusicTrackTile(plane = 0, x = 3222, y = 16383, near = 0, far = 255, volume = 0, musicArchive = 70000))
        codec.assertServerRoundTrip(TileMessage(localX = 7, localY = 7, priority = 65535, duration = 255, color = 0xFFFFFF, message = "Grüße, world"))
        codec.assertServerRoundTrip(TileMessage(localX = 0, localY = 0, priority = 0, duration = 0, color = 0, message = ""))
    }

    @Test
    fun `animate npc round trips its four animations`() {
        val prot = AnimateNpc(npcIndex = 32767, animationIds = intArrayOf(-1, 0, 808, 100000), speed = 255)
        val decoded = codec.decodeServer(prot, codec.serverBytes(prot)) as AnimateNpc
        assertEquals(prot.npcIndex, decoded.npcIndex)
        assertContentEquals(prot.animationIds, decoded.animationIds)
        assertEquals(prot.speed, decoded.speed)
    }

    @Test
    fun `enclosed zone batch expands into the sub-packets it was built from`() {
        val packets = listOf(
            CustomizeObject(3, 4, 1234, 10, 1, clear = true),
            RemoveGroundItem(1, 2, 995),
            CreateGroundItem(7, 0, 4151, 65535),
            DestroyObject(0, 7, 22, 3),
            CreateObject(5, 5, 131072, 10, 2),
            MapProjAnim(2, 3, -5, 5, -1, 500, 40, 36, 30, 60, -1, 64, true),
            MapProjAnimHalfSq(14, 1, 2, -2, 5, -5, 100, -20, 4, 10, 20, 32, 64, basOffIdx = 7, useFloorHeight = true),
            ObjectPrefetch(70000, 3),
            GroundItemReveal(2047, 6, 6, 995, 40000),
            GroundItemCount(4, 4, 995, 10, 20),
            SoundEffectTile(1, 1, -1, 15, 7, 0, 255, 22050),
            TileMessage(7, 7, 1, 100, 0xFF00FF, "Hello there"),
            ObjAnim(2, 2, 10, 0, 808),
            SoundAreaTile(6, 5, 3000, 8, 3, 10, 200, 44100, voice = true),
            SpotAnim(0, 0, 2000, 100, 30, 7),
            TileMessage(0, 0, 0, 0, 0, ""),
            SoundAreaTile(0, 0, -1, 0, 0, 0, 0, 0, voice = false),
        )
        val enclosed = UpdateZonePartialEnclosed(6, 7, 1, ZonePacketEncoder.encodeZoneSubPackets(packets))
        val received = codec.decodeServer(enclosed, codec.serverBytes(enclosed)) as UpdateZonePartialEnclosed
        assertEquals(enclosed, received)
        assertEquals(packets, decodeBatch(received.data))
    }

    @Test
    fun `enclosed customize object arrays are sized from the loc`() {
        val customize = CustomizeObject(1, 6, 70000, 10, 2, modelIds = intArrayOf(-1, 90000), recolors = shortArrayOf(1, -2, 3), retextures = shortArrayOf(-32768))
        val after = SpotAnim(3, 3, 100, 0, 0, 0)
        val data = ZonePacketEncoder.encodeZoneSubPackets(listOf(customize, after))
        val decoded = runBlocking(sizing) { ZonePacketDecoder.decodeZoneSubPackets(codec, data) }
        assertEquals(2, decoded.size)
        assertCustomizeEquals(customize, decoded[0])
        assertEquals(after, decoded[1])
    }

    @Test
    fun `enclosed customize object arrays cannot be split without loc sizes`() {
        val data = ZonePacketEncoder.encodeZoneSubPackets(listOf(CustomizeObject(1, 1, 5, 10, 0, recolors = shortArrayOf(1, 2))))
        assertFailsWith<IllegalStateException> { decodeBatch(data) }
    }

    @Test
    fun `an unknown sub-packet index is rejected`() {
        assertFailsWith<IllegalStateException> { decodeBatch(byteArrayOf(15)) }
    }
}
