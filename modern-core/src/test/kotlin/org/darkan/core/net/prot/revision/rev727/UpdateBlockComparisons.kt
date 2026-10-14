package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.update.CommonUpdateBlocks
import org.darkan.core.update.HitsUpdate
import org.darkan.core.update.KeyIntMapDelta
import org.darkan.core.update.MovementAnimations
import org.darkan.core.update.NpcCustomisation
import org.darkan.core.update.NpcStatsUpdate
import org.darkan.core.update.NpcUpdateBlocks
import org.darkan.core.update.PlayerUpdateBlocks
import org.darkan.core.update.SequencesUpdate

internal fun PlayerUpdateBlocks.comparable(): List<Any?> = listOf(
    common.comparable(),
    appearance?.data?.toList(),
    movementAnimations?.comparable(),
    faceDirection,
    movementTypeId,
    temporaryMoveTypeId,
    reduceAttackPriority,
    mapDot,
    playerConfigsNoClear?.comparable(),
    playerConfigsClear?.comparable(),
    forceTalk,
    publicChat,
    sequences?.comparable(),
)

internal fun NpcUpdateBlocks.comparable(): List<Any?> = listOf(
    common.comparable(),
    movementAnimations?.comparable(),
    customisation?.comparable(),
    headCustomisation?.comparable(),
    basId,
    varnsNoClear?.comparable(),
    varnsClear?.comparable(),
    transformNpcId,
    updateStats?.comparable(),
    combatLevel,
    name,
    say,
    sequences?.comparable(),
)

private fun CommonUpdateBlocks.comparable(): List<Any?> = listOf(
    tint,
    spotAnims.toList(),
    forceMovement,
    faceEntity,
    faceTile,
    hits?.comparable(),
    modelOverrides?.let { it.keys.toList() to it.values.toList() },
)

private fun HitsUpdate.comparable() = hitSplats.toList() to hitBars.toList()

private fun MovementAnimations.comparable() = ids.toList() to speed

private fun KeyIntMapDelta.comparable() = Triple(clear, keys.toList(), values.toList())

private fun SequencesUpdate.comparable() = Triple(ids.toList(), delays.toList(), flags.toList())

private fun NpcStatsUpdate.comparable() = Triple(indices.toList(), maxValues.toList(), currentValues.toList())

private fun NpcCustomisation.comparable() = listOf(clear, models?.toList(), recolors?.toList(), retextures?.toList())
