/**
 * Zone packets: objects, ground items, projectiles and positional effects.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.net.prot.ServerProt
import world.gregs.voidps.type.Tile

data class UpdateZoneFullFollows(val chunkX: Int, val chunkY: Int, val plane: Int) : ServerProt

data class UpdateZonePartialFollows(val chunkX: Int, val chunkY: Int, val plane: Int) : ServerProt

data class UpdateZonePartialEnclosed(val chunkX: Int, val chunkY: Int, val plane: Int, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateZonePartialEnclosed
        if (chunkX != other.chunkX) return false
        if (chunkY != other.chunkY) return false
        if (plane != other.plane) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = chunkX
        result = 31 * result + chunkY
        result = 31 * result + plane
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class MapProjAnim(
    val localX: Int,
    val localY: Int,
    val xOffset: Int,
    val yOffset: Int,
    val lockOn: Int,
    val spotAnimId: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val angle: Int,
    val slope: Int,
    val useFloorHeight: Boolean,
) : ServerProt

data class MapProjAnimHalfSq(
    val localX: Int,
    val localY: Int,
    val xOffset: Int,
    val yOffset: Int,
    val source: Int,
    val lockOn: Int,
    val spotAnimId: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val angle: Int,
    val slope: Int,
    val basOffIdx: Int = -1,
    val useFloorHeight: Boolean = false,
) : ServerProt

// Object and ground item protocols
data class GroundItemCount(val localX: Int, val localY: Int, val itemId: Int, val oldAmount: Int, val newAmount: Int) : ServerProt

data class CreateGroundItem(val localX: Int, val localY: Int, val itemId: Int, val amount: Int) : ServerProt

data class GroundItemReveal(val playerId: Int, val localX: Int, val localY: Int, val itemId: Int, val amount: Int) : ServerProt

data class RemoveGroundItem(val localX: Int, val localY: Int, val itemId: Int) : ServerProt

data class DestroyObject(val localX: Int, val localY: Int, val shape: Int, val rotation: Int) : ServerProt

data class ObjAnim(val localX: Int, val localY: Int, val shape: Int, val rotation: Int, val animationId: Int) : ServerProt

data class ObjAnimSpecific(val tile: Tile, val shape: Int, val rotation: Int, val animationId: Int) : ServerProt

data class CreateObject(val localX: Int, val localY: Int, val objectId: Int, val shape: Int, val rotation: Int) : ServerProt

data class TileMessage(val localX: Int, val localY: Int, val priority: Int, val duration: Int, val color: Int, val message: String) : ServerProt

data class CustomizeObject(
    val localX: Int,
    val localY: Int,
    val objectId: Int,
    val shape: Int,
    val rotation: Int,
    val clear: Boolean = false,
    val modelIds: IntArray? = null,
    val recolors: ShortArray? = null,
    val retextures: ShortArray? = null,
) : ServerProt

data class ObjectPrefetch(val objectId: Int, val typeIndex: Int) : ServerProt

// Animation related protocols
data class SpotAnim(val localX: Int, val localY: Int, val spotAnimId: Int, val height: Int, val speed: Int, val rotation: Int) : ServerProt

data class SpotAnimSpecific(
    val slot: Int,
    val spotAnimId: Int,
    val settings: Int,
    val speed: Int,
    val targetHash: Int,
    val height: Int,
) : ServerProt

data class AnimateNpc(val npcIndex: Int, val animationIds: IntArray, val speed: Int) : ServerProt {
    init {
        require(animationIds.size == 4) { "animationIds must be length 4" }
    }
}

data class ProjAnimSpecific(
    val endHeight: Int,
    val source: Int,
    val endTime: Int,
    val lockOn: Int,
    val flags: Int,
    val y: Int,
    val slope: Int,
    val startTime: Int,
    val spotAnimId: Int,
    val angle: Int,
    val xDist: Int,
    val yDist: Int,
    val x: Int,
    val startHeight: Int,
) : ServerProt

data class SoundEffectTile(
    val localX: Int,
    val localY: Int,
    val soundId: Int,
    val range: Int,
    val mode: Int,
    val delay: Int,
    val volume: Int,
    val sampleRate: Int,
) : ServerProt

data class SoundAreaTile(
    val localX: Int,
    val localY: Int,
    val soundId: Int,
    val range: Int,
    val mode: Int,
    val delay: Int,
    val volume: Int,
    val sampleRate: Int,
    val voice: Boolean,
) : ServerProt

data class MusicTrackTile(val plane: Int, val x: Int, val y: Int, val near: Int, val far: Int, val volume: Int, val musicArchive: Int) : ServerProt
