package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.darkan.core.model.RegionSize
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.InstanceLoadType
import org.darkan.core.net.prot.packet.BuildInstancedRegion
import org.darkan.core.net.prot.packet.BuildRegion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Rev727RegionClientParityTest {

    private companion object {
        val codec: Codec by lazy { register727() }

        const val BASE_CHUNK_X = 600
        const val BASE_CHUNK_Y = 600
        const val FIRST_SEEN_REGION = (52 shl 8) + 49
        const val SECOND_SEEN_REGION = (49 shl 8) + 50
        const val THIRD_SEEN_REGION = (50 shl 8) + 53
        const val LOCAL_PLAYER_INDEX = 37
        const val LOCAL_PLAYER_TILE = 3222 or (3218 shl 14) or (1 shl 28)
        const val PLAYER_SLOTS = 2048

        val CLIENT_MAP_SIZES = mapOf(0 to 104, 1 to 120, 2 to 136, 3 to 168, 4 to 72)
        val SWEEP = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 63, 64, 65, 399, 400, 401, 407, 408, 409, 800, 1200, 1599, 1600)
    }

    private class ClientPacket(val buffer: ByteArray) {
        var position = 0
        private var bitPosition = 0

        val remaining get() = buffer.size - position

        private fun readByte(): Int = buffer[position++].toInt()
        fun readUByte() = readByte() and 0xff
        fun readUByteInverse() = -readByte() and 0xff
        fun readUByteAdd() = (readByte() - 128) and 0xff
        fun readUByteSubtract() = (128 - readByte()) and 0xff
        fun readUShort() = (readUByte() shl 8) or readUByte()
        fun readUShortAdd() = (readUByte() shl 8) or ((readByte() - 128) and 0xff)
        fun readInt() = (readUByte() shl 24) or (readUByte() shl 16) or (readUByte() shl 8) or readUByte()

        fun initBitAccess() {
            bitPosition = position * 8
        }

        fun finishBitAccess() {
            position = (bitPosition + 7) / 8
        }

        fun readBits(numBits: Int): Int {
            var bytePos = bitPosition shr 3
            var bitOffset = 8 - (bitPosition and 0x7)
            var value = 0
            bitPosition += numBits
            var remainingBits = numBits
            while (remainingBits > bitOffset) {
                value += (buffer[bytePos++].toInt() and mask(bitOffset)) shl (remainingBits - bitOffset)
                remainingBits -= bitOffset
                bitOffset = 8
            }
            value += if (remainingBits == bitOffset) {
                buffer[bytePos].toInt() and mask(bitOffset)
            } else {
                (buffer[bytePos].toInt() shr (bitOffset - remainingBits)) and mask(remainingBits)
            }
            return value
        }

        private fun mask(bits: Int) = (1 shl bits) - 1
    }

    private class ClientMap(
        val forced: Boolean,
        val loadType: Int,
        val sceneSize: Int,
        val chunkX: Int,
        val chunkY: Int,
        val chunks: Array<Array<IntArray>>?,
        val regionIds: IntArray,
        val xteas: Array<IntArray>,
        val regionsFilled: Int,
    )

    private fun clientSceneSize(mapSizeId: Int) = CLIENT_MAP_SIZES[mapSizeId] ?: throw NullPointerException("MapSize.get($mapSizeId)")

    private fun readXteas(packet: ClientPacket): Array<IntArray> {
        val count = packet.remaining / 16
        return Array(count) { IntArray(4) { packet.readInt() } }
    }

    private fun decodeOwnPlayer(packet: ClientPacket, localIndex: Int): Int {
        packet.initBitAccess()
        val tile = packet.readBits(30)
        for (index in 1 until PLAYER_SLOTS)
            if (index != localIndex)
                packet.readBits(18)
        packet.finishBitAccess()
        return tile
    }

    private fun initializeRegion(packet: ClientPacket): ClientMap {
        val mapSizeId = packet.readUByte()
        val chunkX = packet.readUShort()
        val chunkY = packet.readUShort()
        val forced = packet.readUByte() == 1
        val sceneSize = clientSceneSize(mapSizeId)
        val xteas = readXteas(packet)
        val regionIds = IntArray(xteas.size)
        var regionCount = 0
        var regionX = (chunkX - (sceneSize shr 4)) / 8
        while (regionX <= (chunkX + (sceneSize shr 4)) / 8) {
            var regionY = (chunkY - (sceneSize shr 4)) / 8
            while (regionY <= (chunkY + (sceneSize shr 4)) / 8) {
                regionIds[regionCount++] = regionY + (regionX shl 8)
                regionY++
            }
            regionX++
        }
        return ClientMap(forced, 0, sceneSize, chunkX, chunkY, null, regionIds, xteas, regionCount)
    }

    private fun initializeConstructedMap(packet: ClientPacket): ClientMap {
        val forced = (packet.readUByteInverse() and 0x1) != 0
        val loadType = packet.readUByteSubtract()
        val mapSizeId = packet.readUByteAdd()
        val chunkY = packet.readUShortAdd()
        val chunkX = packet.readUShort()
        val sceneSize = clientSceneSize(mapSizeId)
        val span = sceneSize shr 3
        val chunks = Array(4) { Array(span) { IntArray(span) } }
        packet.initBitAccess()
        for (plane in 0..3)
            for (x in 0 until span)
                for (y in 0 until span)
                    chunks[plane][x][y] = if (packet.readBits(1) == 1) packet.readBits(26) else -1
        packet.finishBitAccess()
        val xteas = readXteas(packet)
        val regionIds = IntArray(xteas.size)
        var mapCount = 0
        for (plane in 0..3)
            for (x in 0 until span)
                for (y in 0 until span) {
                    val descriptor = chunks[plane][x][y]
                    if (descriptor == -1)
                        continue
                    var regionId = ((descriptor shr 14 and 0x3ff) / 8 shl 8) + (descriptor shr 3 and 0x7ff) / 8
                    for (index in 0 until mapCount)
                        if (regionId == regionIds[index]) {
                            regionId = -1
                            break
                        }
                    if (regionId != -1)
                        regionIds[mapCount++] = regionId
                }
        return ClientMap(forced, loadType, sceneSize, chunkX, chunkY, chunks, regionIds, xteas, mapCount)
    }

    private fun decodeInGame(bytes: ByteArray, decoder: (ClientPacket) -> ClientMap): ClientMap {
        val packet = ClientPacket(bytes)
        val map = decoder(packet)
        assertEquals(bytes.size, packet.position, "client consumed the whole payload")
        return map
    }

    private fun decodeAtLogin(bytes: ByteArray, decoder: (ClientPacket) -> ClientMap): Pair<Int, ClientMap> {
        val login = ClientPacket(bytes)
        val tile = decodeOwnPlayer(login, LOCAL_PLAYER_INDEX)
        return tile to decodeInGame(bytes.copyOfRange(login.position, bytes.size), decoder)
    }

    private fun encode(prot: ServerProt): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        codec.encodeServerProt(prot, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead)
        if (bytes.isNotEmpty()) channel.readFully(bytes)
        channel.close()
        bytes
    }

    private fun keyOf(regionId: Int) = intArrayOf(regionId, regionId * 31 + 7, regionId.inv(), regionId xor 0x5a5a5a5a)

    private fun otherPlayerRegionIds() = IntArray(PLAYER_SLOTS) { (it * 7919) and 0x3ffff }

    private fun assertEveryRegionHasItsOwnKey(map: ClientMap, context: String) {
        assertEquals(map.xteas.size, map.regionsFilled, "$context: every key pairs with a client region")
        for (index in 0 until map.regionsFilled)
            assertContentEquals(keyOf(map.regionIds[index]), map.xteas[index], "$context: key for region ${map.regionIds[index]}")
    }

    private val layout = mapOf(
        Triple(BASE_CHUNK_X, BASE_CHUNK_Y, 0) to BuildInstancedRegion.chunkDescriptor(416, 392, 0, 0),
        Triple(BASE_CHUNK_X, BASE_CHUNK_Y + 1, 0) to BuildInstancedRegion.chunkDescriptor(392, 400, 1, 1),
        Triple(BASE_CHUNK_X + 1, BASE_CHUNK_Y - 2, 0) to BuildInstancedRegion.chunkDescriptor(417, 393, 0, 2),
        Triple(BASE_CHUNK_X - 3, BASE_CHUNK_Y, 1) to BuildInstancedRegion.chunkDescriptor(400, 424, 2, 3),
        Triple(BASE_CHUNK_X, BASE_CHUNK_Y, 3) to BuildInstancedRegion.chunkDescriptor(393, 401, 3, 0),
    )

    private fun instanced(
        mapSize: RegionSize,
        chunkX: Int = BASE_CHUNK_X,
        chunkY: Int = BASE_CHUNK_Y,
        forced: Boolean = false,
        loadType: InstanceLoadType = InstanceLoadType.STANDARD_NO_ENTITY_RESET,
        withLocalPlayer: Boolean = false,
        descriptorAt: (Int, Int, Int) -> Int = { x, y, plane -> layout[Triple(x, y, plane)] ?: BuildInstancedRegion.ABSENT_CHUNK },
    ): BuildInstancedRegion {
        val chunks = BuildInstancedRegion.chunks(chunkX, chunkY, mapSize, descriptorAt)
        val regionIds = BuildInstancedRegion.regionIds(chunks)
        return BuildInstancedRegion(
            mapSize = mapSize,
            chunks = chunks,
            xteas = Array(regionIds.size) { keyOf(regionIds[it]) },
            chunkX = chunkX,
            chunkY = chunkY,
            forceMapRefresh = forced,
            instanceLoadType = loadType,
            localPlayerPid = if (withLocalPlayer) LOCAL_PLAYER_INDEX else null,
            localPlayerBaseTileHash = if (withLocalPlayer) LOCAL_PLAYER_TILE else null,
            otherPlayerRegionIds = if (withLocalPlayer) otherPlayerRegionIds() else null,
        )
    }

    private fun standard(mapSize: RegionSize, chunkX: Int, chunkY: Int, forced: Boolean = false, withLocalPlayer: Boolean = false): BuildRegion {
        val regionIds = BuildRegion.regionIds(chunkX, chunkY, mapSize)
        return BuildRegion(
            regionIds = regionIds,
            mapSize = mapSize,
            xteas = Array(regionIds.size) { keyOf(regionIds[it]) },
            chunkX = chunkX,
            chunkY = chunkY,
            forceMapRefresh = forced,
            localPlayerPid = if (withLocalPlayer) LOCAL_PLAYER_INDEX else null,
            localPlayerBaseTileHash = if (withLocalPlayer) LOCAL_PLAYER_TILE else null,
            otherPlayerRegionIds = if (withLocalPlayer) otherPlayerRegionIds() else null,
        )
    }

    @Test
    fun `instanced keys pair with the client's first-seen regions for every map size`() {
        val firstSeen = intArrayOf(FIRST_SEEN_REGION, SECOND_SEEN_REGION, THIRD_SEEN_REGION)
        assertFalse(firstSeen.contentEquals(firstSeen.sortedArray()))
        for (mapSize in RegionSize.entries) {
            val map = decodeInGame(encode(instanced(mapSize)), ::initializeConstructedMap)
            assertContentEquals(firstSeen, map.regionIds.copyOf(map.regionsFilled), "$mapSize client region order")
            assertEveryRegionHasItsOwnKey(map, mapSize.name)
        }
    }

    @Test
    fun `instanced header round-trips through the client reader`() {
        for (mapSize in RegionSize.entries)
            for (loadType in InstanceLoadType.entries)
                for (forced in listOf(false, true)) {
                    val map = decodeInGame(encode(instanced(mapSize, 601, 1203, forced, loadType)), ::initializeConstructedMap)
                    assertEquals(forced, map.forced)
                    assertEquals(loadType.index, map.loadType)
                    assertEquals(mapSize.size, map.sceneSize)
                    assertEquals(601, map.chunkX)
                    assertEquals(1203, map.chunkY)
                }
    }

    @Test
    fun `an instanced rebuild sent with the default load type keeps the client's npc list`() {
        val span = RegionSize.SIZE_104.size shr 3
        val message = BuildInstancedRegion(chunks = IntArray(4 * span * span) { BuildInstancedRegion.ABSENT_CHUNK }, xteas = emptyArray(), chunkX = BASE_CHUNK_X, chunkY = BASE_CHUNK_Y)
        val map = decodeInGame(encode(message), ::initializeConstructedMap)
        assertEquals(InstanceLoadType.STANDARD_NO_ENTITY_RESET.index, map.loadType)
    }

    @Test
    fun `instanced descriptors carry source coordinates, plane and rotation`() {
        for (mapSize in RegionSize.entries) {
            val radius = mapSize.size shr 4
            val map = decodeInGame(encode(instanced(mapSize)), ::initializeConstructedMap)
            val chunks = map.chunks!!
            var present = 0
            for (plane in 0..3)
                for (x in 0 until (mapSize.size shr 3))
                    for (y in 0 until (mapSize.size shr 3)) {
                        val expected = layout[Triple(BASE_CHUNK_X - radius + x, BASE_CHUNK_Y - radius + y, plane)] ?: -1
                        assertEquals(expected, chunks[plane][x][y], "$mapSize chunk [$plane][$x][$y]")
                        if (expected != -1) present++
                    }
            assertEquals(layout.size, present)
            val rotated = chunks[1][radius - 3][radius]
            assertEquals(400, rotated shr 14 and 0x3ff)
            assertEquals(424, rotated shr 3 and 0x7ff)
            assertEquals(2, rotated shr 24 and 0x3)
            assertEquals(3, rotated shr 1 and 0x3)
        }
    }

    @Test
    fun `instanced login packet decodes like the client's login path`() {
        for (mapSize in RegionSize.entries) {
            val (tile, map) = decodeAtLogin(encode(instanced(mapSize, withLocalPlayer = true)), ::initializeConstructedMap)
            assertEquals(LOCAL_PLAYER_TILE, tile)
            assertEquals(mapSize.size, map.sceneSize)
            assertEveryRegionHasItsOwnKey(map, "login $mapSize")
        }
    }

    @Test
    fun `instanced sweep of scattered source chunks pairs every region with its key`() {
        val random = Random(727)
        for (mapSize in RegionSize.entries)
            for (base in listOf(400, 601, 1203))
                repeat(8) {
                    val sources = HashMap<Triple<Int, Int, Int>, Int>()
                    val message = instanced(mapSize, base, base + 7) { x, y, plane ->
                        sources.getOrPut(Triple(x, y, plane)) {
                            if (random.nextInt(3) == 0) BuildInstancedRegion.ABSENT_CHUNK
                            else BuildInstancedRegion.chunkDescriptor(random.nextInt(8, 1000), random.nextInt(8, 2000), random.nextInt(4), random.nextInt(4))
                        }
                    }
                    val map = decodeInGame(encode(message), ::initializeConstructedMap)
                    assertEveryRegionHasItsOwnKey(map, "$mapSize at $base")
                }
    }

    @Test
    fun `standard region sweep matches the client's grid at every position and map size`() {
        for (mapSize in RegionSize.entries)
            for (chunkX in SWEEP)
                for (chunkY in SWEEP) {
                    val context = "$mapSize at $chunkX,$chunkY"
                    val map = decodeInGame(encode(standard(mapSize, chunkX, chunkY, forced = (chunkX + chunkY) % 2 == 0)), ::initializeRegion)
                    assertEquals(mapSize.size, map.sceneSize, context)
                    assertEquals(chunkX, map.chunkX, context)
                    assertEquals(chunkY, map.chunkY, context)
                    assertEquals((chunkX + chunkY) % 2 == 0, map.forced, context)
                    assertEveryRegionHasItsOwnKey(map, context)
                }
    }

    @Test
    fun `standard login packet decodes like the client's login path`() {
        for (mapSize in RegionSize.entries)
            for (chunkX in listOf(0, 9, 400, 1599)) {
                val (tile, map) = decodeAtLogin(encode(standard(mapSize, chunkX, 402, withLocalPlayer = true)), ::initializeRegion)
                assertEquals(LOCAL_PLAYER_TILE, tile)
                assertEveryRegionHasItsOwnKey(map, "login $mapSize at $chunkX")
            }
    }

    @Test
    fun `the client's grid reaches below the origin for the larger map sizes`() {
        val regionIds = BuildRegion.regionIds(0, 400, RegionSize.SIZE_168)
        assertEquals(-1, regionIds.first() shr 8)
        val map = decodeInGame(encode(standard(RegionSize.SIZE_168, 0, 400)), ::initializeRegion)
        assertEveryRegionHasItsOwnKey(map, "SIZE_168 at the origin")
    }
}
