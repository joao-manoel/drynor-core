package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * A particle producer: index 27 archive 0, the client's `ParticleProducerType`.
 *
 * What a model's particle emitter attachment ([ParticleEmitterAttachment]) names. Everything about
 * a fountain of particles is here - the cone they leave the face in, how fast, how big, how long
 * they live, what colour they start and end, which [ParticleDefinition] force fields push them
 * about - and the client derives another twenty working values from it the moment it loads one.
 *
 * **Every field here is the file's own value, not the client's derived one.** The client's
 * `decode` shifts the four angles left by three bits into a `short` (which overflows), multiplies
 * the sizes by 2^14, and turns three bytes into booleans by comparing them to 1; its `build` then
 * computes the colour and speed fade steps from those. None of that is invertible, and none of it
 * belongs in a file the tree stores, so it is left to whatever wants to render particles. The
 * names say which opcode a value came from where the two disagree.
 */
data class ParticleProducerDefinition(
    override var id: Int = -1,
    /** Opcode 1: the emission cone, as the file's four raw shorts. The client shifts each left 3. */
    var minAngleHorizontal: Int = 0,
    var maxAngleHorizontal: Int = 0,
    var minAngleVertical: Int = 0,
    var maxAngleVertical: Int = 0,
    /** Opcode 2's byte, which the client reads and throws away. Null when the file has no opcode 2. */
    var unused2: Int? = null,
    /** Opcode 3. */
    var minSpeed: Int = 0,
    var maxSpeed: Int = 0,
    /** Opcode 4. */
    var speedUpdateType: Int = 0,
    var speedFallOffStep: Int = 0,
    /** Opcode 5: one raw short setting both the minimum and the maximum size. */
    var size: Int? = null,
    /** Opcode 6: the two ends of the start colour range, as `0xAARRGGBB`. */
    var minStartColour: Int = 0,
    var maxStartColour: Int = 0,
    /** Opcode 7. */
    var minLifetime: Int = 0,
    var maxLifetime: Int = 0,
    /** Opcode 8. */
    var minParticleRate: Int = 0,
    var maxParticleRate: Int = 0,
    /** Opcode 9: particles (index 27 archive 1) the producer also spawns. */
    var secondaryParticles: IntArray? = null,
    /** Opcode 10: the particles (index 27 archive 1) the producer spawns. */
    var particles: IntArray? = null,
    /** Opcode 12. */
    var lowestDisplayPlane: Int = -2,
    /** Opcode 13. */
    var highestDisplayPlane: Int = -2,
    /** Opcode 14. */
    var updatesPerCycle: Int = 0,
    /** Opcode 15: the material the particles are drawn with. */
    var materialId: Int = -1,
    /** Opcode 16's four values, raw: the client reads the first and the last as `byte == 1`. */
    var activeFirst: Int? = null,
    var emissionEndTime: Int? = null,
    var lifetime: Int? = null,
    var periodic: Int? = null,
    /** Opcode 17: the producer to fall back to when textured particles are off. */
    var nonTexturedProducerId: Int = -1,
    /** Opcode 18: the colour the particles fade towards, as `0xAARRGGBB`. */
    var faceColourRgb: Int = 0,
    /** Opcode 19: the particle detail setting this producer needs to be drawn at all. */
    var minimumSetting: Int = 0,
    /** Opcode 20: how much of a particle's life is spent fading its colour, as a percentage. */
    var colourFading: Int = 100,
    /** Opcode 21: the same for its alpha. */
    var alphaFading: Int = 100,
    /** Opcode 22. */
    var endSpeed: Int = -1,
    /** Opcode 23: how much of a particle's life is spent changing speed, as a percentage. */
    var speedChange: Int = 100,
    /** Opcode 24 clears this: each channel of the start colour is then varied independently. */
    var uniformColourVariance: Boolean = true,
    /** Opcode 25: the effective vertex uids the producer's particles are pushed about by. */
    var effectiveVertexUids: IntArray? = null,
    /** Opcode 26 clears this. The client reads it and nothing looks at it. */
    var unusedOpcode26: Boolean = true,
    /** Opcode 27: the size particles end at, as the file's raw short. */
    var endSize: Int? = null,
    /** Opcode 28: how much of a particle's life is spent changing size, as a percentage. */
    var sizeChange: Int = 100,
    /** Opcode 29's short, which the client reads and throws away. */
    var unused29: Int? = null,
    /** Opcode 30 sets this. */
    var textured: Boolean = false,
    /** Opcode 31: the two ends of the size range, as the file's raw shorts. */
    var minSize: Int? = null,
    var maxSize: Int? = null,
    /** Opcode 32 clears this. */
    var adjustsLightIntensity: Boolean = true,
    /** Opcode 33 sets this. */
    var killOverlapping: Boolean = false,
    /** Opcode 34 clears this. */
    var killAboveSurface: Boolean = true
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
