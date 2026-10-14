package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.encoder.BillboardEncoder

/**
 * Index 29 archive 0: the billboard configs a model footer's attachments name.
 *
 * One archive, 360 files in the 727 cache, one opcode keyed record list each. Two of the records
 * are read as something other than what the file holds and the encoder inverts both: opcode 1's
 * `0xffff` is the "no material" sentinel and reaches [BillboardType.materialId] as -1, and opcode
 * 2's two shorts are one less than the sizes they set.
 */
class BillboardDecoder : DefinitionDecoder<BillboardType>(Index.BILLBOARDS) {

    private val encoder = BillboardEncoder()

    override fun create(size: Int) = Array(size) { BillboardType(id = it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(Index.BILLBOARDS, ARCHIVE)

    override fun canonicalOpcodes(definition: BillboardType): IntArray = encoder.opcodes(definition)

    override fun BillboardType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                materialId = buffer.readUnsignedShort()
                if (materialId == NO_MATERIAL) {
                    materialId = -1
                }
            }
            2 -> {
                size2d = buffer.readUnsignedShort() + 1
                size3d = buffer.readUnsignedShort() + 1
            }
            3 -> unknown3 = buffer.readUnsignedByte()
            4 -> shape = buffer.readUnsignedByte()
            5 -> blendType = buffer.readUnsignedByte()
            6 -> stationary = true
            7 -> hasUid = true
            else -> error("Unhandled billboard definition opcode: $opcode for def $id")
        }
    }

    companion object {
        /** Index 29's one archive. */
        const val ARCHIVE = 0

        /** Opcode 1's "this billboard has no material" sentinel. */
        const val NO_MATERIAL = 65535
    }
}
