package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.net.prot.update.AppearanceLayout
import org.drynor.core.net.prot.update.EntityStep
import org.drynor.core.net.prot.update.PlayerBitConfig
import org.drynor.core.net.prot.update.PlayerUpdateClientState
import org.drynor.core.net.prot.update.PlayerUpdateEntry
import org.drynor.core.net.prot.update.PlayerUpdateFrame
import org.drynor.core.net.prot.update.regionHash18Of
import org.drynor.core.net.prot.update.tileHash30
import org.drynor.core.net.prot.update.tileHash30Plane
import org.drynor.core.net.prot.update.tileHash30X
import org.drynor.core.net.prot.update.tileHash30Y
import org.drynor.core.update.PlayerAppearanceData
import org.drynor.core.update.PlayerUpdateBlocks
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader

internal object Rev727PlayerUpdateDecoder {

    private val WALK_DX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
    private val WALK_DY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    private val RUN_DX = intArrayOf(-2, -1, 0, 1, 2, -2, 2, -2, 2, -2, 2, -2, -1, 0, 1, 2)
    private val RUN_DY = intArrayOf(-2, -2, -2, -2, -2, -1, -1, 0, 0, 1, 1, 2, 2, 2, 2, 2)

    private class Pending(val index: Int) {
        var added = false
        var removed = false
        var step = EntityStep.NONE
        var masks: PlayerUpdateBlocks? = null
        var appearance: PlayerAppearanceData? = null
    }

    private class Session(
        val reader: Reader,
        val state: PlayerUpdateClientState,
        val config: PlayerBitConfig,
    ) {
        val pending = LinkedHashMap<Int, Pending>()
        val maskOrder = ArrayList<Int>()

        fun entry(index: Int): Pending = pending.getOrPut(index) { Pending(index) }
    }

    fun decode(data: ByteArray, state: PlayerUpdateClientState, config: PlayerBitConfig, layout: AppearanceLayout): PlayerUpdateFrame {
        check(state.initialised) { "Player update state must be initialised from the region packet first" }
        if (data.isEmpty()) return PlayerUpdateFrame(emptyList())
        val session = Session(BufferReader(data), state, config)

        readLocalPlayers(session, flagged = false)
        readLocalPlayers(session, flagged = true)
        readOutsidePlayers(session, flagged = true)
        readOutsidePlayers(session, flagged = false)

        for (index in session.maskOrder) readMasks(session, index, layout)
        check(session.reader.remaining == 0) { "Player update has ${session.reader.remaining} trailing bytes" }

        rebuildLists(state)
        return PlayerUpdateFrame(session.pending.values.map { toEntry(it, state) })
    }

    private fun readLocalPlayers(session: Session, flagged: Boolean) {
        val state = session.state
        val reader = session.reader
        reader.startBitAccess()
        var skip = 0
        for (i in 0 until state.localIndexCount) {
            val index = state.localIndexes[i]
            if (isFlagged(state, index) != flagged) continue
            if (skip > 0) {
                skip--
                markSkipped(state, index)
                continue
            }
            if (reader.readBits(1) == 0) {
                skip = readSkipCount(session)
                markSkipped(state, index)
                continue
            }
            readLocalPlayer(session, index)
        }
        reader.stopBitAccess()
        check(skip == 0) { "Local player skip count overran the list by $skip" }
    }

    private fun readLocalPlayer(session: Session, index: Int) {
        val state = session.state
        val reader = session.reader
        val config = session.config
        val hasMasks = reader.readBits(1) == 1
        if (hasMasks) session.maskOrder += index
        val entry = session.entry(index)
        val tile = state.tileHashes30[index]
        var x = tileHash30X(tile)
        var y = tileHash30Y(tile)
        var plane = tileHash30Plane(tile)
        when (reader.readBits(2)) {
            0 -> {
                if (hasMasks) return
                check(index != state.localIndex) { "Server removed the local player" }
                entry.removed = true
                state.local[index] = false
                state.regionHashes18[index] = regionHash18Of(tile)
                if (reader.readBits(1) == 1) readRegionUpdate(session, index, reader.readBits(2))
                return
            }
            1 -> {
                val direction = reader.readBits(config.walkDirectionBits)
                x += WALK_DX[direction]
                y += WALK_DY[direction]
                entry.step = EntityStep.WALK
            }
            2 -> {
                val direction = reader.readBits(config.runDirectionBits)
                x += RUN_DX[direction]
                y += RUN_DY[direction]
                entry.step = EntityStep.RUN
            }
            else -> {
                if (reader.readBits(1) == 0) {
                    val packed = reader.readBits(config.smallTeleportBits)
                    x += signed5(packed shr 5)
                    y += signed5(packed)
                    plane = (plane + (packed shr 10)) and 0x3
                } else {
                    val packed = reader.readBits(config.largeTeleportBits)
                    x = (x + ((packed shr 14) and 0x3fff)) and 0x3fff
                    y = (y + (packed and 0x3fff)) and 0x3fff
                    plane = (plane + (packed shr 28)) and 0x3
                }
                entry.step = EntityStep.TELEPORT
            }
        }
        state.tileHashes30[index] = tileHash30(x, y, plane)
    }

    private fun readOutsidePlayers(session: Session, flagged: Boolean) {
        val state = session.state
        val reader = session.reader
        reader.startBitAccess()
        var skip = 0
        for (i in 0 until state.outIndexCount) {
            val index = state.outIndexes[i]
            if (isFlagged(state, index) != flagged) continue
            if (skip > 0) {
                skip--
                markSkipped(state, index)
                continue
            }
            if (reader.readBits(1) == 0) {
                skip = readSkipCount(session)
                markSkipped(state, index)
                continue
            }
            val type = reader.readBits(2)
            if (type == 0) {
                addPlayer(session, index)
                markSkipped(state, index)
            } else {
                readRegionUpdate(session, index, type)
            }
        }
        reader.stopBitAccess()
        check(skip == 0) { "Outside player skip count overran the list by $skip" }
    }

    private fun addPlayer(session: Session, index: Int) {
        val state = session.state
        val reader = session.reader
        val bits = session.config.addPositionBits
        if (reader.readBits(1) == 1) readRegionUpdate(session, index, reader.readBits(2))
        val xInRegion = reader.readBits(bits)
        val yInRegion = reader.readBits(bits)
        if (reader.readBits(1) == 1) session.maskOrder += index
        val region = state.regionHashes18[index]
        val regionX = (region shr 8) and 0xff
        val regionY = region and 0xff
        val plane = (region shr 16) and 0x3
        state.tileHashes30[index] = tileHash30((regionX shl 6) + xInRegion, (regionY shl 6) + yInRegion, plane)
        state.local[index] = true
        session.entry(index).added = true
    }

    private fun readRegionUpdate(session: Session, index: Int, type: Int) {
        val state = session.state
        val reader = session.reader
        val region = state.regionHashes18[index]
        var plane = (region shr 16) and 0x3
        var regionX = (region shr 8) and 0xff
        var regionY = region and 0xff
        when (type) {
            1 -> plane = (plane + reader.readBits(2)) and 0x3
            2 -> {
                val packed = reader.readBits(5)
                val direction = packed and 0x7
                plane = (plane + (packed shr 3)) and 0x3
                regionX = (regionX + WALK_DX[direction]) and 0xff
                regionY = (regionY + WALK_DY[direction]) and 0xff
            }
            3 -> {
                val packed = reader.readBits(18)
                plane = (plane + (packed shr 16)) and 0x3
                regionX = (regionX + ((packed shr 8) and 0xff)) and 0xff
                regionY = (regionY + (packed and 0xff)) and 0xff
            }
            else -> error("Unexpected player add inside a region hash update for $index")
        }
        state.regionHashes18[index] = (plane shl 16) or (regionX shl 8) or regionY
    }

    private fun readMasks(session: Session, index: Int, layout: AppearanceLayout) {
        val state = session.state
        val ctx = PlayerMaskDecodeCtx()
        playerMaskDecoders.decode(ctx, session.reader)
        val blocks = ctx.blocks.build().copy(common = ctx.common.build())
        val entry = session.entry(index)
        entry.masks = blocks
        blocks.movementTypeId?.let { state.moveTypes[index] = it }
        blocks.appearance?.let { appearance ->
            val decoded = runCatching { Rev727AppearanceDecoder.decode(appearance.data, layout) }.getOrNull()
            state.appearances[index] = appearance
            state.appearanceData[index] = decoded
            entry.appearance = decoded
        }
    }

    private fun toEntry(pending: Pending, state: PlayerUpdateClientState) = PlayerUpdateEntry(
        index = pending.index,
        added = pending.added,
        removed = pending.removed,
        step = pending.step,
        tileHash30 = state.tileHashes30[pending.index],
        moveType = state.moveTypes[pending.index],
        masks = pending.masks,
        appearance = pending.appearance,
    )

    private fun rebuildLists(state: PlayerUpdateClientState) {
        state.localIndexCount = 0
        state.outIndexCount = 0
        for (index in 1 until PlayerUpdateClientState.PLAYER_SLOTS) {
            state.slotFlags[index] = (state.slotFlags[index].toInt() shr 1).toByte()
            if (state.local[index]) {
                state.localIndexes[state.localIndexCount++] = index
            } else {
                state.outIndexes[state.outIndexCount++] = index
            }
        }
    }

    private fun readSkipCount(session: Session): Int {
        val width = session.config.skipBitWidths[session.reader.readBits(2)]
        return if (width > 0) session.reader.readBits(width) else 0
    }

    private fun isFlagged(state: PlayerUpdateClientState, index: Int): Boolean =
        (state.slotFlags[index].toInt() and 0x1) != 0

    private fun markSkipped(state: PlayerUpdateClientState, index: Int) {
        state.slotFlags[index] = (state.slotFlags[index].toInt() or 0x2).toByte()
    }

    private fun signed5(value: Int): Int {
        val v = value and 0x1f
        return if (v > 15) v - 32 else v
    }
}
