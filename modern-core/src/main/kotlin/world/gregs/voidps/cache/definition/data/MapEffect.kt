package world.gregs.voidps.cache.definition.data

/**
 * One record of the environment block that follows the tiles of a `m<x>_<y>` map archive.
 *
 * The block is a bare list of records - an opcode byte and its payload, running to the end of the
 * file with no count and no terminator - and the client reads it after the 4 * 64 * 64 tile records
 * (`MapRegion`/`EnvironmentManager` in the bot). Five opcodes exist:
 *
 * ```
 * 0    the environment: a flag byte, then the fields its bits select  [MapEnvironmentEffect]
 * 1    point lights: a count byte, then that many light records       [MapLightsEffect]
 * 2    the HDR triple                                                [MapHdrEffect]
 * 128  the skybox                                                    [MapSkyboxEffect]
 * 129  the per-level light intensity grid                            [MapLightGridEffect]
 * ```
 *
 * Everything here is the value **as stored**: colours are still packed RS HSL, light positions are
 * still 1/128 of a tile, the HDR terms are still bytes before the client's `* 8 / 255`, and an
 * absent field is null rather than the default the client substitutes for it. That is what makes
 * the records re-encodable: a file whose sun colour happens to equal the default is not the same
 * file as one with the sun colour flag clear, and only presence can tell them apart.
 *
 * The renderable view of the same bytes - defaults applied, HSL converted, samples expanded - is
 * `tools`' `RegionEnvironment`, built from its own byte-faithful `EnvironmentLayout`. The two
 * models are deliberately separate: this one is the cache's, and nothing in it may be lossy.
 */
sealed interface MapEffect {

    /** The opcode byte the record is introduced by. */
    val opcode: Int
}

/**
 * Opcode 0. A null field is one whose flag bit was clear, so the file did not carry it and
 * re-encoding must not add it - see [flags], which is derived from presence and never stored.
 *
 * [sunAmbient], [sunLight] and [sunBacklight] are the unsigned shorts before the client's `/ 256`,
 * [sunPosition] is three signed shorts, [cubeTexture] is six material ids and the two colours are
 * the raw ints.
 */
class MapEnvironmentEffect(
    val sunColour: Int? = null,
    val sunAmbient: Int? = null,
    val sunLight: Int? = null,
    val sunBacklight: Int? = null,
    val sunPosition: IntArray? = null,
    val fogColour: Int? = null,
    val fogDepth: Int? = null,
    val cubeTexture: IntArray? = null
) : MapEffect {

    override val opcode: Int
        get() = 0

    /**
     * The flag byte, rebuilt from which fields are present.
     *
     * All eight bits are accounted for, so the byte and the presence of the eight fields carry
     * exactly the same information and the byte itself never has to be stored.
     */
    fun flags(): Int {
        var flags = 0
        if (sunColour != null) flags = flags or FLAG_SUN_COLOUR
        if (sunAmbient != null) flags = flags or FLAG_SUN_AMBIENT
        if (sunLight != null) flags = flags or FLAG_SUN_LIGHT
        if (sunBacklight != null) flags = flags or FLAG_SUN_BACKLIGHT
        if (sunPosition != null) flags = flags or FLAG_SUN_POSITION
        if (fogColour != null) flags = flags or FLAG_FOG_COLOUR
        if (fogDepth != null) flags = flags or FLAG_FOG_DEPTH
        if (cubeTexture != null) flags = flags or FLAG_CUBE_TEXTURE
        return flags
    }

    companion object {
        const val FLAG_SUN_COLOUR = 0x1
        const val FLAG_SUN_AMBIENT = 0x2
        const val FLAG_SUN_LIGHT = 0x4
        const val FLAG_SUN_BACKLIGHT = 0x8
        const val FLAG_SUN_POSITION = 0x10
        const val FLAG_FOG_COLOUR = 0x20
        const val FLAG_FOG_DEPTH = 0x40
        const val FLAG_CUBE_TEXTURE = 0x80

        /** How many material ids opcode 0's cube texture field holds. */
        const val CUBE_TEXTURE_SIZE = 6

        /** How many signed shorts the sun position holds. */
        const val SUN_POSITION_SIZE = 3
    }
}

/** Opcode 1. The count is an unsigned byte, so one record holds at most 255 lights. */
class MapLightsEffect(val lights: List<MapLight>) : MapEffect {

    override val opcode: Int
        get() = 1

    companion object {
        /** The count is one byte. */
        const val MAX_LIGHTS = 255
    }
}

/**
 * A point light exactly as stored: the position in 1/128 of a tile before the client's `shl 2`,
 * the ranges unclamped, and the colour still packed RS HSL.
 */
class MapLight(
    /** `and 0x7` the level, `and 0x8` grows upwards, `and 0x10` grows downwards. */
    val packedLevel: Int,
    val x: Int,
    val z: Int,
    val heightOffset: Int,
    /** The light has `packedRadius * 2 + 1` range slices. */
    val packedRadius: Int,
    /** `packedRadius * 2 + 1` unsigned shorts: the high byte the upper bound, the low the lower. */
    val ranges: IntArray,
    val colour: Int,
    /** `and 0x1f` the type, `and 0xe0` the rotation offset. */
    val packedType: Int,
    /** The light config id, on the wire only when the type is [CONFIG_TYPE]; -1 otherwise. */
    val lightType: Int
) {

    companion object {
        /** The type that means "the flicker parameters come from the light config list". */
        const val CONFIG_TYPE = 31
    }
}

/** Opcode 2. The three terms as bytes; the client scales each by `* 8 / 255`. */
class MapHdrEffect(val bloom: Int, val brightpass: Int, val whitePoint: Int) : MapEffect {

    override val opcode: Int
        get() = 2
}

/** Opcode 128. [x], [y] and [z] are signed shorts, [id] and [rotation] unsigned. */
class MapSkyboxEffect(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int
) : MapEffect {

    override val opcode: Int
        get() = 128
}

/** Opcode 129. Always [LEVELS] levels, each with its own mode byte. */
class MapLightGridEffect(val levels: List<MapLightGridLevel>) : MapEffect {

    override val opcode: Int
        get() = 129

    companion object {
        /** The record describes four levels whether or not the map square has anything on them. */
        const val LEVELS = 4
    }
}

/**
 * One level of an opcode 129 record.
 *
 * ```
 * 0  unlit, no payload
 * 1  256 signed intensities, each covering a 4x4 block of tiles, x then y
 * 2  copy the level below, no payload
 * ```
 *
 * The mode is kept as the byte the file held rather than as an enum: the client only ever compares
 * it against these three, and a fourth value has to survive a round trip regardless.
 */
class MapLightGridLevel(val mode: Int, val samples: ByteArray?) {

    companion object {
        const val MODE_UNLIT = 0
        const val MODE_SAMPLES = 1
        const val MODE_COPY_BELOW = 2

        /** Each sample covers a 4x4 block of tiles. */
        const val BLOCK = 4

        /** 16 samples per axis, so 256 per lit level. */
        const val SAMPLE_COUNT = (64 / BLOCK) * (64 / BLOCK)
    }
}