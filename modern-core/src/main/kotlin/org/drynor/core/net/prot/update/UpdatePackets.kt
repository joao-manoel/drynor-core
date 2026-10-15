package org.drynor.core.net.prot.update

import org.drynor.core.model.MoveType
import org.drynor.core.update.NpcUpdateBlocks
import org.drynor.core.update.PlayerUpdateBlocks
import java.security.MessageDigest

/**
 * Per-tick player update snapshot (semantic, created fresh each tick).
 */
class PlayerUpdateSnapshot(
    val localIndex: Int,
    val largeSceneView: Boolean,
    var localTileHash30: Int,
    val playersByIndex: Array<PlayerUpdatePlayer?>,
)

/**
 * Player update tracking state (persists across ticks for a player session).
 * Manages local/out player lists, slot flags, cached appearance hashes, etc.
 */
class PlayerUpdateTrackingState {
    val slotFlags: ByteArray = ByteArray(2048)
    val localPlayers: Array<PlayerUpdatePlayer?> = arrayOfNulls(2048)
    val localIndexes: IntArray = IntArray(2048)
    var localIndexCount: Int = 0
    val outIndexes: IntArray = IntArray(2048)
    var outIndexCount: Int = 0
    val regionHashes: IntArray = IntArray(2048)
    val cachedAppearanceHashes: Array<ByteArray?> = arrayOfNulls(2048)

    var totalRenderDataSentLength: Int = 0
    var localAddedPlayers: Int = 0

    fun needAppearanceUpdate(index: Int, hash: ByteArray?): Boolean {
        if (hash == null) return false
        if (totalRenderDataSentLength > ((7500 - 500) / 2)) return false
        val cached = cachedAppearanceHashes[index]
        return cached == null || !MessageDigest.isEqual(cached, hash)
    }
}

data class PlayerUpdatePlayer(
    val index: Int,
    val tileHash30: Int,
    val lastTileHash30: Int,
    val regionHash18: Int,
    val xInRegion: Int,
    val yInRegion: Int,
    val finished: Boolean,
    val hidden: Boolean,
    val hasTeleported: Boolean,
    val nextWalkDirection: Int?,
    val nextRunDirectionOrOpcode: Int?,
    val blocks: PlayerUpdateBlocks,
    val zeroDisplacementWalk: Boolean = false,
)

/**
 * Per-tick NPC update snapshot.
 */
class NpcUpdateSnapshot(
    val largeSceneView: Boolean,
    var localTileHash30: Int,
    val nearbyNpcs: List<NpcUpdateNpc>,
)

/**
 * NPC update tracking state (persists across ticks).
 */
class NpcUpdateTrackingState {
    val localIndexes: IntArray = IntArray(1024)
    var localCount: Int = 0
}

data class NpcUpdateNpc(
    val index: Int,
    val id: Int,
    val plane: Int,
    val x: Int,
    val y: Int,
    val size: Int,
    val finished: Boolean,
    val hasTeleported: Boolean,
    val nextWalkDirection: Int?,
    val nextRunDirection: Int?,
    val movementType: MoveType,
    val faceAngle: Int,
    val blocks: NpcUpdateBlocks,
)
