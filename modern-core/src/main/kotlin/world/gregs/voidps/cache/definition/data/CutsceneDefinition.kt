package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord

/**
 * One cutscene: index 35, one archive per cutscene and one file (0) in each.
 *
 * The client's `CutsceneTypeList.isFullyCached` reads a file in a fixed order - a small opcode header
 * carrying the viewport size, then five counted record lists and a counted action list - so nothing here
 * is opcode keyed and the whole file is read in one pass by
 * [world.gregs.voidps.cache.definition.decoder.CutsceneDecoder].
 *
 * Every field is the *raw* value the file holds rather than the value the client derives from it, which is
 * what lets [world.gregs.voidps.cache.definition.encoder.CutsceneEncoder] write the file back byte for
 * byte: an area keeps its packed position int rather than the plane/region triple the client unpacks, and
 * a camera keyframe keeps the unsigned shorts rather than the client's `- 5120` world coordinates.
 */
data class CutsceneDefinition(
    override var id: Int = -1,
    var viewportHeight: Int = -1,
    var viewportWidth: Int = -1,
    var areas: List<CutsceneAreaRecord>? = null,
    var cameraMovements: List<CutsceneCameraMovementRecord>? = null,
    var entities: List<CutsceneEntityRecord>? = null,
    var objects: List<CutsceneObjectRecord>? = null,
    var entityMovements: List<CutsceneEntityMovementRecord>? = null,
    var actions: List<CutsceneActionRecord>? = null
) : Definition

/**
 * One map area a cutscene builds its scene out of; the client's `CutsceneArea`.
 *
 * [position] is the packed int the file holds. The client unpacks it as `plane = position ushr 28`,
 * `regionX = (position ushr 14) and 0x3fff` and `regionY = position and 0x3fff`, which loses nothing -
 * but the packed int is the field the file actually carries, so it is what is kept.
 */
data class CutsceneAreaRecord(
    var position: Int = 0,
    var width: Int = 0,
    var length: Int = 0,
    var cutscenePlane: Int = 0,
    var chunkBaseX: Int = 0,
    var chunkBaseY: Int = 0,
    var rotation: Int = 0
) : DefinitionRecord

/**
 * One camera spline; the client's `CutsceneCameraMovement`.
 *
 * The keyframes are parallel arrays rather than a list of records because a definition field cannot be a
 * nested list. [x], [z], [targetX] and [targetZ] are the file's unsigned shorts, *not* the client's
 * `- 5120` world coordinates; [y], [targetY] and [timestamp] are its signed shorts.
 */
data class CutsceneCameraMovementRecord(
    var x: IntArray = IntArray(0),
    var z: IntArray = IntArray(0),
    var y: IntArray = IntArray(0),
    var targetX: IntArray = IntArray(0),
    var targetZ: IntArray = IntArray(0),
    var targetY: IntArray = IntArray(0),
    var timestamp: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CutsceneCameraMovementRecord) {
            return false
        }
        return x.contentEquals(other.x) &&
            z.contentEquals(other.z) &&
            y.contentEquals(other.y) &&
            targetX.contentEquals(other.targetX) &&
            targetZ.contentEquals(other.targetZ) &&
            targetY.contentEquals(other.targetY) &&
            timestamp.contentEquals(other.timestamp)
    }

    override fun hashCode(): Int {
        var result = x.contentHashCode()
        result = 31 * result + z.contentHashCode()
        result = 31 * result + y.contentHashCode()
        result = 31 * result + targetX.contentHashCode()
        result = 31 * result + targetZ.contentHashCode()
        result = 31 * result + targetY.contentHashCode()
        result = 31 * result + timestamp.contentHashCode()
        return result
    }
}

/**
 * One actor a cutscene moves about; the client's `CutsceneEntity`.
 *
 * [type] 0 is an npc and carries [npcId]; anything else is the local player and carries none. [label] is
 * the string that follows either way - the client reads it and throws it away, so it survives nowhere but
 * here.
 */
data class CutsceneEntityRecord(
    var type: Int = 0,
    var npcId: Int? = null,
    var label: String = ""
) : DefinitionRecord

/** One scenery object a cutscene adds, replaces or destroys; the client's `CutsceneObject`. */
data class CutsceneObjectRecord(
    var locId: Int = -1,
    var locShape: Int = 0
) : DefinitionRecord

/**
 * One walked path; the client's `CutsceneEntityMovement`.
 *
 * Parallel arrays for the same reason as [CutsceneCameraMovementRecord]. The client packs [x] and [y] into
 * a single int per step; the file holds them as two unsigned shorts and so does this.
 */
data class CutsceneEntityMovementRecord(
    var movementTypes: IntArray = IntArray(0),
    var x: IntArray = IntArray(0),
    var y: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CutsceneEntityMovementRecord) {
            return false
        }
        return movementTypes.contentEquals(other.movementTypes) && x.contentEquals(other.x) && y.contentEquals(other.y)
    }

    override fun hashCode(): Int {
        var result = movementTypes.contentHashCode()
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        return result
    }
}

/**
 * One timed action; the client's `CutsceneAction` and its thirty odd subclasses.
 *
 * Flat rather than a class per action because a definition field cannot be a list of a sealed hierarchy,
 * and it reads well anyway: a record only writes the fields its [type] uses, so a `PlaySong` is
 * `{"type": 30, "lengthInCycles": 6, "musicId": 62, "volume": 255}`. Fields are shared wherever two
 * actions mean the same thing - [entityIndex], [plane], [packedPos], [volume], [text] - rather than
 * spelling out one per action.
 *
 * [type] is the id the file holds, which is *not* the client's internal action number: 30 is `PlaySong`
 * on disk and 18 inside `CutsceneActionType`. Ids 2, 3 and 43 map to client branches that read nothing
 * and then dereference null, so they cannot appear in a working file and the decoder rejects them.
 */
data class CutsceneActionRecord(
    var type: Int = -1,
    var lengthInCycles: Int = 0,
    // 0 UncenteredCameraMovement
    var localX: Int? = null,
    var moveZ: Int? = null,
    var localY: Int? = null,
    var angleX: Int? = null,
    var angleY: Int? = null,
    // 1 DirectCameraTransition
    var positionMovementIndex: Int? = null,
    var lookAtMovementIndex: Int? = null,
    var positionKeyframe: Int? = null,
    var lookAtKeyframe: Int? = null,
    var splineSpeedStart: Int? = null,
    var splineSpeedEnd: Int? = null,
    // 10 MoveEntity, 11 ResetEntity, 12 BasicMovement, 13 SetHintDetails, 14 AnimatedMovement,
    // 16 RotateEntity, 17 EntitySpotAnimation
    var entityIndex: Int? = null,
    var movementIndex: Int? = null,
    var movementAnimationId: Int? = null,
    var seqFlag: Int? = null,
    var direction: Int? = null,
    var rotation: Int? = null,
    // 15 ApplyHitmark
    var hitmarkFlags: Int? = null,
    var hitsplatId: Int? = null,
    var hitText: Int? = null,
    var soakHitsplatId: Int? = null,
    var soakText: Int? = null,
    var currentHealth: Int? = null,
    var maxHealth: Int? = null,
    // 20 ReplaceObject, 21 DestroyObject, 22 AnimateObject
    var objectIndex: Int? = null,
    var sequenceId: Int? = null,
    // 10 MoveEntity, 20 ReplaceObject, 41 TileMessage, 42 PositionedGfx, 50-53 Projectile
    var packedPos: Int? = null,
    var packedTargetPos: Int? = null,
    var plane: Int? = null,
    var absX: Int? = null,
    var absY: Int? = null,
    // 17 EntitySpotAnimation, 42 PositionedGfx, 50-53 Projectile
    var gfxId: Int? = null,
    var displayHeight: Int? = null,
    var spotAnimationIndex: Int? = null,
    var adjustmentType: Int? = null,
    // 50-53 Projectile
    var sourceEntityIndex: Int? = null,
    var targetEntityIndex: Int? = null,
    var startHeight: Int? = null,
    var endHeight: Int? = null,
    var angle: Int? = null,
    var slope: Int? = null,
    // 30 PlaySong, 31 PlaySynth, 32 PlayJingle, 33 PlayVorbis
    var musicId: Int? = null,
    var jingleId: Int? = null,
    var soundId: Int? = null,
    var volume: Int? = null,
    var sampleRate: Int? = null,
    var timesRepeated: Int? = null,
    // 13 SetHintDetails, 40 FadeScreen, 41 TileMessage, 50-53 Projectile, 70 ExecuteScript
    var colour: Int? = null,
    var duration: Int? = null,
    var text: String? = null,
    var scriptIntParam: Int? = null,
    // 60 SetVariable, 61 SetBitVariable
    var variableId: Int? = null,
    var value: Int? = null
) : DefinitionRecord
