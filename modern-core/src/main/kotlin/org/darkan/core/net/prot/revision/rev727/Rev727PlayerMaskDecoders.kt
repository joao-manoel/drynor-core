package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.update.MaskDecoderRegistry
import org.darkan.core.net.prot.update.MaskDecoderRegistryBuilder
import org.darkan.core.net.prot.update.MaskHeaderDecoder
import org.darkan.core.net.prot.update.PlayerMaskKey
import org.darkan.core.net.prot.update.decodeHits
import org.darkan.core.update.CommonUpdateBlocksBuilder
import org.darkan.core.update.ForceMovement
import org.darkan.core.update.KeyIntMapDelta
import org.darkan.core.update.ModelOverrides
import org.darkan.core.update.MovementAnimations
import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerUpdateBlocksBuilder
import org.darkan.core.update.PublicChat
import org.darkan.core.update.SequencesUpdate
import org.darkan.core.update.Tint
import world.gregs.voidps.buffer.read.Reader

internal class PlayerMaskDecodeCtx {
    val blocks = PlayerUpdateBlocksBuilder()
    val common = CommonUpdateBlocksBuilder()
}

internal val playerHeaderDecoder = MaskHeaderDecoder { reader ->
    var mask = reader.readUnsignedByte()
    if ((mask and 0x8) != 0) mask = mask or (reader.readUnsignedByte() shl 8)
    if ((mask and 0x100) != 0) mask = mask or (reader.readUnsignedByte() shl 16)
    mask
}

internal val playerMaskDecoders: MaskDecoderRegistry<PlayerMaskDecodeCtx, PlayerMaskKey> =
    MaskDecoderRegistryBuilder<PlayerMaskDecodeCtx, PlayerMaskKey>(header = playerHeaderDecoder).apply {
        mask(PlayerMaskKey.TINT, 0x20000) { ctx, r ->
            ctx.common.tint = Tint(
                hue = r.readByte(),
                saturation = r.readByteInverse(),
                lightness = r.readByteSubtract(),
                opacity = r.readUnsignedByteSubtract(),
                startCycles = r.readUnsignedShortAdd(),
                endCycles = r.readUnsignedShort(),
            )
        }

        mask(PlayerMaskKey.VARN_NO_CLEAR, 0x400000) { ctx, r ->
            ctx.blocks.playerConfigsNoClear = r.readPlayerConfigs(clear = false)
        }

        mask(PlayerMaskKey.SPOT_ANIM_2, 0x200) { ctx, r ->
            ctx.common.decodedSpotAnim(1, r.readUnsignedShort(), r.readIntInverseMiddle(), r.readUnsignedByteSubtract())
        }

        mask(PlayerMaskKey.FACE_DIRECTION, 0x20) { ctx, r ->
            ctx.blocks.faceDirection = r.readUnsignedShortAdd()
        }

        mask(PlayerMaskKey.VARN_CLEAR, 0x800000) { ctx, r ->
            ctx.blocks.playerConfigsClear = r.readPlayerConfigs(clear = true)
        }

        mask(PlayerMaskKey.MAP_DOT_2, 0x10000) { ctx, r ->
            ctx.blocks.reduceAttackPriority = r.readUnsignedByteSubtract() == 1
        }

        mask(PlayerMaskKey.BODY_MODEL_ROTATOR, 0x100000) { ctx, r ->
            val count = r.readUnsignedByteSubtract()
            val keys = IntArray(count)
            val values = IntArray(count)
            for (i in 0 until count) {
                val first = r.readUnsignedShortAdd()
                keys[i] = if (isExtendedOverrideKey(first)) (first shl 16) or r.readUnsignedShortAdd() else first
                values[i] = r.readUnsignedShort()
            }
            ctx.common.modelOverrides = ModelOverrides(keys, values)
        }

        mask(PlayerMaskKey.FORCE_TALK, 0x4000) { ctx, r ->
            ctx.blocks.forceTalk = r.readString()
        }

        mask(PlayerMaskKey.FORCE_CHAT, 0x80000) { ctx, r ->
            ctx.blocks.publicChat = PublicChat(message = r.readString(), chatFlags = r.readUnsignedByteAdd())
        }

        mask(PlayerMaskKey.SPOT_ANIM_3, 0x40000) { ctx, r ->
            ctx.common.decodedSpotAnim(2, r.readShortAddLittle() and 0xffff, r.readIntInverseMiddle(), r.readUnsignedByteAdd())
        }

        mask(PlayerMaskKey.MAP_DOT_1, 0x8000) { ctx, r ->
            ctx.blocks.mapDot = r.readUnsignedByteSubtract() == 1
        }

        mask(PlayerMaskKey.HITS, 0x40) { ctx, r ->
            ctx.common.hits = decodeHits(r,
                readSplatCount = { it.readUnsignedByteSubtract() },
                readBarCount = { it.readUnsignedByte() })
        }

        mask(PlayerMaskKey.APPEARANCE, 0x1) { ctx, r ->
            val data = ByteArray(r.readByteInverse() and 0xff)
            r.readBytes(data)
            ctx.blocks.appearance = PlayerAppearance.of(data)
        }

        mask(PlayerMaskKey.ANIMATION, 0x10) { ctx, r ->
            ctx.blocks.movementAnimations = r.readMovementAnimations()
        }

        mask(PlayerMaskKey.FACE_ENTITY, 0x2) { ctx, r ->
            ctx.common.faceEntity = r.readUnsignedShortAdd().let { if (it == 65535) -1 else it }
        }

        mask(PlayerMaskKey.TEMPORARY_MOVE_TYPE, 0x1000) { ctx, r ->
            ctx.blocks.temporaryMoveTypeId = r.readByteInverse()
        }

        mask(PlayerMaskKey.ANIMATION_SEQUENCE, 0x2000) { ctx, r ->
            val count = r.readUnsignedByteSubtract()
            val ids = IntArray(count)
            val delays = IntArray(count)
            val flags = IntArray(count)
            for (i in 0 until count) {
                ids[i] = r.readBigSmart()
                delays[i] = r.readByteInverse() and 0xff
                flags[i] = r.readShortAddLittle() and 0xffff
            }
            ctx.blocks.sequences = SequencesUpdate(ids, delays, flags)
        }

        mask(PlayerMaskKey.MOVE_TYPE, 0x4) { ctx, r ->
            ctx.blocks.movementTypeId = r.readByteSubtract()
        }

        mask(PlayerMaskKey.SPOT_ANIM_4, 0x200000) { ctx, r ->
            ctx.common.decodedSpotAnim(3, r.readUnsignedShortLittle(), r.readIntLittle(), r.readUnsignedByteSubtract())
        }

        mask(PlayerMaskKey.FORCE_MOVEMENT, 0x800) { ctx, r ->
            ctx.common.forceMovement = ForceMovement(
                diffX1 = r.readByteInverse(),
                diffY1 = r.readByteSubtract(),
                diffX2 = r.readByteAdd(),
                diffY2 = r.readByteInverse(),
                startClientCycles = r.readShortAddLittle() and 0xffff,
                speedClientCycles = r.readUnsignedShortLittle(),
                direction = r.readUnsignedShortAdd(),
            )
        }

        mask(PlayerMaskKey.SPOT_ANIM_1, 0x80) { ctx, r ->
            ctx.common.decodedSpotAnim(0, r.readUnsignedShortLittle(), r.readInt(), r.readUnsignedByteAdd())
        }
    }.build()

internal fun CommonUpdateBlocksBuilder.decodedSpotAnim(slot: Int, id: Int, settingsHash: Int, settings2Hash: Int) {
    spotAnim(
        slot = slot,
        id = if (id == 65535) -1 else id,
        height = settingsHash shr 16,
        speed = settingsHash and 0xffff,
        rotation = settings2Hash and 0x7,
        adjustReplayMode = ((settings2Hash shr 7) and 0x1) == 1,
    )
}

internal fun isExtendedOverrideKey(first: Int): Boolean = (first and 0xc000) == 0xc000

internal fun Reader.readMovementAnimations(): MovementAnimations {
    val ids = IntArray(4) { readBigSmart() }
    return MovementAnimations(ids = ids, speed = readUnsignedByte())
}

private fun Reader.readPlayerConfigs(clear: Boolean): KeyIntMapDelta {
    val count = readUnsignedByte()
    val keys = IntArray(count)
    val values = IntArray(count)
    for (i in 0 until count) {
        keys[i] = readUnsignedShortLittle()
        values[i] = readInt()
    }
    return KeyIntMapDelta(clear = clear, keys = keys, values = values)
}

private fun Reader.readUnsignedByteSubtract(): Int = readByteSubtract() and 0xff
