package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.map.pathfinder.Direction
import org.drynor.core.net.prot.update.EntityStep
import org.drynor.core.net.prot.update.NpcBitConfig
import org.drynor.core.net.prot.update.NpcUpdateClientState
import org.drynor.core.net.prot.update.NpcUpdateEntry
import org.drynor.core.net.prot.update.NpcUpdateFrame
import org.drynor.core.net.prot.update.tileHash30
import org.drynor.core.net.prot.update.tileHash30Plane
import org.drynor.core.net.prot.update.tileHash30X
import org.drynor.core.net.prot.update.tileHash30Y
import org.drynor.core.update.NpcUpdateBlocks
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader

internal object Rev727NpcUpdateDecoder {

    private class Pending(val index: Int, var id: Int) {
        var added = false
        var removed = false
        var teleported = false
        var step = EntityStep.NONE
        var faceAngle: Int? = null
        var masks: NpcUpdateBlocks? = null
    }

    private class BitCursor(private val reader: Reader, private val totalBits: Int) {
        var position = 0
            private set

        val remaining: Int
            get() = totalBits - position

        fun read(count: Int): Int {
            position += count
            return reader.readBits(count)
        }
    }

    fun decode(
        data: ByteArray,
        state: NpcUpdateClientState,
        config: NpcBitConfig,
        localTileHash30: Int,
        largeSceneView: Boolean,
    ): NpcUpdateFrame {
        if (data.isEmpty()) return NpcUpdateFrame(emptyList())
        val reader = BufferReader(data)
        val bits = BitCursor(reader, data.size * 8)
        val pending = LinkedHashMap<Int, Pending>()
        val maskOrder = ArrayList<Int>()
        fun entry(index: Int) = pending.getOrPut(index) { Pending(index, state.npcIds[index]) }

        reader.startBitAccess()
        val count = bits.read(config.localCountBits)
        check(count <= state.localCount) { "Npc update keeps $count npcs but only ${state.localCount} are local" }
        val previous = state.localIndexes.copyOf(state.localCount)
        state.localCount = 0
        for (i in count until previous.size) entry(previous[i]).removed = true

        for (i in 0 until count) {
            val index = previous[i]
            if (bits.read(1) == 0) {
                state.localIndexes[state.localCount++] = index
                continue
            }
            when (bits.read(2)) {
                0 -> maskOrder += index
                1 -> {
                    move(state, index, bits.read(config.walkDirectionBits))
                    entry(index).step = EntityStep.WALK
                    if (bits.read(1) == 1) maskOrder += index
                }
                2 -> {
                    if (bits.read(1) == 1) {
                        move(state, index, bits.read(config.walkDirectionBits))
                        move(state, index, bits.read(config.walkDirectionBits))
                        entry(index).step = EntityStep.RUN
                    } else {
                        move(state, index, bits.read(config.walkDirectionBits))
                        entry(index).step = EntityStep.CRAWL
                    }
                    if (bits.read(1) == 1) maskOrder += index
                }
                else -> {
                    entry(index).removed = true
                    continue
                }
            }
            state.localIndexes[state.localCount++] = index
        }

        val positionBits = if (largeSceneView) config.largePositionBits else config.normalPositionBits
        val localX = tileHash30X(localTileHash30)
        val localY = tileHash30Y(localTileHash30)
        while (bits.remaining >= config.indexBits) {
            val index = bits.read(config.indexBits)
            if (index == config.terminator) break
            val hasMasks = bits.read(1) == 1
            val dy = signed(bits.read(positionBits), positionBits)
            val faceAngle = ((bits.read(config.rotationBits) + 4) shl 11) and 0x3fff
            val id = bits.read(config.idBits)
            val dx = signed(bits.read(positionBits), positionBits)
            val teleported = bits.read(1) == 1
            val plane = bits.read(config.planeBits)

            state.npcIds[index] = id
            state.tileHashes30[index] = tileHash30(localX + dx, localY + dy, plane)
            state.localIndexes[state.localCount++] = index
            if (hasMasks) maskOrder += index
            val added = entry(index)
            added.id = id
            added.added = true
            added.removed = false
            added.teleported = teleported
            added.faceAngle = faceAngle
        }
        reader.stopBitAccess()

        for (index in maskOrder) {
            val ctx = NpcMaskDecodeCtx(state.npcIds[index], state.customisationLayout)
            npcMaskDecoders.decode(ctx, reader)
            val masks = ctx.blocks.build().copy(common = ctx.common.build())
            state.npcIds[index] = ctx.npcId
            val entry = entry(index)
            entry.id = ctx.npcId
            entry.masks = masks
        }
        check(reader.remaining == 0) { "Npc update has ${reader.remaining} trailing bytes" }

        val entries = pending.values.map { toEntry(it, state) }
        for (p in pending.values) {
            if (p.removed) state.npcIds[p.index] = -1
        }
        return NpcUpdateFrame(entries)
    }

    private fun move(state: NpcUpdateClientState, index: Int, directionId: Int) {
        val direction = Direction.getById(directionId)
        val tile = state.tileHashes30[index]
        state.tileHashes30[index] = tileHash30(
            tileHash30X(tile) + direction.dx,
            tileHash30Y(tile) + direction.dy,
            tileHash30Plane(tile),
        )
    }

    private fun toEntry(pending: Pending, state: NpcUpdateClientState) = NpcUpdateEntry(
        index = pending.index,
        id = pending.id,
        added = pending.added,
        removed = pending.removed,
        teleported = pending.teleported,
        step = pending.step,
        tileHash30 = state.tileHashes30[pending.index],
        faceAngle = pending.faceAngle,
        masks = pending.masks,
    )

    private fun signed(value: Int, bitCount: Int): Int {
        val half = 1 shl (bitCount - 1)
        return if (value >= half) value - (1 shl bitCount) else value
    }
}
