package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.data.SpotAnimDefinition

/** Gielenor GraphicsConfig.decodeOSRS, with physical model/sequence references. */
object OsrsSpotConverter {
    fun parse(id: Int, data: ByteArray): SpotAnimDefinition {
        val reader = OsrsByteReader(data)
        val result = SpotAnimDefinition(id)
        while (true) when (val opcode = reader.u8()) {
            0 -> { reader.requireEnd(); require(result.modelId >= 200000); return result }
            1 -> result.modelId = reader.u16() + 200000
            2 -> result.animationId = reader.u16() + 20000
            4 -> result.sizeXY = reader.u16()
            5 -> result.sizeZ = reader.u16()
            6 -> result.rotation = reader.u16()
            7 -> result.ambience = reader.u8()
            8 -> result.contrast = reader.u8()
            40, 41 -> {
                val count = reader.u8()
                val from = ShortArray(count)
                val to = ShortArray(count)
                for (index in 0 until count) {
                    val rawFrom = reader.u16(); val rawTo = reader.u16()
                    if (opcode == 41) {
                        require(rawFrom != 59 && rawTo != 59) { "Composed material requires separate import" }
                        from[index] = GielenorVisualProfile.runtimeMaterial(rawFrom).toShort()
                        to[index] = GielenorVisualProfile.runtimeMaterial(rawTo).toShort()
                    } else { from[index] = rawFrom.toShort(); to[index] = rawTo.toShort() }
                }
                if (opcode == 40) { result.originalColours = from; result.modifiedColours = to }
                else { result.originalTextureColours = from; result.modifiedTextureColours = to }
            }
            else -> error("Unsupported OSRS spot opcode $opcode")
        }
    }

    fun convert(id: Int, source: SpotAnimDefinition, models: Map<Int, Int>, animations: Map<Int, Int>) = source.copy(
        id = id, modelId = models.getValue(source.modelId),
        animationId = if (source.animationId == -1) -1 else animations.getValue(source.animationId),
    )
}
