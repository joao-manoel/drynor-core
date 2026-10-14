package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.TEXTURES
import world.gregs.voidps.cache.definition.TextureOpFormat
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.data.TextureOp
import world.gregs.voidps.cache.definition.data.TextureOpcode
import world.gregs.voidps.cache.definition.data.TextureShape

/**
 * Index 9's procedural texture graphs, read losslessly.
 *
 * The client (`TextureType`, `TextureTypeList.listOperation`) reads exactly this and throws the
 * payloads into per-operation fields it has already scaled, so nothing it keeps can be written
 * back. This keeps the records as the file spells them - see [TextureOpcode] - which makes the
 * whole file a data structure and [world.gregs.voidps.cache.definition.encoder.TextureEncoder]
 * its exact inverse.
 *
 * Two things the client ignores are checked or kept rather than dropped: every operation leads
 * with its own index in the list, which is asserted, and whatever follows the three output indices
 * is kept in [TextureDefinition.trailer].
 */
class TextureDecoder : DefinitionDecoder<TextureDefinition>(TEXTURES) {

    override fun size(cache: Cache): Int {
        return cache.lastArchiveId(index)
    }

    override fun create(size: Int) = Array(size) { TextureDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: TextureDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun TextureDefinition.read(opcode: Int, buffer: Reader) {
        val count = buffer.readUnsignedByte()
        val operations = ArrayList<TextureOp>(count)
        for (position in 0 until count) {
            val self = buffer.readUnsignedByte()
            check(self == position) { "Texture $id operation $position calls itself $self." }
            val type = buffer.readUnsignedByte()
            val capacity = buffer.readUnsignedByte()
            val opcodes = readOpcodes(id, type, buffer)
            val children = IntArray(TextureOpFormat.children(type)) { buffer.readUnsignedByte() }
            operations.add(TextureOp(type, capacity, opcodes, children))
        }
        this.operations = operations
        opaque = buffer.readUnsignedByte()
        opacity = buffer.readUnsignedByte()
        hdr = buffer.readUnsignedByte()
        for (output in intArrayOf(opaque, opacity, hdr)) {
            check(output < count) { "Texture $id names operation $output as an output but has only $count." }
        }
        val remaining = buffer.readableBytes()
        if (remaining > 0) {
            val bytes = ByteArray(remaining)
            buffer.readBytes(bytes)
            trailer = hex(bytes)
        }
    }

    private fun readOpcodes(id: Int, type: Int, buffer: Reader): List<TextureOpcode> {
        val count = buffer.readUnsignedByte()
        val opcodes = ArrayList<TextureOpcode>(count)
        // Perlin noise's step count is read by one opcode and used as a length by another, in
        // whichever order the file gives them, so it is state for the length of one operation.
        var steps = TextureOpFormat.PERLIN_STEPS
        for (position in 0 until count) {
            val opcode = buffer.readUnsignedByte()
            val record = when (val payload = TextureOpFormat.payload(type, opcode)) {
                TextureOpFormat.NONE -> TextureOpcode(opcode)
                TextureOpFormat.BYTE -> TextureOpcode(opcode, buffer.readUnsignedByte())
                TextureOpFormat.SHORT -> TextureOpcode(opcode, buffer.readUnsignedShort())
                TextureOpFormat.MEDIUM -> TextureOpcode(opcode, buffer.readUnsignedMedium())
                TextureOpFormat.CURVE -> curve(opcode, buffer)
                TextureOpFormat.GRADIENT -> gradient(opcode, buffer)
                TextureOpFormat.RASTER -> raster(id, opcode, buffer)
                TextureOpFormat.PERLIN -> perlin(opcode, steps, buffer)
                else -> error("Texture $id operation type $type opcode $opcode has payload $payload.")
            }
            if (type == PERLIN_NOISE && opcode == PERLIN_STEP_COUNT) {
                steps = record.value ?: steps
            }
            opcodes.add(record)
        }
        return opcodes
    }

    /** An interpolation mode and a point count, then that many `x, y` shorts. */
    private fun curve(opcode: Int, buffer: Reader): TextureOpcode {
        val interpolation = buffer.readUnsignedByte()
        val points = buffer.readUnsignedByte()
        return TextureOpcode(opcode, interpolation, IntArray(points * 2) { buffer.readUnsignedShort() })
    }

    /** A preset, or a stop count and that many `position` short plus `r`, `g`, `b` byte stops. */
    private fun gradient(opcode: Int, buffer: Reader): TextureOpcode {
        val preset = buffer.readUnsignedByte()
        if (preset != CUSTOM_GRADIENT) {
            return TextureOpcode(opcode, preset)
        }
        val stops = buffer.readUnsignedByte()
        val values = IntArray(stops * GRADIENT_STOP)
        for (stop in 0 until stops) {
            values[stop * GRADIENT_STOP] = buffer.readUnsignedShort()
            values[stop * GRADIENT_STOP + 1] = buffer.readUnsignedByte()
            values[stop * GRADIENT_STOP + 2] = buffer.readUnsignedByte()
            values[stop * GRADIENT_STOP + 3] = buffer.readUnsignedByte()
        }
        return TextureOpcode(opcode, preset, values)
    }

    /** A shape count, then that many shapes; [TextureShape] is the record. */
    private fun raster(id: Int, opcode: Int, buffer: Reader): TextureOpcode {
        val count = buffer.readUnsignedByte()
        val shapes = ArrayList<TextureShape>(count)
        for (position in 0 until count) {
            val shape = buffer.readUnsignedByte()
            val coordinates = IntArray(TextureOpFormat.coordinates(shape)) { buffer.readUnsignedShort() }
            val fill = if (TextureOpFormat.filled(shape)) buffer.readUnsignedMedium() else null
            shapes.add(TextureShape(shape, coordinates, fill, buffer.readUnsignedMedium(), buffer.readUnsignedByte()))
        }
        check(shapes.size == count) { "Texture $id rasterizer read ${shapes.size} of $count shapes." }
        return TextureOpcode(opcode, null, null, shapes)
    }

    /** A signed amplitude and, when it is negative, one multiplier per noise step. */
    private fun perlin(opcode: Int, steps: Int, buffer: Reader): TextureOpcode {
        val amplitude = buffer.readUnsignedShort()
        if (amplitude < NEGATIVE) {
            return TextureOpcode(opcode, amplitude)
        }
        return TextureOpcode(opcode, amplitude, IntArray(steps) { buffer.readUnsignedShort() })
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append(DIGITS[(byte.toInt() shr 4) and 0xf]).append(DIGITS[byte.toInt() and 0xf])
        }
        return out.toString()
    }

    private companion object {
        private const val PERLIN_NOISE = 34

        /** Perlin noise's opcode 1, which sets the step count opcode 2 reads that many shorts for. */
        private const val PERLIN_STEP_COUNT = 1

        /** A colour gradient preset of 0 means the stops are in the file. */
        private const val CUSTOM_GRADIENT = 0

        /** A gradient stop is a position and three colour channels. */
        private const val GRADIENT_STOP = 4

        /** The unsigned short at which a signed short turns negative. */
        private const val NEGATIVE = 0x8000

        private val DIGITS = "0123456789abcdef".toCharArray()
    }
}
