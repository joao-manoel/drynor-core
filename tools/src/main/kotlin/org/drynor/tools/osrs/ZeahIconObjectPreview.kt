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

/** Restores only icon-bearing objects closed by the audited icon remap, into another isolated cache. */
object ZeahIconObjectPreview {
    private data class Asset(val index: Int, val archive: Int, val file: Int, val bytes: ByteArray)

    fun build(
        iconRootPath: Path, animatedRootPath: Path, landscapeRootPath: Path,
        planPath: Path, staticAuditPath: Path, materialAuditPath: Path, soundAuditPath: Path,
        iconAuditPath: Path, animationAuditPath: Path, outputPath: Path
    ) {
        val iconRoot = iconRootPath.toRealPath()
        val animatedRoot = animatedRootPath.toRealPath()
        val landscapeRoot = landscapeRootPath.toRealPath()
        val iconReport = Json.parseToJsonElement(Files.readString(iconRoot.resolve("preview-report.json"))).jsonObject
        val animatedReport = Json.parseToJsonElement(Files.readString(animatedRoot.resolve("preview-report.json"))).jsonObject
        val landscapeReport = Json.parseToJsonElement(Files.readString(landscapeRoot.resolve("preview-report.json"))).jsonObject
        require(iconReport.getValue("status").jsonPrimitive.content == "experimental-icon-remap-only")
        require(animatedReport.getValue("status").jsonPrimitive.content == "experimental-animated-object-subset")
        require(landscapeReport.getValue("status").jsonPrimitive.content == "experimental-landscapes-without-definitions")
        val iconCache = Path.of(iconReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        val animatedCache = Path.of(animatedReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        val landscapeCache = Path.of(landscapeReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(iconCache == iconRoot.resolve("packed-cache") && animatedCache == animatedRoot.resolve("packed-cache") &&
            landscapeCache == landscapeRoot.resolve("packed-cache"))
        val planBytes = Files.readAllBytes(planPath)
        val planHash = sha256(planBytes)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        require(listOf(iconReport, animatedReport).all { it.getValue("objectPlanSha256").jsonPrimitive.content == planHash })
        val staticAuditBytes = Files.readAllBytes(staticAuditPath)
        val staticAudit = Json.parseToJsonElement(staticAuditBytes.decodeToString()).jsonObject
        val materialAudit = Json.parseToJsonElement(Files.readString(materialAuditPath)).jsonObject
        val soundAudit = Json.parseToJsonElement(Files.readString(soundAuditPath)).jsonObject
        val iconAuditBytes = Files.readAllBytes(iconAuditPath)
        val iconAudit = Json.parseToJsonElement(iconAuditBytes.decodeToString()).jsonObject
        val animationAudit = Json.parseToJsonElement(Files.readString(animationAuditPath)).jsonObject
        require(listOf(staticAudit, materialAudit, soundAudit, iconAudit, animationAudit).all {
            it.getValue("objectPlanSha256").jsonPrimitive.content == planHash
        })
        require(iconReport.getValue("iconAuditSha256").jsonPrimitive.content == sha256(iconAuditBytes))
        require(materialAudit.getValue("objectAuditSha256").jsonPrimitive.content == sha256(staticAuditBytes))
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(iconRoot, animatedRoot, landscapeRoot, sourceRoot, targetRoot).none {
            output.startsWith(it) || it.startsWith(output)
        })

        val mapsceneRemaps = iconReport.getValue("mapsceneConfigRemaps").jsonArray.associate {
            it.jsonObject.getValue("donorClientMapSpriteId").jsonPrimitive.int to
                it.jsonObject.getValue("targetMapSpriteId").jsonPrimitive.int
        }
        val mapAreaRemaps = iconReport.getValue("mapAreaConfigRemaps").jsonArray.associate {
            it.jsonObject.getValue("donorClientMapAreaId").jsonPrimitive.int to
                it.jsonObject.getValue("targetMapAreaId").jsonPrimitive.int
        }
        val iconObjects = iconAudit.getValue("objectReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to it.jsonObject
        }
        val iconClosures = iconAudit.getValue("iconClosures").jsonArray.associate {
            (it.jsonObject.getValue("configArchive").jsonPrimitive.int to it.jsonObject.getValue("id").jsonPrimitive.int) to
                (it.jsonObject.getValue("safeByteReuse").jsonPrimitive.content == "true")
        }
        val safeMaterials = materialAudit.getValue("reports").jsonArray.filter {
            it.jsonObject.getValue("safeToReuse").jsonPrimitive.content == "true"
        }.map { it.jsonObject.getValue("effectiveMaterialId").jsonPrimitive.int }.toSet()
        val safeSounds = soundAudit.getValue("reports").jsonArray.filter {
            it.jsonObject.getValue("identicalAtSameId").jsonPrimitive.content == "true" &&
                it.jsonObject.getValue("sourceNativeRoundtrip").jsonPrimitive.content == "true"
        }.map { it.jsonObject.getValue("soundId").jsonPrimitive.int }.toSet()
        val animationRoot = Path.of(animatedReport.getValue("animationAssetPreview").jsonPrimitive.content).toRealPath()
        val animationReport = Json.parseToJsonElement(Files.readString(animationRoot.resolve("preview-report.json"))).jsonObject
        require(animationReport.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(animationReport.getValue("animationAuditSha256").jsonPrimitive.content == sha256(Files.readAllBytes(animationAuditPath)))
        val animationMap = animationReport.getValue("animationMappings").jsonObject.getValue("sequences").jsonArray.associate {
            it.jsonObject.getValue("source").jsonPrimitive.int to it.jsonObject.getValue("target").jsonPrimitive.int
        }
        val objectMap = plan.getValue("sourceObjects").jsonArray.associate {
            it.jsonObject.getValue("runtimeId").jsonPrimitive.int to it.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }
        val objectHashes = plan.getValue("sourceObjects").jsonArray.associate {
            it.jsonObject.getValue("runtimeId").jsonPrimitive.int to it.jsonObject.getValue("sourceSha256").jsonPrimitive.content
        }
        val modelReports = staticAudit.getValue("modelReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to it.jsonObject
        }
        val modelMap = modelReports.mapValues { it.value.getValue("plannedTargetId").jsonPrimitive.int }
        val reasonsByObject = staticAudit.getValue("objectReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to
                it.jsonObject.getValue("reasons").jsonArray.map { reason -> reason.jsonPrimitive.content }.toSet()
        }

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(animatedCache.toString(), writable = false).use { animated ->
                    FileCache(iconCache.toString(), writable = false).use { staged ->
                        FileCache(landscapeCache.toString(), writable = false).use { full ->
                            // The icon preview must derive from exactly this animated scene, not a similarly named cache.
                            for (index in listOf(Index.MAPS, Index.OBJECTS, Index.MODELS, Index.ANIMATIONS,
                                Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS, Index.SYNTH_SOUNDS)) {
                                for (archive in animated.archiveIds(index)) {
                                    check(animated.container(index, archive).contentEquals(staged.container(index, archive))) {
                                        "Icon preview changed prior index $index archive $archive"
                                    }
                                }
                            }
                            val objects = mutableListOf<Asset>()
                            val requiredModels = sortedSetOf<Int>()
                            var blockedByAnimation = 0
                            for ((id, report) in iconObjects.toSortedMap()) {
                                val reasons = reasonsByObject.getValue(id)
                                if (reasons.none { it == "mapscene-sprite" || it == "map-icon" } ||
                                    reasons.any { it !in setOf("mapscene-sprite", "map-icon", "animation", "ambient-sound", "model-conversion") }) continue
                                val destination = objectMap.getValue(id)
                                if (staged.data(Index.OBJECTS, destination ushr 8, destination and 255) != null) continue
                                val donor = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                                check(sha256(donor) == objectHashes.getValue(id))
                                val deps = OsrsObjectDependencies.parse(donor)
                                if (deps.animations.any { it + 20_000 !in animationMap }) { blockedByAnimation++; continue }
                                if (deps.sounds.any { it !in safeSounds }) continue
                                if (deps.models.any { sourceId ->
                                        val model = modelReports.getValue(sourceId + 200_000)
                                        val status = model.getValue("status").jsonPrimitive.content
                                        status != "native-roundtrip" && (model.getValue("materials").jsonArray.isEmpty() ||
                                            !status.contains("textured-materials-unresolved") ||
                                            model.getValue("materials").jsonArray.any { material ->
                                                material.jsonObject.getValue("effectiveId").jsonPrimitive.int !in safeMaterials
                                            })
                                    }) continue
                                val mapScenes = report.getValue("mapScenes").jsonArray.map {
                                    it.jsonObject.getValue("donorClientMapSpriteId").jsonPrimitive.int
                                }
                                val mapIcons = report.getValue("mapIcons").jsonArray.associate {
                                    it.jsonObject.getValue("rawOsrsId").jsonPrimitive.int to
                                        it.jsonObject.getValue("donorClientMapSpriteId").jsonPrimitive.int.takeIf { mapped -> mapped >= 0 }
                                }
                                val mappedScenes = mapScenes.associateWith { mapsceneRemaps[it] ?: it }
                                val mappedIcons = mapIcons.mapValues { (_, value) -> value?.let { mapAreaRemaps[it] ?: it } }
                                check(mapScenes.size == deps.sprites.size && mapIcons.keys == deps.mapIcons)
                                check(mapScenes.all { it in mapsceneRemaps || iconClosures[34 to it] == true })
                                check(mapIcons.values.filterNotNull().all { it in mapAreaRemaps || iconClosures[36 to it] == true })
                                check(mappedScenes.values.all { staged.data(Index.CONFIGS, 34, it) != null })
                                check(mappedIcons.values.filterNotNull().all { staged.data(Index.CONFIGS, 36, it) != null })
                                check(target.data(Index.OBJECTS, destination ushr 8, destination and 255) == null)
                                requiredModels += deps.models.map { it + 200_000 }
                                val definition = OsrsObjectConverter.convert(destination, donor, modelMap, animationMap, objectMap,
                                    verifiedSoundIds = safeSounds, auditedMapIconIds = mappedIcons, auditedMapsceneIds = mappedScenes)
                                val writer = BufferWriter(donor.size + 1024)
                                with(ObjectEncoder()) { writer.encode(definition) }
                                val bytes = writer.toArray()
                                val decoded = ObjectDefinition(destination)
                                ObjectDecoder().readLoop(decoded, BufferReader(bytes))
                                check(decoded.modelIds.contentDeepEquals(definition.modelIds) &&
                                    decoded.singleAnimation == definition.singleAnimation &&
                                    decoded.animations.contentEquals(definition.animations) &&
                                    decoded.mapscene == definition.mapscene && decoded.mapDefinitionId == definition.mapDefinitionId)
                                objects += Asset(Index.OBJECTS, destination ushr 8, destination and 255, bytes)
                            }
                            require(objects.isNotEmpty())
                            val models = requiredModels.mapNotNull { id ->
                                val destination = modelMap.getValue(id)
                                val donor = requireNotNull(source.data(Index.MODELS, id))
                                check(sha256(donor) == modelReports.getValue(id).getValue("sourceSha256").jsonPrimitive.content)
                                if (staged.data(Index.MODELS, destination) != null) return@mapNotNull null
                                check(target.data(Index.MODELS, destination) == null)
                                val model = if (donor.size == 18 && donor.all { it == 0.toByte() }) {
                                    val template = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                                    check(template.vertexCount == 0 && template.faceCount == 0)
                                    template.copy(id = destination)
                                } else ModelDecoder.read(destination, donor)
                                val converted = model.copy(faceTextures = model.faceTextures?.map { raw ->
                                    if (raw == (-1).toShort()) raw else {
                                        val material = GielenorVisualProfile.runtimeMaterial(raw.toInt() and 65535)
                                        require(material in safeMaterials)
                                        material.toShort()
                                    }
                                }?.toShortArray())
                                val writer = ModelEncoder.encode(converted)
                                check(ModelEncoder.encode(ModelDecoder.read(destination, writer)).contentEquals(writer))
                                Asset(Index.MODELS, destination, 0, writer)
                            }
                            val addedIds = objects.map { (it.archive shl 8) or it.file }.toSet()
                            val existingIds = objectMap.values.filterTo(mutableSetOf()) {
                                staged.data(Index.OBJECTS, it ushr 8, it and 255) != null
                            }
                            val retained = existingIds + addedIds
                            val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
                            var prior = 0
                            var added = 0
                            var excluded = 0
                            val maps = regions.map { region ->
                                val name = "l${region ushr 8}_${region and 255}"
                                val archive = staged.archiveId(Index.MAPS, name)
                                check(archive >= 0 && archive == full.archiveId(Index.MAPS, name))
                                val raw = requireNotNull(full.data(Index.MAPS, name))
                                val map = MapDefinition(region)
                                val decoder = object : MapObjectDecoder() {
                                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                        if (objectId in retained) {
                                            map.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                                            if (objectId in addedIds) added++ else prior++
                                        } else excluded++
                                    }
                                }
                                check(decoder.decode(raw, ByteArray(16384), region ushr 8, region and 255) == raw.size)
                                val writer = BufferWriter(raw.size + 32)
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
                            check(prior == animatedReport.getValue("priorStaticPlacements").jsonPrimitive.int +
                                animatedReport.getValue("animatedPlacementsAdded").jsonPrimitive.int)
                            check(prior + added + excluded == landscapeReport.getValue("placements").jsonPrimitive.int)
                            Files.createDirectories(output)
                            val packed = output.resolve("packed-cache")
                            Files.createDirectory(packed)
                            Files.list(iconCache).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                                .forEach { Files.copy(it, packed.resolve(it.fileName)) } }
                            FileCache(packed.toString(), writable = true).use { result ->
                                for (asset in models + objects) result.write(asset.index, asset.archive, asset.file, asset.bytes)
                                for ((archive, bytes) in maps) result.write(Index.MAPS, archive, 0, bytes)
                            }
                            FileCache(packed.toString(), writable = false).use { result ->
                                for (asset in models + objects) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                                for ((archive, bytes) in maps) check(result.data(Index.MAPS, archive).contentEquals(bytes))
                                val touchedMaps = maps.map { it.first }.toSet()
                                for (archive in staged.archiveIds(Index.MAPS)) if (archive !in touchedMaps) {
                                    check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive)))
                                }
                                for (archive in staged.archiveIds(Index.OBJECTS)) for (file in staged.files(Index.OBJECTS, archive)) {
                                    check(staged.data(Index.OBJECTS, archive, file).contentEquals(result.data(Index.OBJECTS, archive, file)))
                                }
                                for (index in listOf(Index.MODELS, Index.GRAPHICS, Index.ANIMATIONS,
                                    Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS, Index.SYNTH_SOUNDS)) {
                                    for (archive in staged.archiveIds(index))
                                        check(staged.container(index, archive).contentEquals(result.container(index, archive)))
                                }
                                for (group in listOf(34, 36)) for (file in staged.files(Index.CONFIGS, group)) {
                                    check(staged.data(Index.CONFIGS, group, file).contentEquals(result.data(Index.CONFIGS, group, file)))
                                }
                            }
                            val report = buildJsonObject {
                                put("status", "experimental-icon-objects-subset")
                                put("scopeComplete", false)
                                put("packedCache", packed.toString())
                                put("objectPlanSha256", planHash)
                                put("sourceIconPreview", iconRoot.toString())
                                put("sourceAnimatedPreview", animatedRoot.toString())
                                put("iconObjectDefinitionsAdded", objects.size)
                                put("modelsAdded", models.size)
                                put("priorPlacements", prior)
                                put("iconPlacementsAdded", added)
                                put("excludedPlacements", excluded)
                                put("blockedAnimationCandidates", blockedByAnimation)
                                put("existingAssetsPreserved", true)
                                put("clientVisualValidated", false)
                                put("limitations", "Diagnostic icon-bearing objects only; missing animations and morphs remain. Never use as runtime cache.")
                            }
                            Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
                        }
                    }
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
