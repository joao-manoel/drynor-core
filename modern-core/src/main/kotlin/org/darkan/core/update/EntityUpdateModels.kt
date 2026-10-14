package org.darkan.core.update

import java.security.MessageDigest

/**
 * Revision-agnostic entity update models shared across Player/NPC update protocols.
 *
 * These are semantic models (not on-wire encodings). Each revision maps these masks to:
 * - mask bit ids (scrambled per revision)
 * - encoding order / transforms
 */

/**
 * A single spot animation (gfx) update.
 *
 * The slot is determined by the array index in [CommonUpdateBlocks.spotAnims] (0..3),
 * and does not need to be stored here. The revision encoder repacks these semantic
 * fields into the wire format.
 *
 * @param id SpotAnim id, or -1 to clear.
 * @param height Vertical offset in client units (0 = ground level).
 * @param speed Animation delay in client cycles before playing.
 * @param rotation Rotation value (0..7).
 * @param adjustReplayMode Whether to adjust replay mode on the client.
 */
data class SpotAnim(
    val id: Int,
    val height: Int = 0,
    val speed: Int = 0,
    val rotation: Int = 0,
    val adjustReplayMode: Boolean = false,
)

/**
 * A common “tint/glow” style update used by both players and NPCs (body glow in legacy naming).
 */
data class Tint(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
    val opacity: Int,
    val startCycles: Int,
    val endCycles: Int,
)

/**
 * Forced movement between two offsets over time.
 *
 * Offsets are relative to the entity base tile in the revision protocol.
 */
data class ForceMovement(
    val diffX1: Int,
    val diffY1: Int,
    val diffX2: Int,
    val diffY2: Int,
    val startClientCycles: Int,
    val speedClientCycles: Int,
    val direction: Int,
)

/**
 * Model/config override pairs used by some update blocks (client calls `method15797`).
 *
 * @param keys Packed keys (sometimes `ushort` or `ushort<<16|ushort` depending on high bits).
 * @param values Short values corresponding to keys.
 */
data class ModelOverrides(
    val keys: IntArray,
    val values: IntArray,
) {
    init {
        require(keys.size == values.size) { "ModelOverrides keys/values size mismatch" }
    }
}

data class FaceTile(
    val x: Int,
    val y: Int,
)

data class PublicChat(
    val message: String,
    val chatFlags: Int,
)

/**
 * Update blocks shared by Player+NPC where the semantics match.
 */
data class CommonUpdateBlocks(
    val tint: Tint? = null,
    /**
     * Spot animation updates by slot (commonly 0..3).
     */
    val spotAnims: Array<SpotAnim?> = arrayOfNulls(4),
    val forceMovement: ForceMovement? = null,
    /**
     * Face entity index changed THIS TICK (legacy `nextFaceEntity != -2`).
     *
     * Set whenever the entity explicitly changed its face target this tick. May be
     * a real client index, or -1 to indicate "stop facing". Null if no explicit
     * change occurred this tick. The encoder normalises -1 to wire value 65535.
     */
    val faceEntity: Int? = null,
    /**
     * Persistent face target (legacy `lastFaceEntity`), or null if the entity is
     * not currently facing anything.
     *
     * Unlike [faceEntity], this represents the entity's *ongoing* face state that
     * survives across ticks. It is used by the protocol's "added" path: when an
     * entity is newly added to a viewer's local list and already has a face target,
     * the mask must be sent so the new viewer sees the correct facing immediately.
     *
     * Independent from [faceEntity]: both can be set on the same tick. If both are
     * present, the encoder picks based on whether the entity is being added.
     */
    val persistentFaceEntity: Int? = null,
    val faceTile: FaceTile? = null,
    val hits: HitsUpdate? = null,
    val modelOverrides: ModelOverrides? = null,
)

data class PlayerUpdateBlocks(
    val common: CommonUpdateBlocks = CommonUpdateBlocks(),
    val appearance: PlayerAppearance? = null,
    val movementAnimations: MovementAnimations? = null,
    /**
     * Player face direction (yaw/angle), or null if no explicit face change this tick.
     *
     * On-wire in 727 this is `readUShortAdd`.
     *
     * The mask is fired when an explicit value is set this tick (e.g. fixedFaceTile,
     * nextFaceTile while standing) OR when the player is being newly added to a
     * viewer's local list — in the added case, the protocol falls back to
     * [persistentFaceDirection] so the viewer sees the player facing the right way.
     */
    val faceDirection: Int? = null,
    /**
     * Persistent face direction (current `faceAngle`) used only on the "added" path.
     *
     * When a player is newly added to a viewer's local list, the legacy protocol
     * always sends a face direction mask so the new viewer sees the correct yaw.
     * The factory always populates this with the player's current angle; the
     * presence builder only marks the mask present in the added path if
     * [faceDirection] is null.
     */
    val persistentFaceDirection: Int? = null,
    /**
     * Movement type id (1=walk, 2=run) when it changed this tick (legacy
     * `isUpdateMovementType`). Null if no change. The added path falls back to
     * [persistentMovementTypeId].
     */
    val movementTypeId: Int? = null,
    /**
     * Persistent movement type id used only on the "added" path. Always set to
     * the player's current movement type so newly-visible players have the right
     * walk/run state immediately.
     */
    val persistentMovementTypeId: Int? = null,
    val temporaryMoveTypeId: Int? = null,
    val reduceAttackPriority: Boolean? = null,
    val mapDot: Boolean? = null,
    val playerConfigsNoClear: KeyIntMapDelta? = null,
    val playerConfigsClear: KeyIntMapDelta? = null,
    val forceTalk: String? = null,
    val publicChat: PublicChat? = null,
    val sequences: SequencesUpdate? = null,
    // Note: BAS (render animations) for players is encoded inside the appearance block,
    // not as a standalone update mask. Use Appearance.setBAS() to change it.
)

data class NpcUpdateBlocks(
    val common: CommonUpdateBlocks = CommonUpdateBlocks(),
    val movementAnimations: MovementAnimations? = null,
    val customisation: NpcCustomisation? = null,
    val headCustomisation: NpcCustomisation? = null,
    val basId: Int? = null,
    val varnsNoClear: KeyIntMapDelta? = null,
    val varnsClear: KeyIntMapDelta? = null,
    val transformNpcId: Int? = null,
    val updateStats: NpcStatsUpdate? = null,
    /**
     * Combat level set this tick (legacy `hasChangedCombatLevel()`). Null if unchanged.
     * The added path falls back to [persistentCombatLevel] when this is null.
     */
    val combatLevel: Int? = null,
    /**
     * Persistent combat level for the "added" path — only sent when an NPC is
     * newly added to a viewer and has a non-default combat level
     * (`customCombatLevel >= 0`). Null otherwise.
     */
    val persistentCombatLevel: Int? = null,
    /**
     * Custom name set this tick (legacy `hasChangedName()`). Null if unchanged.
     * The added path falls back to [persistentName] when this is null.
     */
    val name: String? = null,
    /**
     * Persistent custom name for the "added" path — only set when an NPC has a
     * non-default custom name. Null otherwise.
     */
    val persistentName: String? = null,
    val say: String? = null,
    val sequences: SequencesUpdate? = null,
)

data class PlayerAppearance(
    val hash: ByteArray,
    val data: ByteArray,
) {
    companion object {
        private const val HASH_ALGORITHM = "MD5"

        /**
         * Wraps an already encoded appearance block, deriving the change-detection hash from it.
         *
         * The hash is what `PlayerUpdateTrackingState` compares
         * per observer to decide whether the block needs re-sending.
         */
        fun of(data: ByteArray): PlayerAppearance =
            PlayerAppearance(hash = MessageDigest.getInstance(HASH_ALGORITHM).digest(data), data = data)
    }
}

data class MovementAnimations(
    val ids: IntArray,
    val speed: Int,
) {
    init {
        require(ids.size == 4) { "MovementAnimations.ids must be length 4" }
    }
}

data class KeyIntMapDelta(
    val clear: Boolean,
    val keys: IntArray,
    val values: IntArray,
) {
    init {
        require(keys.size == values.size) { "KeyIntMapDelta keys/values size mismatch" }
    }
}

data class SequencesUpdate(
    val ids: IntArray,
    val delays: IntArray,
    val flags: IntArray,
) {
    init {
        require(ids.size == delays.size && ids.size == flags.size) { "SequencesUpdate arrays must match length" }
    }
}

data class HitsUpdate(
    val hitSplats: Array<HitSplat>,
    val hitBars: Array<HitBarUpdate>,
)

sealed interface HitSplat {
    /**
     * Standard hitsplat with numeric value.
     */
    data class Standard(val id: Int, val value: Int, val delay: Int) : HitSplat

    /**
     * Missed hitsplat.
     */
    data class Miss(val missValue: Int, val delay: Int) : HitSplat

    /**
     * Standard hitsplat with numeric value plus soaking.
     */
    data class Soak(val id: Int, val value: Int, val soakId: Int, val soakValue: Int, val delay: Int) : HitSplat

    /**
     * Custom text hitsplat (with optional soaking custom).
     */
    data class CustomText(
        val id: Int,
        val text: String,
        val soakId: Int? = null,
        val soakText: String? = null,
        val delay: Int,
    ) : HitSplat
}

data class HitBarUpdate(
    val type: Int,
    /**
     * Lifetime timer in client cycles; 32767 means hide.
     */
    val timer: Int,
    val delay: Int = 0,
    val fromPercentage: Int = 0,
    val toPercentage: Int = fromPercentage,
)

data class NpcStatsUpdate(
    val indices: IntArray,
    val maxValues: IntArray,
    val currentValues: IntArray,
) {
    init {
        require(indices.size == maxValues.size && indices.size == currentValues.size) { "NpcStatsUpdate arrays must match length" }
    }
}

data class NpcCustomisation(
    val clear: Boolean,
    val models: IntArray? = null,
    val recolors: ShortArray? = null,
    val retextures: ShortArray? = null,
)


