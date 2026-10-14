package org.darkan.core.net.prot.update

import org.darkan.core.update.NpcCustomisation
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * Shared NPC customisation encoding, parameterized on the flag byte transform.
 *
 * Body customisation uses `writeByteSubtract` for the flag byte.
 * Head customisation uses `writeByteAdd` for the flag byte.
 * The rest of the encoding (models via writeBigSmart, recolors, retextures) is identical.
 */
inline fun encodeNpcCustomisation(
    out: BufferWriter,
    custom: NpcCustomisation,
    writeFlagByte: (BufferWriter, Int) -> Unit,
) {
    if (custom.clear) {
        writeFlagByte(out, 0x1)
        return
    }
    var flags = 0
    if (custom.models != null) flags = flags or 0x2
    if (custom.recolors != null) flags = flags or 0x4
    if (custom.retextures != null) flags = flags or 0x8
    writeFlagByte(out, flags)
    if (custom.models != null) {
        for (id in custom.models) out.writeBigSmart(id)
    }
    if (custom.recolors != null) {
        for (c in custom.recolors) out.writeShort(c.toInt() and 0xffff)
    }
    if (custom.retextures != null) {
        for (t in custom.retextures) out.writeShort(t.toInt() and 0xffff)
    }
}

inline fun decodeNpcCustomisation(
    reader: Reader,
    head: Boolean,
    shape: () -> NpcCustomisationShape,
    readFlagByte: (Reader) -> Int,
): NpcCustomisation {
    val flags = readFlagByte(reader)
    if ((flags and 0x1) != 0) return NpcCustomisation(clear = true)
    val counts = shape()
    val modelCount = if (head) counts.headModels else counts.models
    val models = if ((flags and 0x2) != 0) IntArray(modelCount) { reader.readBigSmart() } else null
    val recolours = if ((flags and 0x4) != 0) ShortArray(counts.recolours) { reader.readShort().toShort() } else null
    val retextures = if ((flags and 0x8) != 0) ShortArray(counts.retextures) { reader.readShort().toShort() } else null
    return NpcCustomisation(clear = false, models = models, recolors = recolours, retextures = retextures)
}
