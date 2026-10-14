package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.INVENTORIES
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.InventoryDefinition
import world.gregs.voidps.cache.config.encoder.InventoryEncoder

class InventoryDecoder : ConfigDecoder<InventoryDefinition>(INVENTORIES) {

    private val encoder = InventoryEncoder()

    override fun create(size: Int) = Array(size) { InventoryDefinition(it) }

    override fun canonicalOpcodes(definition: InventoryDefinition): IntArray = encoder.opcodes(definition)

    override fun InventoryDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            2 -> length = buffer.readUnsignedShort()
            4 -> {
                val size = buffer.readUnsignedByte()
                ids = IntArray(size)
                amounts = IntArray(size)
                for (i in 0 until size) {
                    ids!![i] = buffer.readUnsignedShort()
                    amounts!![i] = buffer.readUnsignedShort()
                }
            }
            else -> error("Unhandled inventory definition opcode: $opcode for def $id")
        }
    }

}