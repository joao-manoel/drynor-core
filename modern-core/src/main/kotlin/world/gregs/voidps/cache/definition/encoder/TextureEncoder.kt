package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.TextureOpFormat
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.data.TextureOp
import world.gregs.voidps.cache.definition.data.TextureOpcode
import world.gregs.voidps.cache.definition.data.TextureShape

/**
 * The exact inverse of [world.gregs.voidps.cache.definition.decoder.TextureDecoder].
 *
 * Nothing here decides anything: an operation writes the opcodes it carries in the order it
 * carries them, and each payload's shape comes from [TextureOpFormat] exactly as the decoder read
 * it. What it does do is refuse to write a graph that would not read back - a child count that
 * disagrees with the type, an output index past the end of the list, a perlin step list that no
 * longer matches the step count - because a texture that desyncs is a texture the client draws as
 * noise.
 */
class TextureEncoder {

    fun Writer.encode(definition: TextureDefinition) {
        val operations = definition.operations
        require(operations.size <= LIMIT) {
            "Texture ${definition.id} has ${operations.size} operations; the count is one byte."
        }
        writeByte(operations.size)
        for (position in operations.indices) {
            val operation = operations[position]
            writeByte(position)
            writeByte(operation.type)
            writeByte(operation.imageCacheCapacity)
            writeOpcodes(definition, operation)
            val children = TextureOpFormat.children(operation.type)
            require(operation.children.size == children) {
                "Texture ${definition.id} operation $position is type ${operation.type}, which takes " +
                    "$children inputs, but names ${operation.children.size}."
            }
            for (child in operation.children) {
                require(child < operations.size) {
                    "Texture ${definition.id} operation $position feeds on operation $child, which does not exist."
                }
                writeByte(child)
            }
        }
        for (output in intArrayOf(definition.opaque, definition.opacity, definition.hdr)) {
            require(output < operations.size) {
                "Texture ${definition.id} names operation $output as an output but has ${operations.size}."
            }
            writeByte(output)
        }
        definition.trailer?.let { writeBytes(bytes(definition, it)) }
    }

    private fun Writer.writeOpcodes(definition: TextureDefinition, operation: TextureOp) {
        require(operation.opcodes.size <= LIMIT) {
            "Texture ${definition.id} operation has ${operation.opcodes.size} opcodes; the count is one byte."
        }
        writeByte(operation.opcodes.size)
        var steps = TextureOpFormat.PERLIN_STEPS
        for (record in operation.opcodes) {
            writeByte(record.opcode)
            when (val payload = TextureOpFormat.payload(operation.type, record.opcode)) {
                TextureOpFormat.NONE -> Unit
                TextureOpFormat.BYTE -> writeByte(value(definition, record))
                TextureOpFormat.SHORT -> writeShort(value(definition, record))
                TextureOpFormat.MEDIUM -> writeMedium(value(definition, record))
                TextureOpFormat.CURVE -> writeCurve(definition, record)
                TextureOpFormat.GRADIENT -> writeGradient(definition, record)
                TextureOpFormat.RASTER -> writeRaster(definition, record)
                TextureOpFormat.PERLIN -> writePerlin(definition, record, steps)
                else -> error("Texture ${definition.id} opcode ${record.opcode} has payload $payload.")
            }
            if (operation.type == PERLIN_NOISE && record.opcode == PERLIN_STEP_COUNT) {
                steps = record.value ?: steps
            }
        }
    }

    private fun Writer.writeCurve(definition: TextureDefinition, record: TextureOpcode) {
        val values = values(definition, record)
        require(values.size % 2 == 0) {
            "Texture ${definition.id} curve has ${values.size} values, which is not whole x, y pairs."
        }
        writeByte(value(definition, record))
        writeByte(values.size / 2)
        for (value in values) {
            writeShort(value)
        }
    }

    private fun Writer.writeGradient(definition: TextureDefinition, record: TextureOpcode) {
        val preset = value(definition, record)
        writeByte(preset)
        if (preset != CUSTOM_GRADIENT) {
            require(record.values == null) {
                "Texture ${definition.id} gradient uses preset $preset, which carries no stops."
            }
            return
        }
        val values = values(definition, record)
        require(values.size % GRADIENT_STOP == 0) {
            "Texture ${definition.id} gradient has ${values.size} values, which is not whole stops."
        }
        writeByte(values.size / GRADIENT_STOP)
        for (stop in 0 until values.size / GRADIENT_STOP) {
            writeShort(values[stop * GRADIENT_STOP])
            writeByte(values[stop * GRADIENT_STOP + 1])
            writeByte(values[stop * GRADIENT_STOP + 2])
            writeByte(values[stop * GRADIENT_STOP + 3])
        }
    }

    private fun Writer.writeRaster(definition: TextureDefinition, record: TextureOpcode) {
        val shapes = record.shapes
            ?: error("Texture ${definition.id} has a rasterizer opcode with no shapes.")
        require(shapes.size <= LIMIT) {
            "Texture ${definition.id} rasterizer has ${shapes.size} shapes; the count is one byte."
        }
        writeByte(shapes.size)
        for (shape in shapes) {
            writeByte(shape.shape)
            val coordinates = TextureOpFormat.coordinates(shape.shape)
            require(shape.coordinates.size == coordinates) {
                "Texture ${definition.id} shape ${shape.shape} takes $coordinates coordinates, " +
                    "not ${shape.coordinates.size}."
            }
            for (coordinate in shape.coordinates) {
                writeShort(coordinate)
            }
            writeFill(definition, shape)
            writeMedium(shape.stroke)
            writeByte(shape.strokeWidth)
        }
    }

    private fun Writer.writeFill(definition: TextureDefinition, shape: TextureShape) {
        if (!TextureOpFormat.filled(shape.shape)) {
            require(shape.fill == null) {
                "Texture ${definition.id} shape ${shape.shape} carries no fill colour."
            }
            return
        }
        writeMedium(
            shape.fill ?: error("Texture ${definition.id} shape ${shape.shape} needs a fill colour.")
        )
    }

    private fun Writer.writePerlin(definition: TextureDefinition, record: TextureOpcode, steps: Int) {
        val amplitude = value(definition, record)
        writeShort(amplitude)
        if (amplitude < NEGATIVE) {
            require(record.values == null) {
                "Texture ${definition.id} perlin amplitude $amplitude is positive, so it carries no multipliers."
            }
            return
        }
        val values = values(definition, record)
        require(values.size == steps) {
            "Texture ${definition.id} perlin has ${values.size} multipliers for $steps noise steps."
        }
        for (value in values) {
            writeShort(value)
        }
    }

    private fun value(definition: TextureDefinition, record: TextureOpcode): Int =
        record.value ?: error("Texture ${definition.id} opcode ${record.opcode} needs a value.")

    private fun values(definition: TextureDefinition, record: TextureOpcode): IntArray =
        record.values ?: error("Texture ${definition.id} opcode ${record.opcode} needs a value list.")

    private fun bytes(definition: TextureDefinition, hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Texture ${definition.id}'s trailer '$hex' is not whole bytes." }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        private const val PERLIN_NOISE = 34

        private const val PERLIN_STEP_COUNT = 1

        private const val CUSTOM_GRADIENT = 0

        private const val GRADIENT_STOP = 4

        private const val NEGATIVE = 0x8000

        /** Every count in the format is a single byte. */
        private const val LIMIT = 255
    }
}
