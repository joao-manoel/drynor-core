package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.VARP
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.PlayerVariableParameterDefinition
import world.gregs.voidps.cache.config.encoder.PlayerVariableParameterEncoder

class PlayerVariableParameterDecoder : ConfigDecoder<PlayerVariableParameterDefinition>(VARP) {

    private val encoder = PlayerVariableParameterEncoder()

    override fun create(size: Int) = Array(size) { PlayerVariableParameterDefinition(it) }

    override fun canonicalOpcodes(definition: PlayerVariableParameterDefinition): IntArray = encoder.opcodes(definition)

    override fun PlayerVariableParameterDefinition.read(opcode: Int, buffer: Reader) {
        if (opcode == 1) {
            // Nothing in 727 carries it, but reading it keeps an unexpected file from desyncing.
            typeChar = byteToChar(buffer.readByte().toByte())
        } else if (opcode == 5) {
            type = buffer.readShort()
        } else {
            error("Unhandled player variable definition opcode: $opcode for def $id")
        }
    }

}