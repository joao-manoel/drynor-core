package org.drynor.tools.cache.map.environment

import org.drynor.tools.util.ColorUtil
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Decodes the environment/effects block that follows the tile section of a
 * `m{regionX}_{regionY}` map archive.
 *
 * Layout of a map archive:
 * ```
 * [4 planes * 64 * 64 tile opcode streams]   <- consumed by TerrainLoader
 * [effects block, opcodes until end of file] <- decoded here
 * ```
 *
 * Effects opcodes:
 * - `0`   environment (flag byte, then the fields the flags select)
 * - `1`   point lights (ubyte count, then that many light records)
 * - `2`   HDR triple (bloom, brightpass, whitePoint as ubytes)
 * - `128` skybox (ushort id, 3 shorts position, ushort rotation)
 * - `129` per-plane light intensity grid (4 planes, each a mode byte)
 *
 * Anything else is malformed - the client throws, and so do we, because a bad opcode means
 * the read head desynced rather than that the file has an unknown feature.
 *
 * Decoding runs in two stages. [readLayout] produces an [EnvironmentLayout], a byte-faithful
 * echo of the block that [MapEnvironmentEncoder] can turn straight back into the original
 * bytes; [buildRegion] then applies defaults, colour conversion and clamping to reach the
 * renderable [RegionEnvironment]. Keeping the lossy step separate is what makes write-back
 * possible - see [EnvironmentLayout] for what would otherwise be lost.
 */
object MapEnvironmentDecoder {

    private const val TILES_PER_PLANE = 64 * 64
    private const val PLANES = RegionEnvironment.PLANES
    private const val SIZE = RegionEnvironment.SIZE
    private const val CHUNKS = RegionEnvironment.CHUNKS

    private const val LIGHT_BLOCK = RawLightGridPlane.BLOCK

    /** Packed light type meaning "parameters come from the light config list". */
    private const val LIGHT_TYPE_CONFIG = 31

    /**
     * Decode the effects block of a full map archive.
     *
     * @param data raw (decompressed) `m{x}_{y}` archive bytes
     * @param lightFlicker resolves a light config id to its flicker parameters; when null,
     *   type-31 lights fall back to [LightFlicker.NONE]
     */
    fun decode(
        data: ByteArray,
        regionX: Int,
        regionY: Int,
        lightFlicker: ((Int) -> LightFlicker)? = null,
    ): RegionEnvironment = buildRegion(readLayout(data, regionX, regionY), regionX, regionY, lightFlicker)

    /**
     * Byte-faithful structure of the effects block, for round-tripping and write-back.
     * Also records where the tile section ends so callers can splice the original tile
     * bytes back in unchanged.
     */
    fun readLayout(data: ByteArray, regionX: Int, regionY: Int): EnvironmentLayout {
        val buffer = BufferReader(data)
        skipTiles(buffer)
        val tileSectionLength = buffer.position()
        val blocks = readBlocks(buffer, regionX, regionY)
        return EnvironmentLayout(
            blocks = blocks,
            tileSectionLength = tileSectionLength,
            sourceLength = data.size - tileSectionLength,
        )
    }

    /**
     * Advance past the `4 * 64 * 64` tile opcode streams at the head of a map archive.
     * Mirrors the tile opcode grammar in `TerrainLoader.decodeRawTile`.
     */
    fun skipTiles(buffer: BufferReader) {
        repeat(PLANES * TILES_PER_PLANE) {
            while (true) {
                val opcode = buffer.readUnsignedByte()
                if (opcode == 0) break
                if (opcode == 1) {
                    buffer.skip(1)
                    break
                }
                if (opcode <= 49) buffer.skip(1)
            }
        }
    }

    private fun readBlocks(buffer: BufferReader, regionX: Int, regionY: Int): List<EffectsBlock> {
        val blocks = ArrayList<EffectsBlock>()
        while (buffer.remaining > 0) {
            when (val opcode = buffer.readUnsignedByte()) {
                0 -> blocks.add(readEnvironmentRecord(buffer))
                1 -> {
                    val count = buffer.readUnsignedByte()
                    val lights = ArrayList<RawPointLight>(count)
                    repeat(count) { lights.add(readLight(buffer)) }
                    blocks.add(PointLightsRecord(lights))
                }
                2 -> blocks.add(
                    HdrRecord(
                        bloom = buffer.readUnsignedByte(),
                        brightpass = buffer.readUnsignedByte(),
                        whitePoint = buffer.readUnsignedByte(),
                    )
                )
                128 -> blocks.add(
                    SkyboxRecord(
                        id = buffer.readUnsignedShort(),
                        x = buffer.readShort(),
                        y = buffer.readShort(),
                        z = buffer.readShort(),
                        rotation = buffer.readUnsignedShort(),
                    )
                )
                129 -> blocks.add(readLightGrid(buffer))
                else -> throw IllegalStateException(
                    "Unknown map effects opcode $opcode in region ($regionX, $regionY) at byte ${buffer.position() - 1}"
                )
            }
        }
        return blocks
    }

    private fun readEnvironmentRecord(buffer: BufferReader): EnvironmentRecord {
        val flags = buffer.readUnsignedByte()
        return EnvironmentRecord(
            sunColour = if (flags and EnvironmentRecord.FLAG_SUN_COLOUR != 0) buffer.readInt() else null,
            sunAmbient = if (flags and EnvironmentRecord.FLAG_SUN_AMBIENT != 0) buffer.readUnsignedShort() else null,
            sunLight = if (flags and EnvironmentRecord.FLAG_SUN_LIGHT != 0) buffer.readUnsignedShort() else null,
            sunBacklight = if (flags and EnvironmentRecord.FLAG_SUN_BACKLIGHT != 0) buffer.readUnsignedShort() else null,
            sunPosition = if (flags and EnvironmentRecord.FLAG_SUN_POSITION != 0) {
                intArrayOf(buffer.readShort(), buffer.readShort(), buffer.readShort())
            } else {
                null
            },
            fogColour = if (flags and EnvironmentRecord.FLAG_FOG_COLOUR != 0) buffer.readInt() else null,
            fogDepth = if (flags and EnvironmentRecord.FLAG_FOG_DEPTH != 0) buffer.readUnsignedShort() else null,
            cubeTexture = if (flags and EnvironmentRecord.FLAG_CUBE_TEXTURE != 0) {
                IntArray(6) { buffer.readUnsignedShort() }
            } else {
                null
            },
        )
    }

    /** Mirror of `FlickeringEffect`'s constructor read order, without the derived values. */
    private fun readLight(buffer: BufferReader): RawPointLight {
        val packedLevel = buffer.readUnsignedByte()
        val x = buffer.readUnsignedShort()
        val z = buffer.readUnsignedShort()
        val heightOffset = buffer.readUnsignedShort()
        val packedRadius = buffer.readUnsignedByte()
        val ranges = IntArray(packedRadius * 2 + 1) { buffer.readUnsignedShort() }
        val colourHsl = buffer.readUnsignedShort()
        val packedType = buffer.readUnsignedByte()
        val lightTypeId = if (packedType and 0x1f == LIGHT_TYPE_CONFIG) buffer.readUnsignedShort() else -1
        return RawPointLight(
            packedLevel = packedLevel,
            x = x,
            z = z,
            heightOffset = heightOffset,
            packedRadius = packedRadius,
            ranges = ranges,
            colourHsl = colourHsl,
            packedType = packedType,
            lightTypeId = lightTypeId,
        )
    }

    /**
     * Opcode 129: four per-plane mode bytes.
     * - `0` plane is unlit
     * - `1` 16x16 signed intensity samples, each covering a 4x4 tile block
     * - `2` copy the plane below (planes above 0 only)
     */
    private fun readLightGrid(buffer: BufferReader): LightGridRecord {
        val planes = Array(PLANES) {
            when (val mode = buffer.readByte()) {
                RawLightGridPlane.MODE_SAMPLES -> {
                    val samples = ByteArray(RawLightGridPlane.SAMPLE_COUNT)
                    for (index in samples.indices) samples[index] = buffer.readByte().toByte()
                    RawLightGridPlane(mode, samples)
                }
                else -> RawLightGridPlane(mode, null)
            }
        }
        return LightGridRecord(planes)
    }

    /**
     * Turn a decoded layout into the renderable region view: defaults for absent fields,
     * HSL to RGB, clamped light ranges and the 16x16 samples expanded to 64x64.
     */
    fun buildRegion(
        layout: EnvironmentLayout,
        regionX: Int,
        regionY: Int,
        lightFlicker: ((Int) -> LightFlicker)? = null,
    ): RegionEnvironment {
        var builder: EnvironmentBuilder? = null
        var environmentBlocks = 0
        var lights: MutableList<MapPointLight>? = null
        var lightGrid: Array<ByteArray?>? = null

        for (block in layout.blocks) {
            when (block) {
                is EnvironmentRecord -> {
                    // The client leaves HDR at zero when opcode 0 arrives first, and only
                    // applies reset() defaults when an HDR/skybox opcode created the
                    // environment. Reproduce that exactly - it is visible in the tone mapping.
                    val target = builder ?: EnvironmentBuilder(hdrDefaults = false).also { builder = it }
                    applyEnvironment(block, target)
                    environmentBlocks++
                }
                is PointLightsRecord -> {
                    if (block.lights.isNotEmpty()) {
                        val list = lights ?: ArrayList<MapPointLight>(block.lights.size).also { lights = it }
                        for (raw in block.lights) list.add(buildLight(raw, lightFlicker))
                    }
                }
                is HdrRecord -> {
                    val target = builder ?: EnvironmentBuilder(hdrDefaults = true).also { builder = it }
                    target.bloom = block.bloom * 8 / 255.0f
                    target.brightpass = block.brightpass * 8 / 255.0f
                    target.whitePoint = block.whitePoint * 8 / 255.0f
                }
                is SkyboxRecord -> {
                    val target = builder ?: EnvironmentBuilder(hdrDefaults = true).also { builder = it }
                    target.skybox = SkyboxRef(
                        id = block.id,
                        x = block.x,
                        y = block.y,
                        z = block.z,
                        rotation = block.rotation,
                    )
                }
                is LightGridRecord -> lightGrid = buildLightGrid(block, lightGrid)
            }
        }

        val chunks = arrayOfNulls<MapEnvironment>(CHUNKS * CHUNKS)
        builder?.build()?.let { chunks.fill(it) }

        return RegionEnvironment(
            regionX = regionX,
            regionY = regionY,
            chunkEnvironments = chunks,
            lights = lights ?: emptyList(),
            lightGrid = lightGrid ?: arrayOfNulls(PLANES),
            environmentBlocks = environmentBlocks,
            layout = layout,
        )
    }

    private fun applyEnvironment(record: EnvironmentRecord, target: EnvironmentBuilder) {
        target.sunColour = record.sunColour ?: MapEnvironment.DEFAULT_SUN_COLOUR
        target.sunAmbient = record.sunAmbient?.let { it / 256.0f } ?: MapEnvironment.DEFAULT_SUN_AMBIENT
        target.sunLight = record.sunLight?.let { it / 256.0f } ?: MapEnvironment.DEFAULT_SUN_LIGHT
        target.sunBacklight = record.sunBacklight?.let { it / 256.0f } ?: MapEnvironment.DEFAULT_SUN_BACKLIGHT

        val position = record.sunPosition
        if (position != null) {
            target.sunPositionX = position[0].toFloat()
            target.sunPositionY = position[1].toFloat()
            target.sunPositionZ = position[2].toFloat()
        } else {
            target.sunPositionX = MapEnvironment.DEFAULT_SUN_POSITION_X
            target.sunPositionY = MapEnvironment.DEFAULT_SUN_POSITION_Y
            target.sunPositionZ = MapEnvironment.DEFAULT_SUN_POSITION_Z
        }

        target.fogColour = record.fogColour ?: MapEnvironment.DEFAULT_FOG_COLOUR
        target.fogDepth = record.fogDepth ?: 0

        val cube = record.cubeTexture
        target.cubeTexture = if (cube == null) {
            null
        } else {
            CubeTextureRef(
                positiveX = cube[0],
                negativeX = cube[1],
                positiveY = cube[2],
                negativeY = cube[3],
                positiveZ = cube[4],
                negativeZ = cube[5],
            )
        }
    }

    /**
     * Raw positions are 1/128 of a tile; the client shifts them left by 2 to reach the
     * scene's 512-units-per-tile space, and expands the packed radius the same way.
     */
    private fun buildLight(raw: RawPointLight, lightFlicker: ((Int) -> LightFlicker)?): MapPointLight {
        val sliceCount = raw.packedRadius * 2 + 1
        val ranges = ShortArray(sliceCount)
        for (index in 0 until sliceCount) {
            val packed = raw.ranges[index]
            var upper = packed ushr 8
            var lower = packed and 0xff
            if (upper >= sliceCount) upper = sliceCount - 1
            if (lower > sliceCount - upper) lower = sliceCount - upper
            ranges[index] = ((upper shl 8) or lower).toShort()
        }

        val type = raw.packedType and 0x1f
        val flicker = if (type == LIGHT_TYPE_CONFIG) {
            lightFlicker?.invoke(raw.lightTypeId) ?: LightFlicker.NONE
        } else {
            LightFlicker.forType(type)
        }

        return MapPointLight(
            plane = raw.packedLevel and 0x7,
            growsUpwards = raw.packedLevel and 0x8 != 0,
            growsDownwards = raw.packedLevel and 0x10 != 0,
            x = raw.x shl 2,
            heightOffset = raw.heightOffset shl 2,
            z = raw.z shl 2,
            radius = (raw.packedRadius shl MapPointLight.TILE_SHIFT) + (MapPointLight.TILE_UNITS shr 1),
            ranges = ranges,
            colourHsl = raw.colourHsl,
            colour = hslToRgb(raw.colourHsl),
            type = type,
            rotationOffset = (raw.packedType and 0xe0) shl 3,
            lightTypeId = raw.lightTypeId,
            flicker = flicker,
        )
    }

    /** Expands the 16x16 samples of each lit plane across its 4x4 tile blocks. */
    private fun buildLightGrid(record: LightGridRecord, existing: Array<ByteArray?>?): Array<ByteArray?> {
        val grid = existing ?: arrayOfNulls(PLANES)
        for (plane in 0 until PLANES) {
            val source = record.planes[plane]
            when (source.mode) {
                RawLightGridPlane.MODE_UNLIT -> grid[plane] = null
                RawLightGridPlane.MODE_SAMPLES -> {
                    val samples = source.samples ?: continue
                    val values = ByteArray(SIZE * SIZE)
                    var sample = 0
                    var blockX = 0
                    while (blockX < SIZE) {
                        var blockY = 0
                        while (blockY < SIZE) {
                            val intensity = samples[sample++]
                            for (x in blockX until blockX + LIGHT_BLOCK) {
                                val row = x * SIZE + blockY
                                values.fill(intensity, row, row + LIGHT_BLOCK)
                            }
                            blockY += LIGHT_BLOCK
                        }
                        blockX += LIGHT_BLOCK
                    }
                    grid[plane] = values
                }
                RawLightGridPlane.MODE_COPY_BELOW ->
                    grid[plane] = if (plane > 0) grid[plane - 1]?.copyOf() else ByteArray(SIZE * SIZE)
            }
        }
        return grid
    }

    /** Packs a 16-bit RS HSL colour into 0xRRGGBB. */
    private fun hslToRgb(hsl: Int): Int {
        val rgb = ColorUtil.hslToRgb(hsl)
        val r = (rgb.x * 255.0f + 0.5f).toInt().coerceIn(0, 255)
        val g = (rgb.y * 255.0f + 0.5f).toInt().coerceIn(0, 255)
        val b = (rgb.z * 255.0f + 0.5f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** Mutable accumulator; the file may carry the environment across several opcodes. */
    private class EnvironmentBuilder(hdrDefaults: Boolean) {
        var sunColour = MapEnvironment.DEFAULT_SUN_COLOUR
        var sunPositionX = MapEnvironment.DEFAULT_SUN_POSITION_X
        var sunPositionY = MapEnvironment.DEFAULT_SUN_POSITION_Y
        var sunPositionZ = MapEnvironment.DEFAULT_SUN_POSITION_Z
        var sunAmbient = MapEnvironment.DEFAULT_SUN_AMBIENT
        var sunLight = MapEnvironment.DEFAULT_SUN_LIGHT
        var sunBacklight = MapEnvironment.DEFAULT_SUN_BACKLIGHT
        var fogColour = MapEnvironment.DEFAULT_FOG_COLOUR
        var fogDepth = 0
        var cubeTexture: CubeTextureRef? = null
        var bloom = if (hdrDefaults) MapEnvironment.DEFAULT_BLOOM else 0.0f
        var brightpass = if (hdrDefaults) MapEnvironment.DEFAULT_BRIGHTPASS else 0.0f
        var whitePoint = if (hdrDefaults) MapEnvironment.DEFAULT_WHITE_POINT else 0.0f
        var skybox: SkyboxRef? = null

        fun build() = MapEnvironment(
            sunColour = sunColour,
            sunPositionX = sunPositionX,
            sunPositionY = sunPositionY,
            sunPositionZ = sunPositionZ,
            sunAmbient = sunAmbient,
            sunLight = sunLight,
            sunBacklight = sunBacklight,
            fogColour = fogColour,
            fogDepth = fogDepth,
            cubeTexture = cubeTexture,
            bloom = bloom,
            brightpass = brightpass,
            whitePoint = whitePoint,
            skybox = skybox,
        )
    }
}
