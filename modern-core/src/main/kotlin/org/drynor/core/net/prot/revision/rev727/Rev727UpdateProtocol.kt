package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.model.MoveType
import org.drynor.core.net.prot.update.*
import org.drynor.core.update.PlayerAppearance
import org.drynor.core.update.PlayerAppearanceData
import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.math.abs

/**
 * Rev727 entity update protocol (PlayerUpdate/NpcUpdate).
 *
 * All revision-oriented update encoding lives here.
 */
object Rev727UpdateProtocol : UpdateProtocol {

    override val playerBitConfig = PlayerBitConfig(
        smallTeleportThreshold = 14,
        smallTeleportBits = 12,
        largeTeleportBits = 30,
        walkDirectionBits = 3,
        runDirectionBits = 4,
        skipThresholds = intArrayOf(0, 1, 32, 256),
        skipBitWidths = intArrayOf(0, 5, 8, 11),
        addPositionBits = 6,
        maxAddPerTick = 15,
    )

    override val npcBitConfig = NpcBitConfig(
        localCountBits = 8,
        indexBits = 15,
        normalPositionBits = 5,
        largePositionBits = 8,
        rotationBits = 3,
        terminator = 32767,
        walkDirectionBits = 3,
        idBits = 15,
        planeBits = 2,
    )

    // ----------------------------
    // Appearance
    // ----------------------------

    override fun encodePlayerAppearance(data: PlayerAppearanceData): PlayerAppearance =
        PlayerAppearance.of(Rev727AppearanceEncoder.encode(data))

    override fun decodePlayerAppearance(data: ByteArray, layout: AppearanceLayout): PlayerAppearanceData =
        Rev727AppearanceDecoder.decode(data, layout)

    override fun decodePlayerUpdate(data: ByteArray, state: PlayerUpdateClientState): PlayerUpdateFrame =
        Rev727PlayerUpdateDecoder.decode(data, state, playerBitConfig, state.appearanceLayout ?: Rev727AppearanceDecoder.DEFAULT_LAYOUT)

    override fun decodeNpcUpdate(
        data: ByteArray,
        state: NpcUpdateClientState,
        localTileHash30: Int,
        largeSceneView: Boolean,
    ): NpcUpdateFrame = Rev727NpcUpdateDecoder.decode(data, state, npcBitConfig, localTileHash30, largeSceneView)

    // ----------------------------
    // Player update
    // ----------------------------

    override fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray {
        val local = snapshot.playersByIndex[snapshot.localIndex] ?: return ByteArray(0)

        val stream = BufferWriter(capacity = 16384)
        val updateBlocks = BufferWriter(capacity = 16384)

        processLocalPlayers(stream, updateBlocks, snapshot, state, local, snapshot.largeSceneView, true)
        processLocalPlayers(stream, updateBlocks, snapshot, state, local, snapshot.largeSceneView, false)
        processOutsidePlayers(stream, updateBlocks, snapshot, state, local, snapshot.playersByIndex, snapshot.largeSceneView, true)
        processOutsidePlayers(stream, updateBlocks, snapshot, state, local, snapshot.playersByIndex, snapshot.largeSceneView, false)

        stream.writeBytes(updateBlocks.toArray())

        state.totalRenderDataSentLength = 0
        state.localIndexCount = 0
        state.outIndexCount = 0

        for (playerIndex in 1 until 2048) {
            state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() shr 1).toByte()
            val p = state.localPlayers[playerIndex]
            if (p == null) {
                state.outIndexes[state.outIndexCount++] = playerIndex
            } else {
                state.localIndexes[state.localIndexCount++] = playerIndex
            }
        }

        return stream.toArray()
    }

    private fun withinDistance(local: PlayerUpdatePlayer, other: PlayerUpdatePlayer, largeSceneView: Boolean): Boolean {
        if (other.hidden && other.index != local.index) return false
        if (tileHash30Plane(local.tileHash30) != tileHash30Plane(other.tileHash30)) return false
        val radius = if (largeSceneView) 126 else 14
        return abs(tileHash30X(local.tileHash30) - tileHash30X(other.tileHash30)) <= radius &&
            abs(tileHash30Y(local.tileHash30) - tileHash30Y(other.tileHash30)) <= radius
    }

    private fun needsRemove(local: PlayerUpdatePlayer, p: PlayerUpdatePlayer, largeSceneView: Boolean): Boolean {
        return p.finished || !withinDistance(local, p, largeSceneView)
    }

    private fun needsAdd(state: PlayerUpdateTrackingState, local: PlayerUpdatePlayer, p: PlayerUpdatePlayer?, largeSceneView: Boolean): Boolean {
        val cfg = playerBitConfig
        return p != null && !p.finished && withinDistance(local, p, largeSceneView) && state.localAddedPlayers < cfg.maxAddPerTick
    }

    private fun updateRegionHash(stream: BufferWriter, lastHash: Int, currentHash: Int) {
        val lastX = lastHash shr 8
        val lastY = lastHash and 0xff
        val lastPlane = lastHash shr 16
        val currentX = currentHash shr 8
        val currentY = currentHash and 0xff
        val currentPlane = currentHash shr 16
        val planeOffset = currentPlane - lastPlane

        if (lastX == currentX && lastY == currentY) {
            stream.writeBits(2, 1)
            stream.writeBits(2, planeOffset)
        } else if (abs(currentX - lastX) <= 1 && abs(currentY - lastY) <= 1) {
            val dx = currentX - lastX
            val dy = currentY - lastY
            val opcode = when {
                dx == -1 && dy == -1 -> 0
                dx == 1 && dy == -1 -> 2
                dx == -1 && dy == 1 -> 5
                dx == 1 && dy == 1 -> 7
                dy == -1 -> 1
                dx == -1 -> 3
                dx == 1 -> 4
                else -> 6
            }
            stream.writeBits(2, 2)
            stream.writeBits(5, (planeOffset shl 3) + (opcode and 0x7))
        } else {
            val xOff = currentX - lastX
            val yOff = currentY - lastY
            stream.writeBits(2, 3)
            stream.writeBits(18, (yOff and 0xff) + ((xOff and 0xff) shl 8) + (planeOffset shl 16))
        }
    }

    private fun skipPlayers(stream: BufferWriter, amount: Int) {
        val cfg = playerBitConfig
        for (i in cfg.skipThresholds.indices.reversed()) {
            if (amount >= cfg.skipThresholds[i]) {
                stream.writeBits(2, i)
                if (cfg.skipBitWidths[i] > 0) stream.writeBits(cfg.skipBitWidths[i], amount)
                return
            }
        }
    }

    private fun processOutsidePlayers(stream: BufferWriter, updateBlocks: BufferWriter, snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState, local: PlayerUpdatePlayer, playersByIndex: Array<PlayerUpdatePlayer?>, largeSceneView: Boolean, nsn2: Boolean) {
        val cfg = playerBitConfig
        stream.startBitAccess()
        var skip = 0
        state.localAddedPlayers = 0

        for (i in 0 until state.outIndexCount) {
            val playerIndex = state.outIndexes[i]
            if (nsn2 == ((state.slotFlags[playerIndex].toInt() and 0x1) == 0)) continue

            if (skip > 0) {
                skip--
                state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() or 2).toByte()
                continue
            }

            val p = playersByIndex[playerIndex]
            if (needsAdd(state, local, p, largeSceneView)) {
                val player = p!!
                stream.writeBits(1, 1)
                stream.writeBits(2, 0)

                val hash = player.regionHash18
                if (hash == state.regionHashes[playerIndex]) {
                    stream.writeBits(1, 0)
                } else {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, state.regionHashes[playerIndex], hash)
                    state.regionHashes[playerIndex] = hash
                }

                stream.writeBits(cfg.addPositionBits, player.xInRegion)
                stream.writeBits(cfg.addPositionBits, player.yInRegion)

                appendUpdateBlock(updateBlocks, state, player, added = true)
                stream.writeBits(1, 1)

                state.localAddedPlayers++
                state.localPlayers[player.index] = player
                state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() or 2).toByte()
            } else {
                val hash = p?.regionHash18 ?: state.regionHashes[playerIndex]
                if (p != null && hash != state.regionHashes[playerIndex]) {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, state.regionHashes[playerIndex], hash)
                    state.regionHashes[playerIndex] = hash
                } else {
                    stream.writeBits(1, 0)
                    for (i2 in i + 1 until state.outIndexCount) {
                        val p2Index = state.outIndexes[i2]
                        if (nsn2 == ((state.slotFlags[p2Index].toInt() and 0x1) == 0)) continue
                        val p2 = playersByIndex[p2Index]
                        if (needsAdd(state, local, p2, largeSceneView) || (p2 != null && p2.regionHash18 != state.regionHashes[p2Index])) break
                        skip++
                    }
                    skipPlayers(stream, skip)
                    state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() or 2).toByte()
                }
            }
        }
        stream.stopBitAccess()
    }

    private fun processLocalPlayers(stream: BufferWriter, updateBlocks: BufferWriter, snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState, local: PlayerUpdatePlayer, largeSceneView: Boolean, nsn0: Boolean) {
        val cfg = playerBitConfig
        stream.startBitAccess()
        var skip = 0

        for (i in 0 until state.localIndexCount) {
            val playerIndex = state.localIndexes[i]
            if (nsn0 == ((state.slotFlags[playerIndex].toInt() and 0x1) != 0)) continue

            if (skip > 0) {
                skip--
                state.localPlayers[playerIndex] = snapshot.playersByIndex[playerIndex]
                state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() or 2).toByte()
                continue
            }

            val clientRegionHash = regionHash18Of(state.localPlayers[playerIndex]!!.tileHash30)
            val p = snapshot.playersByIndex[playerIndex]
            if (p == null || needsRemove(local, p, largeSceneView)) {
                stream.writeBits(1, 1)
                stream.writeBits(1, 0)
                stream.writeBits(2, 0)
                val hash = p?.regionHash18 ?: clientRegionHash
                state.regionHashes[playerIndex] = hash
                if (hash == clientRegionHash) {
                    stream.writeBits(1, 0)
                } else {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, clientRegionHash, hash)
                }
                state.localPlayers[playerIndex] = null
                continue
            }
            state.localPlayers[playerIndex] = p

            val needUpdate = needsUpdateBlock(state, p)
            if (needUpdate) appendUpdateBlock(updateBlocks, state, p, added = false)

            if (p.hasTeleported) {
                stream.writeBits(1, 1)
                stream.writeBits(1, if (needUpdate) 1 else 0)
                stream.writeBits(2, 3)
                val last = p.lastTileHash30
                val curr = p.tileHash30
                val lastX = (last shr 14) and 0x3fff
                val lastY = last and 0x3fff
                val lastPlane = (last shr 28) and 0x3
                val currX = (curr shr 14) and 0x3fff
                val currY = curr and 0x3fff
                val currPlane = (curr shr 28) and 0x3
                var xOff = currX - lastX
                var yOff = currY - lastY
                val planeOff = currPlane - lastPlane
                if (abs(xOff) <= cfg.smallTeleportThreshold && abs(yOff) <= cfg.smallTeleportThreshold) {
                    stream.writeBits(1, 0)
                    if (xOff < 0) xOff += 32
                    if (yOff < 0) yOff += 32
                    stream.writeBits(cfg.smallTeleportBits, yOff + (xOff shl 5) + (planeOff shl 10))
                } else {
                    stream.writeBits(1, 1)
                    stream.writeBits(cfg.largeTeleportBits, (yOff and 0x3fff) + ((xOff and 0x3fff) shl 14) + ((planeOff and 0x3) shl 28))
                }
            } else if (p.nextWalkDirection != null) {
                stream.writeBits(1, 1)
                stream.writeBits(1, if (needUpdate) 1 else 0)
                if (p.nextRunDirectionOrOpcode != null) {
                    stream.writeBits(2, 2)
                    stream.writeBits(cfg.runDirectionBits, p.nextRunDirectionOrOpcode)
                } else {
                    stream.writeBits(2, 1)
                    stream.writeBits(cfg.walkDirectionBits, p.nextWalkDirection)
                }
            } else if (p.zeroDisplacementWalk) {
                stream.writeBits(1, 1)
                stream.writeBits(1, 1)
                stream.writeBits(2, 0)
                if (!needUpdate) appendUpdateBlock(updateBlocks, state, p, added = false)
            } else if (needUpdate) {
                stream.writeBits(1, 1)
                stream.writeBits(1, 1)
                stream.writeBits(2, 0)
            } else {
                stream.writeBits(1, 0)
                for (i2 in i + 1 until state.localIndexCount) {
                    val p2Index = state.localIndexes[i2]
                    if (nsn0 == ((state.slotFlags[p2Index].toInt() and 0x1) != 0)) continue
                    val p2 = snapshot.playersByIndex[p2Index] ?: break
                    val p2Need = needsRemove(local, p2, largeSceneView) || p2.hasTeleported || p2.nextWalkDirection != null || p2.zeroDisplacementWalk || needsUpdateBlock(state, p2)
                    if (p2Need) break
                    skip++
                }
                skipPlayers(stream, skip)
                state.slotFlags[playerIndex] = (state.slotFlags[playerIndex].toInt() or 2).toByte()
            }
        }
        stream.stopBitAccess()
    }

    private fun needsUpdateBlock(state: PlayerUpdateTrackingState, p: PlayerUpdatePlayer): Boolean {
        val b = p.blocks
        val c = b.common
        if (c.tint != null) return true
        if (c.faceEntity != null) return true
        if (c.faceTile != null) return true
        if (c.forceMovement != null) return true
        if (c.hits != null) return true
        if (c.modelOverrides != null) return true
        for (s in c.spotAnims) if (s != null) return true
        if (b.appearance != null && state.needAppearanceUpdate(p.index, b.appearance.hash)) return true
        if (b.movementAnimations != null) return true
        if (b.faceDirection != null) return true
        if (b.movementTypeId != null) return true
        if (b.temporaryMoveTypeId != null) return true
        if (b.reduceAttackPriority != null) return true
        if (b.mapDot != null) return true
        if (b.playerConfigsNoClear != null) return true
        if (b.playerConfigsClear != null) return true
        if (b.forceTalk != null) return true
        if (b.publicChat != null) return true
        if (b.sequences != null) return true
        return false
    }

    private fun appendUpdateBlock(out: BufferWriter, state: PlayerUpdateTrackingState, p: PlayerUpdatePlayer, added: Boolean) {
        val block = BufferWriter(4096)
        val ctx = PlayerMaskCtx(state = state, player = p, added = added)
        val present = buildPlayerMaskPresence(state, p, added)
        playerMasks.encode(ctx, present, out, block)
        out.writeBytes(block.toArray())
    }

    // ----------------------------
    // NPC update
    // ----------------------------

    override fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray {
        val cfg = npcBitConfig
        val stream = BufferWriter(capacity = 16384)
        val maskData = BufferWriter(capacity = 16384)

        stream.startBitAccess()
        processInScreen(stream, maskData, snapshot, state)
        addInScreen(stream, maskData, snapshot, state)
        if (maskData.position() > 0) {
            stream.writeBits(cfg.indexBits, cfg.terminator)
        }
        stream.stopBitAccess()

        stream.writeBytes(maskData.toArray())
        return stream.toArray()
    }

    private fun within(localTileHash30: Int, npc: NpcUpdateNpc, radius: Int): Boolean {
        val localX = (localTileHash30 shr 14) and 0x3fff
        val localY = localTileHash30 and 0x3fff
        return abs(npc.x - localX) <= radius && abs(npc.y - localY) <= radius
    }

    private fun processInScreen(stream: BufferWriter, maskOut: BufferWriter, snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState) {
        val cfg = npcBitConfig
        stream.writeBits(cfg.localCountBits, state.localCount)
        val radius = if (snapshot.largeSceneView) 126 else 14

        var writeIndex = 0
        for (i in 0 until state.localCount) {
            val idx = state.localIndexes[i]
            val npc = snapshot.nearbyNpcs.firstOrNull { it.index == idx }
            if (npc == null || npc.finished || npc.hasTeleported || !within(snapshot.localTileHash30, npc, radius)) {
                stream.writeBits(1, 1)
                stream.writeBits(2, 3)
                continue
            }

            val needUpdate = needsNpcUpdateBlock(npc)
            val walkUpdate = npc.nextWalkDirection != null
            stream.writeBits(1, if (needUpdate || walkUpdate) 1 else 0)
            if (walkUpdate) {
                when (npc.movementType) {
                    MoveType.WALK -> {
                        stream.writeBits(2, 1)
                        stream.writeBits(cfg.walkDirectionBits, npc.nextWalkDirection)
                    }
                    MoveType.CRAWL -> {
                        stream.writeBits(2, 2)
                        stream.writeBits(1, 0)
                        stream.writeBits(cfg.walkDirectionBits, npc.nextWalkDirection)
                    }
                    MoveType.RUN -> {
                        stream.writeBits(2, 2)
                        stream.writeBits(1, 1)
                        stream.writeBits(cfg.walkDirectionBits, npc.nextWalkDirection)
                        stream.writeBits(cfg.walkDirectionBits, npc.nextRunDirection ?: 0)
                    }
                    // TELEPORT is filtered upstream via hasTeleported; NPCs that teleport
                    // are removed from the local list and re-added with the 1-bit teleport flag,
                    // so this branch should never execute in practice.
                    MoveType.TELEPORT -> {}
                }
                stream.writeBits(1, if (needUpdate) 1 else 0)
            } else if (needUpdate) {
                stream.writeBits(2, 0)
            }

            if (needUpdate) {
                appendNpcMasks(maskOut, npc, added = false)
            }

            state.localIndexes[writeIndex++] = idx
        }

        state.localCount = writeIndex
    }

    private fun addInScreen(stream: BufferWriter, maskOut: BufferWriter, snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState) {
        val cfg = npcBitConfig
        val radius = if (snapshot.largeSceneView) 126 else 14
        val localX = (snapshot.localTileHash30 shr 14) and 0x3fff
        val localY = snapshot.localTileHash30 and 0x3fff
        val posBits = if (snapshot.largeSceneView) cfg.largePositionBits else cfg.normalPositionBits
        val posMask = (1 shl posBits) - 1

        for (npc in snapshot.nearbyNpcs) {
            if (state.localCount >= state.localIndexes.size) break
            if (state.localIndexes.take(state.localCount).any { it == npc.index }) continue
            if (npc.finished || !within(snapshot.localTileHash30, npc, radius)) continue

            stream.writeBits(cfg.indexBits, npc.index)

            val dx = npc.x - localX
            val dy = npc.y - localY
            val encX = ((dx and posMask) + (1 shl posBits)) % (1 shl posBits)
            val encY = ((dy and posMask) + (1 shl posBits)) % (1 shl posBits)

            stream.writeBits(1, 1)
            stream.writeBits(posBits, encY)
            stream.writeBits(cfg.rotationBits, (npc.faceAngle shr 11) - 4)
            stream.writeBits(cfg.idBits, npc.id)
            stream.writeBits(posBits, encX)
            stream.writeBits(1, if (npc.hasTeleported) 1 else 0)
            stream.writeBits(cfg.planeBits, npc.plane)

            state.localIndexes[state.localCount++] = npc.index
            appendNpcMasks(maskOut, npc, added = true)
        }
    }

    private fun appendNpcMasks(out: BufferWriter, npc: NpcUpdateNpc, added: Boolean) {
        val block = BufferWriter(4096)
        val ctx = NpcMaskCtx(npc = npc, added = added)
        val present = buildNpcMaskPresence(npc, added)
        npcMasks.encode(ctx, present, out, block)
        out.writeBytes(block.toArray())
    }

    private fun needsNpcUpdateBlock(npc: NpcUpdateNpc): Boolean {
        val b = npc.blocks
        val c = b.common
        if (b.movementAnimations != null) return true
        if (b.customisation != null) return true
        if (b.headCustomisation != null) return true
        if (b.basId != null) return true
        if (b.varnsNoClear != null) return true
        if (b.varnsClear != null) return true
        if (b.transformNpcId != null) return true
        if (b.updateStats != null) return true
        if (b.combatLevel != null) return true
        if (b.name != null) return true
        if (b.say != null) return true
        if (b.sequences != null) return true
        if (c.tint != null) return true
        if (c.forceMovement != null) return true
        if (c.faceEntity != null) return true
        if (c.faceTile != null) return true
        if (c.hits != null) return true
        if (c.modelOverrides != null) return true
        for (s in c.spotAnims) if (s != null) return true
        return false
    }
}
