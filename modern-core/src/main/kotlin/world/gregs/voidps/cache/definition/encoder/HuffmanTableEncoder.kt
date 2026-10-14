package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.HuffmanTableDefinition

/** Index 10's huffman table: the code lengths, one byte each, and nothing else. */
class HuffmanTableEncoder : DefinitionEncoder<HuffmanTableDefinition> {

    override fun Writer.encode(definition: HuffmanTableDefinition) {
        for (length in definition.lengths) {
            writeByte(length)
        }
    }
}
