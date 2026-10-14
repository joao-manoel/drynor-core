package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.CUTSCENES
import world.gregs.voidps.cache.definition.data.CutsceneActionRecord
import world.gregs.voidps.cache.definition.data.CutsceneAreaRecord
import world.gregs.voidps.cache.definition.data.CutsceneCameraMovementRecord
import world.gregs.voidps.cache.definition.data.CutsceneDefinition
import world.gregs.voidps.cache.definition.data.CutsceneEntityMovementRecord
import world.gregs.voidps.cache.definition.data.CutsceneEntityRecord
import world.gregs.voidps.cache.definition.data.CutsceneObjectRecord

/**
 * Index 35, one cutscene per archive and one file (0) in each.
 *
 * Nothing in the file is opcode keyed past a two entry header, so - like
 * [WorldMapDetailsDecoder] - the whole file is read in one pass and [readLoop] just hands over to it.
 *
 * Reads exactly what the client's `CutsceneTypeList.isFullyCached`, `readValueLoop` and
 * `listCutsceneAction` read, in the same order and with the same widths, but keeps the raw values rather
 * than the client's derived ones so [world.gregs.voidps.cache.definition.encoder.CutsceneEncoder] can put
 * the file back byte for byte. Anything the client would tolerate but a re-encode could not reproduce is
 * an error rather than a silent loss.
 */
class CutsceneDecoder : DefinitionDecoder<CutsceneDefinition>(CUTSCENES) {

    override fun create(size: Int) = Array(size) { CutsceneDefinition(it) }

    /** One archive per cutscene, so the count is the archive count and not the usual archive * 256 + file. */
    override fun size(cache: Cache): Int = cache.lastArchiveId(index)

    override fun getArchive(id: Int) = id

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: CutsceneDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun CutsceneDefinition.read(opcode: Int, buffer: Reader) {
        readHeader(buffer)
        areas = List(buffer.readUnsignedByte()) {
            CutsceneAreaRecord(
                position = buffer.readInt(),
                width = buffer.readUnsignedByte(),
                length = buffer.readUnsignedByte(),
                cutscenePlane = buffer.readUnsignedByte(),
                chunkBaseX = buffer.readUnsignedByte(),
                chunkBaseY = buffer.readUnsignedByte(),
                rotation = buffer.readUnsignedByte()
            )
        }
        cameraMovements = List(buffer.readSmart()) { readCameraMovement(buffer) }
        entities = List(buffer.readSmart()) {
            val type = buffer.readUnsignedByte()
            CutsceneEntityRecord(
                type = type,
                npcId = if (type == 0) buffer.readBigSmart() else null,
                label = buffer.readString()
            )
        }
        objects = List(buffer.readSmart()) {
            CutsceneObjectRecord(locId = buffer.readBigSmart(), locShape = buffer.readUnsignedByte())
        }
        entityMovements = List(buffer.readSmart()) { readEntityMovement(buffer) }
        actions = List(buffer.readSmart()) { readAction(this, buffer) }
    }

    /**
     * The header: opcode 0 carries the viewport size and 255 ends it.
     *
     * Every other opcode reads nothing and loops forever in the client, so a file carrying one is
     * unplayable and rejected here rather than hanging. A second opcode 0 is rejected for a different
     * reason: the client would keep the last one and the encoder writes exactly one, so it is a value that
     * could not survive a round trip.
     */
    private fun CutsceneDefinition.readHeader(buffer: Reader) {
        var viewport = false
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 255) {
                return
            }
            if (opcode != 0) {
                error("Cutscene $id has header opcode $opcode, which reads nothing and loops forever in the client.")
            }
            if (viewport) {
                error("Cutscene $id lists its viewport twice; only the last survives, so it cannot be written back.")
            }
            viewport = true
            viewportHeight = buffer.readUnsignedShort()
            viewportWidth = buffer.readUnsignedShort()
        }
    }

    private fun readCameraMovement(buffer: Reader): CutsceneCameraMovementRecord {
        val keyframes = buffer.readSmart()
        val record = CutsceneCameraMovementRecord(
            x = IntArray(keyframes),
            z = IntArray(keyframes),
            y = IntArray(keyframes),
            targetX = IntArray(keyframes),
            targetZ = IntArray(keyframes),
            targetY = IntArray(keyframes),
            timestamp = IntArray(keyframes)
        )
        for (index in 0 until keyframes) {
            record.x[index] = buffer.readUnsignedShort()
            record.z[index] = buffer.readUnsignedShort()
            record.y[index] = buffer.readShort()
            record.targetX[index] = buffer.readUnsignedShort()
            record.targetZ[index] = buffer.readUnsignedShort()
            record.targetY[index] = buffer.readShort()
            record.timestamp[index] = buffer.readShort()
        }
        return record
    }

    private fun readEntityMovement(buffer: Reader): CutsceneEntityMovementRecord {
        val steps = buffer.readSmart()
        val record = CutsceneEntityMovementRecord(
            movementTypes = IntArray(steps),
            x = IntArray(steps),
            y = IntArray(steps)
        )
        for (index in 0 until steps) {
            record.movementTypes[index] = buffer.readUnsignedByte()
            record.x[index] = buffer.readUnsignedShort()
            record.y[index] = buffer.readUnsignedShort()
        }
        return record
    }

    /**
     * One action: its disk id, the cycle length every action starts with, and then a body decided by the id.
     *
     * The ids are `CutsceneActionType`'s second field, which is not the client's internal action number -
     * the dispatch in `listCutsceneAction` goes through the lookup, so 30 on disk is internal 18,
     * `CutscenePlaySong`.
     */
    private fun readAction(definition: CutsceneDefinition, buffer: Reader): CutsceneActionRecord {
        val type = buffer.readUnsignedByte()
        val record = CutsceneActionRecord(type = type, lengthInCycles = buffer.readUnsignedShort())
        when (type) {
            0 -> {
                record.localX = buffer.readUnsignedShort()
                record.moveZ = buffer.readUnsignedShort()
                record.localY = buffer.readUnsignedShort()
                record.angleX = buffer.readUnsignedShort()
                record.angleY = buffer.readUnsignedShort()
            }
            1 -> {
                record.positionMovementIndex = buffer.readUnsignedShort()
                record.lookAtMovementIndex = buffer.readUnsignedShort()
                record.positionKeyframe = buffer.readUnsignedShort()
                record.lookAtKeyframe = buffer.readUnsignedShort()
                record.splineSpeedStart = buffer.readUnsignedShort()
                record.splineSpeedEnd = buffer.readUnsignedShort()
            }
            10 -> {
                record.entityIndex = buffer.readUnsignedShort()
                record.packedPos = buffer.readInt()
                record.plane = buffer.readUnsignedByte()
                // The client's `readSmart`, which is the *signed* smart, not its `readUSmart`; the values
                // in the 727 files are 0..16383, an angle, and only the signed reading gives that range.
                record.direction = buffer.readSignedSmart()
            }
            11 -> record.entityIndex = buffer.readUnsignedShort()
            12 -> {
                record.entityIndex = buffer.readUnsignedShort()
                record.movementIndex = buffer.readUnsignedShort()
                record.plane = buffer.readUnsignedByte()
            }
            13 -> {
                record.entityIndex = buffer.readUnsignedShort()
                record.text = buffer.readString()
                record.colour = buffer.readInt()
                record.duration = buffer.readUnsignedShort()
            }
            14 -> {
                record.entityIndex = buffer.readUnsignedShort()
                record.movementAnimationId = buffer.readBigSmart()
                record.seqFlag = buffer.readInt()
            }
            15 -> {
                record.entityIndex = buffer.readUnsignedShort()
                val flags = buffer.readUnsignedByte()
                record.hitmarkFlags = flags
                if ((flags and 0x1) != 0) {
                    record.hitsplatId = buffer.readUnsignedShort()
                    record.hitText = buffer.readUnsignedShort()
                }
                if ((flags and 0x2) != 0) {
                    record.soakHitsplatId = buffer.readUnsignedShort()
                    record.soakText = buffer.readUnsignedShort()
                }
                if ((flags and 0x4) != 0) {
                    record.currentHealth = buffer.readUnsignedShort()
                    record.maxHealth = buffer.readUnsignedShort()
                }
            }
            16 -> {
                record.entityIndex = buffer.readUnsignedShort()
                record.rotation = buffer.readUnsignedShort()
            }
            17 -> {
                readSpotAnimation(record, buffer)
                record.entityIndex = buffer.readUnsignedShort()
                record.spotAnimationIndex = buffer.readUnsignedByte()
                record.adjustmentType = buffer.readUnsignedShort()
            }
            20 -> {
                record.objectIndex = buffer.readUnsignedShort()
                record.packedPos = buffer.readInt()
                record.plane = buffer.readUnsignedByte()
                record.rotation = buffer.readUnsignedByte()
            }
            21 -> record.objectIndex = buffer.readUnsignedShort()
            22 -> {
                record.objectIndex = buffer.readUnsignedShort()
                record.sequenceId = buffer.readBigSmart()
            }
            30 -> {
                record.musicId = buffer.readUnsignedShort()
                record.volume = buffer.readUnsignedByte()
            }
            31, 33 -> {
                record.soundId = buffer.readUnsignedShort()
                record.volume = buffer.readUnsignedByte()
                record.sampleRate = buffer.readUnsignedByte()
                record.timesRepeated = buffer.readUnsignedByte()
            }
            32 -> {
                record.jingleId = buffer.readUnsignedShort()
                record.volume = buffer.readUnsignedByte()
            }
            40 -> {
                record.duration = buffer.readUnsignedShort()
                record.colour = buffer.readInt()
            }
            41 -> {
                record.absX = buffer.readUnsignedShort()
                record.absY = buffer.readUnsignedShort()
                record.text = buffer.readString()
                record.colour = buffer.readInt()
                record.duration = buffer.readUnsignedShort()
            }
            42 -> {
                readSpotAnimation(record, buffer)
                record.packedPos = buffer.readInt()
                record.plane = buffer.readUnsignedByte()
            }
            50, 51, 52, 53 -> readProjectile(record, type, buffer)
            60, 61 -> {
                record.variableId = buffer.readUnsignedShort()
                record.value = buffer.readInt()
            }
            70 -> {
                record.text = buffer.readString()
                record.scriptIntParam = buffer.readUnsignedShort()
            }
            255 -> {}
            2, 3, 43 -> error(
                "Cutscene ${definition.id} carries action $type, which the client dispatches to a branch " +
                    "that reads nothing and then dereferences null; the file cannot be a working one."
            )
            else -> error("Cutscene ${definition.id} carries unknown action $type.")
        }
        return record
    }

    /** The client's `AbstractCutsceneSpotAnimation`, shared by actions 17 and 42 and read before their own fields. */
    private fun readSpotAnimation(record: CutsceneActionRecord, buffer: Reader) {
        record.gfxId = buffer.readUnsignedShort()
        record.rotation = buffer.readUnsignedShort()
        record.displayHeight = buffer.readUnsignedByte()
    }

    /**
     * The client's `CutsceneProjectile`, whose source and target are each either a packed coordinate or an
     * entity index. `listCutsceneAction` pairs the four disk ids with the two flags as 52 -> (0, 0),
     * 53 -> (1, 0), 51 -> (0, 1) and 50 -> (1, 1), source flag first, where 0 means a coordinate.
     */
    private fun readProjectile(record: CutsceneActionRecord, type: Int, buffer: Reader) {
        val sourceIsEntity = type == 50 || type == 53
        val targetIsEntity = type == 50 || type == 51
        if (sourceIsEntity) {
            record.sourceEntityIndex = buffer.readUnsignedShort()
        } else {
            record.packedPos = buffer.readInt()
        }
        if (targetIsEntity) {
            record.targetEntityIndex = buffer.readUnsignedShort()
        } else {
            record.packedTargetPos = buffer.readInt()
        }
        if (!sourceIsEntity && !targetIsEntity) {
            record.plane = buffer.readUnsignedByte()
        }
        record.gfxId = buffer.readUnsignedShort()
        record.startHeight = buffer.readUnsignedByte()
        record.endHeight = buffer.readUnsignedByte()
        record.duration = buffer.readUnsignedMedium()
        record.angle = buffer.readUnsignedShort()
        record.slope = buffer.readUnsignedByte()
    }
}
