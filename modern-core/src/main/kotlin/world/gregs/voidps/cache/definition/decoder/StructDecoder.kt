package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.STRUCTS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.encoder.StructEncoder

/**
 * Decodes struct definitions from the CONFIGS index (2), archive 26.
 *
 * Structs are simple key-value containers (opcode 249 params only).
 * Each struct is a single file within archive 26.
 */
class StructDecoder : DefinitionDecoder<StructDefinition>(Index.CONFIGS) {

    private val encoder = StructEncoder()

    override fun create(size: Int) = Array(size) { StructDefinition(it) }

    override fun canonicalOpcodes(definition: StructDefinition): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = STRUCTS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, STRUCTS)
    }

    override fun StructDefinition.read(opcode: Int, buffer: Reader) {
        if (opcode != 249) {
            error("Unhandled struct definition opcode: $opcode for def $id")
        }
        val records = readParameterRecords(buffer)
        // 37 shipped structs carry the same key twice - the client's map drops one too - so the
        // records are kept whole whenever the map is not enough to write the file back.
        if (records.size != (params?.size ?: 0)) {
            paramRecords = records
        }
    }
}
