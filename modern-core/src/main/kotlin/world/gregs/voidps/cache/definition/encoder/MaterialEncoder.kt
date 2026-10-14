package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.MaterialDefinition

/**
 * The exact inverse of [world.gregs.voidps.cache.definition.decoder.MaterialDecoder]: the whole of
 * index 26 as one blob.
 *
 * A single material cannot be encoded on its own - the format is columns, so every field of every
 * material is interleaved with the same field of all the others - which is why this takes the list
 * and not a definition. The count is the list's size and the presence column is
 * [MaterialDefinition.defined]; only defined materials contribute to the columns after it.
 *
 * The booleans are written as 0 and 1, which is what every one of the 727 cache's 2,591 materials
 * holds - the client reads them as `byte == 1`, so any other value would be a lossy read.
 */
class MaterialEncoder {

    fun Writer.encode(materials: List<MaterialDefinition>) {
        require(materials.size <= LIMIT) { "${materials.size} materials do not fit in the blob's two byte count." }
        writeShort(materials.size)
        for (material in materials) {
            writeByte(material.defined)
        }
        val defined = materials.filter { it.defined }
        for (material in defined) {
            // `detailsOnly` is the presence byte being 0, so it is the one inverted column.
            writeByte(if (material.detailsOnly) 0 else 1)
        }
        for (material in defined) {
            writeByte(material.isSmall)
        }
        for (material in defined) {
            writeByte(material.skipTriangles)
        }
        for (material in defined) {
            writeByte(material.brightness.toInt())
        }
        for (material in defined) {
            writeByte(material.alpha.toInt())
        }
        for (material in defined) {
            writeByte(material.effectId.toInt())
        }
        for (material in defined) {
            writeByte(material.effectParam1.toInt())
        }
        for (material in defined) {
            writeShort(material.colourHsl)
        }
        for (material in defined) {
            writeByte(material.textureSpeedU.toInt())
        }
        for (material in defined) {
            writeByte(material.textureSpeedV.toInt())
        }
        for (material in defined) {
            writeByte(material.unusedBoolean)
        }
        for (material in defined) {
            writeByte(material.isBrickTile)
        }
        for (material in defined) {
            writeByte(material.mipmapping.toInt())
        }
        for (material in defined) {
            writeByte(material.repeatS)
        }
        for (material in defined) {
            writeByte(material.repeatT)
        }
        for (material in defined) {
            writeByte(material.hdr)
        }
        for (material in defined) {
            writeByte(material.combineMode)
        }
        for (material in defined) {
            writeInt(material.effectParam2)
        }
        for (material in defined) {
            writeByte(material.effectCombiner)
        }
    }

    private companion object {
        /** The blob's count is a short. */
        private const val LIMIT = 0xffff
    }
}
