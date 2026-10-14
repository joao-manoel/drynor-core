package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.data.MapEffect
import world.gregs.voidps.cache.definition.data.MapEnvironmentEffect
import world.gregs.voidps.cache.definition.data.MapHdrEffect
import world.gregs.voidps.cache.definition.data.MapLight
import world.gregs.voidps.cache.definition.data.MapLightGridEffect
import world.gregs.voidps.cache.definition.data.MapLightGridLevel
import world.gregs.voidps.cache.definition.data.MapLightsEffect
import world.gregs.voidps.cache.definition.data.MapSkyboxEffect

/**
 * Decodes the environment block that follows the tiles of a `m<x>_<y>` map archive.
 *
 * The block is a list of records with no count and no terminator: it runs to the end of the file,
 * so decoding stops when the buffer does. Every field is kept as the file holds it - see
 * [MapEffect] - which is what makes [world.gregs.voidps.cache.definition.encoder.MapEffectEncoder]
 * the exact inverse.
 *
 * An opcode that is none of the five is a desynchronised read head rather than a feature nobody has
 * decoded yet - the client throws too - so it fails loudly with the region and the byte offset
 * instead of returning a half decoded block.
 */
object MapEffectDecoder {

    /** Every record from [offset] to the end of [data]. */
    fun decode(data: ByteArray, offset: Int, region: Int = -1): MutableList<MapEffect> {
        if (offset >= data.size) {
            return ArrayList(0)
        }
        val buffer = BufferReader(data)
        buffer.position(offset)
        return decode(buffer, region)
    }

    /** Every record from [buffer]'s position to the end of it. */
    fun decode(buffer: Reader, region: Int = -1): MutableList<MapEffect> {
        val effects = ArrayList<MapEffect>(2)
        while (buffer.remaining > 0) {
            val opcode = buffer.readUnsignedByte()
            effects.add(
                when (opcode) {
                    ENVIRONMENT -> environment(buffer)
                    LIGHTS -> lights(buffer)
                    HDR -> MapHdrEffect(
                        bloom = buffer.readUnsignedByte(),
                        brightpass = buffer.readUnsignedByte(),
                        whitePoint = buffer.readUnsignedByte()
                    )
                    SKYBOX -> MapSkyboxEffect(
                        id = buffer.readUnsignedShort(),
                        x = buffer.readShort(),
                        y = buffer.readShort(),
                        z = buffer.readShort(),
                        rotation = buffer.readUnsignedShort()
                    )
                    LIGHT_GRID -> lightGrid(buffer)
                    else -> error(
                        "Unhandled map effect opcode $opcode in region $region at byte ${buffer.position() - 1}."
                    )
                }
            )
        }
        return effects
    }

    private fun environment(buffer: Reader): MapEnvironmentEffect {
        val flags = buffer.readUnsignedByte()
        return MapEnvironmentEffect(
            sunColour = if (flags and MapEnvironmentEffect.FLAG_SUN_COLOUR != 0) buffer.readInt() else null,
            sunAmbient = if (flags and MapEnvironmentEffect.FLAG_SUN_AMBIENT != 0) buffer.readUnsignedShort() else null,
            sunLight = if (flags and MapEnvironmentEffect.FLAG_SUN_LIGHT != 0) buffer.readUnsignedShort() else null,
            sunBacklight = if (flags and MapEnvironmentEffect.FLAG_SUN_BACKLIGHT != 0) {
                buffer.readUnsignedShort()
            } else {
                null
            },
            sunPosition = if (flags and MapEnvironmentEffect.FLAG_SUN_POSITION != 0) {
                IntArray(MapEnvironmentEffect.SUN_POSITION_SIZE) { buffer.readShort() }
            } else {
                null
            },
            fogColour = if (flags and MapEnvironmentEffect.FLAG_FOG_COLOUR != 0) buffer.readInt() else null,
            fogDepth = if (flags and MapEnvironmentEffect.FLAG_FOG_DEPTH != 0) buffer.readUnsignedShort() else null,
            cubeTexture = if (flags and MapEnvironmentEffect.FLAG_CUBE_TEXTURE != 0) {
                IntArray(MapEnvironmentEffect.CUBE_TEXTURE_SIZE) { buffer.readUnsignedShort() }
            } else {
                null
            }
        )
    }

    private fun lights(buffer: Reader): MapLightsEffect {
        val count = buffer.readUnsignedByte()
        val lights = ArrayList<MapLight>(count)
        for (index in 0 until count) {
            val packedLevel = buffer.readUnsignedByte()
            val x = buffer.readUnsignedShort()
            val z = buffer.readUnsignedShort()
            val heightOffset = buffer.readUnsignedShort()
            val packedRadius = buffer.readUnsignedByte()
            val ranges = IntArray(packedRadius * 2 + 1) { buffer.readUnsignedShort() }
            val colour = buffer.readUnsignedShort()
            val packedType = buffer.readUnsignedByte()
            lights.add(
                MapLight(
                    packedLevel = packedLevel,
                    x = x,
                    z = z,
                    heightOffset = heightOffset,
                    packedRadius = packedRadius,
                    ranges = ranges,
                    colour = colour,
                    packedType = packedType,
                    // The config id is on the wire only for the type that needs one.
                    lightType = if (packedType and 0x1f == MapLight.CONFIG_TYPE) buffer.readUnsignedShort() else -1
                )
            )
        }
        return MapLightsEffect(lights)
    }

    private fun lightGrid(buffer: Reader): MapLightGridEffect {
        val levels = ArrayList<MapLightGridLevel>(MapLightGridEffect.LEVELS)
        for (level in 0 until MapLightGridEffect.LEVELS) {
            val mode = buffer.readByte()
            levels.add(
                if (mode == MapLightGridLevel.MODE_SAMPLES) {
                    val samples = ByteArray(MapLightGridLevel.SAMPLE_COUNT)
                    for (index in samples.indices) {
                        samples[index] = buffer.readByte().toByte()
                    }
                    MapLightGridLevel(mode, samples)
                } else {
                    MapLightGridLevel(mode, null)
                }
            )
        }
        return MapLightGridEffect(levels)
    }

    private const val ENVIRONMENT = 0
    private const val LIGHTS = 1
    private const val HDR = 2
    private const val SKYBOX = 128
    private const val LIGHT_GRID = 129
}
