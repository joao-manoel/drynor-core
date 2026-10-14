package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.encoder.BASEncoder

/**
 * Base Animation Set (BAS / Render Animations) Definition
 *
 * Stores movement animation references for players and NPCs,
 * including stand, walk, run, and teleport animations with
 * directional variants and turn animations.
 *
 * The fields below the animations are the rest of the client's `BasType`: the model box, the
 * per-slot obj transforms, the camera easing values and the random stand table. They were read and
 * thrown away until the encoder needed them; the names follow the client's.
 */
data class BASDefinition(
    override var id: Int = -1,

    // Stand animations
    var standAnimation: Int = -1,
    var standTurn1: Int = -1,
    var standTurn2: Int = -1,

    // Walk animations
    var walkAnimation: Int = -1,
    var walkDir1: Int = -1,
    var walkDir2: Int = -1,
    var walkDir3: Int = -1,
    var walkTurn1: Int = -1,
    var walkTurn2: Int = -1,

    // Run animations
    var runAnimation: Int = -1,
    var runDir1: Int = -1,
    var runDir2: Int = -1,
    var runDir3: Int = -1,
    var runTurn1: Int = -1,
    var runTurn2: Int = -1,

    // Teleport animations
    var teleportAnimation: Int = -1,
    var teleDir1: Int = -1,
    var teleDir2: Int = -1,
    var teleDir3: Int = -1,
    var teleTurn1: Int = -1,
    var teleTurn2: Int = -1,

    /** Opcode 26, `(byte * 4)` widened back through a short. */
    var modelWidth: Int = 0,
    var modelLength: Int = 0,
    /** Opcode 27, per equipment slot: translate x/y/z then rotate x/y/z. Absent in 727. */
    var objVerticeTransformations: Array<IntArray?>? = null,
    /** Opcode 28, per equipment slot; 255 reads back as -1. Absent in 727. */
    var objVisibility: IntArray? = null,
    var yawAcceleration: Int = 0,
    var yawMaxVelocity: Int = 0,
    var rollAcceleration: Int = 0,
    var rollMaxVelocity: Int = 0,
    var rollTargetAngle: Int = 0,
    var pitchAcceleration: Int = 0,
    var pitchMaxVelocity: Int = 0,
    var pitchTargetAngle: Int = 0,
    /** Opcode 37, read and unused by the client. */
    var unused: Int = -1,
    /** Opcodes 43 and 44, read and unused by the client. */
    var unused43: Int = -1,
    var unused44: Int = -1,
    var iconHeightOverride: Int = -1,
    /** Opcode 52: the stand animations picked at random, and their weights. */
    var randomStandSequences: IntArray? = null,
    var randomStandSequenceChances: IntArray? = null,
    /** Opcode 53. */
    var rendersShadow: Boolean = true,
    /** Opcode 54, both `byte shl 6`. */
    var hillRotateX: Int = 0,
    var hillRotateZ: Int = 0,
    /** Opcode 55, per equipment slot. Absent in 727. */
    var turnAngleAdjustment: IntArray? = null,
    /** Opcode 56, per equipment slot, three directions. Absent in 727. */
    var projectionOffset: Array<IntArray?>? = null,
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null

    /**
     * Opcodes 27, 55 and 56 are one record per populated slot, not a field written twice.
     *
     * @see BASEncoder
     */
    override fun repeats(opcode: Int): Boolean = opcode == 27 || opcode == 55 || opcode == 56

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BASDefinition

        if (id != other.id) return false
        if (standAnimation != other.standAnimation) return false
        if (standTurn1 != other.standTurn1) return false
        if (standTurn2 != other.standTurn2) return false
        if (walkAnimation != other.walkAnimation) return false
        if (walkDir1 != other.walkDir1) return false
        if (walkDir2 != other.walkDir2) return false
        if (walkDir3 != other.walkDir3) return false
        if (walkTurn1 != other.walkTurn1) return false
        if (walkTurn2 != other.walkTurn2) return false
        if (runAnimation != other.runAnimation) return false
        if (runDir1 != other.runDir1) return false
        if (runDir2 != other.runDir2) return false
        if (runDir3 != other.runDir3) return false
        if (runTurn1 != other.runTurn1) return false
        if (runTurn2 != other.runTurn2) return false
        if (teleportAnimation != other.teleportAnimation) return false
        if (teleDir1 != other.teleDir1) return false
        if (teleDir2 != other.teleDir2) return false
        if (teleDir3 != other.teleDir3) return false
        if (teleTurn1 != other.teleTurn1) return false
        if (teleTurn2 != other.teleTurn2) return false
        if (modelWidth != other.modelWidth) return false
        if (modelLength != other.modelLength) return false
        if (!objVerticeTransformations.contentDeepEquals(other.objVerticeTransformations)) return false
        if (!objVisibility.contentEquals(other.objVisibility)) return false
        if (yawAcceleration != other.yawAcceleration) return false
        if (yawMaxVelocity != other.yawMaxVelocity) return false
        if (rollAcceleration != other.rollAcceleration) return false
        if (rollMaxVelocity != other.rollMaxVelocity) return false
        if (rollTargetAngle != other.rollTargetAngle) return false
        if (pitchAcceleration != other.pitchAcceleration) return false
        if (pitchMaxVelocity != other.pitchMaxVelocity) return false
        if (pitchTargetAngle != other.pitchTargetAngle) return false
        if (unused != other.unused) return false
        if (unused43 != other.unused43) return false
        if (unused44 != other.unused44) return false
        if (iconHeightOverride != other.iconHeightOverride) return false
        if (!randomStandSequences.contentEquals(other.randomStandSequences)) return false
        if (!randomStandSequenceChances.contentEquals(other.randomStandSequenceChances)) return false
        if (rendersShadow != other.rendersShadow) return false
        if (hillRotateX != other.hillRotateX) return false
        if (hillRotateZ != other.hillRotateZ) return false
        if (!turnAngleAdjustment.contentEquals(other.turnAngleAdjustment)) return false
        if (!projectionOffset.contentDeepEquals(other.projectionOffset)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + standAnimation
        result = 31 * result + standTurn1
        result = 31 * result + standTurn2
        result = 31 * result + walkAnimation
        result = 31 * result + walkDir1
        result = 31 * result + walkDir2
        result = 31 * result + walkDir3
        result = 31 * result + walkTurn1
        result = 31 * result + walkTurn2
        result = 31 * result + runAnimation
        result = 31 * result + runDir1
        result = 31 * result + runDir2
        result = 31 * result + runDir3
        result = 31 * result + runTurn1
        result = 31 * result + runTurn2
        result = 31 * result + teleportAnimation
        result = 31 * result + teleDir1
        result = 31 * result + teleDir2
        result = 31 * result + teleDir3
        result = 31 * result + teleTurn1
        result = 31 * result + teleTurn2
        result = 31 * result + modelWidth
        result = 31 * result + modelLength
        result = 31 * result + (objVerticeTransformations?.contentDeepHashCode() ?: 0)
        result = 31 * result + (objVisibility?.contentHashCode() ?: 0)
        result = 31 * result + yawAcceleration
        result = 31 * result + yawMaxVelocity
        result = 31 * result + rollAcceleration
        result = 31 * result + rollMaxVelocity
        result = 31 * result + rollTargetAngle
        result = 31 * result + pitchAcceleration
        result = 31 * result + pitchMaxVelocity
        result = 31 * result + pitchTargetAngle
        result = 31 * result + unused
        result = 31 * result + unused43
        result = 31 * result + unused44
        result = 31 * result + iconHeightOverride
        result = 31 * result + (randomStandSequences?.contentHashCode() ?: 0)
        result = 31 * result + (randomStandSequenceChances?.contentHashCode() ?: 0)
        result = 31 * result + rendersShadow.hashCode()
        result = 31 * result + hillRotateX
        result = 31 * result + hillRotateZ
        result = 31 * result + (turnAngleAdjustment?.contentHashCode() ?: 0)
        result = 31 * result + (projectionOffset?.contentDeepHashCode() ?: 0)
        return result
    }

    companion object {
        val EMPTY = BASDefinition()

        /**
         * Slots the per-slot opcodes (27, 55, 56) index into. The client sizes these by the
         * equipment defaults file's customisable obj slot count; nothing in 727 carries them.
         */
        const val SLOTS = 15
    }
}
