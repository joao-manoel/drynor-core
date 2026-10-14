package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.VARC
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.ClientVariableParameterDefinition
import world.gregs.voidps.cache.config.encoder.ClientVariableParameterEncoder

class ClientVariableParameterDecoder : ConfigDecoder<ClientVariableParameterDefinition>(VARC) {

    private val encoder = ClientVariableParameterEncoder()

    override fun create(size: Int)= Array(size) { ClientVariableParameterDefinition(it) }

    override fun canonicalOpcodes(definition: ClientVariableParameterDefinition): IntArray = encoder.opcodes(definition)

    override fun ClientVariableParameterDefinition.read(opcode: Int, buffer: Reader) {
        if (opcode == 1) {
            aChar3210 = byteToChar(buffer.readByte().toByte())
        } else if (opcode == 2) {
            anInt3208 = 0
        } else {
            error("Unhandled client variable definition opcode: $opcode for def $id")
        }
    }

}