package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.update.MaskDecoderRegistry
import org.darkan.core.net.prot.update.MaskDecoderRegistryBuilder
import org.darkan.core.net.prot.update.MaskHeaderDecoder
import org.darkan.core.net.prot.update.NpcCustomisationLayout
import org.darkan.core.net.prot.update.NpcCustomisationShape
import org.darkan.core.net.prot.update.NpcMaskKey
import org.darkan.core.net.prot.update.decodeHits
import org.darkan.core.net.prot.update.decodeNpcCustomisation
import org.darkan.core.update.CommonUpdateBlocksBuilder
import org.darkan.core.update.FaceTile
import org.darkan.core.update.ForceMovement
import org.darkan.core.update.KeyIntMapDelta
import org.darkan.core.update.ModelOverrides
import org.darkan.core.update.NpcStatsUpdate
import org.darkan.core.update.NpcUpdateBlocksBuilder
import org.darkan.core.update.SequencesUpdate
import org.darkan.core.update.Tint
import world.gregs.voidps.buffer.read.Reader

internal class NpcMaskDecodeCtx(
    var npcId: Int,
    private val layout: NpcCustomisationLayout,
) {
    val blocks = NpcUpdateBlocksBuilder()
    val common = CommonUpdateBlocksBuilder()

    fun customisationShape(): NpcCustomisationShape =
        layout.shape(npcId) ?: error("No customisation shape for npc $npcId; the rest of the update cannot be decoded")
}

internal val npcHeaderDecoder = MaskHeaderDecoder { reader ->
    var mask = reader.readUnsignedByte()
    if ((mask and 0x40) != 0) mask = mask or (reader.readUnsignedByte() shl 8)
    if ((mask and 0x2000) != 0) mask = mask or (reader.readUnsignedByte() shl 16)
    if ((mask and 0x100000) != 0) mask = mask or (reader.readUnsignedByte() shl 24)
    mask
}

internal val npcMaskDecoders: MaskDecoderRegistry<NpcMaskDecodeCtx, NpcMaskKey> =
    MaskDecoderRegistryBuilder<NpcMaskDecodeCtx, NpcMaskKey>(header = npcHeaderDecoder).apply {
        mask(NpcMaskKey.ANIMATION, 0x10) { ctx, r ->
            ctx.blocks.movementAnimations = r.readMovementAnimations()
        }

        mask(NpcMaskKey.BODY_MESH_MODIFIER, 0x100) { ctx, r ->
            ctx.blocks.customisation = decodeNpcCustomisation(r, head = false, shape = ctx::customisationShape,
                readFlagByte = { it.readUnsignedByteSubtract() })
        }

        mask(NpcMaskKey.FORCE_MOVEMENT, 0x400) { ctx, r ->
            ctx.common.forceMovement = ForceMovement(
                diffX1 = r.readByteSubtract(),
                diffY1 = r.readByteAdd(),
                diffX2 = r.readByteInverse(),
                diffY2 = r.readByteAdd(),
                startClientCycles = r.readUnsignedShortLittle(),
                speedClientCycles = r.readUnsignedShortLittle(),
                direction = r.readShortAddLittle() and 0xffff,
            )
        }

        mask(NpcMaskKey.BAS, 0x1000) { ctx, r ->
            ctx.blocks.basId = r.readUnsignedShort().let { if (it == 65535) -1 else it }
        }

        mask(NpcMaskKey.VARN_SET, 0x200000) { ctx, r ->
            ctx.blocks.varnsNoClear = r.readVarns(clear = false) { readShortAddLittle() and 0xffff }
        }

        mask(NpcMaskKey.SPOT_ANIM_4, 0x1000000) { ctx, r ->
            ctx.common.decodedSpotAnim(3, r.readUnsignedShortAdd(), r.readIntInverseMiddle(), r.readUnsignedByteAdd())
        }

        mask(NpcMaskKey.FACE_ENTITY, 0x80) { ctx, r ->
            ctx.common.faceEntity = r.readUnsignedShortLittle().let { if (it == 65535) -1 else it }
        }

        mask(NpcMaskKey.ANIMATION_SEQUENCE, 0x8000) { ctx, r ->
            val count = r.readByteInverse() and 0xff
            val ids = IntArray(count)
            val delays = IntArray(count)
            val flags = IntArray(count)
            for (i in 0 until count) {
                ids[i] = r.readBigSmart()
                delays[i] = r.readUnsignedByte()
                flags[i] = r.readUnsignedShortLittle()
            }
            ctx.blocks.sequences = SequencesUpdate(ids, delays, flags)
        }

        mask(NpcMaskKey.SPOT_ANIM_2, 0x800) { ctx, r ->
            ctx.common.decodedSpotAnim(1, r.readUnsignedShortLittle(), r.readInt(), r.readUnsignedByteAdd())
        }

        mask(NpcMaskKey.HITS, 0x1) { ctx, r ->
            ctx.common.hits = decodeHits(r,
                readSplatCount = { it.readUnsignedByteAdd() },
                readBarCount = { it.readUnsignedByteAdd() })
        }

        mask(NpcMaskKey.TRANSFORMATION, 0x8) { ctx, r ->
            val id = r.readBigSmart()
            ctx.blocks.transformNpcId = id
            ctx.npcId = id
        }

        mask(NpcMaskKey.SPOT_ANIM_3, 0x2000000) { ctx, r ->
            ctx.common.decodedSpotAnim(2, r.readUnsignedShortLittle(), r.readIntLittle(), r.readUnsignedByte())
        }

        mask(NpcMaskKey.STATS_UPDATE, 0x80000) { ctx, r ->
            val count = r.readUnsignedByte()
            val indices = IntArray(count)
            val maxValues = IntArray(count)
            val currentValues = IntArray(count)
            for (i in 0 until count) {
                indices[i] = r.readUnsignedByteAdd()
                maxValues[i] = r.readInt()
                val high = r.readUnsignedByte()
                val low = r.readUnsignedByte()
                val middle = r.readUnsignedByte()
                currentValues[i] = (high shl 16) or (middle shl 8) or low
            }
            ctx.blocks.updateStats = NpcStatsUpdate(indices, maxValues, currentValues)
        }

        mask(NpcMaskKey.HEAD_MESH_MODIFIER, 0x40000) { ctx, r ->
            ctx.blocks.headCustomisation = decodeNpcCustomisation(r, head = true, shape = ctx::customisationShape,
                readFlagByte = { it.readUnsignedByteAdd() })
        }

        mask(NpcMaskKey.CHANGE_COMBAT_LEVEL, 0x10000) { ctx, r ->
            ctx.blocks.combatLevel = r.readUnsignedShortAdd().let { if (it == 65535) -1 else it }
        }

        mask(NpcMaskKey.VARN_CLEAR, 0x400000) { ctx, r ->
            ctx.blocks.varnsClear = r.readVarns(clear = true) { readUnsignedShort() }
        }

        mask(NpcMaskKey.FACE_TILE, 0x4) { ctx, r ->
            val x = (r.readShortAddLittle() and 0xffff) shr 1
            val y = r.readUnsignedShortLittle() shr 1
            ctx.common.faceTile = FaceTile(x, y)
        }

        mask(NpcMaskKey.TINT, 0x20000) { ctx, r ->
            ctx.common.tint = Tint(
                hue = r.readByteSubtract(),
                saturation = r.readByte(),
                lightness = r.readByte(),
                opacity = r.readUnsignedByteSubtract(),
                startCycles = r.readUnsignedShortLittle(),
                endCycles = r.readUnsignedShort(),
            )
        }

        mask(NpcMaskKey.NAME_CHANGE, 0x800000) { ctx, r ->
            ctx.blocks.name = r.readString()
        }

        mask(NpcMaskKey.FORCE_TALK, 0x2) { ctx, r ->
            ctx.blocks.say = r.readString()
        }

        mask(NpcMaskKey.BODY_MODEL_ROTATOR, 0x4000) { ctx, r ->
            val count = r.readUnsignedByteSubtract()
            val keys = IntArray(count)
            val values = IntArray(count)
            for (i in 0 until count) {
                val first = r.readShortAddLittle() and 0xffff
                keys[i] = if (isExtendedOverrideKey(first)) (first shl 16) or r.readUnsignedShort() else first
                values[i] = r.readShortAddLittle() and 0xffff
            }
            ctx.common.modelOverrides = ModelOverrides(keys, values)
        }

        mask(NpcMaskKey.SPOT_ANIM_1, 0x20) { ctx, r ->
            ctx.common.decodedSpotAnim(0, r.readUnsignedShort(), r.readIntInverseMiddle(), r.readByteInverse() and 0xff)
        }
    }.build()

private inline fun Reader.readVarns(clear: Boolean, readKey: Reader.() -> Int): KeyIntMapDelta {
    val count = readUnsignedByte()
    val keys = IntArray(count)
    val values = IntArray(count)
    for (i in 0 until count) {
        keys[i] = readKey()
        values[i] = readUnsignedIntMiddle()
    }
    return KeyIntMapDelta(clear = clear, keys = keys, values = values)
}

private fun Reader.readUnsignedByteSubtract(): Int = readByteSubtract() and 0xff
