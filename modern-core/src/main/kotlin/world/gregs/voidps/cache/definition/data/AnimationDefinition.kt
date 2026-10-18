package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class AnimationDefinition(
    override var id: Int = -1,
    var durations: IntArray? = null,
    var frames: IntArray? = null,
    var loopOffset: Int = -1,
    var interleaveOrder: BooleanArray? = null,
    var priority: Int = 5,
    var leftHandItem: Int = -1,
    var rightHandItem: Int = -1,
    var maxLoops: Int = 99,
    var animatingPrecedence: Int = -1,
    var walkingPrecedence: Int = -1,
    var replayMode: Int = 2,
    var expressionFrames: IntArray? = null,
    var sounds: Array<IntArray?>? = null,
    var aBoolean691: Boolean = false,
    var tweened: Boolean = false,
    var useSounds: Boolean = false,
    var volumes: IntArray? = null,
    var primarySpeeds: IntArray? = null,
    var secondarySpeeds: IntArray? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    /**
     * The opcode 19 records in file order, as (frame, volume) pairs. [volumes] holds the same values indexed
     * by frame, but not which frames the file wrote a record for - a frame may be given its default 255.
     */
    var soundVolumeRecords: IntArray? = null,
    /**
     * The opcode 20 records in file order, as (frame, primary, secondary) triples. [primarySpeeds] and
     * [secondarySpeeds] hold the same values indexed by frame; see [soundVolumeRecords].
     */
    var soundSpeedRecords: IntArray? = null,
    /**
     * The opcode 3 interleave indices in file order, kept only when they are not the ascending distinct set
     * [interleaveOrder] holds - ten seqs list an index twice.
     */
    var interleaveRecords: IntArray? = null,
    /** Opcode 16, a flag with no payload that the client of this revision reads and discards. */
    var unusedFlag16: Boolean = false
) : Definition, Extra, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null

    fun getDurationMillis(): Int = getEmoteTime() * 20
    val emoteGameTicks: Int get() = getDurationMillis() / 600

    /** Opcodes 19 and 20 are one record per sound frame, not a field written twice. */
    override fun repeats(opcode: Int): Boolean = opcode == 19 || opcode == 20

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnimationDefinition

        if (id != other.id) return false
        if (durations != null) {
            if (other.durations == null) return false
            if (!durations.contentEquals(other.durations)) return false
        } else if (other.durations != null) return false
        if (frames != null) {
            if (other.frames == null) return false
            if (!frames.contentEquals(other.frames)) return false
        } else if (other.frames != null) return false
        if (loopOffset != other.loopOffset) return false
        if (interleaveOrder != null) {
            if (other.interleaveOrder == null) return false
            if (!interleaveOrder.contentEquals(other.interleaveOrder)) return false
        } else if (other.interleaveOrder != null) return false
        if (priority != other.priority) return false
        if (leftHandItem != other.leftHandItem) return false
        if (rightHandItem != other.rightHandItem) return false
        if (maxLoops != other.maxLoops) return false
        if (animatingPrecedence != other.animatingPrecedence) return false
        if (walkingPrecedence != other.walkingPrecedence) return false
        if (replayMode != other.replayMode) return false
        if (expressionFrames != null) {
            if (other.expressionFrames == null) return false
            if (!expressionFrames.contentEquals(other.expressionFrames)) return false
        } else if (other.expressionFrames != null) return false
        if (sounds != null) {
            if (other.sounds == null) return false
            if (!sounds.contentDeepEquals(other.sounds)) return false
        } else if (other.sounds != null) return false
        if (aBoolean691 != other.aBoolean691) return false
        if (tweened != other.tweened) return false
        if (useSounds != other.useSounds) return false
        if (volumes != null) {
            if (other.volumes == null) return false
            if (!volumes.contentEquals(other.volumes)) return false
        } else if (other.volumes != null) return false
        if (primarySpeeds != null) {
            if (other.primarySpeeds == null) return false
            if (!primarySpeeds.contentEquals(other.primarySpeeds)) return false
        } else if (other.primarySpeeds != null) return false
        if (secondarySpeeds != null) {
            if (other.secondarySpeeds == null) return false
            if (!secondarySpeeds.contentEquals(other.secondarySpeeds)) return false
        } else if (other.secondarySpeeds != null) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false
        if (soundVolumeRecords != null) {
            if (other.soundVolumeRecords == null) return false
            if (!soundVolumeRecords.contentEquals(other.soundVolumeRecords)) return false
        } else if (other.soundVolumeRecords != null) return false
        if (soundSpeedRecords != null) {
            if (other.soundSpeedRecords == null) return false
            if (!soundSpeedRecords.contentEquals(other.soundSpeedRecords)) return false
        } else if (other.soundSpeedRecords != null) return false
        if (interleaveRecords != null) {
            if (other.interleaveRecords == null) return false
            if (!interleaveRecords.contentEquals(other.interleaveRecords)) return false
        } else if (other.interleaveRecords != null) return false
        if (unusedFlag16 != other.unusedFlag16) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (durations?.contentHashCode() ?: 0)
        result = 31 * result + (frames?.contentHashCode() ?: 0)
        result = 31 * result + loopOffset
        result = 31 * result + (interleaveOrder?.contentHashCode() ?: 0)
        result = 31 * result + priority
        result = 31 * result + leftHandItem
        result = 31 * result + rightHandItem
        result = 31 * result + maxLoops
        result = 31 * result + animatingPrecedence
        result = 31 * result + walkingPrecedence
        result = 31 * result + replayMode
        result = 31 * result + (expressionFrames?.contentHashCode() ?: 0)
        result = 31 * result + (sounds?.contentDeepHashCode() ?: 0)
        result = 31 * result + aBoolean691.hashCode()
        result = 31 * result + tweened.hashCode()
        result = 31 * result + useSounds.hashCode()
        result = 31 * result + (volumes?.contentHashCode() ?: 0)
        result = 31 * result + (primarySpeeds?.contentHashCode() ?: 0)
        result = 31 * result + (secondarySpeeds?.contentHashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + extras.hashCode()
        result = 31 * result + (soundVolumeRecords?.contentHashCode() ?: 0)
        result = 31 * result + (soundSpeedRecords?.contentHashCode() ?: 0)
        result = 31 * result + (interleaveRecords?.contentHashCode() ?: 0)
        result = 31 * result + unusedFlag16.hashCode()
        return result
    }

    /** Returns total duration of all frames in client cycles (20ms). */
    fun getEmoteTime(): Int = durations?.sum() ?: 0

    companion object {
        val EMPTY = AnimationDefinition()
    }

}
