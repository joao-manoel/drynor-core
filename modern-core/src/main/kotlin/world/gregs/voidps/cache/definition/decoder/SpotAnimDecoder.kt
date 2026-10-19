package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.SPOTANIMS
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder

class SpotAnimDecoder : DefinitionDecoder<SpotAnimDefinition>(SPOTANIMS) {

    private val encoder = SpotAnimEncoder()

    override fun create(size: Int) = Array(size) { SpotAnimDefinition(it) }

    override fun canonicalOpcodes(definition: SpotAnimDefinition): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun size(cache: Cache): Int {
        val archive = cache.lastArchiveId(index)
        return if (archive < 0) -1 else archive * 256 + cache.lastFileId(index, archive)
    }

    override fun SpotAnimDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // Opcode 1: model ID (bigSmart for rev 727)
            1 -> modelId = buffer.readBigSmart()
            // Opcode 2: animation/sequence ID (bigSmart for rev 727)
            2 -> animationId = buffer.readBigSmart()
            4 -> sizeXY = buffer.readUnsignedShort()
            5 -> sizeZ = buffer.readUnsignedShort()
            6 -> rotation = buffer.readUnsignedShort()
            7 -> ambience = buffer.readUnsignedByte()
            8 -> contrast = buffer.readUnsignedByte()
            9 -> {
                aByte2381 = 3
                anInt2385 = 8224
            }
            10 -> aBoolean2402 = true
            11 -> aByte2381 = 1.toByte()
            12 -> aByte2381 = 4.toByte()
            13 -> aByte2381 = 5.toByte()
            14 -> {
                aByte2381 = 2
                anInt2385 = 256 * buffer.readUnsignedByte()
            }
            15 -> {
                aByte2381 = 3
                anInt2385 = buffer.readUnsignedShort()
            }
            16 -> {
                aByte2381 = 3
                anInt2385 = buffer.readInt()
            }
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            else -> error("Unhandled spotanim definition opcode: $opcode for def $id")
        }
    }
}
