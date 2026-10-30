package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Closed visual package for common Karuulm combat, never written into the runtime cache. */
internal object ZeahKaruulmCombatPreview {
    private val npcIds = listOf(28610, 28611, 28613)

    fun build(sourcePath: Path, previewRoot: Path, auditPath: Path, outputRoot: Path) {
        val donorPath = sourcePath.toRealPath()
        val inputRoot = previewRoot.toRealPath()
        val baselinePath = inputRoot.resolve("packed-cache").toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(inputRoot) && !output.startsWith(donorPath))
        val auditBytes = Files.readAllBytes(auditPath)
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        require(audit.getValue("status").jsonPrimitive.content == "read-only-karuulm-combat-visual-audit")
        require(Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath() == donorPath)
        require(Path.of(audit.getValue("targetCache").jsonPrimitive.content).toRealPath() == baselinePath)
        FileCache(donorPath.toString(), writable = false).use { donor ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val requestedSpots = audit.getValue("spots").jsonArray.map { it.jsonObject }.associate { row ->
                    val id = row.getValue("id").jsonPrimitive.int
                    val raw = requireNotNull(donor.data(Index.SPOTANIMS, id ushr 8, id and 255))
                    require(sha256(raw) == row.getValue("sourceSha256").jsonPrimitive.content)
                    id to OsrsSpotConverter.parse(id, raw)
                }
                val npcRaw = npcIds.associateWith { id ->
                    val row = audit.getValue("npcs").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.int == id }.jsonObject
                    val raw = requireNotNull(donor.data(Index.NPCS, id ushr 7, id and 127))
                    require(sha256(raw) == row.getValue("sourceSha256").jsonPrimitive.content)
                    require(baseline.data(Index.NPCS, id ushr 7, id and 127) == null)
                    raw
                }
                val npcDeps = npcRaw.mapValues { OsrsNpcDependencies.parse(it.value) }
                require(npcDeps.values.all { it.morphs.isEmpty() && it.headIcons.isEmpty() })
                val requestedSequences = audit.getValue("animations").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }.toSet() +
                    requestedSpots.values.mapNotNull { it.animationId.takeIf { id -> id >= 0 } } +
                    npcDeps.values.flatMap { it.animations.map { raw -> raw + 20000 } }
                val previous = Json.parseToJsonElement(Files.readString(inputRoot.resolve("preview-report.json"))).jsonObject
                val existingSequences = mutableMapOf<Int, Int>()
                previous["commonCombatAnimationMap"]?.jsonObject?.forEach { (name, entry) ->
                    val originals = when (name) {
                        "lizardman" -> listOf(27192, 27194, 27196)
                        "sulphurLizard" -> listOf(28283, 28284)
                        else -> emptyList()
                    }
                    for ((i, key) in listOf("attack", "defend", "death").withIndex())
                        if (i < originals.size) entry.jsonObject[key]?.let { existingSequences[originals[i]] = it.jsonPrimitive.int }
                }
                val sequenceBytes = requestedSequences.associateWith { id ->
                    requireNotNull(donor.data(Index.ANIMATIONS, id ushr 7, id and 127)) { "Missing source sequence $id" }
                }
                val soundIds = sequenceBytes.values.flatMap { OsrsSequenceDependencies.parse(it).sounds }.toSet()
                val safeSounds = audit.getValue("sounds").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("identical").jsonPrimitive.boolean && it.getValue("nativeRoundtrip").jsonPrimitive.boolean }
                    .map { it.getValue("id").jsonPrimitive.int }.toSet()
                require(soundIds.all { it in safeSounds }) { "Unverified Karuulm sequence sounds: ${soundIds - safeSounds}" }
                val animation = ZulrahAnimationAssets.plan(donor, baseline, requestedSequences - existingSequences.keys, verifiedSoundIds = safeSounds)
                val sequenceMap = existingSequences + animation.sequenceMap
                val modelIds = requestedSpots.values.map { it.modelId }.toSet() +
                    npcDeps.values.flatMap { it.models.map { raw -> raw + 200000 } }
                val modelMap = DeterministicIds.plan(modelIds, baseline.archiveIds(Index.MODELS).toSet())
                val occupiedSpots = baseline.archiveIds(Index.SPOTANIMS).flatMap { a -> baseline.files(Index.SPOTANIMS, a).map { (a shl 8) or it } }.toSet()
                val spotMap = DeterministicIds.plan(requestedSpots.keys, occupiedSpots)
                val assets = animation.assets.toMutableList()
                for ((from, to) in modelMap.toSortedMap()) {
                    val model = ModelDecoder.read(to, requireNotNull(donor.data(Index.MODELS, from)))
                    require(model.faceTextures?.all { it == (-1).toShort() } != false)
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes)
                }
                for ((from, to) in spotMap.toSortedMap()) {
                    val converted = OsrsSpotConverter.convert(to, requestedSpots.getValue(from), modelMap, sequenceMap)
                    val writer = BufferWriter(1 shl 20)
                    with(SpotAnimEncoder()) { writer.encode(converted) }
                    val bytes = writer.toArray()
                    val read = SpotAnimDefinition(to)
                    SpotAnimDecoder().readLoop(read, BufferReader(bytes))
                    check(read.modelId == converted.modelId && read.animationId == converted.animationId)
                    assets += InfernoAssetWriter.Asset(Index.SPOTANIMS, to ushr 8, to and 255, bytes)
                }
                val usedBas = baseline.files(Index.CONFIGS, Config.RENDER_ANIMATIONS).toMutableSet()
                val basMap = sortedMapOf<Int, Int>()
                for ((id, raw) in npcRaw) {
                    val basId = (0..65534).first { it !in usedBas }
                    usedBas += basId
                    basMap[id] = basId
                    val converted = OsrsNpcConverter.convert(id, raw, modelMap, sequenceMap, basId)
                    require(converted.warnings.size == 1) { "NPC $id has unreviewed visual omission: ${converted.warnings}" }
                    val npcWriter = BufferWriter(1 shl 20)
                    with(NPCEncoder()) { npcWriter.encode(converted.npc) }
                    val npcBytes = npcWriter.toArray()
                    val readNpc = NPCDefinition(id)
                    NPCDecoder().readLoop(readNpc, BufferReader(npcBytes))
                    check(readNpc.modelIds.contentEquals(converted.npc.modelIds) && readNpc.renderEmote == basId)
                    assets += InfernoAssetWriter.Asset(Index.NPCS, id ushr 7, id and 127, npcBytes)
                    val basWriter = BufferWriter(1 shl 20)
                    with(BASEncoder()) { basWriter.encode(converted.bas) }
                    val basBytes = basWriter.toArray()
                    val readBas = BASDefinition(basId)
                    BASDecoder().readLoop(readBas, BufferReader(basBytes))
                    check(readBas.standAnimation == converted.bas.standAnimation && readBas.walkAnimation == converted.bas.walkAnimation)
                    assets += InfernoAssetWriter.Asset(Index.CONFIGS, Config.RENDER_ANIMATIONS, basId, basBytes)
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
                val report = buildJsonObject {
                    previous.forEach { (key, value) -> put(key, value) }
                    put("packedCache", "packed-cache")
                    put("karuulmCombatAuditSha256", sha256(auditBytes))
                    put("karuulmCombatAssets", assets.size)
                    put("karuulmSequenceMap", mapJson(sequenceMap))
                    put("karuulmSpotMap", mapJson(spotMap))
                    put("karuulmNpcBasMap", mapJson(basMap))
                    put("karuulmVerifiedSoundIds", JsonArray(safeSounds.sorted().map(::JsonPrimitive)))
                    put("karuulmNativeReadback", true)
                    put("karuulmClientValidated", false)
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    private fun mapJson(values: Map<Int, Int>) = buildJsonObject { values.toSortedMap().forEach { (from, to) -> put(from.toString(), to) } }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
