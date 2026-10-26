package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only object sequence/frame/base closure and native conversion probe. */
object ZeahObjectAnimationAudit {
    fun audit(planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val objects = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val owners = sortedMapOf<Int, MutableSet<Int>>()
                for (record in objects) {
                    val objectId = record.getValue("runtimeId").jsonPrimitive.int
                    val bytes = requireNotNull(source.data(Index.OBJECTS, objectId ushr 8, objectId and 255))
                    check(sha256(bytes) == record.getValue("sourceSha256").jsonPrimitive.content)
                    OsrsObjectDependencies.parse(bytes).animations.forEach { raw ->
                        owners.getOrPut(raw + 20_000) { sortedSetOf() } += objectId
                    }
                }
                val reports = buildJsonArray {
                    owners.forEach { (sequenceId, objectIds) ->
                        val missingFrames = sortedSetOf<Int>()
                        val missingBases = sortedSetOf<Int>()
                        val frameFailures = mutableListOf<String>()
                        var soundCount = 0
                        var equipmentCount = 0
                        var soundIds = emptySet<Int>()
                        var equipmentIds = emptySet<Int>()
                        var frameCount = 0
                        var complete = false
                        var sequenceError = ""
                        val sequenceBytes = source.data(Index.ANIMATIONS, sequenceId ushr 7, sequenceId and 127)
                        if (sequenceBytes == null) sequenceError = "missing-source-sequence"
                        else try {
                            val deps = OsrsSequenceDependencies.parse(sequenceBytes)
                            soundCount = deps.sounds.size
                            equipmentCount = deps.equipment.size
                            soundIds = deps.sounds
                            equipmentIds = deps.equipment
                            frameCount = deps.frameIds.size
                            val frameSetMap = sortedMapOf<Int, Int>()
                            for (packed in deps.frameIds) {
                                val archive = (packed ushr 16) + 5000
                                val file = packed and 65535
                                if (archive !in 0..65534) {
                                    frameFailures += "frame-set-outside-native-range:$archive"
                                    continue
                                }
                                frameSetMap[archive] = archive
                                val frameBytes = source.data(Index.ANIMATION_FRAMES, archive, file)
                                if (frameBytes == null) {
                                    missingFrames += (archive shl 16) or file
                                    continue
                                }
                                try {
                                    val info = OsrsFrameDependencies.parseFrame(frameBytes)
                                    val baseId = GielenorVisualProfile.sourceBaseId(
                                        info.baseId, GielenorVisualProfile.forceOsrsSkeleton(sequenceId))
                                    val baseBytes = source.data(Index.ANIMATION_SKELETONS, baseId)
                                    if (baseBytes == null) {
                                        missingBases += baseId
                                        continue
                                    }
                                    val base = ZulrahAnimationAssets.decodeSourceBase(baseId, baseId, baseBytes)
                                    val baseWriter = BufferWriter(baseBytes.size + 1024)
                                    with(AnimationBaseEncoder()) { baseWriter.encode(base) }
                                    val nativeBase = AnimationBaseDefinition(baseId)
                                    AnimationBaseDecoder().readLoop(nativeBase, BufferReader(baseWriter.toArray()))
                                    check(nativeBase == base)
                                    val frame = OsrsAnimationConverter.convertFrame((archive shl 16) or file, frameBytes, base)
                                    val frameWriter = BufferWriter(frameBytes.size * 8 + 1024)
                                    with(AnimationFrameEncoder()) { frameWriter.encode(frame) }
                                    val nativeFrame = AnimationFrameDefinition(frame.id)
                                    AnimationFrameDecoder().readLoop(nativeFrame, BufferReader(frameWriter.toArray()))
                                    check(nativeFrame.baseId == frame.baseId && nativeFrame.flags.contentEquals(frame.flags) &&
                                        nativeFrame.values.contentEquals(frame.values))
                                } catch (error: Exception) {
                                    frameFailures += "$archive/$file:${error.javaClass.simpleName}:${error.message?.take(100)}"
                                }
                            }
                            if (missingFrames.isEmpty() && missingBases.isEmpty() && frameFailures.isEmpty() &&
                                soundCount == 0 && equipmentCount == 0) {
                                try {
                                    val converted = OsrsAnimationConverter.convertSequence(sequenceId, sequenceBytes, frameSetMap)
                                    val writer = BufferWriter(sequenceBytes.size * 8 + 1024)
                                    with(AnimationEncoder()) { writer.encode(converted) }
                                    val native = AnimationDefinition(sequenceId)
                                    AnimationDecoder().readLoop(native, BufferReader(writer.toArray()))
                                    check(native.frames.contentEquals(converted.frames) && native.durations.contentEquals(converted.durations))
                                    complete = true
                                } catch (error: Exception) {
                                    sequenceError = "${error.javaClass.simpleName}:${error.message?.take(160)}"
                                }
                            }
                        } catch (error: Exception) {
                            sequenceError = "${error.javaClass.simpleName}:${error.message?.take(160)}"
                        }
                        add(buildJsonObject {
                            put("sequenceRuntimeId", sequenceId)
                            put("objectRuntimeIds", buildJsonArray { objectIds.forEach { add(JsonPrimitive(it)) } })
                            put("sourceSequencePresent", sequenceBytes != null)
                            put("targetSameIdOccupied", target.data(Index.ANIMATIONS, sequenceId ushr 7, sequenceId and 127) != null)
                            put("referencedFrames", frameCount)
                            put("sequenceSoundIds", soundCount)
                            put("equipmentOverrides", equipmentCount)
                            put("sequenceSoundReferences", buildJsonArray { soundIds.sorted().forEach { add(JsonPrimitive(it)) } })
                            put("equipmentOverrideValues", buildJsonArray { equipmentIds.sorted().forEach { add(JsonPrimitive(it)) } })
                            put("missingSourceFrames", buildJsonArray { missingFrames.forEach { add(JsonPrimitive(it)) } })
                            put("missingSourceBases", buildJsonArray { missingBases.forEach { add(JsonPrimitive(it)) } })
                            put("frameConversionErrors", buildJsonArray { frameFailures.forEach { add(JsonPrimitive(it)) } })
                            put("sequenceError", sequenceError)
                            put("nativeConversionProbePassed", complete)
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("objectPlanSha256", sha256(planBytes))
                    put("objectSequences", owners.size)
                    put("sequencesPassingNativeProbe", reports.count { it.jsonObject.getValue("nativeConversionProbePassed").jsonPrimitive.content == "true" })
                    put("reports", reports)
                    put("writesCache", false)
                    put("clientAnimationValidated", false)
                    put("limitations", "Conversion probe uses existing OSRS 5000 frame-set/skeleton mapping. ID allocation, animation collision and actual client playback remain separate gates.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
