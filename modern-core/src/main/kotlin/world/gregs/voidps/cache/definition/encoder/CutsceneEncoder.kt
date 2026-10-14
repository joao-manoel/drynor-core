package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.CutsceneActionRecord
import world.gregs.voidps.cache.definition.data.CutsceneCameraMovementRecord
import world.gregs.voidps.cache.definition.data.CutsceneDefinition
import world.gregs.voidps.cache.definition.data.CutsceneEntityMovementRecord

/**
 * Writes an index 35 cutscene back, byte for byte, from what
 * [world.gregs.voidps.cache.definition.decoder.CutsceneDecoder] read.
 *
 * The file has no opcode ordering to reproduce past its two entry header, so the whole thing is one
 * straight pass in the decoder's order. Every field the decoder kept is raw, so nothing is re-derived
 * here; a field a record's [CutsceneActionRecord.type] needs and does not carry is an error naming the
 * cutscene, the action and the field rather than a null pointer or a short file.
 */
class CutsceneEncoder : DefinitionEncoder<CutsceneDefinition> {

    override fun Writer.encode(definition: CutsceneDefinition) {
        // The header the client's `readValueLoop` reads: opcode 0 carries the viewport, 255 ends it.
        writeByte(0)
        writeShort(definition.viewportHeight)
        writeShort(definition.viewportWidth)
        writeByte(255)

        val areas = definition.areas ?: emptyList()
        writeByte(areas.size)
        for (area in areas) {
            writeInt(area.position)
            writeByte(area.width)
            writeByte(area.length)
            writeByte(area.cutscenePlane)
            writeByte(area.chunkBaseX)
            writeByte(area.chunkBaseY)
            writeByte(area.rotation)
        }

        val cameraMovements = definition.cameraMovements ?: emptyList()
        writeSmart(cameraMovements.size)
        for (movement in cameraMovements) {
            encodeCameraMovement(definition.id, movement)
        }

        val entities = definition.entities ?: emptyList()
        writeSmart(entities.size)
        for (entity in entities) {
            writeByte(entity.type)
            if (entity.type == 0) {
                val npcId = entity.npcId ?: error("Cutscene ${definition.id} has a type 0 entity with no npcId.")
                writeBigSmart(npcId)
            }
            writeString(entity.label)
        }

        val objects = definition.objects ?: emptyList()
        writeSmart(objects.size)
        for (obj in objects) {
            writeBigSmart(obj.locId)
            writeByte(obj.locShape)
        }

        val entityMovements = definition.entityMovements ?: emptyList()
        writeSmart(entityMovements.size)
        for (movement in entityMovements) {
            encodeEntityMovement(definition.id, movement)
        }

        val actions = definition.actions ?: emptyList()
        writeSmart(actions.size)
        for (action in actions) {
            writeByte(action.type)
            writeShort(action.lengthInCycles)
            encodeAction(definition.id, action)
        }
    }

    private fun Writer.encodeCameraMovement(id: Int, movement: CutsceneCameraMovementRecord) {
        val keyframes = movement.x.size
        require(
            movement.z.size == keyframes && movement.y.size == keyframes && movement.targetX.size == keyframes &&
                movement.targetZ.size == keyframes && movement.targetY.size == keyframes &&
                movement.timestamp.size == keyframes
        ) { "Cutscene $id has a camera movement whose keyframe arrays are different lengths." }
        writeSmart(keyframes)
        for (index in 0 until keyframes) {
            writeShort(movement.x[index])
            writeShort(movement.z[index])
            writeShort(movement.y[index])
            writeShort(movement.targetX[index])
            writeShort(movement.targetZ[index])
            writeShort(movement.targetY[index])
            writeShort(movement.timestamp[index])
        }
    }

    private fun Writer.encodeEntityMovement(id: Int, movement: CutsceneEntityMovementRecord) {
        val steps = movement.movementTypes.size
        require(movement.x.size == steps && movement.y.size == steps) {
            "Cutscene $id has an entity movement whose step arrays are different lengths."
        }
        writeSmart(steps)
        for (index in 0 until steps) {
            writeByte(movement.movementTypes[index])
            writeShort(movement.x[index])
            writeShort(movement.y[index])
        }
    }

    private fun Writer.encodeAction(id: Int, action: CutsceneActionRecord) {
        fun int(name: String, value: Int?): Int = value ?: error("Cutscene $id action ${action.type} has no \"$name\".")
        fun string(name: String, value: String?): String = value ?: error("Cutscene $id action ${action.type} has no \"$name\".")
        when (action.type) {
            0 -> {
                writeShort(int("localX", action.localX))
                writeShort(int("moveZ", action.moveZ))
                writeShort(int("localY", action.localY))
                writeShort(int("angleX", action.angleX))
                writeShort(int("angleY", action.angleY))
            }
            1 -> {
                writeShort(int("positionMovementIndex", action.positionMovementIndex))
                writeShort(int("lookAtMovementIndex", action.lookAtMovementIndex))
                writeShort(int("positionKeyframe", action.positionKeyframe))
                writeShort(int("lookAtKeyframe", action.lookAtKeyframe))
                writeShort(int("splineSpeedStart", action.splineSpeedStart))
                writeShort(int("splineSpeedEnd", action.splineSpeedEnd))
            }
            10 -> {
                writeShort(int("entityIndex", action.entityIndex))
                writeInt(int("packedPos", action.packedPos))
                writeByte(int("plane", action.plane))
                // The signed smart the client's `readSmart` reads; see the decoder.
                writeSignedSmart(int("direction", action.direction))
            }
            11 -> writeShort(int("entityIndex", action.entityIndex))
            12 -> {
                writeShort(int("entityIndex", action.entityIndex))
                writeShort(int("movementIndex", action.movementIndex))
                writeByte(int("plane", action.plane))
            }
            13 -> {
                writeShort(int("entityIndex", action.entityIndex))
                writeString(string("text", action.text))
                writeInt(int("colour", action.colour))
                writeShort(int("duration", action.duration))
            }
            14 -> {
                writeShort(int("entityIndex", action.entityIndex))
                writeBigSmart(int("movementAnimationId", action.movementAnimationId))
                writeInt(int("seqFlag", action.seqFlag))
            }
            15 -> {
                writeShort(int("entityIndex", action.entityIndex))
                val flags = int("hitmarkFlags", action.hitmarkFlags)
                writeByte(flags)
                if ((flags and 0x1) != 0) {
                    writeShort(int("hitsplatId", action.hitsplatId))
                    writeShort(int("hitText", action.hitText))
                }
                if ((flags and 0x2) != 0) {
                    writeShort(int("soakHitsplatId", action.soakHitsplatId))
                    writeShort(int("soakText", action.soakText))
                }
                if ((flags and 0x4) != 0) {
                    writeShort(int("currentHealth", action.currentHealth))
                    writeShort(int("maxHealth", action.maxHealth))
                }
            }
            16 -> {
                writeShort(int("entityIndex", action.entityIndex))
                writeShort(int("rotation", action.rotation))
            }
            17 -> {
                writeShort(int("gfxId", action.gfxId))
                writeShort(int("rotation", action.rotation))
                writeByte(int("displayHeight", action.displayHeight))
                writeShort(int("entityIndex", action.entityIndex))
                writeByte(int("spotAnimationIndex", action.spotAnimationIndex))
                writeShort(int("adjustmentType", action.adjustmentType))
            }
            20 -> {
                writeShort(int("objectIndex", action.objectIndex))
                writeInt(int("packedPos", action.packedPos))
                writeByte(int("plane", action.plane))
                writeByte(int("rotation", action.rotation))
            }
            21 -> writeShort(int("objectIndex", action.objectIndex))
            22 -> {
                writeShort(int("objectIndex", action.objectIndex))
                writeBigSmart(int("sequenceId", action.sequenceId))
            }
            30 -> {
                writeShort(int("musicId", action.musicId))
                writeByte(int("volume", action.volume))
            }
            31, 33 -> {
                writeShort(int("soundId", action.soundId))
                writeByte(int("volume", action.volume))
                writeByte(int("sampleRate", action.sampleRate))
                writeByte(int("timesRepeated", action.timesRepeated))
            }
            32 -> {
                writeShort(int("jingleId", action.jingleId))
                writeByte(int("volume", action.volume))
            }
            40 -> {
                writeShort(int("duration", action.duration))
                writeInt(int("colour", action.colour))
            }
            41 -> {
                writeShort(int("absX", action.absX))
                writeShort(int("absY", action.absY))
                writeString(string("text", action.text))
                writeInt(int("colour", action.colour))
                writeShort(int("duration", action.duration))
            }
            42 -> {
                writeShort(int("gfxId", action.gfxId))
                writeShort(int("rotation", action.rotation))
                writeByte(int("displayHeight", action.displayHeight))
                writeInt(int("packedPos", action.packedPos))
                writeByte(int("plane", action.plane))
            }
            50, 51, 52, 53 -> {
                val sourceIsEntity = action.type == 50 || action.type == 53
                val targetIsEntity = action.type == 50 || action.type == 51
                if (sourceIsEntity) {
                    writeShort(int("sourceEntityIndex", action.sourceEntityIndex))
                } else {
                    writeInt(int("packedPos", action.packedPos))
                }
                if (targetIsEntity) {
                    writeShort(int("targetEntityIndex", action.targetEntityIndex))
                } else {
                    writeInt(int("packedTargetPos", action.packedTargetPos))
                }
                if (!sourceIsEntity && !targetIsEntity) {
                    writeByte(int("plane", action.plane))
                }
                writeShort(int("gfxId", action.gfxId))
                writeByte(int("startHeight", action.startHeight))
                writeByte(int("endHeight", action.endHeight))
                writeMedium(int("duration", action.duration))
                writeShort(int("angle", action.angle))
                writeByte(int("slope", action.slope))
            }
            60, 61 -> {
                writeShort(int("variableId", action.variableId))
                writeInt(int("value", action.value))
            }
            70 -> {
                writeString(string("text", action.text))
                writeShort(int("scriptIntParam", action.scriptIntParam))
            }
            255 -> {}
            else -> error("Cutscene $id carries unknown action ${action.type}.")
        }
    }
}
