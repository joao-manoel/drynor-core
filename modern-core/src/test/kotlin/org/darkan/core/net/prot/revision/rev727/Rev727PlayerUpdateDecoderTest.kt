package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.update.AppearanceLayout
import org.darkan.core.net.prot.update.EntityStep
import org.darkan.core.net.prot.update.PlayerUpdateClientState
import org.darkan.core.net.prot.update.PlayerUpdateFrame
import org.darkan.core.net.prot.update.PlayerUpdatePlayer
import org.darkan.core.net.prot.update.PlayerUpdateSnapshot
import org.darkan.core.net.prot.update.PlayerUpdateTrackingState
import org.darkan.core.net.prot.update.WornModelLayout
import org.darkan.core.net.prot.update.regionHash18Of
import org.darkan.core.net.prot.update.tileHash30
import org.darkan.core.update.AppearanceSlot
import org.darkan.core.update.AppearanceTitle
import org.darkan.core.update.BodyModelOverride
import org.darkan.core.update.CommonUpdateBlocks
import org.darkan.core.update.ForceMovement
import org.darkan.core.update.HeadModelOverride
import org.darkan.core.update.HitBarUpdate
import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import org.darkan.core.update.ItemMeshOverride
import org.darkan.core.update.KeyIntMapDelta
import org.darkan.core.update.ModelOverrides
import org.darkan.core.update.MovementAnimations
import org.darkan.core.update.NpcRenderAnimations
import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerAppearanceData
import org.darkan.core.update.PlayerUpdateBlocks
import org.darkan.core.update.PublicChat
import org.darkan.core.update.SequencesUpdate
import org.darkan.core.update.SpotAnim
import org.darkan.core.update.Tint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Rev727PlayerUpdateDecoderTest {

    private val localIndex = 1

    private class Harness(private val localIndex: Int, initial: List<PlayerUpdatePlayer>) {
        val server = PlayerUpdateTrackingState()
        val client = PlayerUpdateClientState()

        init {
            val local = initial.first { it.index == localIndex }
            val regions = IntArray(PlayerUpdateClientState.PLAYER_SLOTS)
            for (p in initial) regions[p.index] = p.regionHash18
            server.localPlayers[localIndex] = local
            server.localIndexes[server.localIndexCount++] = localIndex
            for (index in 1 until PlayerUpdateClientState.PLAYER_SLOTS) {
                if (index == localIndex) continue
                server.regionHashes[index] = regions[index]
                server.outIndexes[server.outIndexCount++] = index
            }
            client.initialise(localIndex, local.tileHash30, regions)
        }

        fun tick(vararg players: PlayerUpdatePlayer, largeSceneView: Boolean = false): PlayerUpdateFrame {
            val byIndex = arrayOfNulls<PlayerUpdatePlayer>(PlayerUpdateClientState.PLAYER_SLOTS)
            for (p in players) byIndex[p.index] = p
            val snapshot = PlayerUpdateSnapshot(localIndex, largeSceneView, byIndex[localIndex]!!.tileHash30, byIndex)
            val data = Rev727UpdateProtocol.encodePlayerUpdate(snapshot, server)
            val frame = Rev727UpdateProtocol.decodePlayerUpdate(data, client)
            assertInSync(byIndex)
            return frame
        }

        private fun assertInSync(byIndex: Array<PlayerUpdatePlayer?>) {
            val serverLocals = (0 until server.localIndexCount).map { server.localIndexes[it] }
            assertEquals(serverLocals, client.localPlayerIndices())
            for (index in serverLocals) {
                assertEquals(byIndex[index]!!.tileHash30, client.tileHashes30[index], "position of player $index")
            }
            for (i in 0 until server.outIndexCount) {
                val index = server.outIndexes[i]
                assertEquals(server.regionHashes[index], client.regionHashes18[index], "region hash of player $index")
            }
            assertContentEquals(server.slotFlags, client.slotFlags)
        }
    }

    private fun player(
        index: Int,
        x: Int,
        y: Int,
        plane: Int = 0,
        from: Int = tileHash30(x, y, plane),
        walk: Int? = null,
        run: Int? = null,
        teleported: Boolean = false,
        finished: Boolean = false,
        blocks: PlayerUpdateBlocks = PlayerUpdateBlocks(),
    ): PlayerUpdatePlayer {
        val tile = tileHash30(x, y, plane)
        return PlayerUpdatePlayer(
            index = index,
            tileHash30 = tile,
            lastTileHash30 = from,
            regionHash18 = regionHash18Of(tile),
            xInRegion = x and 0x3f,
            yInRegion = y and 0x3f,
            finished = finished,
            hidden = false,
            hasTeleported = teleported,
            nextWalkDirection = walk,
            nextRunDirectionOrOpcode = run,
            blocks = blocks,
        )
    }

    private fun stay(p: PlayerUpdatePlayer) = p.copy(
        lastTileHash30 = p.tileHash30,
        hasTeleported = false,
        nextWalkDirection = null,
        nextRunDirectionOrOpcode = null,
        blocks = PlayerUpdateBlocks(),
    )

    private fun appearanceData(name: String, combatLevel: Int) = PlayerAppearanceData(
        male = true,
        npcTransformSizeFlag = false,
        showSkillTotal = false,
        title = null,
        skullIcon = -1,
        headIcon = -1,
        hidden = false,
        transformedNpcId = -1,
        slots = List(13) { if (it == 3) AppearanceSlot.Worn(4151) else if (it == 4) AppearanceSlot.Body(452) else AppearanceSlot.Empty },
        meshOverrides = emptyList(),
        colours = IntArray(10) { it * 3 },
        renderEmote = 1426,
        displayName = name,
        combatLevel = combatLevel,
        totalLevel = 0,
        summoningCombatLevel = combatLevel,
        pvpCombatLevelThreshold = -1,
        npcRenderAnimations = null,
    )

    private fun appearance(name: String, combatLevel: Int): PlayerAppearance =
        Rev727UpdateProtocol.encodePlayerAppearance(appearanceData(name, combatLevel))

    @Test
    fun `players entering view are added with their position name and combat level`() {
        val local = player(localIndex, 3200, 3200, blocks = PlayerUpdateBlocks(appearance = appearance("Observer", 3)))
        val alice = player(
            2, 3205, 3203,
            blocks = PlayerUpdateBlocks(
                appearance = appearance("Alice", 99),
                persistentFaceDirection = 1024,
                persistentMovementTypeId = 2,
            ),
        )
        val far = player(3, 3300, 3300, blocks = PlayerUpdateBlocks(appearance = appearance("Faraway", 50)))
        val harness = Harness(localIndex, listOf(local, alice, far))

        val frame = harness.tick(local, alice, far)

        val added = assertNotNull(frame[2])
        assertTrue(added.added)
        assertEquals(3205, added.x)
        assertEquals(3203, added.y)
        assertEquals("Alice", added.appearance?.displayName)
        assertEquals(99, added.appearance?.combatLevel)
        assertEquals(appearanceData("Alice", 99), added.appearance)
        assertEquals(1024, added.masks?.faceDirection)
        assertEquals(2, added.masks?.movementTypeId)
        assertEquals(2, added.moveType)
        assertEquals("Observer", frame[localIndex]?.appearance?.displayName)
        assertNull(frame[3])
        assertEquals(listOf(1, 2), harness.client.localPlayerIndices())
    }

    @Test
    fun `walking running and teleporting players are tracked across ticks`() {
        var local = player(localIndex, 3200, 3200)
        var bob = player(2, 3203, 3201)
        val harness = Harness(localIndex, listOf(local, bob))
        harness.tick(local, bob)

        local = player(localIndex, 3200, 3201, from = local.tileHash30, walk = 6)
        bob = player(2, 3205, 3202, from = bob.tileHash30, walk = 4, run = 10)
        var frame = harness.tick(local, bob)
        assertEquals(EntityStep.WALK, frame[localIndex]?.step)
        assertEquals(EntityStep.RUN, frame[2]?.step)
        assertEquals(3202, frame[2]?.y)

        local = stay(local)
        bob = player(2, 3195, 3190, from = bob.tileHash30, teleported = true, blocks = PlayerUpdateBlocks(forceTalk = "Whoosh"))
        frame = harness.tick(local, bob)
        assertEquals(EntityStep.TELEPORT, frame[2]?.step)
        assertTrue(frame[2]!!.teleported)
        assertEquals("Whoosh", frame[2]?.masks?.forceTalk)

        local = player(localIndex, 3230, 3170, plane = 1, from = local.tileHash30, teleported = true)
        val carol = player(3, 3232, 3168, plane = 1)
        frame = harness.tick(local, stay(bob), carol)
        assertEquals(EntityStep.TELEPORT, frame[localIndex]?.step)
        assertEquals(1, frame[localIndex]?.plane)
        assertTrue(frame[2]!!.removed)
        assertTrue(frame[3]!!.added)
        assertEquals(1, frame[3]?.plane)
        assertEquals(tileHash30(3230, 3170, 1), harness.client.localTileHash30)

        val downstairs = player(3, 3233, 3168, plane = 0, from = carol.tileHash30, teleported = true)
        frame = harness.tick(stay(local), downstairs)
        assertTrue(frame[3]!!.removed)

        local = player(localIndex, 3230, 3170, plane = 0, from = local.tileHash30, teleported = true)
        frame = harness.tick(local, stay(downstairs))
        assertTrue(frame[3]!!.added)
        assertEquals(0, frame[3]?.plane)
        assertEquals(3233, frame[3]?.x)
    }

    @Test
    fun `players on another plane are never local`() {
        val local = player(localIndex, 3200, 3200)
        val upstairs = player(2, 3201, 3200, plane = 1)
        val harness = Harness(localIndex, listOf(local, upstairs))

        var frame = harness.tick(local, upstairs)
        assertNull(frame[2])
        assertEquals(listOf(1), harness.client.localPlayerIndices())

        val downstairs = player(2, 3201, 3200, plane = 0, from = upstairs.tileHash30, teleported = true)
        frame = harness.tick(local, downstairs)
        assertTrue(frame[2]!!.added)

        frame = harness.tick(local, player(2, 3201, 3200, plane = 1, from = downstairs.tileHash30, teleported = true))
        assertTrue(frame[2]!!.removed)
        assertEquals(listOf(1), harness.client.localPlayerIndices())
    }

    @Test
    fun `a slot freed by a local player who changed region is reused at the right tile`() {
        val local = player(localIndex, 3200, 3200)
        var leaver = player(2, 3190, 3195)
        val harness = Harness(localIndex, listOf(local, leaver))
        harness.tick(local, leaver)

        leaver = player(2, 3205, 3205, from = leaver.tileHash30, teleported = true)
        harness.tick(local, leaver)

        var frame = harness.tick(local)
        assertTrue(frame[2]!!.removed)

        frame = harness.tick(local, player(2, 3203, 3198))
        assertTrue(frame[2]!!.added)
        assertEquals(tileHash30(3203, 3198, 0), frame[2]?.tileHash30)
    }

    @Test
    fun `players leaving and returning keep their cached appearance`() {
        val local = player(localIndex, 3200, 3200)
        val dave = player(2, 3202, 3202, blocks = PlayerUpdateBlocks(appearance = appearance("Dave", 88)))
        val harness = Harness(localIndex, listOf(local, dave))
        harness.tick(local, dave)

        var frame = harness.tick(local, dave.copy(finished = true))
        assertTrue(frame[2]!!.removed)
        assertEquals(listOf(1), harness.client.localPlayerIndices())

        harness.tick(local)
        frame = harness.tick(local, dave)
        assertTrue(frame[2]!!.added)
        assertNull(frame[2]?.appearance)
        assertEquals("Dave", harness.client.appearanceData[2]?.displayName)
        assertEquals(88, harness.client.appearanceData[2]?.combatLevel)
    }

    @Test
    fun `players outside view moving between regions are added at the right tile`() {
        val local = player(localIndex, 3200, 3200)
        var erin = player(4, 3100, 3100)
        val harness = Harness(localIndex, listOf(local, erin))
        harness.tick(local, erin)

        erin = player(4, 3140, 3150, from = erin.tileHash30)
        var frame = harness.tick(local, erin)
        assertNull(frame[4])

        erin = player(4, 3141, 3150, plane = 2, from = erin.tileHash30)
        harness.tick(local, erin)

        erin = player(4, 3100, 3300, plane = 2, from = erin.tileHash30)
        harness.tick(local, erin)

        erin = player(4, 3190, 3210, plane = 0, from = erin.tileHash30)
        frame = harness.tick(local, erin)
        assertTrue(frame[4]!!.added)
        assertEquals(tileHash30(3190, 3210, 0), frame[4]?.tileHash30)
    }

    @Test
    fun `large scene view adds distant players`() {
        val local = player(localIndex, 3200, 3200)
        val frank = player(5, 3300, 3120)
        val harness = Harness(localIndex, listOf(local, frank))

        val frame = harness.tick(local, frank, largeSceneView = true)

        assertTrue(frame[5]!!.added)
        assertEquals(3300, frame[5]?.x)
        assertEquals(3120, frame[5]?.y)
    }

    @Test
    fun `skipped players between updated ones keep their state`() {
        val others = (2..40).map { player(it, 3195 + it % 10, 3195 + it / 10) }
        val local = player(localIndex, 3200, 3200)
        val harness = Harness(localIndex, listOf(local) + others)
        repeat(3) { harness.tick(local, *others.toTypedArray()) }
        assertEquals(40, harness.client.localIndexCount)

        val moved = others.map {
            if (it.index % 7 == 0) player(it.index, (it.tileHash30 shr 14) and 0x3fff, (it.tileHash30 and 0x3fff) + 1, from = it.tileHash30, walk = 6) else it
        }
        val frame = harness.tick(local, *moved.toTypedArray())

        assertEquals(listOf(7, 14, 21, 28, 35), frame.entries.map { it.index }.sorted())
    }

    @Test
    fun `every player mask decodes to what the server encoded`() {
        val local = player(localIndex, 3200, 3200)
        val gina = player(2, 3201, 3201)
        val harness = Harness(localIndex, listOf(local, gina))
        harness.tick(local, gina)

        val hits = HitsUpdate(
            hitSplats = arrayOf(
                HitSplat.Standard(id = 1, value = 55, delay = 0),
                HitSplat.Standard(id = 2, value = 20000, delay = 3),
                HitSplat.Miss(missValue = 200, delay = 1),
                HitSplat.Soak(id = 3, value = 120, soakId = 4, soakValue = 30, delay = 2),
                HitSplat.Soak(id = 3, value = 30000, soakId = 4, soakValue = 5, delay = 0),
                HitSplat.CustomText(id = 5, text = "Immune", delay = 0),
                HitSplat.CustomText(id = 6, text = "Crit", soakId = 7, soakText = "Blocked", delay = 4),
            ),
            hitBars = arrayOf(
                HitBarUpdate(type = 0, timer = 32767),
                HitBarUpdate(type = 1, timer = 0, delay = 2, fromPercentage = 180, toPercentage = 180),
                HitBarUpdate(type = 2, timer = 30, delay = 1, fromPercentage = 255, toPercentage = 10),
            ),
        )
        val spotAnims = arrayOf<SpotAnim?>(
            SpotAnim(id = 2000, height = 100, speed = 30, rotation = 3),
            SpotAnim(id = -1),
            SpotAnim(id = 40000, height = 0, speed = 65535, rotation = 7),
            SpotAnim(id = 7, height = -5, speed = 1, rotation = 1),
        )
        val blocks = PlayerUpdateBlocks(
            common = CommonUpdateBlocks(
                tint = Tint(hue = -20, saturation = 30, lightness = -40, opacity = 200, startCycles = 5, endCycles = 40000),
                spotAnims = spotAnims,
                forceMovement = ForceMovement(-3, 4, -6, 8, startClientCycles = 30, speedClientCycles = 60000, direction = 1024),
                faceEntity = 40000,
                hits = hits,
                modelOverrides = ModelOverrides(keys = intArrayOf(5, (0xC001 shl 16) or 2), values = intArrayOf(100, 60000)),
            ),
            appearance = appearance("Gina", 126),
            movementAnimations = MovementAnimations(ids = intArrayOf(808, -1, 70000, 5), speed = 3),
            faceDirection = 1536,
            movementTypeId = 2,
            temporaryMoveTypeId = 127,
            reduceAttackPriority = true,
            mapDot = true,
            playerConfigsNoClear = KeyIntMapDelta(clear = false, keys = intArrayOf(1, 60000), values = intArrayOf(-1, 123456789)),
            playerConfigsClear = KeyIntMapDelta(clear = true, keys = intArrayOf(7), values = intArrayOf(8)),
            forceTalk = "Hello",
            publicChat = PublicChat(message = "hi there", chatFlags = 0x81),
            sequences = SequencesUpdate(ids = intArrayOf(1000, -1), delays = intArrayOf(0, 200), flags = intArrayOf(1, 0x8000)),
        )

        val frame = harness.tick(local, gina.copy(blocks = blocks))

        assertEquals(blocks.comparable(), frame[2]!!.masks!!.comparable())
        assertEquals(2, harness.client.moveTypes[2])
        assertEquals("Gina", harness.client.appearanceData[2]?.displayName)
    }

    @Test
    fun `face entity cleared with -1 decodes back to -1`() {
        val local = player(localIndex, 3200, 3200)
        val hank = player(2, 3201, 3199)
        val harness = Harness(localIndex, listOf(local, hank))
        harness.tick(local, hank)

        val frame = harness.tick(local, hank.copy(blocks = PlayerUpdateBlocks(common = CommonUpdateBlocks(faceEntity = -1))))

        assertEquals(-1, frame[2]?.masks?.common?.faceEntity)
    }

    @Test
    fun `appearance with title and mesh overrides decodes with the cache layout`() {
        val layout = AppearanceLayout(
            customizableObjSlots = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0),
            wornModels = { if (it == 4151) WornModelLayout(secondaryBody = true, tertiaryBody = false, secondaryHead = true) else WornModelLayout.PRIMARY_ONLY },
        )
        val data = appearanceData("Ivy", 138).copy(
            male = false,
            title = AppearanceTitle("<col=ff0000>the Great</col>", after = false),
            skullIcon = 1,
            headIcon = 3,
            hidden = true,
            slots = List(13) { if (it == 3) AppearanceSlot.Worn(4151) else if (it == 12) AppearanceSlot.Worn(20000) else AppearanceSlot.Body(it) },
            meshOverrides = listOf(
                ItemMeshOverride(
                    slot = 3,
                    bodyModels = BodyModelOverride(intArrayOf(1, 2, -1), intArrayOf(3, 40000, -1), includeSecondary = true, includeTertiary = false),
                    headModels = HeadModelOverride(intArrayOf(5, 6), intArrayOf(7, 8), includeSecondary = true),
                    colours = intArrayOf(100, 200, 300),
                    textures = intArrayOf(9),
                ),
                ItemMeshOverride(slot = 12, colours = intArrayOf(1, 2, 3, 4), textures = intArrayOf(10, 11)),
            ),
            summoningCombatLevel = 138,
        )

        val decoded = Rev727UpdateProtocol.decodePlayerAppearance(Rev727AppearanceEncoder.encode(data), layout)

        assertEquals(data, decoded)
    }

    @Test
    fun `transformed appearance with skill total decodes the npc and render animations`() {
        val data = appearanceData("Jay", 100).copy(
            npcTransformSizeFlag = true,
            showSkillTotal = true,
            title = AppearanceTitle("the Tiny", after = true),
            transformedNpcId = 50,
            slots = emptyList(),
            totalLevel = 2496,
            summoningCombatLevel = 0,
            pvpCombatLevelThreshold = 0,
            npcRenderAnimations = NpcRenderAnimations(walk = 1, rotate180 = 2, rotate90Right = 3, rotate90Left = -1, special = 1),
        )

        val decoded = Rev727AppearanceDecoder.decode(Rev727AppearanceEncoder.encode(data))

        assertEquals(data, decoded)
        assertTrue(decoded.transformed)
    }
}
