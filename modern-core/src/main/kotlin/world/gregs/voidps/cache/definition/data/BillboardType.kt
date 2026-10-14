package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * Configuration type for billboards (2D graphics attached to 3D model faces).
 *
 * Billboards define textured quads that are rendered at model face centroids.
 * The key property is [hasUid]: when true, the anchor face is hidden and replaced
 * by the billboard quad. This is used for signpost labels, particle effect anchors, etc.
 *
 * Decoded from cache Index 29 (BILLBOARDS), archive 0, file = billboardId, by
 * [BillboardDecoder][world.gregs.voidps.cache.definition.decoder.BillboardDecoder]; the model
 * footer's [BillboardData] names one of these. Matches the client's `BillboardType` decode.
 */
data class BillboardType(
    override var id: Int = -1,
    /** Material/texture ID for the billboard quad (-1 = no material) */
    var materialId: Int = -1,
    /** Horizontal size (width) in RS model units */
    var size2d: Int = 64,
    /** Vertical size (height) in RS model units */
    var size3d: Int = 64,
    /** Shape: 0=rectangle, 1=circle, 2=brightened rectangle */
    var shape: Int = 2,
    /** Blend type: 0=color mix, 1=no color mix */
    var blendType: Int = 1,
    /** If true, billboard does not rotate to face camera (bloom-only effect) */
    var stationary: Boolean = false,
    /** If true, the anchor face is hidden and replaced by the billboard quad */
    var hasUid: Boolean = false,
    /**
     * Opcode 3's byte, which the client reads and throws away.
     *
     * -1 when the file has no opcode 3 record at all, which is every billboard in the 727 cache
     * bar none; the field is what keeps one that had it from losing it.
     */
    var unknown3: Int = -1
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
