package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.VAR_CLAN
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.cs2.CS2Type
import world.gregs.voidps.cache.definition.data.ClanVarDefinition
import world.gregs.voidps.cache.definition.encoder.ClanVarEncoder

/**
 * Decodes clan variable definitions from the CONFIGS index (2), archive 47.
 *
 * Each clan var is a single file within the archive. Opcode format:
 * - 0: terminator
 * - 1: single cp1252 byte encoding the CS2Type descriptor (i/s/long/etc.)
 * - 3: basevar (ushort) + startBit (ubyte) + endBit (ubyte) for bitpacked vars
 */
class ClanVarDecoder : DefinitionDecoder<ClanVarDefinition>(Index.CONFIGS) {

    private val encoder = ClanVarEncoder()

    override fun create(size: Int) = Array(size) { ClanVarDefinition(it) }

    override fun canonicalOpcodes(definition: ClanVarDefinition): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = VAR_CLAN

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, VAR_CLAN)
    }

    override fun ClanVarDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                // forJagexDesc is many to one, so the descriptor byte is kept as well.
                typeChar = byteToChar(buffer.readByte().toByte())
                type = CS2Type.forJagexDesc(typeChar)
            }
            3 -> {
                baseVar = buffer.readUnsignedShort()
                startBit = buffer.readUnsignedByte()
                endBit = buffer.readUnsignedByte()
            }
            else -> error("Unhandled clan variable definition opcode: $opcode for def $id")
        }
    }

}
