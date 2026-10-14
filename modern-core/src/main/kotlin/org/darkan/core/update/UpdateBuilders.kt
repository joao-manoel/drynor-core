package org.darkan.core.update

/**
 * Lightweight builders for revision-agnostic update blocks.
 *
 * These are intended for world logic to construct update blocks without hand-building arrays.
 */

class CommonUpdateBlocksBuilder {
    var tint: Tint? = null
    var forceMovement: ForceMovement? = null
    var faceEntity: Int? = null
    var persistentFaceEntity: Int? = null
    var faceTile: FaceTile? = null
    var hits: HitsUpdate? = null
    var modelOverrides: ModelOverrides? = null

    private val spotAnims: Array<SpotAnim?> = arrayOfNulls(4)

    fun spotAnim(slot: Int, id: Int, height: Int = 0, speed: Int = 0, rotation: Int = 0, adjustReplayMode: Boolean = false) {
        require(slot in 0..3) { "spotAnim slot must be in 0..3 (got $slot)" }
        spotAnims[slot] = SpotAnim(id, height, speed, rotation, adjustReplayMode)
    }

    fun build(): CommonUpdateBlocks =
        CommonUpdateBlocks(
            tint = tint,
            spotAnims = spotAnims,
            forceMovement = forceMovement,
            faceEntity = faceEntity,
            persistentFaceEntity = persistentFaceEntity,
            faceTile = faceTile,
            hits = hits,
            modelOverrides = modelOverrides,
        )
}

class PlayerUpdateBlocksBuilder {
    private val common = CommonUpdateBlocksBuilder()

    var appearance: PlayerAppearance? = null
    var movementAnimations: MovementAnimations? = null
    var faceDirection: Int? = null
    var persistentFaceDirection: Int? = null
    var movementTypeId: Int? = null
    var persistentMovementTypeId: Int? = null
    var temporaryMoveTypeId: Int? = null
    var reduceAttackPriority: Boolean? = null
    var mapDot: Boolean? = null
    var playerConfigsNoClear: KeyIntMapDelta? = null
    var playerConfigsClear: KeyIntMapDelta? = null
    var forceTalk: String? = null
    var publicChat: PublicChat? = null
    var sequences: SequencesUpdate? = null

    fun common(block: CommonUpdateBlocksBuilder.() -> Unit) {
        common.block()
    }

    fun build(): PlayerUpdateBlocks =
        PlayerUpdateBlocks(
            common = common.build(),
            appearance = appearance,
            movementAnimations = movementAnimations,
            faceDirection = faceDirection,
            persistentFaceDirection = persistentFaceDirection,
            movementTypeId = movementTypeId,
            persistentMovementTypeId = persistentMovementTypeId,
            temporaryMoveTypeId = temporaryMoveTypeId,
            reduceAttackPriority = reduceAttackPriority,
            mapDot = mapDot,
            playerConfigsNoClear = playerConfigsNoClear,
            playerConfigsClear = playerConfigsClear,
            forceTalk = forceTalk,
            publicChat = publicChat,
            sequences = sequences,
        )
}

class NpcUpdateBlocksBuilder {
    private val common = CommonUpdateBlocksBuilder()

    var movementAnimations: MovementAnimations? = null
    var customisation: NpcCustomisation? = null
    var headCustomisation: NpcCustomisation? = null
    var basId: Int? = null
    var varnsNoClear: KeyIntMapDelta? = null
    var varnsClear: KeyIntMapDelta? = null
    var transformNpcId: Int? = null
    var updateStats: NpcStatsUpdate? = null
    var combatLevel: Int? = null
    var persistentCombatLevel: Int? = null
    var name: String? = null
    var persistentName: String? = null
    var say: String? = null
    var sequences: SequencesUpdate? = null

    fun common(block: CommonUpdateBlocksBuilder.() -> Unit) {
        common.block()
    }

    fun build(): NpcUpdateBlocks =
        NpcUpdateBlocks(
            common = common.build(),
            movementAnimations = movementAnimations,
            customisation = customisation,
            headCustomisation = headCustomisation,
            basId = basId,
            varnsNoClear = varnsNoClear,
            varnsClear = varnsClear,
            transformNpcId = transformNpcId,
            updateStats = updateStats,
            combatLevel = combatLevel,
            persistentCombatLevel = persistentCombatLevel,
            name = name,
            persistentName = persistentName,
            say = say,
            sequences = sequences,
        )
}


