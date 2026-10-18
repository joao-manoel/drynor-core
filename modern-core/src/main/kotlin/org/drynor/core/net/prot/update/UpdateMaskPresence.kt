package org.drynor.core.net.prot.update

/**
 * Shared (cross-revision) presence logic for update masks.
 *
 * Revisions should not re-specify “is this mask present?”; that is derived from the packet/entity model.
 */

fun buildPlayerMaskPresence(state: PlayerUpdateTrackingState, player: PlayerUpdatePlayer, added: Boolean): BooleanArray {
    val p = BooleanArray(PlayerMaskKey.entries.size)
    val b = player.blocks
    val c = b.common

    p[PlayerMaskKey.TINT.ordinal] = c.tint != null
    p[PlayerMaskKey.VARN_NO_CLEAR.ordinal] = b.playerConfigsNoClear != null
    p[PlayerMaskKey.SPOT_ANIM_2.ordinal] = c.spotAnims[1] != null
    // Face direction: explicit value this tick, or fall back to persistent angle when added.
    p[PlayerMaskKey.FACE_DIRECTION.ordinal] =
        b.faceDirection != null || (added && b.persistentFaceDirection != null)
    p[PlayerMaskKey.VARN_CLEAR.ordinal] = b.playerConfigsClear != null
    p[PlayerMaskKey.MAP_DOT_2.ordinal] = b.reduceAttackPriority != null
    p[PlayerMaskKey.BODY_MODEL_ROTATOR.ordinal] = c.modelOverrides != null
    p[PlayerMaskKey.FORCE_TALK.ordinal] = b.forceTalk != null
    p[PlayerMaskKey.FORCE_CHAT.ordinal] = b.publicChat != null
    p[PlayerMaskKey.SPOT_ANIM_3.ordinal] = c.spotAnims[2] != null
    p[PlayerMaskKey.MAP_DOT_1.ordinal] = b.mapDot != null
    p[PlayerMaskKey.HITS.ordinal] = c.hits != null

    val appearance = b.appearance
    p[PlayerMaskKey.APPEARANCE.ordinal] =
        appearance != null && state.needAppearanceUpdate(player.index, appearance.hash)

    p[PlayerMaskKey.ANIMATION.ordinal] = b.movementAnimations != null
    // Always sent on add: the client reuses player slots and keeps the last face target it saw.
    p[PlayerMaskKey.FACE_ENTITY.ordinal] = c.faceEntity != null || added
    p[PlayerMaskKey.TEMPORARY_MOVE_TYPE.ordinal] = b.temporaryMoveTypeId != null
    p[PlayerMaskKey.ANIMATION_SEQUENCE.ordinal] = b.sequences != null
    // Movement type: explicit change this tick, or always sent on add.
    p[PlayerMaskKey.MOVE_TYPE.ordinal] =
        b.movementTypeId != null || (added && b.persistentMovementTypeId != null)
    p[PlayerMaskKey.SPOT_ANIM_4.ordinal] = c.spotAnims[3] != null
    p[PlayerMaskKey.FORCE_MOVEMENT.ordinal] = c.forceMovement != null
    p[PlayerMaskKey.SPOT_ANIM_1.ordinal] = c.spotAnims[0] != null
    // Note: BAS is encoded inside the appearance block, not as a standalone player mask.

    return p
}

fun buildNpcMaskPresence(npc: NpcUpdateNpc, added: Boolean = false): BooleanArray {
    val p = BooleanArray(NpcMaskKey.entries.size)
    val b = npc.blocks
    val c = b.common

    p[NpcMaskKey.ANIMATION.ordinal] = b.movementAnimations != null
    p[NpcMaskKey.BODY_MESH_MODIFIER.ordinal] = b.customisation != null
    p[NpcMaskKey.FORCE_MOVEMENT.ordinal] = c.forceMovement != null
    p[NpcMaskKey.BAS.ordinal] = b.basId != null
    p[NpcMaskKey.VARN_SET.ordinal] = b.varnsNoClear != null
    p[NpcMaskKey.SPOT_ANIM_4.ordinal] = c.spotAnims[3] != null
    // Always sent on add: the client reuses npc slots and keeps the last face target it saw.
    p[NpcMaskKey.FACE_ENTITY.ordinal] = c.faceEntity != null || added
    p[NpcMaskKey.ANIMATION_SEQUENCE.ordinal] = b.sequences != null
    p[NpcMaskKey.SPOT_ANIM_2.ordinal] = c.spotAnims[1] != null
    p[NpcMaskKey.HITS.ordinal] = c.hits != null
    p[NpcMaskKey.TRANSFORMATION.ordinal] = b.transformNpcId != null
    p[NpcMaskKey.SPOT_ANIM_3.ordinal] = c.spotAnims[2] != null
    p[NpcMaskKey.STATS_UPDATE.ordinal] = b.updateStats != null
    p[NpcMaskKey.HEAD_MESH_MODIFIER.ordinal] = b.headCustomisation != null
    // Combat level: changed this tick, or newly added with a custom level.
    p[NpcMaskKey.CHANGE_COMBAT_LEVEL.ordinal] =
        b.combatLevel != null || (added && b.persistentCombatLevel != null)
    p[NpcMaskKey.VARN_CLEAR.ordinal] = b.varnsClear != null
    p[NpcMaskKey.FACE_TILE.ordinal] = c.faceTile != null
    p[NpcMaskKey.TINT.ordinal] = c.tint != null
    // Custom name: changed this tick, or newly added with a custom name.
    p[NpcMaskKey.NAME_CHANGE.ordinal] = b.name != null || (added && b.persistentName != null)
    p[NpcMaskKey.FORCE_TALK.ordinal] = b.say != null
    p[NpcMaskKey.BODY_MODEL_ROTATOR.ordinal] = c.modelOverrides != null
    p[NpcMaskKey.SPOT_ANIM_1.ordinal] = c.spotAnims[0] != null

    return p
}


