package world.gregs.voidps.cache.definition.decoder

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ENUMS
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.encoder.EnumEncoder

class EnumDecoder : DefinitionDecoder<EnumDefinition>(ENUMS) {

    private val encoder = EnumEncoder()

    override fun create(size: Int) = Array(size) { EnumDefinition(it) }

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun canonicalOpcodes(definition: EnumDefinition): IntArray = encoder.opcodes(definition)

    override fun EnumDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> keyType = byteToChar(buffer.readByte().toByte())
            2 -> valueType = byteToChar(buffer.readByte().toByte())
            3 -> defaultString = buffer.readString()
            4 -> defaultInt = buffer.readInt()
            5, 6 -> {
                length = buffer.readUnsignedShort()
                // Linked so the keys come back out in the order the file listed them, which is what the
                // encoder writes back.
                val hashtable = Int2ObjectLinkedOpenHashMap<Any>(length)
                val records = ArrayList<KeyedValue>(length)
                for (count in 0 until length) {
                    val id = buffer.readInt()
                    val value: Any = if (opcode == 5) buffer.readString() else buffer.readInt()
                    hashtable[id] = value
                    records.add(KeyedValue(id, value))
                }
                map = hashtable
                if (records.size != hashtable.size) {
                    entries = records
                }
            }
            7, 8 -> {
                // Indexed array variant: arraySize is the array capacity, length is the entry count
                arraySize = buffer.readUnsignedShort()
                length = buffer.readUnsignedShort()
                val hashtable = Int2ObjectLinkedOpenHashMap<Any>(length)
                val records = ArrayList<KeyedValue>(length)
                for (count in 0 until length) {
                    val index = buffer.readUnsignedShort()
                    val value: Any = if (opcode == 7) buffer.readString() else buffer.readInt()
                    hashtable[index] = value
                    records.add(KeyedValue(index, value))
                }
                map = hashtable
                if (records.size != hashtable.size) {
                    entries = records
                }
            }
            else -> error("Unhandled enum definition opcode: $opcode for def $id")
        }
    }
}
