package org.darkan.core.net.prot.update

import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerAppearanceData

/**
 * Revision-specific codec for the entity update schema: the server encodes it, a headless client decodes it.
 *
 * Implementations own every on-wire decision (bit widths, mask ordering, byte transforms); callers
 * supply only semantic snapshots.
 */
interface UpdateProtocol {
    val playerBitConfig: PlayerBitConfig
    val npcBitConfig: NpcBitConfig

    fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray

    fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray

    /**
     * Encodes a player's appearance block and pairs it with the hash used for per-observer
     * change detection.
     */
    fun encodePlayerAppearance(data: PlayerAppearanceData): PlayerAppearance

    fun decodePlayerUpdate(data: ByteArray, state: PlayerUpdateClientState): PlayerUpdateFrame

    fun decodeNpcUpdate(
        data: ByteArray,
        state: NpcUpdateClientState,
        localTileHash30: Int,
        largeSceneView: Boolean,
    ): NpcUpdateFrame

    fun decodePlayerAppearance(data: ByteArray, layout: AppearanceLayout): PlayerAppearanceData
}
