package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.IDENTITY_KIT
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.IdentityKitDefinition
import world.gregs.voidps.cache.config.encoder.IdentityKitEncoder

class IdentityKitDecoder : ConfigDecoder<IdentityKitDefinition>(IDENTITY_KIT) {

    private val encoder = IdentityKitEncoder()

    override fun create(size: Int) = Array(size) { IdentityKitDefinition(it) }

    override fun canonicalOpcodes(definition: IdentityKitDefinition): IntArray = encoder.opcodes(definition)

    override fun IdentityKitDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> bodyPartId = buffer.readUnsignedByte()
            2 -> {
                // Model ids are big smarts, not shorts (client IdkType). Read as shorts they
                // desync the rest of the file: 29 of the 652 shipped files ran off the end.
                val length = buffer.readUnsignedByte()
                modelIds = IntArray(length) { buffer.readBigSmart() }
            }
            // Not in the client's IdkType, but 13 of the shipped files carry it, always a 2 byte
            // payload immediately before the model list (0x0103, 0x0104, 0x0105).
            3 -> unknown3 = buffer.readUnsignedShort()
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            in 60..69 -> headModels[opcode - 60] = buffer.readBigSmart()
            else -> error("Unhandled identity kit definition opcode: $opcode for def $id")
        }
    }

}
