package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.data.ShaderProgramDefine
import world.gregs.voidps.cache.definition.data.ShaderProgramDefinition
import world.gregs.voidps.cache.definition.data.ShaderProgramMapping
import world.gregs.voidps.cache.definition.data.ShaderProgramVariable

/**
 * Index 31's program definitions, read exactly as the client's `ShaderLoader` reads them.
 *
 * Two things about the format are worth stating because nothing else in the cache does them:
 * every scalar is **little endian** and **signed** (the client accumulates two bytes into a
 * `Short` and widens it), and a string is one of those as a length - so `-1` is null and `0` is
 * the empty string, and both occur.
 *
 * The three counts are read one after the other before any of the three lists, because the client
 * builds all three arrays first and only then decodes into them.
 */
class ShaderProgramDecoder {

    /** Whether [data] is a program definition rather than a shader source. */
    fun isProgram(data: ByteArray): Boolean =
        data.size >= 2 && short(data, 0) == ShaderProgramDefinition.HEADER

    fun read(definition: ShaderProgramDefinition, data: ByteArray) {
        val buffer = BufferReader(data)
        read(definition, buffer)
        check(buffer.remaining == 0) {
            "A shader program definition has ${buffer.remaining} bytes left over after its mappings."
        }
    }

    fun read(definition: ShaderProgramDefinition, buffer: Reader) {
        definition.header = buffer.readShortLittle()
        check(definition.header == ShaderProgramDefinition.HEADER) {
            "A shader program definition's header is ${definition.header}, not ${ShaderProgramDefinition.HEADER}."
        }
        definition.name = string(buffer)
        val variables = buffer.readShortLittle()
        val optional = buffer.readShortLittle()
        val mappings = buffer.readShortLittle()
        definition.variables = List(variables) { variable(buffer) }
        definition.optionalVariables = List(optional) { variable(buffer) }
        definition.mappings = List(mappings) { mapping(buffer) }
    }

    private fun variable(buffer: Reader): ShaderProgramVariable {
        val type = buffer.readShortLittle()
        val array = type == ShaderProgramVariable.ARRAY
        val elementType = if (array) buffer.readShortLittle() else null
        val arraySize = if (array) buffer.readShortLittle() else null
        val name = string(buffer)
        val alias = string(buffer)
        val bindings = buffer.readShortLittle()
        val program = IntArray(bindings)
        val slot = IntArray(bindings)
        for (binding in 0 until bindings) {
            program[binding] = buffer.readShortLittle()
            slot[binding] = buffer.readShortLittle()
        }
        return ShaderProgramVariable(type, elementType, arraySize, name, alias, program, slot)
    }

    private fun mapping(buffer: Reader): ShaderProgramMapping {
        val name = string(buffer)
        val vertex = string(buffer)
        val fragment = string(buffer)
        // Both counts come before either list, as the client reads them.
        val vertexDefines = buffer.readShortLittle()
        val fragmentDefines = buffer.readShortLittle()
        return ShaderProgramMapping(
            name = name,
            vertexSource = vertex,
            fragmentSource = fragment,
            vertexDefines = List(vertexDefines) { define(buffer) },
            fragmentDefines = List(fragmentDefines) { define(buffer) }
        )
    }

    private fun define(buffer: Reader) = ShaderProgramDefine(string(buffer), string(buffer))

    /**
     * A length prefixed string, or null for a length of -1.
     *
     * ISO-8859-1 rather than the cache's usual cp1252 because the bytes are a shader identifier and
     * a round trip that cannot change a byte matters more than a spelling for the 0x80..0x9f range
     * that no file uses.
     */
    private fun string(buffer: Reader): String? {
        val length = buffer.readShortLittle()
        if (length == NULL) {
            return null
        }
        check(length in 0..LIMIT) { "A shader program definition string is $length bytes long." }
        val bytes = ByteArray(length)
        buffer.readBytes(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun short(data: ByteArray, position: Int): Int =
        (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)

    private companion object {
        private const val NULL = -1

        /** The client throws above 256, so a longer string is not a program definition. */
        private const val LIMIT = 256
    }
}
