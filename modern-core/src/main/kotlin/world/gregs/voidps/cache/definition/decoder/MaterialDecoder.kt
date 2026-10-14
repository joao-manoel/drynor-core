package world.gregs.voidps.cache.definition.decoder

import org.darkan.core.Logger.logInfo
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.TEXTURE_DEFINITIONS
import world.gregs.voidps.cache.definition.data.MaterialDefinition

/**
 * Index 26, which is one archive holding every material at once.
 *
 * The blob is a count, then a presence byte per material, then one column per field holding that
 * field for every *defined* material in id order - so a field cannot be read without having read
 * every field before it. [world.gregs.voidps.cache.definition.encoder.MaterialEncoder] writes the
 * same columns back; the field names are the client's `MaterialTypeList`.
 */
class MaterialDecoder : DefinitionDecoder<MaterialDefinition>(TEXTURE_DEFINITIONS) {

    override fun create(size: Int) = Array(size) { MaterialDefinition(it) }

    override fun getArchive(id: Int) = 0

    override fun getFile(id: Int) = 0

    override fun load(cache: Cache): Array<MaterialDefinition> {
        val start = System.currentTimeMillis()
        val data = cache.data(index, 0, 0)!!
        val reader = BufferReader(data)
        val size = reader.readUnsignedShort()
        val definitions = create(size)
        load(definitions, reader)
        logInfo("$size ${this::class.simpleName} definitions loaded in ${System.currentTimeMillis() - start}ms")
        return definitions
    }

    /** [definitions] is the whole blob's worth; [reader] is positioned just past the count. */
    override fun load(definitions: Array<MaterialDefinition>, reader: Reader) {
        val data = mutableListOf<MaterialDefinition>()
        for (index in definitions.indices) {
            val defined = reader.readUnsignedBoolean()
            definitions[index].defined = defined
            if (defined) {
                data.add(definitions[index])
            }
        }

        for (material in data) {
            material.detailsOnly = !reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.isSmall = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.skipTriangles = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.brightness = reader.readByte().toByte()
        }
        for (material in data) {
            material.alpha = reader.readByte().toByte()
        }
        for (material in data) {
            material.effectId = reader.readByte().toByte()
        }
        for (material in data) {
            material.effectParam1 = reader.readByte().toByte()
        }
        for (material in data) {
            material.colourHsl = reader.readUnsignedShort()
        }
        for (material in data) {
            material.textureSpeedU = reader.readByte().toByte()
        }
        for (material in data) {
            material.textureSpeedV = reader.readByte().toByte()
        }
        for (material in data) {
            material.unusedBoolean = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.isBrickTile = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.mipmapping = reader.readByte().toByte()
        }
        for (material in data) {
            material.repeatS = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.repeatT = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.hdr = reader.readUnsignedBoolean()
        }
        for (material in data) {
            material.combineMode = reader.readUnsignedByte()
        }
        for (material in data) {
            material.effectParam2 = reader.readInt()
        }
        for (material in data) {
            material.effectCombiner = reader.readUnsignedByte()
        }
    }

    override fun MaterialDefinition.read(opcode: Int, buffer: Reader) {
        throw IllegalStateException("Index 26 is one blob of every material, not one file per material.")
    }
}
