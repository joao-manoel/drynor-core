package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.update.*
import world.gregs.voidps.buffer.write.BufferWriter

internal data class NpcMaskCtx(
    val npc: NpcUpdateNpc,
    val added: Boolean,
)

internal val npcHeader = MaskHeader(
    withExtensions = { mask ->
        var m = mask
        if (m > 0xff) m = m or 0x40
        if (m > 0xffff) m = m or 0x2000
        if (m > 0xffffff) m = m or 0x100000
        m
    },
    writeHeader = { mask, out ->
        out.writeByte(mask)
        if (mask > 0xff) out.writeByte(mask shr 8)
        if (mask > 0xffff) out.writeByte(mask shr 16)
        if (mask > 0xffffff) out.writeByte(mask shr 24)
    }
)

/**
 * NPC mask registry for revision 727, in client decode order.
 */
internal val npcMasks: MaskRegistry<NpcMaskCtx, NpcMaskKey> =
    MaskRegistryBuilder<NpcMaskCtx, NpcMaskKey>(header = npcHeader).apply {
        // 0x10 movement animations
        mask(NpcMaskKey.ANIMATION, 0x10) { ctx, out ->
                val ma = ctx.npc.blocks.movementAnimations!!
                for (id in ma.ids) out.writeBigSmart(id)
                out.writeByte(ma.speed)
        }

        // 0x100 customise (body)
        mask(NpcMaskKey.BODY_MESH_MODIFIER, 0x100) { ctx, out ->
                encodeNpcCustomisation(out, ctx.npc.blocks.customisation!!,
                    writeFlagByte = { o, f -> o.writeByteSubtract(f) })
        }

        // 0x400 force move
        mask(NpcMaskKey.FORCE_MOVEMENT, 0x400) { ctx, out ->
                val fm = ctx.npc.blocks.common.forceMovement!!
                out.writeByteSubtract(fm.diffX1)
                out.writeByteAdd(fm.diffY1)
                out.writeByteInverse(fm.diffX2)
                out.writeByteAdd(fm.diffY2)
                out.writeShortLittle(fm.startClientCycles)
                out.writeShortLittle(fm.speedClientCycles)
                out.writeShortAddLittle(fm.direction)
        }

        // 0x1000 BAS
        mask(NpcMaskKey.BAS, 0x1000) { ctx, out ->
                val v = ctx.npc.blocks.basId!!
                out.writeShort(if (v == -1) 65535 else v)
        }

        // 0x200000 SET_VARN
        mask(NpcMaskKey.VARN_SET, 0x200000) { ctx, out ->
                val d = ctx.npc.blocks.varnsNoClear!!
                out.writeByte(d.keys.size)
                for (i in d.keys.indices) {
                    out.writeShortAddLittle(d.keys[i])
                    out.writeIntMiddle(d.values[i])
                }
        }

        // 0x1000000 PLAY_GFX slot=3
        mask(NpcMaskKey.SPOT_ANIM_4, 0x1000000) { ctx, out ->
                val s3 = ctx.npc.blocks.common.spotAnims[3]!!
                val settingsHash = (s3.height shl 16) or (s3.speed and 0xFFFF)
                val settings2Hash = s3.rotation and 0x7
                out.writeShortAdd(if (s3.id == -1) 65535 else s3.id)
                out.writeIntInverseMiddle(settingsHash)
                out.writeByteAdd(settings2Hash)
        }

        // 0x80 face entity
        mask(NpcMaskKey.FACE_ENTITY, 0x80) { ctx, out ->
                val c = ctx.npc.blocks.common
                val face = c.faceEntity ?: c.persistentFaceEntity ?: -1
                out.writeShortLittle(if (face == -1) 65535 else face)
        }

        // 0x8000 animate sequences
        mask(NpcMaskKey.ANIMATION_SEQUENCE, 0x8000) { ctx, out ->
                val s = ctx.npc.blocks.sequences!!
                out.writeByteInverse(s.ids.size)
                for (i in s.ids.indices) {
                    out.writeBigSmart(s.ids[i])
                    out.writeByte(s.delays[i])
                    out.writeShortLittle(s.flags[i])
                }
        }

        // 0x800 GFX_ slot=1
        mask(NpcMaskKey.SPOT_ANIM_2, 0x800) { ctx, out ->
                val s1 = ctx.npc.blocks.common.spotAnims[1]!!
                val settingsHash = (s1.height shl 16) or (s1.speed and 0xFFFF)
                val settings2Hash = s1.rotation and 0x7
                out.writeShortLittle(if (s1.id == -1) 65535 else s1.id)
                out.writeInt(settingsHash)
                out.writeByteAdd(settings2Hash)
        }

        // 0x1 hits
        mask(NpcMaskKey.HITS, 0x1) { ctx, out ->
                encodeHits(out, ctx.npc.blocks.common.hits!!,
                    writeSplatCount = { o, c -> o.writeByteAdd(c) },
                    writeBarCount = { o, c -> o.writeByteAdd(c) })
        }

        // 0x8 transform
        mask(NpcMaskKey.TRANSFORMATION, 0x8) { ctx, out -> out.writeBigSmart(ctx.npc.blocks.transformNpcId!!) }

        // 0x2000000 spotanim slot=2
        mask(NpcMaskKey.SPOT_ANIM_3, 0x2000000) { ctx, out ->
                val s2 = ctx.npc.blocks.common.spotAnims[2]!!
                val settingsHash = (s2.height shl 16) or (s2.speed and 0xFFFF)
                val settings2Hash = s2.rotation and 0x7
                out.writeShortLittle(if (s2.id == -1) 65535 else s2.id)
                out.writeIntLittle(settingsHash)
                out.writeByte(settings2Hash)
        }

        // 0x80000 update stats
        mask(NpcMaskKey.STATS_UPDATE, 0x80000) { ctx, out ->
                val st = ctx.npc.blocks.updateStats!!
                out.writeByte(st.indices.size)
                for (i in st.indices.indices) {
                    out.writeByteAdd(st.indices[i])
                    out.writeInt(st.maxValues[i])
                    val current = st.currentValues[i]
                    out.writeByte(current shr 16)
                    out.writeByte(current)
                    out.writeByte(current shr 8)
                }
        }

        // 0x40000 head customisation
        mask(NpcMaskKey.HEAD_MESH_MODIFIER, 0x40000) { ctx, out ->
                encodeNpcCustomisation(out, ctx.npc.blocks.headCustomisation!!,
                    writeFlagByte = { o, f -> o.writeByteAdd(f) })
        }

        // 0x10000 combat level
        mask(NpcMaskKey.CHANGE_COMBAT_LEVEL, 0x10000) { ctx, out ->
                val b = ctx.npc.blocks
                val cb = b.combatLevel ?: b.persistentCombatLevel!!
                out.writeShortAdd(cb)
        }

        // 0x400000 clear varns
        mask(NpcMaskKey.VARN_CLEAR, 0x400000) { ctx, out ->
                val d = ctx.npc.blocks.varnsClear!!
                out.writeByte(d.keys.size)
                for (i in d.keys.indices) {
                    out.writeShort(d.keys[i])
                    out.writeIntMiddle(d.values[i])
                }
        }

        // 0x4 face tile
        mask(NpcMaskKey.FACE_TILE, 0x4) { ctx, out ->
                val ft = ctx.npc.blocks.common.faceTile!!
                out.writeShortAddLittle((ft.x shl 1) + 1)
                out.writeShortLittle((ft.y shl 1) + 1)
        }

        // 0x20000 tint
        mask(NpcMaskKey.TINT, 0x20000) { ctx, out ->
                val t = ctx.npc.blocks.common.tint!!
                out.writeByteSubtract(t.hue)
                out.writeByte(t.saturation)
                out.writeByte(t.lightness)
                out.writeByteSubtract(t.opacity)
                out.writeShortLittle(t.startCycles)
                out.writeShort(t.endCycles)
        }

        // 0x800000 name
        mask(NpcMaskKey.NAME_CHANGE, 0x800000) { ctx, out ->
                val b = ctx.npc.blocks
                out.writeString(b.name ?: b.persistentName!!)
        }

        // 0x2 say text
        mask(NpcMaskKey.FORCE_TALK, 0x2) { ctx, out -> out.writeString(ctx.npc.blocks.say!!) }

        // 0x4000 model overrides
        mask(NpcMaskKey.BODY_MODEL_ROTATOR, 0x4000) { ctx, out ->
                val o = ctx.npc.blocks.common.modelOverrides!!
                out.writeByteSubtract(o.keys.size)
                for (i in o.keys.indices) {
                    val key = o.keys[i]
                    val first = if (key ushr 16 != 0) (key ushr 16) else key
                    out.writeShortAddLittle(first)
                    if (key ushr 16 != 0) out.writeShort(key and 0xffff)
                    out.writeShortAddLittle(o.values[i])
                }
        }

        // 0x20 spotanim slot=0
        mask(NpcMaskKey.SPOT_ANIM_1, 0x20) { ctx, out ->
                val s0 = ctx.npc.blocks.common.spotAnims[0]!!
                val settingsHash = (s0.height shl 16) or (s0.speed and 0xFFFF)
                val settings2Hash = s0.rotation and 0x7
                out.writeShort(if (s0.id == -1) 65535 else s0.id)
                out.writeIntInverseMiddle(settingsHash)
                out.writeByteInverse(settings2Hash)
        }
    }.build()
