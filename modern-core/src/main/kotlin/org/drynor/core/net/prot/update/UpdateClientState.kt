package org.drynor.core.net.prot.update

import org.drynor.core.update.NpcUpdateBlocks
import org.drynor.core.update.PlayerAppearance
import org.drynor.core.update.PlayerAppearanceData
import org.drynor.core.update.PlayerUpdateBlocks

enum class EntityStep {
    NONE,
    WALK,
    CRAWL,
    RUN,
    TELEPORT,
}

data class WornModelLayout(
    val secondaryBody: Boolean,
    val tertiaryBody: Boolean,
    val secondaryHead: Boolean,
) {
    companion object {
        val PRIMARY_ONLY = WornModelLayout(secondaryBody = false, tertiaryBody = false, secondaryHead = false)
    }
}

class AppearanceLayout(
    // Equipment defaults opcode 1: 1 = slot not transmitted, 0 = transmitted and mesh-customisable.
    val customizableObjSlots: IntArray,
    val wornModels: (itemId: Int) -> WornModelLayout,
)

data class NpcCustomisationShape(
    val models: Int,
    val headModels: Int,
    val recolours: Int,
    val retextures: Int,
)

fun interface NpcCustomisationLayout {
    fun shape(npcId: Int): NpcCustomisationShape?
}

fun tileHash30(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

fun tileHash30X(tileHash30: Int): Int = (tileHash30 shr 14) and 0x3fff

fun tileHash30Y(tileHash30: Int): Int = tileHash30 and 0x3fff

fun tileHash30Plane(tileHash30: Int): Int = (tileHash30 shr 28) and 0x3

fun regionHash18Of(tileHash30: Int): Int =
    (tileHash30Plane(tileHash30) shl 16) or ((tileHash30X(tileHash30) shr 6) shl 8) or (tileHash30Y(tileHash30) shr 6)

class PlayerUpdateClientState(
    val appearanceLayout: AppearanceLayout? = null,
) {
    var localIndex: Int = -1
        private set
    val slotFlags: ByteArray = ByteArray(PLAYER_SLOTS)
    val local: BooleanArray = BooleanArray(PLAYER_SLOTS)
    val tileHashes30: IntArray = IntArray(PLAYER_SLOTS)
    val regionHashes18: IntArray = IntArray(PLAYER_SLOTS)
    val localIndexes: IntArray = IntArray(PLAYER_SLOTS)
    var localIndexCount: Int = 0
    val outIndexes: IntArray = IntArray(PLAYER_SLOTS)
    var outIndexCount: Int = 0
    val moveTypes: IntArray = IntArray(PLAYER_SLOTS) { DEFAULT_MOVE_TYPE }
    val appearances: Array<PlayerAppearance?> = arrayOfNulls(PLAYER_SLOTS)
    val appearanceData: Array<PlayerAppearanceData?> = arrayOfNulls(PLAYER_SLOTS)

    val initialised: Boolean
        get() = localIndex > 0

    val localTileHash30: Int
        get() = tileHashes30[localIndex]

    fun initialise(localIndex: Int, localTileHash30: Int, regionHash18ByIndex: IntArray) {
        require(localIndex in 1 until PLAYER_SLOTS) { "local index out of range: $localIndex" }
        this.localIndex = localIndex
        slotFlags.fill(0)
        local.fill(false)
        tileHashes30.fill(0)
        regionHashes18.fill(0)
        moveTypes.fill(DEFAULT_MOVE_TYPE)
        appearances.fill(null)
        appearanceData.fill(null)
        local[localIndex] = true
        tileHashes30[localIndex] = localTileHash30
        localIndexCount = 0
        localIndexes[localIndexCount++] = localIndex
        outIndexCount = 0
        for (index in 1 until PLAYER_SLOTS) {
            if (index == localIndex) continue
            regionHashes18[index] = regionHash18ByIndex.getOrElse(index) { 0 }
            outIndexes[outIndexCount++] = index
        }
    }

    fun localPlayerIndices(): List<Int> = (0 until localIndexCount).map { localIndexes[it] }

    companion object {
        const val PLAYER_SLOTS = 2048
        const val DEFAULT_MOVE_TYPE = 1
    }
}

data class PlayerUpdateEntry(
    val index: Int,
    val added: Boolean,
    val removed: Boolean,
    val step: EntityStep,
    val tileHash30: Int,
    val moveType: Int,
    val masks: PlayerUpdateBlocks?,
    val appearance: PlayerAppearanceData?,
) {
    val x: Int get() = tileHash30X(tileHash30)
    val y: Int get() = tileHash30Y(tileHash30)
    val plane: Int get() = tileHash30Plane(tileHash30)
    val teleported: Boolean get() = step == EntityStep.TELEPORT
}

data class PlayerUpdateFrame(val entries: List<PlayerUpdateEntry>) {
    operator fun get(index: Int): PlayerUpdateEntry? = entries.firstOrNull { it.index == index }
}

class NpcUpdateClientState(
    val customisationLayout: NpcCustomisationLayout = NpcCustomisationLayout { null },
) {
    val localIndexes: IntArray = IntArray(MAX_LOCAL_NPCS)
    var localCount: Int = 0
    val npcIds: IntArray = IntArray(NPC_SLOTS) { -1 }
    val tileHashes30: IntArray = IntArray(NPC_SLOTS)

    fun reset() {
        localCount = 0
        npcIds.fill(-1)
        tileHashes30.fill(0)
    }

    fun localNpcIndices(): List<Int> = (0 until localCount).map { localIndexes[it] }

    companion object {
        const val MAX_LOCAL_NPCS = 1024
        const val NPC_SLOTS = 32768
    }
}

data class NpcUpdateEntry(
    val index: Int,
    val id: Int,
    val added: Boolean,
    val removed: Boolean,
    val teleported: Boolean,
    val step: EntityStep,
    val tileHash30: Int,
    val faceAngle: Int?,
    val masks: NpcUpdateBlocks?,
) {
    val x: Int get() = tileHash30X(tileHash30)
    val y: Int get() = tileHash30Y(tileHash30)
    val plane: Int get() = tileHash30Plane(tileHash30)
}

data class NpcUpdateFrame(val entries: List<NpcUpdateEntry>) {
    operator fun get(index: Int): NpcUpdateEntry? = entries.lastOrNull { it.index == index }
}
