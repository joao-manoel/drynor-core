package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.net.prot.update.PlayerUpdatePlayer
import org.drynor.core.net.prot.update.PlayerUpdateSnapshot
import org.drynor.core.net.prot.update.PlayerUpdateTrackingState
import org.drynor.core.update.PlayerUpdateBlocks
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerUpdateProtocolTest {

    private val observerIndex = 1
    private val otherIndex = 2

    private fun player(index: Int, hidden: Boolean): PlayerUpdatePlayer {
        val x = 3200
        val y = 3200
        return PlayerUpdatePlayer(
            index = index,
            tileHash30 = (x shl 14) or y,
            lastTileHash30 = (x shl 14) or y,
            regionHash18 = ((x shr 6) shl 8) or (y shr 6),
            xInRegion = x and 0x3f,
            yInRegion = y and 0x3f,
            finished = false,
            hidden = hidden,
            hasTeleported = false,
            nextWalkDirection = null,
            nextRunDirectionOrOpcode = null,
            blocks = PlayerUpdateBlocks(),
        )
    }

    private fun snapshot(vararg players: PlayerUpdatePlayer): PlayerUpdateSnapshot {
        val byIndex = arrayOfNulls<PlayerUpdatePlayer>(2048)
        for (p in players) byIndex[p.index] = p
        val local = byIndex[observerIndex]!!
        return PlayerUpdateSnapshot(
            localIndex = observerIndex,
            largeSceneView = false,
            localTileHash30 = local.tileHash30,
            playersByIndex = byIndex,
        )
    }

    private fun state(vararg localPlayers: PlayerUpdatePlayer): PlayerUpdateTrackingState {
        val state = PlayerUpdateTrackingState()
        for (p in localPlayers) {
            state.localPlayers[p.index] = p
            state.localIndexes[state.localIndexCount++] = p.index
        }
        for (index in 1 until 2048) {
            if (state.localPlayers[index] != null) continue
            state.outIndexes[state.outIndexCount++] = index
        }
        return state
    }

    @Test
    fun `hidden player is not added to another player's local list`() {
        val observer = player(observerIndex, hidden = false)
        val other = player(otherIndex, hidden = true)
        val state = state(observer)

        Rev727UpdateProtocol.encodePlayerUpdate(snapshot(observer, other), state)

        assertNull(state.localPlayers[otherIndex])
    }

    @Test
    fun `visible player is added to another player's local list`() {
        val observer = player(observerIndex, hidden = false)
        val other = player(otherIndex, hidden = false)
        val state = state(observer)

        Rev727UpdateProtocol.encodePlayerUpdate(snapshot(observer, other), state)

        assertNotNull(state.localPlayers[otherIndex])
    }

    @Test
    fun `player who becomes hidden is removed from another player's local list`() {
        val observer = player(observerIndex, hidden = false)
        val other = player(otherIndex, hidden = true)
        val state = state(observer, player(otherIndex, hidden = false))

        Rev727UpdateProtocol.encodePlayerUpdate(snapshot(observer, other), state)

        assertNull(state.localPlayers[otherIndex])
    }

    @Test
    fun `hidden player stays in their own local list`() {
        val observer = player(observerIndex, hidden = true)
        val state = state(observer)

        Rev727UpdateProtocol.encodePlayerUpdate(snapshot(observer), state)

        assertNotNull(state.localPlayers[observerIndex])
    }
}
