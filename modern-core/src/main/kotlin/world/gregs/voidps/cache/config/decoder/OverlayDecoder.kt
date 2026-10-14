package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.FLOOR_OVERLAY
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.encoder.OverlayEncoder

class OverlayDecoder : ConfigDecoder<OverlayDefinition>(FLOOR_OVERLAY) {

    private val encoder = OverlayEncoder()

    override fun create(size: Int) = Array(size) { OverlayDefinition(it) }

    override fun canonicalOpcodes(definition: OverlayDefinition): IntArray = encoder.opcodes(definition)

    override fun OverlayDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                // The packed hsl the client uses is not invertible, so the rgb is kept too.
                colourRgb = buffer.readUnsignedMedium()
                colour = calculateHsl(colourRgb)
            }
            2 -> texture = buffer.readUnsignedByte()
            3 -> {
                texture = buffer.readShort()
                if (texture == 65535) {
                    texture = -1
                }
            }
            5 -> hideUnderlay = false
            7 -> {
                blendColourRgb = buffer.readUnsignedMedium()
                blendColour = calculateHsl(blendColourRgb)
            }
            8 -> anInt961 = id
            9 -> scale = buffer.readShort() shl 2
            10 -> blockShadow = false
            11 -> slot = buffer.readUnsignedByte()
            12 -> underlayOverrides = true
            13 -> waterColour = buffer.readUnsignedMedium()
            14 -> waterScale = buffer.readUnsignedByte() shl 2
            16 -> waterIntensity = buffer.readUnsignedByte()
            else -> error("Unhandled overlay definition opcode: $opcode for def $id")
        }
    }

    override fun changeValues(definitions: Array<OverlayDefinition>, definition: OverlayDefinition) {
        definition.anInt3633 = definition.id or (definition.slot shl 8)
    }

    companion object {

        private fun calculateHsl(i: Int): Int {
            return if (i == 16711935) {
                -1
            } else rgbToHsl(i)
        }

        /**
         * Convert RGB color to packed HSL format.
         * This matches the client's ColorUtil.rgbToHsl24() function exactly.
         *
         * Packed HSL format:
         * - Bits 10-15: Hue (6 bits, 0-63)
         * - Bits 7-9: Saturation (3 bits, 0-7)
         * - Bits 0-6: Lightness (7 bits, 0-127)
         */
        private fun rgbToHsl(rgb: Int): Int {
            // Extract RGB components (using /256.0 to match client, not /255.0)
            val r = (rgb shr 16 and 0xff).toDouble() / 256.0
            val g = (rgb shr 8 and 0xff).toDouble() / 256.0
            val b = (rgb and 0xff).toDouble() / 256.0

            // Find minimum and maximum RGB values
            var minimum = r
            if (g < minimum) minimum = g
            if (b < minimum) minimum = b

            var maximum = r
            if (g > maximum) maximum = g
            if (b > maximum) maximum = b

            // Calculate lightness (average of min and max)
            val l = (minimum + maximum) / 2.0

            // Calculate saturation and hue
            var h = 0.0
            var s = 0.0
            if (minimum != maximum) {
                // Saturation depends on lightness
                s = if (l < 0.5) {
                    (maximum - minimum) / (minimum + maximum)
                } else {
                    (maximum - minimum) / (2.0 - maximum - minimum)
                }

                // Hue depends on which component is maximum
                h = when (maximum) {
                    r -> (g - b) / (maximum - minimum)
                    g -> 2.0 + (b - r) / (maximum - minimum)
                    else -> 4.0 + (r - g) / (maximum - minimum)  // b == maximum
                }
            }

            // Normalize hue to 0-1 range
            h /= 6.0

            // Convert to integer values (0-255 range)
            val hue = (256.0 * h).toInt()
            var saturation = (256.0 * s).toInt()
            var lightness = (256.0 * l).toInt()

            // Clamp saturation and lightness
            saturation = saturation.coerceIn(0, 255)
            lightness = lightness.coerceIn(0, 255)

            // Apply saturation reduction at high lightness (matches client)
            saturation = when {
                lightness > 243 -> saturation shr 4
                lightness > 217 -> saturation shr 3
                lightness > 192 -> saturation shr 2
                lightness > 179 -> saturation shr 1
                else -> saturation
            }

            // Pack into RS HSL format: hue(6 bits) << 10 | sat(3 bits) << 7 | lightness(7 bits)
            return ((hue and 0xff) shr 2 shl 10) + (saturation shr 5 shl 7) + (lightness shr 1)
        }
    }
}