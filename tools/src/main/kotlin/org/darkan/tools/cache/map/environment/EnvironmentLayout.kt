package org.darkan.tools.cache.map.environment

/**
 * A byte-faithful echo of the effects block that follows the tile section of a
 * `m{regionX}_{regionY}` map archive.
 *
 * [RegionEnvironment] is the *semantic* view: defaults filled in, HSL converted to RGB,
 * light ranges clamped, the 16x16 light samples expanded to 64x64. All of that is lossy,
 * so re-encoding from it cannot reproduce the original bytes. The layout keeps what the
 * semantic view throws away:
 *
 * - **which fields were present** rather than defaulted. A file whose sun colour happens to
 *   be white is not the same as a file with the sun-colour flag clear; encoding from
 *   "the value equals the default" would flip the flag and change the file length.
 * - **opcode order and repetition**, including opcode-1 blocks with a zero light count.
 * - **raw field encodings** - unclamped light ranges, HDR bytes before the `* 8 / 255`
 *   scaling, the light-grid mode byte (mode `2` means "copy the plane below", which the
 *   semantic view has already resolved into a copy).
 *
 * Every edit path therefore works by *merging* changes into a layout decoded from the
 * cache, never by encoding a [MapEnvironment] from scratch. Untouched fields keep their
 * original presence and their original bytes.
 */
class EnvironmentLayout(
    /** Effects blocks in file order. */
    val blocks: List<EffectsBlock>,
    /** Byte length of the tile section that precedes the effects block. */
    val tileSectionLength: Int = 0,
    /** Byte length of the original effects block, or -1 when the layout was not decoded. */
    val sourceLength: Int = -1,
) {

    /** Index of the first opcode-0 block, or -1 when the file carries none. */
    fun environmentIndex(): Int = blocks.indexOfFirst { it is EnvironmentRecord }

    /** Index of the first opcode-2 block, or -1. */
    fun hdrIndex(): Int = blocks.indexOfFirst { it is HdrRecord }

    /** Index of the first opcode-128 block, or -1. */
    fun skyboxIndex(): Int = blocks.indexOfFirst { it is SkyboxRecord }

    /** Total encoded size in bytes, opcode bytes included. */
    fun encodedSize(): Int = blocks.sumOf { it.encodedSize() }

    companion object {
        val EMPTY = EnvironmentLayout(emptyList())
    }
}

/** One opcode of the effects block. */
sealed interface EffectsBlock {
    /** Opcode byte this block is introduced by. */
    val opcode: Int

    /** Encoded size in bytes, including the opcode byte. */
    fun encodedSize(): Int
}

/**
 * Opcode `0`. Null fields are the ones whose flag bit was clear - the decoder substitutes
 * defaults for them, but the file did not contain them and re-encoding must not add them.
 *
 * All values are stored exactly as they sit on the wire: [sunAmbient]/[sunLight]/
 * [sunBacklight] are the unsigned shorts *before* the `/ 256` scaling, [sunPosition] is
 * three signed shorts, and the colours are the raw ints.
 */
class EnvironmentRecord(
    val sunColour: Int?,
    val sunAmbient: Int?,
    val sunLight: Int?,
    val sunBacklight: Int?,
    val sunPosition: IntArray?,
    val fogColour: Int?,
    val fogDepth: Int?,
    val cubeTexture: IntArray?,
) : EffectsBlock {

    override val opcode: Int get() = 0

    /** Flag byte reconstructed from which fields are present. */
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

    override fun encodedSize(): Int {
        var size = 2 // opcode + flags
        if (sunColour != null) size += 4
        if (sunAmbient != null) size += 2
        if (sunLight != null) size += 2
        if (sunBacklight != null) size += 2
        if (sunPosition != null) size += 6
        if (fogColour != null) size += 4
        if (fogDepth != null) size += 2
        if (cubeTexture != null) size += 12
        return size
    }

    fun copy(
        sunColour: Int? = this.sunColour,
        sunAmbient: Int? = this.sunAmbient,
        sunLight: Int? = this.sunLight,
        sunBacklight: Int? = this.sunBacklight,
        sunPosition: IntArray? = this.sunPosition,
        fogColour: Int? = this.fogColour,
        fogDepth: Int? = this.fogDepth,
        cubeTexture: IntArray? = this.cubeTexture,
    ) = EnvironmentRecord(
        sunColour, sunAmbient, sunLight, sunBacklight, sunPosition, fogColour, fogDepth, cubeTexture
    )

    companion object {
        const val FLAG_SUN_COLOUR = 0x1
        const val FLAG_SUN_AMBIENT = 0x2
        const val FLAG_SUN_LIGHT = 0x4
        const val FLAG_SUN_BACKLIGHT = 0x8
        const val FLAG_SUN_POSITION = 0x10
        const val FLAG_FOG_COLOUR = 0x20
        const val FLAG_FOG_DEPTH = 0x40
        const val FLAG_CUBE_TEXTURE = 0x80

        /** A record with every flag clear - the shape the client writes for "all defaults". */
        val EMPTY = EnvironmentRecord(null, null, null, null, null, null, null, null)
    }
}

/** Opcode `2`. Raw bytes; the decoder scales each by `* 8 / 255`. */
class HdrRecord(val bloom: Int, val brightpass: Int, val whitePoint: Int) : EffectsBlock {
    override val opcode: Int get() = 2
    override fun encodedSize(): Int = 4
}

/** Opcode `128`. [x]/[y]/[z] are signed shorts, [id] and [rotation] unsigned. */
class SkyboxRecord(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int,
) : EffectsBlock {
    override val opcode: Int get() = 128
    override fun encodedSize(): Int = 11
}

/** Opcode `1`. The count is an unsigned byte, so a block holds at most 255 lights. */
class PointLightsRecord(val lights: List<RawPointLight>) : EffectsBlock {
    override val opcode: Int get() = 1
    override fun encodedSize(): Int = 2 + lights.sumOf { it.encodedSize() }
}

/**
 * A point light exactly as stored: positions in 1/128-tile units before the client's
 * `shl 2`, ranges unclamped, colour still packed RS HSL.
 */
class RawPointLight(
    /** `& 0x7` plane, `& 0x8` grows upwards, `& 0x10` grows downwards. */
    val packedLevel: Int,
    val x: Int,
    val z: Int,
    val heightOffset: Int,
    /** Slice count is `packedRadius * 2 + 1`. */
    val packedRadius: Int,
    /** [packedRadius] * 2 + 1 unsigned shorts, high byte upper height, low byte lower. */
    val ranges: IntArray,
    val colourHsl: Int,
    /** `& 0x1f` type, `& 0xe0` rotation offset (the client shifts it left by 3). */
    val packedType: Int,
    /** Light config id, present on the wire only when the type is 31. -1 otherwise. */
    val lightTypeId: Int,
) {
    fun encodedSize(): Int = 1 + 2 + 2 + 2 + 1 + ranges.size * 2 + 2 + 1 + (if (lightTypeId >= 0) 2 else 0)
}

/** Opcode `129`. Always four planes, each with its own mode byte. */
class LightGridRecord(val planes: Array<RawLightGridPlane>) : EffectsBlock {
    override val opcode: Int get() = 129
    override fun encodedSize(): Int = 1 + planes.sumOf { it.encodedSize() }
}

/**
 * One plane of an opcode-129 block.
 *
 * - mode `0` - unlit, no payload
 * - mode `1` - [samples] holds `(64/4) * (64/4)` = 256 signed intensities, row-major over
 *   x then y, each covering a 4x4 tile block
 * - mode `2` - copy the plane below, no payload
 */
class RawLightGridPlane(val mode: Int, val samples: ByteArray?) {
    fun encodedSize(): Int = 1 + (samples?.size ?: 0)

    companion object {
        const val MODE_UNLIT = 0
        const val MODE_SAMPLES = 1
        const val MODE_COPY_BELOW = 2

        /** 16x16 samples covering 4x4 tiles each. */
        const val BLOCK = 4
        const val SAMPLES_PER_AXIS = RegionEnvironment.SIZE / BLOCK
        const val SAMPLE_COUNT = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS
    }
}
