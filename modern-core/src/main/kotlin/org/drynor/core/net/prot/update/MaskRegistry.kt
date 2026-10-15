package org.drynor.core.net.prot.update

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * Revision-agnostic mask registry for entity update blocks.
 *
 * A revision defines:
 * - The mask bit ids (scrambled per revision)
 * - The on-wire block encoding (byte/short transforms, ordering)
 * - The header encoding rules (how many header bytes, extension-bit rules)
 *
 * Then the revision encoder can build a mask data integer and a block buffer in one pass.
 */

fun interface MaskHeaderWriter {
    fun withExtensions(maskData: Int): Int
}

fun interface MaskHeaderEncoder {
    fun write(maskData: Int, out: BufferWriter)
}

data class MaskHeader(
    val withExtensions: MaskHeaderWriter,
    val writeHeader: MaskHeaderEncoder,
)

data class MaskDef<C, K : Enum<K>>(
    val key: K,
    val bit: Int,
    val encode: (C, BufferWriter) -> Unit,
)

class MaskRegistry<C, K : Enum<K>>(
    private val header: MaskHeader,
    private val defs: List<MaskDef<C, K>>,
) {
    /**
     * Encodes header + block.
     *
     * @param present A boolean array indexed by [K.ordinal] marking which semantic masks are present.
     */
    fun encode(ctx: C, present: BooleanArray, out: BufferWriter, block: BufferWriter) {
        var maskData = 0
        for (def in defs) {
            if (!present[def.key.ordinal]) continue
            maskData = maskData or def.bit
            def.encode(ctx, block)
        }
        maskData = header.withExtensions.withExtensions(maskData)
        header.writeHeader.write(maskData, out)
    }
}

class MaskRegistryBuilder<C, K : Enum<K>>(
    private val header: MaskHeader,
) {
    private val defs = mutableListOf<MaskDef<C, K>>()

    fun mask(key: K, bit: Int, encode: (C, BufferWriter) -> Unit) {
        defs += MaskDef(key = key, bit = bit, encode = encode)
    }

    fun build(): MaskRegistry<C, K> = MaskRegistry(header = header, defs = defs.toList())
}

fun interface MaskHeaderDecoder {
    fun read(reader: Reader): Int
}

data class MaskDecodeDef<C, K : Enum<K>>(
    val key: K,
    val bit: Int,
    val decode: (C, Reader) -> Unit,
)

class MaskDecoderRegistry<C, K : Enum<K>>(
    private val header: MaskHeaderDecoder,
    private val defs: List<MaskDecodeDef<C, K>>,
) {
    fun decode(ctx: C, reader: Reader): Int {
        val maskData = header.read(reader)
        for (def in defs) {
            if ((maskData and def.bit) != 0) def.decode(ctx, reader)
        }
        return maskData
    }
}

class MaskDecoderRegistryBuilder<C, K : Enum<K>>(
    private val header: MaskHeaderDecoder,
) {
    private val defs = mutableListOf<MaskDecodeDef<C, K>>()

    fun mask(key: K, bit: Int, decode: (C, Reader) -> Unit) {
        defs += MaskDecodeDef(key = key, bit = bit, decode = decode)
    }

    fun build(): MaskDecoderRegistry<C, K> = MaskDecoderRegistry(header = header, defs = defs.toList())
}
