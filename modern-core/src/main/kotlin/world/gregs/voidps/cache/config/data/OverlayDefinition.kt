package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class OverlayDefinition(
    override var id: Int = -1,
    var colour: Int = 0,
    /**
     * The packed rgb opcode 1 carried. [colour] is `rgbToHsl` of it, which is not invertible, so
     * the rgb is kept alongside for the encoder. -1 when the file had no opcode 1.
     */
    var colourRgb: Int = -1,
    /** The packed rgb opcode 7 carried; see [colourRgb]. -1 when the file had no opcode 7. */
    var blendColourRgb: Int = -1,
    var texture: Int = -1,
    var hideUnderlay: Boolean = true,
    var blendColour: Int = -1,
    var anInt961: Int = 0,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var anInt3633: Int = DEFAULT_SLOT,
    /**
     * The slot byte opcode 11 carried, [DEFAULT_SLOT] when the file had none.
     *
     * [anInt3633] is `id or (slot shl 8)`, which the decoder only derives in `changeValues` (client
     * `FloType.postDecode`), so the raw byte is kept for the encoder: a file has to be reproducible from
     * `readLoop` alone, before any derivation.
     */
    var slot: Int = DEFAULT_SLOT,
    var underlayOverrides: Boolean = false,
    var waterColour: Int = 1190717,
    var waterScale: Int = 64,
    var waterIntensity: Int = 127
) : Definition, OpcodeOrdered {

    companion object {
        /** The slot of an overlay whose file has no opcode 11. */
        const val DEFAULT_SLOT = 8
    }

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null
}