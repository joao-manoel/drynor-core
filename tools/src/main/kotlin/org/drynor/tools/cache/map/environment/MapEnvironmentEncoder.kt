package org.drynor.tools.cache.map.environment

import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.math.roundToInt

/**
 * Serialises an [EnvironmentLayout] back into the effects block of a `m{x}_{y}` map archive.
 *
 * The inverse of [MapEnvironmentDecoder.readLayout], byte for byte: encoding a layout that
 * came straight out of the decoder reproduces the original bytes exactly. That property is
 * what `tools env roundtrip` checks across every map archive in the cache, and it is the
 * only thing that makes write-back safe.
 *
 * The trap this design exists to avoid: an encoder driven by the *decoded* [MapEnvironment]
 * has no way to tell "the file omitted this field" from "the file stored the default value",
 * so it sets every flag bit and a file of all-defaults grows by 32 bytes. Presence lives in
 * the layout's nullable fields instead, and [merge] only flips a flag when the edited value
 * actually differs from what the file (or the client default) already produced.
 */
object MapEnvironmentEncoder {

    /** Sun/ambient/backlight terms are stored as `value * 256`. */
    private const val LIGHT_SCALE = 256.0f

    /** HDR terms are stored as `value * 255 / 8`. */
    private const val HDR_SCALE = 255.0f / 8.0f

    /** Encode just the effects block. */
    fun encodeEffects(layout: EnvironmentLayout): ByteArray {
        val writer = BufferWriter(layout.encodedSize().coerceAtLeast(1))
        for (block in layout.blocks) {
            writer.writeByte(block.opcode)
            when (block) {
                is EnvironmentRecord -> writeEnvironment(writer, block)
                is PointLightsRecord -> writeLights(writer, block)
                is HdrRecord -> {
                    writer.writeByte(block.bloom)
                    writer.writeByte(block.brightpass)
                    writer.writeByte(block.whitePoint)
                }
                is SkyboxRecord -> {
                    writer.writeShort(block.id)
                    writer.writeShort(block.x)
                    writer.writeShort(block.y)
                    writer.writeShort(block.z)
                    writer.writeShort(block.rotation)
                }
                is LightGridRecord -> writeLightGrid(writer, block)
            }
        }
        return writer.toArray()
    }

    /**
     * Rebuild a whole map archive: the original tile bytes verbatim, then the encoded
     * effects block.
     *
     * The tile section is spliced, never re-encoded. Tile records have their own opcode
     * grammar with several ways to spell the same thing, so a decode/encode cycle over them
     * is not known to be byte-exact and there is no reason to risk the terrain to change the
     * lighting.
     */
    fun encodeArchive(original: ByteArray, layout: EnvironmentLayout): ByteArray {
        val tileLength = layout.tileSectionLength
        require(tileLength in 0..original.size) {
            "Tile section length $tileLength outside the ${original.size}-byte archive"
        }
        val effects = encodeEffects(layout)
        val result = ByteArray(tileLength + effects.size)
        System.arraycopy(original, 0, result, 0, tileLength)
        System.arraycopy(effects, 0, result, tileLength, effects.size)
        return result
    }

    private fun writeEnvironment(writer: BufferWriter, record: EnvironmentRecord) {
        writer.writeByte(record.flags())
        record.sunColour?.let { writer.writeInt(it) }
        record.sunAmbient?.let { writer.writeShort(it) }
        record.sunLight?.let { writer.writeShort(it) }
        record.sunBacklight?.let { writer.writeShort(it) }
        record.sunPosition?.let { position ->
            writer.writeShort(position[0])
            writer.writeShort(position[1])
            writer.writeShort(position[2])
        }
        record.fogColour?.let { writer.writeInt(it) }
        record.fogDepth?.let { writer.writeShort(it) }
        record.cubeTexture?.let { cube -> for (material in cube) writer.writeShort(material) }
    }

    private fun writeLights(writer: BufferWriter, record: PointLightsRecord) {
        require(record.lights.size <= 255) {
            "A point-light block holds at most 255 lights, got ${record.lights.size}"
        }
        writer.writeByte(record.lights.size)
        for (light in record.lights) {
            writer.writeByte(light.packedLevel)
            writer.writeShort(light.x)
            writer.writeShort(light.z)
            writer.writeShort(light.heightOffset)
            writer.writeByte(light.packedRadius)
            for (range in light.ranges) writer.writeShort(range)
            writer.writeShort(light.colourHsl)
            writer.writeByte(light.packedType)
            if (light.lightTypeId >= 0) writer.writeShort(light.lightTypeId)
        }
    }

    private fun writeLightGrid(writer: BufferWriter, record: LightGridRecord) {
        for (plane in record.planes) {
            writer.writeByte(plane.mode)
            plane.samples?.let { samples -> for (sample in samples) writer.writeByte(sample.toInt()) }
        }
    }

    /**
     * Merge panel edits into a layout decoded from the cache.
     *
     * Per field: a value that still encodes to what the file already held leaves the file
     * untouched, including staying *absent* when it was absent. Only a genuine change flips
     * the flag bit on and writes the new bytes.
     */
    fun merge(layout: EnvironmentLayout, edit: EnvironmentEdit): EnvironmentLayout {
        val blocks = ArrayList(layout.blocks)

        var environmentIndex = layout.environmentIndex()
        val existing = if (environmentIndex >= 0) blocks[environmentIndex] as EnvironmentRecord else EnvironmentRecord.EMPTY
        val merged = EnvironmentRecord(
            sunColour = field(existing.sunColour, edit.sunColour, MapEnvironment.DEFAULT_SUN_COLOUR),
            sunAmbient = field(existing.sunAmbient, scaled(edit.sunAmbient), scaled(MapEnvironment.DEFAULT_SUN_AMBIENT)),
            sunLight = field(existing.sunLight, scaled(edit.sunLight), scaled(MapEnvironment.DEFAULT_SUN_LIGHT)),
            sunBacklight = field(
                existing.sunBacklight,
                scaled(edit.sunBacklight),
                scaled(MapEnvironment.DEFAULT_SUN_BACKLIGHT),
            ),
            sunPosition = sunPositionField(existing.sunPosition, edit),
            fogColour = field(existing.fogColour, edit.fogColour, MapEnvironment.DEFAULT_FOG_COLOUR),
            fogDepth = field(existing.fogDepth, edit.fogDepth.coerceIn(0, 0xffff), 0),
            // Not editable from the panel - carried through untouched.
            cubeTexture = existing.cubeTexture,
        )

        if (environmentIndex >= 0) {
            blocks[environmentIndex] = merged
        } else if (merged.flags() != 0) {
            // A brand new record goes first so a later opcode 128 cannot create the client's
            // environment record and pull in the reset() HDR defaults.
            blocks.add(0, merged)
            environmentIndex = 0
        }

        mergeSkybox(blocks, environmentIndex, edit)

        return EnvironmentLayout(
            blocks = blocks,
            tileSectionLength = layout.tileSectionLength,
            sourceLength = layout.sourceLength,
        )
    }

    private fun mergeSkybox(
        blocks: MutableList<EffectsBlock>,
        environmentIndex: Int,
        edit: EnvironmentEdit,
    ) {
        // Found in the already-mutated list: inserting a new environment record above shifts
        // every later block down by one, so an index taken from the original layout would
        // point at the wrong block.
        val skyboxIndex = blocks.indexOfFirst { it is SkyboxRecord }
        val existing = if (skyboxIndex >= 0) blocks[skyboxIndex] as SkyboxRecord else null
        if (edit.skyboxId < 0) {
            if (skyboxIndex >= 0) blocks.removeAt(skyboxIndex)
            return
        }
        val record = SkyboxRecord(
            id = edit.skyboxId and 0xffff,
            x = existing?.x ?: 0,
            y = existing?.y ?: 0,
            z = existing?.z ?: 0,
            rotation = edit.skyboxRotation and 0xffff,
        )
        if (skyboxIndex >= 0) {
            blocks[skyboxIndex] = record
        } else {
            // After the environment record for the same reason as above.
            blocks.add(if (environmentIndex >= 0) environmentIndex + 1 else blocks.size, record)
        }
    }

    /**
     * Presence-preserving field merge.
     *
     * - present and unchanged -> keep the original bytes
     * - absent and the edit still encodes to the client default -> stay absent
     * - anything else -> present with the new value
     */
    private fun field(stored: Int?, edited: Int, default: Int): Int? = when {
        stored != null && stored == edited -> stored
        stored == null && edited == default -> null
        else -> edited
    }

    private fun sunPositionField(stored: IntArray?, edit: EnvironmentEdit): IntArray? {
        val edited = intArrayOf(
            clampShort(edit.sunPositionX),
            clampShort(edit.sunPositionY),
            clampShort(edit.sunPositionZ),
        )
        if (stored != null && stored.contentEquals(edited)) return stored
        if (stored == null &&
            edited[0] == MapEnvironment.DEFAULT_SUN_POSITION_X.toInt() &&
            edited[1] == MapEnvironment.DEFAULT_SUN_POSITION_Y.toInt() &&
            edited[2] == MapEnvironment.DEFAULT_SUN_POSITION_Z.toInt()
        ) {
            return null
        }
        return edited
    }

    private fun scaled(value: Float): Int = (value * LIGHT_SCALE).roundToInt().coerceIn(0, 0xffff)

    private fun clampShort(value: Float): Int = value.roundToInt().coerceIn(-0x8000, 0x7fff)

    /** HDR byte for a decoded HDR term; the inverse of `raw * 8 / 255`. */
    fun hdrByte(value: Float): Int = (value * HDR_SCALE).roundToInt().coerceIn(0, 0xff)
}

/**
 * The subset of an environment the Map Editor's Environment panel can change, in the same
 * units [MapEnvironment] uses.
 *
 * Everything else in the effects block - point lights, the light grid, HDR, the cube texture,
 * the skybox anchor position - is carried through the merge untouched.
 */
data class EnvironmentEdit(
    val sunColour: Int,
    val sunPositionX: Float,
    val sunPositionY: Float,
    val sunPositionZ: Float,
    val sunAmbient: Float,
    val sunLight: Float,
    val sunBacklight: Float,
    val fogColour: Int,
    val fogDepth: Int,
    /** Skybox config id, or -1 for "no skybox override", which removes the opcode-128 block. */
    val skyboxId: Int,
    val skyboxRotation: Int,
)
