package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder

/**
 * Index 27 archive 1: particles, the client's `ParticleType`.
 *
 * Eighteen files in the 727 cache. As with the producers, the client's `setDefaults` - a cosine, a
 * 3D magnitude, a squared reach - is derivation and stays out of the read path.
 *
 * **Opcode 4 is a byte and an int, not a bare int.** The reference client reads only the int, which
 * leaves its `type` field - the one `setDefaults` switches on - permanently 0 and desynchronises
 * the rest of the record list; ten of the eighteen 727 files carry the record, and every one of
 * them parses to its last byte with the leading byte read and to nothing sensible without it. That
 * byte is the type: 1 and 2 are the values the cache uses.
 */
class ParticleDecoder : DefinitionDecoder<ParticleDefinition>(Index.PARTICLES) {

    private val encoder = ParticleEncoder()

    override fun create(size: Int) = Array(size) { ParticleDefinition(id = it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(Index.PARTICLES, ARCHIVE)

    override fun canonicalOpcodes(definition: ParticleDefinition): IntArray = encoder.opcodes(definition)

    override fun ParticleDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> rotation = buffer.readUnsignedShort()
            2 -> unused2 = buffer.readUnsignedByte()
            3 -> {
                offsetX = buffer.readInt()
                offsetY = buffer.readInt()
                offsetZ = buffer.readInt()
            }
            4 -> {
                type = buffer.readUnsignedByte()
                sizeMultiplier = buffer.readInt()
            }
            6 -> particleHandlingType = buffer.readUnsignedByte()
            8 -> currentOffset = true
            9 -> verticeCalculationType = true
            10 -> unusedOpcode10 = true
            else -> error("Unhandled particle definition opcode: $opcode for def $id")
        }
    }

    companion object {
        /** Index 27's particle archive. */
        const val ARCHIVE = 1
    }
}
