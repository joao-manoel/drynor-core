package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Read-only conversion probe; it never stages an incomplete NPC into a game cache. */
object ZeahNpcConversionAudit {
    fun audit(sourcePath: Path, dependencyPath: Path, idPlanPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val sourceRoot = sourcePath.toRealPath()
        val dependenciesBytes = Files.readAllBytes(dependencyPath)
        val planBytes = Files.readAllBytes(idPlanPath)
        val dependencies = Json.parseToJsonElement(dependenciesBytes.decodeToString()).jsonObject
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        require(Path.of(dependencies.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(plan.getValue("dependencyReportSha256").jsonPrimitive.content == hash(dependenciesBytes))
        val mappings = plan.getValue("npcMappings").jsonArray.associate { row ->
            row.jsonObject.getValue("sourceId").jsonPrimitive.int to row.jsonObject.getValue("targetId").jsonPrimitive.int
        }
        val npcReports = dependencies.getValue("npcReports").jsonArray.map { it.jsonObject }
        require(npcReports.size == mappings.size)
        val modelIds = npcReports.flatMap { it.getValue("modelIds").jsonArray.map { id -> id.jsonPrimitive.int } }.toSet()
        val animationIds = npcReports.flatMap { it.getValue("animationIds").jsonArray.map { id -> id.jsonPrimitive.int } }.toSet()
        val modelMap = modelIds.associateWith { it }
        val animationMap = animationIds.associateWith { it }
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            val frameOwners = sortedMapOf<Int, MutableMap<Int, MutableSet<Int>>>()
            for (sequenceId in animationIds.sorted()) {
                val bytes = requireNotNull(source.data(Index.ANIMATIONS, sequenceId ushr 7, sequenceId and 127))
                for (packed in OsrsSequenceDependencies.parse(bytes).frameIds) {
                    val frame = requireNotNull(source.data(Index.ANIMATION_FRAMES, (packed ushr 16) + 5000, packed and 65535))
                    val base = GielenorVisualProfile.sourceBaseId(
                        OsrsFrameDependencies.parseFrame(frame).baseId,
                        GielenorVisualProfile.forceOsrsSkeleton(sequenceId),
                    )
                    frameOwners.getOrPut(packed) { sortedMapOf() }.getOrPut(base) { sortedSetOf() }.add(sequenceId)
                }
            }
            val frameConflicts = frameOwners.filterValues { it.size > 1 }
            val conflictingSequences = frameConflicts.values.flatMap { owners -> owners.values.flatten() }.toSet()
            val modelReports = modelIds.sorted().map { id ->
                val bytes = source.data(Index.MODELS, id)
                val issue = try {
                    val model = ModelDecoder.read(id, requireNotNull(bytes))
                    val packed = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(id, packed)).contentEquals(packed))
                    when {
                        model.faceTextures?.any { it != (-1).toShort() } == true -> "textured-model-requires-material-review"
                        !model.particleEmitters.isNullOrEmpty() || !model.particleEffectors.isNullOrEmpty() || !model.billboards.isNullOrEmpty() -> "model-effects-require-review"
                        else -> null
                    }
                } catch (error: Exception) { "${error.javaClass.simpleName}: ${error.message?.take(120)}" }
                buildJsonObject { put("id", id); put("status", if (issue == null) "native-roundtrip" else "blocked"); put("issue", issue) }
            }
            val unsafeModels = modelReports.filter { it["status"]?.jsonPrimitive?.content != "native-roundtrip" }
                .map { it.getValue("id").jsonPrimitive.int }.toSet()
            val sequenceReports = animationIds.sorted().map { id ->
                val bytes = source.data(Index.ANIMATIONS, id ushr 7, id and 127)
                val issue = try {
                    val deps = OsrsSequenceDependencies.parse(requireNotNull(bytes))
                    when {
                        id in conflictingSequences -> "frame-set-has-conflicting-skeletons"
                        deps.sounds.isNotEmpty() -> "sequence-sounds-require-audited-mapping"
                        deps.equipment.isNotEmpty() -> "sequence-equipment-requires-consumer-review"
                        else -> null
                    }
                } catch (error: Exception) { "${error.javaClass.simpleName}: ${error.message?.take(120)}" }
                buildJsonObject { put("id", id); put("status", if (issue == null) "dependency-probe-passed" else "blocked"); put("issue", issue) }
            }
            val unsafeSequences = sequenceReports.filter { it["status"]?.jsonPrimitive?.content != "dependency-probe-passed" }
                .map { it.getValue("id").jsonPrimitive.int }.toSet()
            val reports = npcReports.sortedBy { it.getValue("id").jsonPrimitive.int }.map { sourceReport ->
                val from = sourceReport.getValue("id").jsonPrimitive.int
                val to = mappings.getValue(from)
                val raw = requireNotNull(source.data(Index.NPCS, from ushr 7, from and 127))
                val deps = OsrsNpcDependencies.parse(raw)
                val blockers = mutableListOf<String>()
                if (deps.morphs.isNotEmpty()) blockers += "morph-selector-and-targets"
                if (deps.models.any { it + 200000 in unsafeModels }) blockers += "model-assets"
                if (deps.animations.any { it + 20000 in unsafeSequences }) blockers += "animation-assets"
                var warnings: List<String> = emptyList()
                try {
                    val converted = OsrsNpcConverter.convert(to, raw, modelMap, animationMap, 0)
                    val npcWriter = BufferWriter(1 shl 20)
                    with(NPCEncoder()) { npcWriter.encode(converted.npc) }
                    val decoded = NPCDefinition(to)
                    NPCDecoder().readLoop(decoded, BufferReader(npcWriter.toArray()))
                    check(decoded.modelIds.contentEquals(converted.npc.modelIds) && decoded.size == converted.npc.size && decoded.renderEmote == 0)
                    val basWriter = BufferWriter(1 shl 20)
                    with(BASEncoder()) { basWriter.encode(converted.bas) }
                    val decodedBas = BASDefinition(0)
                    BASDecoder().readLoop(decodedBas, BufferReader(basWriter.toArray()))
                    check(decodedBas.standAnimation == converted.bas.standAnimation && decodedBas.walkAnimation == converted.bas.walkAnimation)
                    warnings = converted.warnings
                    converted.warnings.drop(1).forEach { blockers += "visual-omission: $it" }
                } catch (error: Exception) {
                    blockers += "definition: ${error.javaClass.simpleName}: ${error.message?.take(120)}"
                }
                buildJsonObject {
                    put("sourceId", from); put("targetId", to); put("name", deps.name)
                    put("sourceSha256", hash(raw))
                    put("nativeDefinitionRoundtrip", blockers.none { it.startsWith("definition:") })
                    put("assetReadyForPreview", blockers.isEmpty())
                    put("blockers", JsonArray(blockers.distinct().map(::JsonPrimitive)))
                    put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                }
            }
            val report = buildJsonObject {
                put("schemaVersion", 1); put("scopeComplete", false); put("writesCache", false)
                put("sourceCache", sourceRoot.toString())
                put("dependencyReportSha256", hash(dependenciesBytes)); put("idPlanSha256", hash(planBytes))
                put("npcCount", reports.size)
                put("nativeDefinitionRoundtripCount", reports.count { it.getValue("nativeDefinitionRoundtrip").jsonPrimitive.boolean })
                put("assetReadyForPreviewCount", reports.count { it.getValue("assetReadyForPreview").jsonPrimitive.boolean })
                put("modelCount", modelReports.size); put("unsafeModelCount", unsafeModels.size)
                put("sequenceCount", sequenceReports.size); put("unsafeSequenceCount", unsafeSequences.size)
                put("frameSkeletonConflicts", buildJsonArray {
                    frameConflicts.forEach { (packed, owners) ->
                        add(buildJsonObject {
                            put("packedFrameId", packed)
                            put("skeletons", buildJsonArray { owners.forEach { (base, sequences) ->
                                add(buildJsonObject {
                                    put("baseId", base)
                                    put("sequenceIds", buildJsonArray { sequences.forEach { add(JsonPrimitive(it)) } })
                                })
                            } })
                        })
                    }
                })
                put("models", JsonArray(modelReports)); put("sequences", JsonArray(sequenceReports)); put("npcs", JsonArray(reports))
                put("limitations", "Read-only conversion probe. No animation/model ID allocation, spawn import, morph behavior, Java overrides, drops or client validation.")
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
