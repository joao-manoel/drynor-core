package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Passive visual actor package for the explicitly convertible Zeah subset; no spawns or gameplay. */
object ZeahNpcAssetPreview {
    fun build(sourcePath: Path, mapPreviewRoot: Path, idPlanPath: Path, auditPath: Path, outputRoot: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val mapRoot = mapPreviewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(sourceRoot) && !output.startsWith(mapRoot))
        val mapReport = Json.parseToJsonElement(Files.readString(mapRoot.resolve("preview-report.json"))).jsonObject
        require(mapReport.getValue("status").jsonPrimitive.content == "experimental-zeah-worldmap-diagnostic")
        val baselinePath = mapRoot.resolve("packed-cache").toRealPath()
        val planBytes = Files.readAllBytes(idPlanPath)
        val auditBytes = Files.readAllBytes(auditPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(audit.getValue("idPlanSha256").jsonPrimitive.content == hash(planBytes))
        val idMap = plan.getValue("npcMappings").jsonArray.associate { row ->
            row.jsonObject.getValue("sourceId").jsonPrimitive.int to row.jsonObject.getValue("targetId").jsonPrimitive.int
        }
        val selected = audit.getValue("npcs").jsonArray.map { it.jsonObject }
            .filter { it.getValue("assetReadyForPreview").jsonPrimitive.boolean }
            .map { it.getValue("sourceId").jsonPrimitive.int }.toSortedSet()
        require(selected.size == audit.getValue("assetReadyForPreviewCount").jsonPrimitive.int && selected.isNotEmpty())
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val npcData = selected.associateWith { id ->
                    val bytes = requireNotNull(source.data(Index.NPCS, id ushr 7, id and 127))
                    val expected = audit.getValue("npcs").jsonArray.first { it.jsonObject.getValue("sourceId").jsonPrimitive.int == id }
                        .jsonObject.getValue("sourceSha256").jsonPrimitive.content
                    require(hash(bytes) == expected) { "NPC source changed: $id" }
                    bytes
                }
                val deps = npcData.mapValues { OsrsNpcDependencies.parse(it.value) }
                require(deps.values.all { it.morphs.isEmpty() && it.headIcons.isEmpty() })
                val modelIds = deps.values.flatMap { it.models.map { raw -> raw + 200000 } }.toSet()
                val sequenceIds = deps.values.flatMap { it.animations.map { raw -> raw + 20000 } }.toSet()
                val modelMap = DeterministicIds.plan(modelIds, baseline.archiveIds(Index.MODELS).toSet())
                val animation = ZulrahAnimationAssets.plan(source, baseline, sequenceIds)
                val assets = animation.assets.toMutableList()
                val usedBas = baseline.files(Index.CONFIGS, Config.RENDER_ANIMATIONS).toMutableSet()
                val basMap = sortedMapOf<Int, Int>()
                for (from in selected) {
                    val to = idMap.getValue(from)
                    require(to in 0..32767 && baseline.data(Index.NPCS, to ushr 7, to and 127) == null)
                    val basId = (0..65534).first { it !in usedBas }
                    usedBas += basId
                    basMap[from] = basId
                    val converted = OsrsNpcConverter.convert(to, npcData.getValue(from), modelMap, animation.sequenceMap, basId)
                    require(converted.warnings.size == 1) { "NPC $from has an unreviewed visual omission" }
                    val npcWriter = BufferWriter(1 shl 20)
                    with(NPCEncoder()) { npcWriter.encode(converted.npc) }
                    val npcBytes = npcWriter.toArray()
                    val readNpc = NPCDefinition(to)
                    NPCDecoder().readLoop(readNpc, BufferReader(npcBytes))
                    check(readNpc.modelIds.contentEquals(converted.npc.modelIds) && readNpc.renderEmote == basId)
                    val basWriter = BufferWriter(1 shl 20)
                    with(BASEncoder()) { basWriter.encode(converted.bas) }
                    val basBytes = basWriter.toArray()
                    val readBas = BASDefinition(basId)
                    BASDecoder().readLoop(readBas, BufferReader(basBytes))
                    check(readBas.standAnimation == converted.bas.standAnimation && readBas.walkAnimation == converted.bas.walkAnimation)
                    assets += InfernoAssetWriter.Asset(Index.NPCS, to ushr 7, to and 127, npcBytes)
                    assets += InfernoAssetWriter.Asset(Index.CONFIGS, Config.RENDER_ANIMATIONS, basId, basBytes)
                }
                for ((from, to) in modelMap.toSortedMap()) {
                    val raw = requireNotNull(source.data(Index.MODELS, from))
                    val model = ModelDecoder.read(to, raw)
                    require(model.faceTextures?.all { it == (-1).toShort() } != false)
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes)
                }
                ZulrahActorPreview.validateFreshAssets(baseline, assets)
                Files.createDirectories(output)
                ZulrahActorPreview.stage(output.resolve("editable-assets"), assets, baseline)
                val destination = output.resolve("packed-cache")
                Files.createDirectory(destination)
                Files.list(baselinePath).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                    .forEach { Files.copy(it, destination.resolve(it.fileName)) } }
                InfernoAssetWriter.write(destination, assets)
                FileCache(destination.toString(), writable = false).use { result ->
                    for (asset in assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index to archive !in touched) check(baseline.container(index, archive).contentEquals(result.container(index, archive)))
                        else for (file in baseline.files(index, archive)) check(baseline.data(index, archive, file).contentEquals(result.data(index, archive, file)))
                    }
                }
                val report = buildJsonObject {
                    put("status", "experimental-zeah-passive-npcs")
                    put("packedCache", "packed-cache")
                    put("sourceCache", sourceRoot.toString())
                    put("mapPreviewSha256", hash(Files.readAllBytes(mapRoot.resolve("preview-report.json"))))
                    put("idPlanSha256", hash(planBytes)); put("conversionAuditSha256", hash(auditBytes))
                    put("npcCount", selected.size); put("modelCount", modelMap.size)
                    put("sequenceCount", animation.sequenceMap.size); put("assetCount", assets.size)
                    put("npcIds", buildJsonArray { selected.forEach { add(JsonPrimitive(idMap.getValue(it))) } })
                    put("renderingValidated", false); put("spawnsInstalled", false); put("gameplayImplemented", false)
                    put("limitations", "Passive NPC visuals only. Morphs, blocked animations/materials, native-range divergence, spawns, Java overrides, combat and drops remain excluded.")
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
