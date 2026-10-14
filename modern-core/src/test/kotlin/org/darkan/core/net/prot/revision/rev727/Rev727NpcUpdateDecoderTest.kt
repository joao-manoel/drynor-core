package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.model.MoveType
import org.darkan.core.net.prot.update.EntityStep
import org.darkan.core.net.prot.update.NpcCustomisationLayout
import org.darkan.core.net.prot.update.NpcCustomisationShape
import org.darkan.core.net.prot.update.NpcUpdateClientState
import org.darkan.core.net.prot.update.NpcUpdateFrame
import org.darkan.core.net.prot.update.NpcUpdateNpc
import org.darkan.core.net.prot.update.NpcUpdateSnapshot
import org.darkan.core.net.prot.update.NpcUpdateTrackingState
import org.darkan.core.net.prot.update.tileHash30
import org.darkan.core.update.CommonUpdateBlocks
import org.darkan.core.update.FaceTile
import org.darkan.core.update.ForceMovement
import org.darkan.core.update.HitBarUpdate
import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import org.darkan.core.update.KeyIntMapDelta
import org.darkan.core.update.ModelOverrides
import org.darkan.core.update.MovementAnimations
import org.darkan.core.update.NpcCustomisation
import org.darkan.core.update.NpcStatsUpdate
import org.darkan.core.update.NpcUpdateBlocks
import org.darkan.core.update.SequencesUpdate
import org.darkan.core.update.SpotAnim
import org.darkan.core.update.Tint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Rev727NpcUpdateDecoderTest {

    private val customisationLayout = NpcCustomisationLayout { id ->
        when (id) {
            100 -> NpcCustomisationShape(models = 2, headModels = 1, recolours = 2, retextures = 1)
            101 -> NpcCustomisationShape(models = 3, headModels = 2, recolours = 1, retextures = 2)
            else -> null
        }
    }

    private class Harness(layout: NpcCustomisationLayout) {
        val server = NpcUpdateTrackingState()
        val client = NpcUpdateClientState(layout)

        fun tick(localTile: Int, vararg npcs: NpcUpdateNpc, largeSceneView: Boolean = false): NpcUpdateFrame {
            val snapshot = NpcUpdateSnapshot(largeSceneView, localTile, npcs.toList())
            val data = Rev727UpdateProtocol.encodeNpcUpdate(snapshot, server)
            val frame = Rev727UpdateProtocol.decodeNpcUpdate(data, client, localTile, largeSceneView)
            val serverLocals = (0 until server.localCount).map { server.localIndexes[it] }
            assertEquals(serverLocals, client.localNpcIndices())
            for (index in serverLocals) {
                val npc = npcs.first { it.index == index }
                assertEquals(tileHash30(npc.x, npc.y, npc.plane), client.tileHashes30[index], "position of npc $index")
                assertEquals(npc.id, client.npcIds[index], "id of npc $index")
            }
            return frame
        }
    }

    private val localTile = tileHash30(3200, 3200, 0)

    private fun npc(
        index: Int,
        id: Int,
        x: Int,
        y: Int,
        plane: Int = 0,
        walk: Int? = null,
        run: Int? = null,
        moveType: MoveType = MoveType.WALK,
        teleported: Boolean = false,
        finished: Boolean = false,
        faceAngle: Int = 0,
        blocks: NpcUpdateBlocks = NpcUpdateBlocks(),
    ) = NpcUpdateNpc(
        index = index,
        id = id,
        plane = plane,
        x = x,
        y = y,
        size = 1,
        finished = finished,
        hasTeleported = teleported,
        nextWalkDirection = walk,
        nextRunDirection = run,
        movementType = moveType,
        faceAngle = faceAngle,
        blocks = blocks,
    )

    @Test
    fun `npcs in view are added with id position facing face target and persistent name and combat level`() {
        val harness = Harness(customisationLayout)
        val guard = npc(10, 3010, 3203, 3202, faceAngle = 4096)
        val captain = npc(
            11, 3011, 3195, 3186, plane = 0, faceAngle = 14336,
            blocks = NpcUpdateBlocks(
                common = CommonUpdateBlocks(persistentFaceEntity = 32768 + 7),
                persistentName = "Captain Rovin",
                persistentCombatLevel = 250,
            ),
        )
        val far = npc(12, 3012, 3220, 3200)

        val frame = harness.tick(localTile, guard, captain, far)

        val added = frame[10]!!
        assertTrue(added.added)
        assertEquals(3010, added.id)
        assertEquals(3203, added.x)
        assertEquals(3202, added.y)
        assertEquals(4096, added.faceAngle)
        assertEquals(-1, added.masks?.common?.faceEntity)
        assertNull(added.masks?.name)
        val named = frame[11]!!
        assertEquals(tileHash30(3195, 3186, 0), named.tileHash30)
        assertEquals(14336, named.faceAngle)
        assertEquals(32768 + 7, named.masks?.common?.faceEntity)
        assertEquals("Captain Rovin", named.masks?.name)
        assertEquals(250, named.masks?.combatLevel)
        assertNull(frame[12])
    }

    @Test
    fun `npcs added without a face target clear the face target the client kept for the slot`() {
        val harness = Harness(customisationLayout)

        val frame = harness.tick(localTile, npc(1, 1, 3201, 3201), npc(2, 2, 3199, 3190))

        assertEquals(listOf(1, 2), frame.entries.map { it.index })
        assertTrue(frame.entries.all { it.added && it.masks?.common?.faceEntity == -1 })
    }

    @Test
    fun `idle npcs without masks end the packet without a terminator`() {
        val harness = Harness(customisationLayout)
        harness.tick(localTile, npc(1, 1, 3201, 3201), npc(2, 2, 3199, 3190))

        val frame = harness.tick(localTile, npc(1, 1, 3201, 3201), npc(2, 2, 3199, 3190))

        assertEquals(emptyList(), frame.entries)
        assertEquals(listOf(1, 2), harness.client.localNpcIndices())
    }

    @Test
    fun `walking crawling running teleporting and leaving npcs are tracked`() {
        val harness = Harness(customisationLayout)
        val walker = npc(20, 1, 3200, 3205)
        val crawler = npc(21, 2, 3195, 3195)
        val runner = npc(22, 3, 3205, 3195)
        val jumper = npc(23, 4, 3202, 3198)
        val leaver = npc(24, 5, 3198, 3202)
        val idle = npc(25, 6, 3201, 3201)
        harness.tick(localTile, walker, crawler, runner, jumper, leaver, idle)

        val frame = harness.tick(
            localTile,
            npc(20, 1, 3201, 3206, walk = 1),
            npc(21, 2, 3195, 3194, walk = 4, moveType = MoveType.CRAWL),
            npc(22, 3, 3203, 3195, walk = 6, run = 6, moveType = MoveType.RUN),
            npc(23, 4, 3190, 3210, plane = 1, teleported = true),
            npc(24, 5, 3198, 3202, finished = true),
            npc(25, 6, 3201, 3201, blocks = NpcUpdateBlocks(say = "Hello")),
        )

        assertEquals(EntityStep.WALK, frame[20]?.step)
        assertEquals(EntityStep.CRAWL, frame[21]?.step)
        assertEquals(EntityStep.RUN, frame[22]?.step)
        val jumped = frame[23]!!
        assertTrue(jumped.added)
        assertTrue(jumped.teleported)
        assertEquals(tileHash30(3190, 3210, 1), jumped.tileHash30)
        assertTrue(frame[24]!!.removed)
        assertEquals(-1, harness.client.npcIds[24])
        assertEquals("Hello", frame[25]?.masks?.say)
        assertEquals(EntityStep.NONE, frame[25]?.step)
        assertFalse(frame[25]!!.added)

        val gone = harness.tick(localTile.plus(20 shl 14), npc(20, 1, 3201, 3206))
        assertEquals(listOf(20, 21, 22, 23, 25), gone.entries.map { it.index }.sorted())
        assertTrue(gone.entries.all { it.removed })
        assertEquals(emptyList(), harness.client.localNpcIndices())
    }

    @Test
    fun `large scene view adds npcs far from the local player`() {
        val harness = Harness(customisationLayout)
        val distant = npc(30, 7, 3300, 3080, plane = 2)

        val frame = harness.tick(localTile, distant, largeSceneView = true)

        assertEquals(tileHash30(3300, 3080, 2), frame[30]?.tileHash30)
    }

    @Test
    fun `every npc mask decodes to what the server encoded`() {
        val harness = Harness(customisationLayout)
        harness.tick(localTile, npc(40, 100, 3204, 3204))

        val blocks = NpcUpdateBlocks(
            common = CommonUpdateBlocks(
                tint = Tint(hue = -20, saturation = 30, lightness = -40, opacity = 200, startCycles = 40000, endCycles = 7),
                spotAnims = arrayOf(
                    SpotAnim(id = 1, height = 50, speed = 10, rotation = 2),
                    SpotAnim(id = 40000, height = -3, speed = 0, rotation = 7),
                    SpotAnim(id = -1),
                    SpotAnim(id = 65534, height = 0, speed = 65535, rotation = 4),
                ),
                forceMovement = ForceMovement(-7, 5, 3, -2, startClientCycles = 60000, speedClientCycles = 45, direction = 50000),
                faceEntity = -1,
                faceTile = FaceTile(3210, 3190),
                hits = HitsUpdate(
                    hitSplats = arrayOf(HitSplat.Standard(1, 300, 2), HitSplat.Miss(0, 0), HitSplat.Soak(3, 5, 4, 6, 1)),
                    hitBars = arrayOf(HitBarUpdate(type = 5, timer = 100, delay = 3, fromPercentage = 90, toPercentage = 40)),
                ),
                modelOverrides = ModelOverrides(keys = intArrayOf((0xC123 shl 16) or 0xFFFF, 12), values = intArrayOf(65535, 0)),
            ),
            movementAnimations = MovementAnimations(ids = intArrayOf(1, 2, -1, 40000), speed = 255),
            customisation = NpcCustomisation(clear = false, models = intArrayOf(7, 70000), recolors = shortArrayOf(-1, 5), retextures = shortArrayOf(9)),
            headCustomisation = NpcCustomisation(clear = false, models = intArrayOf(11, 12), recolors = shortArrayOf(13), retextures = shortArrayOf(14, 15)),
            basId = -1,
            varnsNoClear = KeyIntMapDelta(clear = false, keys = intArrayOf(1, 40000), values = intArrayOf(-1, 5)),
            varnsClear = KeyIntMapDelta(clear = true, keys = intArrayOf(60000), values = intArrayOf(Int.MIN_VALUE)),
            transformNpcId = 101,
            updateStats = NpcStatsUpdate(indices = intArrayOf(0, 3), maxValues = intArrayOf(990, 100000), currentValues = intArrayOf(500, 0x123456)),
            combatLevel = 1200,
            name = "Transformed",
            say = "Grr",
            sequences = SequencesUpdate(ids = intArrayOf(5, 90000), delays = intArrayOf(255, 0), flags = intArrayOf(0xffff, 2)),
        )

        val frame = harness.tick(localTile, npc(40, 101, 3204, 3204, blocks = blocks))

        assertEquals(blocks.comparable(), frame[40]!!.masks!!.comparable())
        assertEquals(101, frame[40]?.id)
        assertEquals(101, harness.client.npcIds[40])
    }

    @Test
    fun `customisation clear decodes without a shape lookup`() {
        val harness = Harness(customisationLayout)
        harness.tick(localTile, npc(41, 999, 3201, 3204))

        val cleared = NpcUpdateBlocks(customisation = NpcCustomisation(clear = true), headCustomisation = NpcCustomisation(clear = true))
        val frame = harness.tick(localTile, npc(41, 999, 3201, 3204, blocks = cleared))

        assertTrue(frame[41]!!.masks!!.customisation!!.clear)
        assertTrue(frame[41]!!.masks!!.headCustomisation!!.clear)
    }
}
