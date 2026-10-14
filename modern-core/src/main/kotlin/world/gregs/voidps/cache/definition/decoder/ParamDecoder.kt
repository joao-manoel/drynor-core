package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.PARAMS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.encoder.ParamEncoder

/**
 * Decodes param (attribute) definitions from the CONFIGS index (2), archive 11.
 *
 * Params are flat, unbucketed like structs - each param is a single file within archive 11.
 */
class ParamDecoder : DefinitionDecoder<ParamDefinition>(Index.CONFIGS) {

    private val encoder = ParamEncoder()

    override fun create(size: Int) = Array(size) { ParamDefinition(it) }

    override fun canonicalOpcodes(definition: ParamDefinition): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = PARAMS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, PARAMS)
    }

    override fun ParamDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // cp1252 stack type char; 's' means the param lives on the string stack
            1 -> type = byteToChar(buffer.readByte().toByte())
            2 -> defaultInt = buffer.readInt()
            // Present when the param is not auto-disabled on members-only content
            4 -> autoDisable = false
            5 -> defaultString = buffer.readString()
            else -> error("Unhandled param definition opcode: $opcode for def $id")
        }
    }

}
