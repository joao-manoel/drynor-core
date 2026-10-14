package org.darkan.core.net.prot.update

import org.darkan.core.update.HitBarUpdate
import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * Shared hit splat + bar encoding, parameterized on the count-prefix byte transforms.
 *
 * Player hits uses `writeByteSubtract` for splat count and `writeByte` for bar count.
 * NPC hits uses `writeByteAdd` for both splat and bar count.
 * The splat and bar encoding bodies are identical between player and NPC.
 */
inline fun encodeHits(
    out: BufferWriter,
    hits: HitsUpdate,
    writeSplatCount: (BufferWriter, Int) -> Unit,
    writeBarCount: (BufferWriter, Int) -> Unit,
) {
    writeSplatCount(out, hits.hitSplats.size)

    for (splat in hits.hitSplats) {
        when (splat) {
            is HitSplat.Miss -> {
                out.writeSmart(32766)
                out.writeByteInverse(splat.missValue)
                out.writeSmart(splat.delay)
            }
            is HitSplat.Soak -> {
                val big = splat.value > Short.MAX_VALUE / 2 || splat.soakValue > Short.MAX_VALUE / 2
                if (big) {
                    out.writeSmart(32764)
                    out.writeSmart(splat.id)
                    out.writeString(splat.value.toString())
                    out.writeSmart(splat.soakId)
                    out.writeString(splat.soakValue.toString())
                } else {
                    out.writeSmart(32767)
                    out.writeSmart(splat.id)
                    out.writeSmart(splat.value)
                    out.writeSmart(splat.soakId)
                    out.writeSmart(splat.soakValue)
                }
                out.writeSmart(splat.delay)
            }
            is HitSplat.Standard -> {
                val big = splat.value > Short.MAX_VALUE / 2
                if (big) {
                    out.writeSmart(32765)
                    out.writeSmart(splat.id)
                    out.writeString(splat.value.toString())
                } else {
                    out.writeSmart(splat.id)
                    out.writeSmart(splat.value)
                }
                out.writeSmart(splat.delay)
            }
            is HitSplat.CustomText -> {
                if (splat.soakId != null && splat.soakText != null) {
                    out.writeSmart(32764)
                    out.writeSmart(splat.id)
                    out.writeString(splat.text)
                    out.writeSmart(splat.soakId)
                    out.writeString(splat.soakText)
                } else {
                    out.writeSmart(32765)
                    out.writeSmart(splat.id)
                    out.writeString(splat.text)
                }
                out.writeSmart(splat.delay)
            }
        }
    }

    writeBarCount(out, hits.hitBars.size)
    for (bar in hits.hitBars) {
        out.writeSmart(bar.type)
        out.writeSmart(bar.timer)
        if (bar.timer != 32767) {
            out.writeSmart(bar.delay)
            out.writeByteSubtract(bar.fromPercentage)
            if (bar.timer > 0) {
                out.writeByteAdd(bar.toPercentage)
            }
        }
    }
}

private const val BIG_HIT_THRESHOLD = Short.MAX_VALUE / 2

inline fun decodeHits(
    reader: Reader,
    readSplatCount: (Reader) -> Int,
    readBarCount: (Reader) -> Int,
): HitsUpdate {
    val splats = Array(readSplatCount(reader)) { decodeHitSplat(reader) }
    val bars = Array(readBarCount(reader)) { decodeHitBar(reader) }
    return HitsUpdate(hitSplats = splats, hitBars = bars)
}

fun decodeHitSplat(reader: Reader): HitSplat {
    return when (val type = reader.readSmart()) {
        32766 -> HitSplat.Miss(missValue = reader.readByteInverse() and 0xff, delay = reader.readSmart())
        32767 -> HitSplat.Soak(
            id = reader.readSmart(),
            value = reader.readSmart(),
            soakId = reader.readSmart(),
            soakValue = reader.readSmart(),
            delay = reader.readSmart(),
        )
        32764 -> {
            val id = reader.readSmart()
            val text = reader.readString()
            val soakId = reader.readSmart()
            val soakText = reader.readString()
            val delay = reader.readSmart()
            val value = text.toIntOrNull()
            val soakValue = soakText.toIntOrNull()
            if (value != null && soakValue != null && (value > BIG_HIT_THRESHOLD || soakValue > BIG_HIT_THRESHOLD)) {
                HitSplat.Soak(id, value, soakId, soakValue, delay)
            } else {
                HitSplat.CustomText(id, text, soakId, soakText, delay)
            }
        }
        32765 -> {
            val id = reader.readSmart()
            val text = reader.readString()
            val delay = reader.readSmart()
            val value = text.toIntOrNull()
            if (value != null && value > BIG_HIT_THRESHOLD) {
                HitSplat.Standard(id, value, delay)
            } else {
                HitSplat.CustomText(id = id, text = text, delay = delay)
            }
        }
        else -> HitSplat.Standard(id = type, value = reader.readSmart(), delay = reader.readSmart())
    }
}

fun decodeHitBar(reader: Reader): HitBarUpdate {
    val type = reader.readSmart()
    val timer = reader.readSmart()
    if (timer == 32767) return HitBarUpdate(type = type, timer = timer)
    val delay = reader.readSmart()
    val from = reader.readByteSubtract() and 0xff
    val to = if (timer > 0) reader.readUnsignedByteAdd() else from
    return HitBarUpdate(type = type, timer = timer, delay = delay, fromPercentage = from, toPercentage = to)
}
