package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.MapEffect
import world.gregs.voidps.cache.definition.data.MapEnvironmentEffect
import world.gregs.voidps.cache.definition.data.MapHdrEffect
import world.gregs.voidps.cache.definition.data.MapLight
import world.gregs.voidps.cache.definition.data.MapLightGridEffect
import world.gregs.voidps.cache.definition.data.MapLightsEffect
import world.gregs.voidps.cache.definition.data.MapSkyboxEffect

/**
 * Writes the environment block of a `m<x>_<y>` map archive, the exact inverse of
 * [world.gregs.voidps.cache.definition.decoder.MapEffectDecoder].
 *
 * Records are written in the order the list holds them, which is the order the file had them: the
 * client applies them in order and two records of the same opcode are both legal, so nothing here
 * sorts, merges or de-duplicates.
 *
 * The one derived value is opcode 0's flag byte, and it is derived from which fields are present
 * rather than from which fields differ from the client's defaults - see [MapEnvironmentEffect.flags].
 */
object MapEffectEncoder {

    /** Write [effects] at [Writer]'s current position. */
    fun Writer.writeEffects(effects: List<MapEffect>) {
        for (effect in effects) {
            writeByte(effect.opcode)
            when (effect) {
                is MapEnvironmentEffect -> environment(effect)
                is MapLightsEffect -> lights(effect)
                is MapHdrEffect -> {
                    writeByte(effect.bloom)
                    writeByte(effect.brightpass)
                    writeByte(effect.whitePoint)
                }
                is MapSkyboxEffect -> {
                    writeShort(effect.id)
                    writeShort(effect.x)
                    writeShort(effect.y)
                    writeShort(effect.z)
                    writeShort(effect.rotation)
                }
                is MapLightGridEffect -> lightGrid(effect)
            }
        }
    }

    private fun Writer.environment(effect: MapEnvironmentEffect) {
        writeByte(effect.flags())
        effect.sunColour?.let { writeInt(it) }
        effect.sunAmbient?.let { writeShort(it) }
        effect.sunLight?.let { writeShort(it) }
        effect.sunBacklight?.let { writeShort(it) }
        effect.sunPosition?.let { position ->
            require(position.size == MapEnvironmentEffect.SUN_POSITION_SIZE) {
                "A sun position is ${MapEnvironmentEffect.SUN_POSITION_SIZE} shorts, not ${position.size}."
            }
            for (value in position) {
                writeShort(value)
            }
        }
        effect.fogColour?.let { writeInt(it) }
        effect.fogDepth?.let { writeShort(it) }
        effect.cubeTexture?.let { cube ->
            require(cube.size == MapEnvironmentEffect.CUBE_TEXTURE_SIZE) {
                "A cube texture is ${MapEnvironmentEffect.CUBE_TEXTURE_SIZE} materials, not ${cube.size}."
            }
            for (material in cube) {
                writeShort(material)
            }
        }
    }

    private fun Writer.lights(effect: MapLightsEffect) {
        require(effect.lights.size <= MapLightsEffect.MAX_LIGHTS) {
            "A point light record holds at most ${MapLightsEffect.MAX_LIGHTS} lights, not ${effect.lights.size}."
        }
        writeByte(effect.lights.size)
        for (light in effect.lights) {
            require(light.ranges.size == light.packedRadius * 2 + 1) {
                "A light of radius ${light.packedRadius} has ${light.packedRadius * 2 + 1} range slices, " +
                    "not ${light.ranges.size}."
            }
            writeByte(light.packedLevel)
            writeShort(light.x)
            writeShort(light.z)
            writeShort(light.heightOffset)
            writeByte(light.packedRadius)
            for (range in light.ranges) {
                writeShort(range)
            }
            writeShort(light.colour)
            writeByte(light.packedType)
            // The config id is on the wire for exactly one type, so a light that carries one
            // without being that type - or is that type without one - cannot be written at all
            // rather than written into a file that reads back as something else.
            if (light.packedType and 0x1f == MapLight.CONFIG_TYPE) {
                require(light.lightType >= 0) {
                    "A light of type ${MapLight.CONFIG_TYPE} needs a light config id."
                }
                writeShort(light.lightType)
            } else {
                require(light.lightType < 0) {
                    "A light of type ${light.packedType and 0x1f} cannot carry the light config id " +
                        "${light.lightType}; only type ${MapLight.CONFIG_TYPE} does."
                }
            }
        }
    }

    private fun Writer.lightGrid(effect: MapLightGridEffect) {
        require(effect.levels.size == MapLightGridEffect.LEVELS) {
            "A light grid describes ${MapLightGridEffect.LEVELS} levels, not ${effect.levels.size}."
        }
        for (level in effect.levels) {
            writeByte(level.mode)
            level.samples?.let { samples ->
                for (sample in samples) {
                    writeByte(sample.toInt())
                }
            }
        }
    }
}
