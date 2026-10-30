package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Stages Shaman combat visuals over the passive NPC preview without touching runtime caches. */
object ZeahShamanCombatPreview {
    private val actorAnimations = setOf(27192, 27193, 27194, 27196)
    private val spots = setOf(6290, 6291, 6293, 6294, 6295)

    fun build(sourcePath: Path, npcPreviewRoot: Path, auditPath: Path, outputRoot: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val npcRoot = npcPreviewRoot.toRealPath()
        val baselinePath = npcRoot.resolve("packed-cache").toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(sourceRoot) && !output.startsWith(npcRoot))
        val auditBytes = Files.readAllBytes(auditPath)
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        require(audit.getValue("status").jsonPrimitive.content == "read-only-shaman-combat-asset-audit")
        require(Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(audit.getValue("targetCache").jsonPrimitive.content).toRealPath() == baselinePath)
        require(audit.getValue("animationConversionStatus").jsonPrimitive.content == "planned")
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val spotDefs = spots.sorted().associateWith { id ->
                    val raw = requireNotNull(source.data(Index.SPOTANIMS, id ushr 8, id and 255))
                    val expected = audit.getValue("spots").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.int == id }
                        .jsonObject.getValue("donorSha256").jsonPrimitive.content
                    require(hash(raw) == expected) { "Shaman source spot changed: $id" }
                    OsrsSpotConverter.parse(id, raw)
                }
                val sequenceIds = actorAnimations + spotDefs.values.mapNotNull { it.animationId.takeIf { value -> value >= 0 } }
                for (id in sequenceIds) {
                    val raw = requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127))
                    val expected = audit.getValue("animations").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.int == id }
                        .jsonObject.getValue("donorSha256").jsonPrimitive.content
                    require(hash(raw) == expected) { "Shaman source animation changed: $id" }
                }
                val animation = ZulrahAnimationAssets.plan(source, baseline, sequenceIds)
                val models = spotDefs.values.map { it.modelId }.toSet()
                val modelMap = DeterministicIds.plan(models, baseline.archiveIds(Index.MODELS).toSet())
                val occupiedSpots = baseline.archiveIds(Index.SPOTANIMS).flatMap { a -> baseline.files(Index.SPOTANIMS, a).map { (a shl 8) or it } }.toSet()
                val spotMap = DeterministicIds.plan(spots, occupiedSpots)
                require(spotMap.values.all { it in 0..65535 })
                val assets = animation.assets.toMutableList()
                for ((sourceId, targetId) in modelMap.toSortedMap()) {
                    val model = ModelDecoder.read(targetId, requireNotNull(source.data(Index.MODELS, sourceId)))
                    require(model.faceTextures?.all { it == (-1).toShort() } != false)
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(targetId, bytes)).contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, targetId, 0, bytes)
                }
                for ((sourceId, targetId) in spotMap.toSortedMap()) {
                    val spot = OsrsSpotConverter.convert(targetId, spotDefs.getValue(sourceId), modelMap, animation.sequenceMap)
                    val writer = BufferWriter(1 shl 20)
                    with(SpotAnimEncoder()) { writer.encode(spot) }
                    val bytes = writer.toArray()
                    val read = SpotAnimDefinition(targetId)
                    SpotAnimDecoder().readLoop(read, BufferReader(bytes))
                    check(read.modelId == spot.modelId && read.animationId == spot.animationId)
                    assets += InfernoAssetWriter.Asset(Index.SPOTANIMS, targetId ushr 8, targetId and 255, bytes)
                }
                ZulrahActorPreview.validateFreshAssets(baseline, assets)
                Files.createDirectories(output)
                ZulrahActorPreview.stage(output.resolve("editable-assets"), assets, baseline)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(baselinePath).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                    .forEach { Files.copy(it, packed.resolve(it.fileName)) } }
                InfernoAssetWriter.write(packed, assets)
                FileCache(packed.toString(), writable = false).use { result ->
                    for (asset in assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index to archive !in touched) check(baseline.container(index, archive).contentEquals(result.container(index, archive)))
                        else for (file in baseline.files(index, archive)) check(baseline.data(index, archive, file).contentEquals(result.data(index, archive, file)))
                    }
                }
                val npcReport = Json.parseToJsonElement(Files.readString(npcRoot.resolve("preview-report.json"))).jsonObject
                val report = buildJsonObject {
                    npcReport.forEach { (key, value) -> put(key, value) }
                    put("packedCache", "packed-cache")
                    put("shamanCombatAssetAuditSha256", hash(auditBytes))
                    put("shamanCombatAssetCount", assets.size)
                    put("shamanAnimationMap", buildJsonObject { animation.sequenceMap.toSortedMap().forEach { (from, to) -> put(from.toString(), to) } })
                    put("shamanSpotMap", buildJsonObject { spotMap.toSortedMap().forEach { (from, to) -> put(from.toString(), to) } })
                    put("gameplayImplemented", false)
                    put("limitations", "Shaman combat visuals staged in an isolated cache; combat logic, playback and drops require separate validation.")
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
