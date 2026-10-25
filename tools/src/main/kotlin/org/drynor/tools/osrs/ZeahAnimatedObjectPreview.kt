package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
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
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Adds only closed, audited animated objects to a separate asset preview. */
object ZeahAnimatedObjectPreview {
    private data class Asset(val index: Int, val archive: Int, val file: Int, val bytes: ByteArray)

    fun build(
        animationRootPath: Path,
        landscapeRootPath: Path,
        planPath: Path,
        staticAuditPath: Path,
        materialAuditPath: Path,
        soundAuditPath: Path,
        iconAuditPath: Path,
        animationAuditPath: Path,
        outputPath: Path
    ) {
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val planHash = sha256(planBytes)
        val staticAuditBytes = Files.readAllBytes(staticAuditPath)
        val staticAudit = Json.parseToJsonElement(staticAuditBytes.decodeToString()).jsonObject
        require(staticAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        val materialAudit = Json.parseToJsonElement(Files.readString(materialAuditPath)).jsonObject
        require(materialAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(materialAudit.getValue("objectAuditSha256").jsonPrimitive.content == sha256(staticAuditBytes))
        val safeMaterials = materialAudit.getValue("reports").jsonArray.filter {
            it.jsonObject.getValue("safeToReuse").jsonPrimitive.content == "true"
        }.map { it.jsonObject.getValue("effectiveMaterialId").jsonPrimitive.int }.toSet()
        val soundAudit = Json.parseToJsonElement(Files.readString(soundAuditPath)).jsonObject
        require(soundAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        val safeSounds = soundAudit.getValue("reports").jsonArray.filter {
            val item = it.jsonObject
            item.getValue("identicalAtSameId").jsonPrimitive.content == "true" &&
                item.getValue("sourceNativeRoundtrip").jsonPrimitive.content == "true"
        }.map { it.jsonObject.getValue("soundId").jsonPrimitive.int }.toSet()
        val iconAudit = Json.parseToJsonElement(Files.readString(iconAuditPath)).jsonObject
        require(iconAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        val donorClient = Path.of(iconAudit.getValue("donorClientObjectConfig").jsonPrimitive.content).toRealPath()
        require(sha256(Files.readAllBytes(donorClient)) == iconAudit.getValue("donorClientObjectConfigSha256").jsonPrimitive.content)
        val safeIconReports = iconAudit.getValue("objectReports").jsonArray.map { it.jsonObject }.filter {
            it.getValue("allDisplayedIconsReusable").jsonPrimitive.content == "true"
        }
        val safeIconObjects = safeIconReports.map { it.getValue("sourceRuntimeId").jsonPrimitive.int }.toSet()
        val mapsceneIds = safeIconReports.flatMap { report -> report.getValue("mapScenes").jsonArray.map {
            it.jsonObject.getValue("donorClientMapSpriteId").jsonPrimitive.int
        } }.toSet()
        val mapIconIds = safeIconReports.flatMap { report -> report.getValue("mapIcons").jsonArray.map {
            val entry = it.jsonObject
            entry.getValue("rawOsrsId").jsonPrimitive.int to entry.getValue("donorClientMapSpriteId").jsonPrimitive.int.takeIf { value -> value >= 0 }
        } }.toMap()
        val animationAuditBytes = Files.readAllBytes(animationAuditPath)
        val animationAudit = Json.parseToJsonElement(animationAuditBytes.decodeToString()).jsonObject
        require(animationAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        val passedSequences = animationAudit.getValue("reports").jsonArray.filter {
            it.jsonObject.getValue("nativeConversionProbePassed").jsonPrimitive.content == "true"
        }.map { it.jsonObject.getValue("sequenceRuntimeId").jsonPrimitive.int }.toSet()
        val animationRoot = animationRootPath.toRealPath()
        val animationReport = Json.parseToJsonElement(Files.readString(animationRoot.resolve("preview-report.json"))).jsonObject
        require(animationReport.getValue("status").jsonPrimitive.content == "experimental-object-animation-assets-only")
        require(animationReport.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(animationReport.getValue("animationAuditSha256").jsonPrimitive.content == sha256(animationAuditBytes))
        val animationCache = Path.of(animationReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(animationCache == animationRoot.resolve("packed-cache"))
        val animationMap = animationReport.getValue("animationMappings").jsonObject.getValue("sequences").jsonArray.associate {
            it.jsonObject.getValue("source").jsonPrimitive.int to it.jsonObject.getValue("target").jsonPrimitive.int
        }
        require(animationMap.keys == passedSequences)
        val landscapeRoot = landscapeRootPath.toRealPath()
        val landscapeReport = Json.parseToJsonElement(Files.readString(landscapeRoot.resolve("preview-report.json"))).jsonObject
        require(landscapeReport.getValue("status").jsonPrimitive.content == "experimental-landscapes-without-definitions")
        val landscapeCache = Path.of(landscapeReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(landscapeCache == landscapeRoot.resolve("packed-cache"))
        val staticReport = Json.parseToJsonElement(Files.readString(Path.of(animationReport.getValue("sourceStaticPreview").jsonPrimitive.content).resolve("preview-report.json"))).jsonObject
        val previousPlacements = staticReport.getValue("keptPlacements").jsonPrimitive.int
        val objectMap = plan.getValue("sourceObjects").jsonArray.associate {
            it.jsonObject.getValue("runtimeId").jsonPrimitive.int to it.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }
        val objectHashes = plan.getValue("sourceObjects").jsonArray.associate {
            it.jsonObject.getValue("runtimeId").jsonPrimitive.int to it.jsonObject.getValue("sourceSha256").jsonPrimitive.content
        }
        val modelMap = staticAudit.getValue("modelReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to it.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }
        val modelHashes = staticAudit.getValue("modelReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to it.jsonObject.getValue("sourceSha256").jsonPrimitive.content
        }
        val candidates = staticAudit.getValue("objectReports").jsonArray.filter { entry ->
            val item = entry.jsonObject
            val reasons = item.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val objectId = item.getValue("sourceRuntimeId").jsonPrimitive.int
            "animation" in reasons && reasons.all { it in setOf("animation", "model-conversion", "ambient-sound", "mapscene-sprite", "map-icon") } &&
                ("mapscene-sprite" !in reasons && "map-icon" !in reasons || objectId in safeIconObjects)
        }.map { it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int }
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(sourceRoot, targetRoot, animationRoot, landscapeRoot).none { output.startsWith(it) || it.startsWith(output) })
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(animationCache.toString(), writable = false).use { staged ->
                    FileCache(landscapeCache.toString(), writable = false).use { full ->
                        val objects = mutableListOf<Asset>()
                        val neededModels = sortedSetOf<Int>()
                        for (id in candidates.sorted()) {
                            val donor = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                            check(sha256(donor) == objectHashes.getValue(id))
                            val deps = OsrsObjectDependencies.parse(donor)
                            if (deps.animations.any { it + 20_000 !in animationMap }) continue
                            if (deps.sounds.any { it !in safeSounds }) continue
                            if (deps.sprites.map { if (it == 88) 0 else it }.any { it !in mapsceneIds }) continue
                            if (deps.mapIcons.any { it !in mapIconIds }) continue
                            require(deps.morphs.isEmpty() && deps.varbits.isEmpty() && deps.varps.isEmpty())
                            val destination = objectMap.getValue(id)
                            check(target.data(Index.OBJECTS, destination ushr 8, destination and 255) == null)
                            check(staged.data(Index.OBJECTS, destination ushr 8, destination and 255) == null)
                            neededModels += deps.models.map { it + 200_000 }
                            val definition = OsrsObjectConverter.convert(destination, donor, modelMap, animationMap, objectMap,
                                verifiedSoundIds = safeSounds, verifiedMapsceneIds = mapsceneIds, auditedMapIconIds = mapIconIds)
                            val writer = BufferWriter(donor.size + 1024)
                            with(ObjectEncoder()) { writer.encode(definition) }
                            val bytes = writer.toArray()
                            val readback = ObjectDefinition(destination)
                            ObjectDecoder().readLoop(readback, BufferReader(bytes))
                            check(readback.modelIds.contentDeepEquals(definition.modelIds) &&
                                readback.singleAnimation == definition.singleAnimation &&
                                readback.animations.contentEquals(definition.animations) &&
                                readback.mapscene == definition.mapscene && readback.mapDefinitionId == definition.mapDefinitionId &&
                                readback.anInt3015 == definition.anInt3015 &&
                                readback.anIntArray3036.contentEquals(definition.anIntArray3036))
                            objects += Asset(Index.OBJECTS, destination ushr 8, destination and 255, bytes)
                        }
                        require(objects.isNotEmpty())
                        val models = neededModels.mapNotNull { sourceId ->
                            val destination = modelMap.getValue(sourceId)
                            val donor = requireNotNull(source.data(Index.MODELS, sourceId))
                            check(sha256(donor) == modelHashes.getValue(sourceId))
                            if (staged.data(Index.MODELS, destination) != null) return@mapNotNull null
                            check(target.data(Index.MODELS, destination) == null)
                            val model = if (donor.size == 18 && donor.all { it == 0.toByte() }) {
                                val template = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                                check(template.vertexCount == 0 && template.faceCount == 0)
                                template.copy(id = destination)
                            } else ModelDecoder.read(destination, donor)
                            val converted = model.copy(faceTextures = model.faceTextures?.map { raw ->
                                if (raw == (-1).toShort()) raw else {
                                    val effective = GielenorVisualProfile.runtimeMaterial(raw.toInt() and 65535)
                                    require(effective in safeMaterials) { "Unverified material $effective on model $sourceId" }
                                    effective.toShort()
                                }
                            }?.toShortArray())
                            val bytes = ModelEncoder.encode(converted)
                            check(ModelEncoder.encode(ModelDecoder.read(destination, bytes)).contentEquals(bytes))
                            Asset(Index.MODELS, destination, 0, bytes)
                        }
                        val addedTargets = objects.map { (it.archive shl 8) or it.file }.toSet()
                        val existingTargets = objectMap.values.filterTo(mutableSetOf()) {
                            staged.data(Index.OBJECTS, it ushr 8, it and 255) != null
                        }
                        val retained = existingTargets + addedTargets
                        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
                        var prior = 0
                        var added = 0
                        var excluded = 0
                        val maps = regions.map { region ->
                            val name = "l${region ushr 8}_${region and 255}"
                            val archive = staged.archiveId(Index.MAPS, name)
                            check(archive >= 0 && archive == full.archiveId(Index.MAPS, name))
                            val sourceBytes = requireNotNull(full.data(Index.MAPS, name))
                            val map = MapDefinition(region)
                            val decoder = object : MapObjectDecoder() {
                                override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                    if (objectId in retained) {
                                        map.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                                        if (objectId in addedTargets) added++ else prior++
                                    } else excluded++
                                }
                            }
                            check(decoder.decode(sourceBytes, ByteArray(16384), region ushr 8, region and 255) == sourceBytes.size)
                            val writer = BufferWriter(sourceBytes.size + 32)
                            with(MapObjectEncoder()) { writer.encode(map) }
                            val bytes = writer.toArray()
                            val readback = MapDefinition(region)
                            val verify = object : MapObjectDecoder() {
                                override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                    readback.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                                }
                            }
                            check(verify.decode(bytes, ByteArray(16384), region ushr 8, region and 255) == bytes.size)
                            check(readback.objects.map { it.packed }.sorted() == map.objects.map { it.packed }.sorted())
                            archive to bytes
                        }
                        check(prior == previousPlacements)
                        check(prior + added + excluded == landscapeReport.getValue("placements").jsonPrimitive.int)
                        Files.createDirectories(output)
                        val packed = output.resolve("packed-cache")
                        Files.createDirectory(packed)
                        Files.list(animationCache).use { files ->
                            files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                                .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                        }
                        FileCache(packed.toString(), writable = true).use { result ->
                            for (asset in models + objects) result.write(asset.index, asset.archive, asset.file, asset.bytes)
                            for ((archive, bytes) in maps) result.write(Index.MAPS, archive, 0, bytes)
                        }
                        FileCache(packed.toString(), writable = false).use { result ->
                            for (asset in models + objects) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                            for ((archive, bytes) in maps) check(result.data(Index.MAPS, archive).contentEquals(bytes))
                            val touched = maps.map { it.first }.toSet()
                            for (archive in staged.archiveIds(Index.MAPS)) if (archive !in touched) {
                                check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive)))
                            }
                            for (archive in staged.archiveIds(Index.OBJECTS)) for (file in staged.files(Index.OBJECTS, archive)) {
                                check(staged.data(Index.OBJECTS, archive, file).contentEquals(result.data(Index.OBJECTS, archive, file)))
                            }
                            for (archive in staged.archiveIds(Index.MODELS)) {
                                check(staged.container(Index.MODELS, archive).contentEquals(result.container(Index.MODELS, archive)))
                            }
                            for (index in listOf(Index.ANIMATIONS, Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS)) {
                                for (archive in staged.archiveIds(index)) check(staged.container(index, archive).contentEquals(result.container(index, archive)))
                            }
                        }
                        val report = buildJsonObject {
                            put("status", "experimental-animated-object-subset")
                            put("packedCache", packed.toString())
                            put("objectPlanSha256", planHash)
                            put("animationAssetPreview", animationRoot.toString())
                            put("animatedObjectDefinitionsAdded", objects.size)
                            put("modelsAdded", models.size)
                            put("priorStaticPlacements", prior)
                            put("animatedPlacementsAdded", added)
                            put("excludedPlacements", excluded)
                            put("landscapeRegions", maps.size)
                            put("existingAssetsPreserved", true)
                            put("scopeComplete", false)
                            put("clientAnimationValidated", false)
                            put("limitations", "Only audited non-morph animated objects staged. Two blocked sequences, divergent icons and dynamic morphs remain. Never use as runtime cache.")
                        }
                        Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
                    }
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
