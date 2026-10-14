/**
 * Player and NPC entity packets, including the pre-encoded update blocks.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt

/** NPC update (small scene view). Pre-encoded by the world-side snapshot factory + core protocol. */
data class NpcUpdate(val data: ByteArray) : ServerProt

/** Player update. Pre-encoded by the world-side snapshot factory + core protocol. */
data class PlayerUpdate(val data: ByteArray) : ServerProt

/** NPC update (large scene view). Pre-encoded by the world-side snapshot factory + core protocol. */
data class NpcUpdateLarge(val data: ByteArray) : ServerProt

@JvmInline value class UpdateUid192(val data: ByteArray) : ServerProt

data class UpdateStat(val xp: Int, val skillId: Int, val level: Int) : ServerProt

@JvmInline value class SetDrawOrder(val playerFirst: Boolean) : ServerProt

@JvmInline value class ResetAllAnimations(val dummy: Int = 0) : ServerProt

// Player related protocols
@JvmInline value class PlayerWeight(val weight: Int) : ServerProt

data class PlayerOption(val option: String, val slot: Int, val top: Boolean, val cursor: Int) : ServerProt

@JvmInline value class RunEnergy(val energy: Int) : ServerProt

@JvmInline value class ReduceAttackPriority(val enabled: Boolean) : ServerProt

@JvmInline value class SetTarget(val target: Int) : ServerProt
