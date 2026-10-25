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
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only feasibility gate for exact static objects; excluded features are never silently stripped. */
object ZeahStaticObjectAudit {
    fun audit(planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        require(plan.getValue("scopeComplete").jsonPrimitive.content == "false")
        require(plan.getValue("maxPlacementObjectId").jsonPrimitive.int == ZeahPlacementIds.MAX_ID)
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val objects = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        val objectMap = objects.associate { it.getValue("runtimeId").jsonPrimitive.int to it.getValue("plannedTargetId").jsonPrimitive.int }
        check(objectMap.size == objects.size && objectMap.values.toSet().size == objects.size)
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val dependencies = objects.associate { record ->
                    val id = record.getValue("runtimeId").jsonPrimitive.int
                    val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                    check(sha256(bytes) == record.getValue("sourceSha256").jsonPrimitive.content) { "Donor object changed: $id" }
                    id to OsrsObjectDependencies.parse(bytes)
                }
                val modelIds = dependencies.values.flatMap { it.models.map { raw -> raw + 200_000 } }.toSortedSet()
                val occupiedModels = target.archiveIds(Index.MODELS).toSet()
                val modelMap = DeterministicIds.plan(modelIds, occupiedModels)
                val modelStatus = sortedMapOf<Int, String>()
                val modelReports = buildJsonArray {
                    for (id in modelIds) {
                        val destination = modelMap.getValue(id)
                        val bytes = source.data(Index.MODELS, id)
                        val status = try {
                            requireNotNull(bytes) { "missing-source" }
                            val model = if (bytes.size == 18 && bytes.all { it == 0.toByte() }) {
                                val template = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                                check(template.vertexCount == 0 && template.faceCount == 0)
                                template.copy(id = destination)
                            } else ModelDecoder.read(destination, bytes)
                            require(model.faceTextures?.all { it == (-1).toShort() } != false) { "textured-materials-unresolved" }
                            val encoded = ModelEncoder.encode(model)
                            check(ModelEncoder.encode(ModelDecoder.read(destination, encoded)).contentEquals(encoded))
                            "native-roundtrip"
                        } catch (error: Exception) {
                            "blocked:${error.message?.take(120) ?: error.javaClass.simpleName}"
                        }
                        modelStatus[id] = status
                        add(buildJsonObject {
                            put("sourceRuntimeId", id)
                            put("plannedTargetId", destination)
                            put("status", status)
                            if (bytes != null) put("sourceSha256", sha256(bytes))
                        })
                    }
                }
                var exactStatic = 0
                val blockedReasons = sortedMapOf<String, Int>()
                val objectReports = buildJsonArray {
                    for (record in objects) {
                        val id = record.getValue("runtimeId").jsonPrimitive.int
                        val deps = dependencies.getValue(id)
                        val reasons = mutableListOf<String>()
                        if (deps.morphs.isNotEmpty() || deps.varbits.isNotEmpty() || deps.varps.isNotEmpty()) reasons += "morph-selector"
                        if (deps.animations.isNotEmpty()) reasons += "animation"
                        if (deps.sounds.isNotEmpty()) reasons += "ambient-sound"
                        if (deps.sprites.isNotEmpty()) reasons += "mapscene-sprite"
                        if (deps.mapIcons.isNotEmpty()) reasons += "map-icon"
                        if (deps.models.any { modelStatus[it + 200_000] != "native-roundtrip" }) reasons += "model-conversion"
                        if (reasons.isEmpty()) {
                            try {
                                val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                                val destination = objectMap.getValue(id)
                                val definition = OsrsObjectConverter.convert(destination, bytes, modelMap, emptyMap(), objectMap, staticPreview = true)
                                val writer = BufferWriter(bytes.size + 1024)
                                with(ObjectEncoder()) { writer.encode(definition) }
                                val encoded = writer.toArray()
                                val decoded = ObjectDefinition(destination)
                                ObjectDecoder().readLoop(decoded, BufferReader(encoded))
                                check(decoded.modelIds.contentDeepEquals(definition.modelIds))
                                check(decoded.sizeX == definition.sizeX && decoded.sizeY == definition.sizeY)
                                check(decoded.solid == definition.solid && decoded.blocksSky == definition.blocksSky)
                                exactStatic++
                            } catch (error: Exception) {
                                reasons += "definition-conversion:${error.message?.take(120) ?: error.javaClass.simpleName}"
                            }
                        }
                        reasons.forEach { blockedReasons[it] = (blockedReasons[it] ?: 0) + 1 }
                        add(buildJsonObject {
                            put("sourceRuntimeId", id)
                            put("plannedTargetId", objectMap.getValue(id))
                            put("status", if (reasons.isEmpty()) "exact-static-roundtrip" else "blocked")
                            put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("sourceCache", sourceRoot.toString())
                    put("targetCache", targetRoot.toString())
                    put("objectPlanSha256", sha256(Files.readAllBytes(planPath)))
                    put("objectsAudited", objects.size)
                    put("modelsAudited", modelIds.size)
                    put("modelsNativeRoundtrip", modelStatus.values.count { it == "native-roundtrip" })
                    put("exactStaticObjectDefinitions", exactStatic)
                    put("blockedObjectDefinitions", objects.size - exactStatic)
                    put("blockedReasonCounts", buildJsonObject { blockedReasons.forEach { (reason, count) -> put(reason, count) } })
                    put("modelReports", modelReports)
                    put("objectReports", objectReports)
                    put("writesCache", false)
                    put("clientVisualValidated", false)
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
