package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * A particle: index 27 archive 1, the client's `ParticleType`.
 *
 * Not a particle in the sense of one speck of dust - it is a force field a
 * [ParticleProducerDefinition]'s particles are pushed about by, at an offset from the vertex a
 * model's particle effector attachment ([ParticleEffectorAttachment]) puts it on. Eighteen of them
 * in the whole 727 cache.
 *
 * As with the producer, the values here are the file's: the client's `setDefaults` derives a
 * cosine, a 3D magnitude and a squared reach from them, and none of that is stored.
 */
data class ParticleDefinition(
    override var id: Int = -1,
    /** Opcode 1: the rotation the client turns into a cosine, in units of an eighth of a degree. */
    var rotation: Int = 0,
    /** Opcode 2's byte, which the client reads and throws away. */
    var unused2: Int? = null,
    /** Opcode 3: where the field sits relative to the vertex it is attached to. */
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var offsetZ: Int = 0,
    /**
     * Opcode 4's first byte: 0 gives the field unlimited reach, 1 a spherical one and 2 a linear
     * one - which is what the client's `setDefaults` switches on to turn [sizeMultiplier] and the
     * offsets into the reach it actually tests against.
     *
     * The reference client's `ParticleType.decode` reads opcode 4 as a bare int and never sets
     * this at all, which desyncs every one of the ten 727 files that carry the record. The record
     * is a byte and an int; see the decoder.
     */
    var type: Int = 0,
    /** Opcode 4's int: divides the field's reach. */
    var sizeMultiplier: Int = 0,
    /** Opcode 6: 0 attracts, 1 repels, 2 registers the field for the whole scene. */
    var particleHandlingType: Int = 0,
    /** Opcode 8 sets this. */
    var currentOffset: Boolean = false,
    /** Opcode 9 sets this. */
    var verticeCalculationType: Boolean = false,
    /** Opcode 10 sets this. A bare marker the client reads and does nothing with. */
    var unusedOpcode10: Boolean = false
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
