package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class UnderlayDefinition(
    override var id: Int = -1,
    var colour: Int = 0,
    var texture: Int = -1,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var aBoolean2892: Boolean = true,
    var hue: Int = 0,
    var saturation: Int = 0,
    var lightness: Int = 0,
    var chroma: Int = 0
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null
}