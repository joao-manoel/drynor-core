package org.drynor.tools.osrs

import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder

/** A stored explicit height of zero is flat, not the procedural-height terminator. */
internal object LegacyTerrainConversion {
    fun decode(data: ByteArray, regionId: Int): MapDefinition {
        val end = LegacyTerrainScan.validate(data)
        require(end == data.size) { "Environment conversion is not implemented for this preview." }
        val result = MapDefinition(regionId)
        MapTileDecoder.decode(data, result)
        val reader = OsrsByteReader(data)
        for (index in result.tiles.indices) {
            while (true) {
                val opcode = reader.u8()
                if (opcode == 0) break
                if (opcode <= 49) {
                    val value = reader.u8()
                    if (opcode == 1) {
                        if (value == 0) {
                            val tile = MapTile(result.tiles[index])
                            result.tiles[index] = MapTile(1, tile.attrOpcode, tile.overlayId, tile.overlayPath, tile.overlayRotation, tile.settings, tile.underlayId).packed
                        }
                        break
                    }
                }
            }
        }
        return result
    }
}
