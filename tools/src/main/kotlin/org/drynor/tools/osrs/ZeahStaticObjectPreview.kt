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

/** Explicitly incomplete static scene preview: unsupported placements are excluded, not disguised. */
object ZeahStaticObjectPreview {
    private data class Asset(val index: Int, val archive: Int, val file: Int, val bytes: ByteArray)

    fun build(landscapeRootPath: Path, planPath: Path, auditPath: Path, outputPath: Path, materialAuditPath: Path? = null) {
        val json = Json { prettyPrint = true }
        val landscapeRoot = landscapeRootPath.toRealPath()
        val landscape = Json.parseToJsonElement(Files.readString(landscapeRoot.resolve("preview-report.json"))).jsonObject
        require(landscape.getValue("status").jsonPrimitive.content == "experimental-landscapes-without-definitions")
        val landscapeCache = Path.of(landscape.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(landscapeCache == landscapeRoot.resolve("packed-cache"))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val audit = Json.parseToJsonElement(Files.readString(auditPath)).jsonObject
        require(audit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        val safeMaterials = if (materialAuditPath == null) emptySet() else {
            val materialAudit = Json.parseToJsonElement(Files.readString(materialAuditPath)).jsonObject
            require(materialAudit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
            require(materialAudit.getValue("objectAuditSha256").jsonPrimitive.content == sha256(Files.readAllBytes(auditPath)))
            materialAudit.getValue("reports").jsonArray.filter {
                it.jsonObject.getValue("safeToReuse").jsonPrimitive.content == "true"
            }.map { it.jsonObject.getValue("effectiveMaterialId").jsonPrimitive.int }.toSet()
        }
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        require(Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(audit.getValue("targetCache").jsonPrimitive.content).toRealPath() == targetRoot)
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(sourceRoot, targetRoot, landscapeRoot).none { output.startsWith(it) || it.startsWith(output) })
        val objectMap = plan.getValue("sourceObjects").jsonArray.associate { record ->
            record.jsonObject.getValue("runtimeId").jsonPrimitive.int to record.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }
        val objectHashes = plan.getValue("sourceObjects").jsonArray.associate { record ->
            record.jsonObject.getValue("runtimeId").jsonPrimitive.int to record.jsonObject.getValue("sourceSha256").jsonPrimitive.content
        }
        val modelMap = audit.getValue("modelReports").jsonArray.associate { record ->
            record.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to record.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }
        val modelHashes = audit.getValue("modelReports").jsonArray.associate { record ->
            record.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to record.jsonObject.getValue("sourceSha256").jsonPrimitive.content
        }
        val originallySelected = audit.getValue("objectReports").jsonArray.filter {
            it.jsonObject.getValue("status").jsonPrimitive.content == "exact-static-roundtrip"
        }.map { it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int }.toSet()
        require(originallySelected.size == audit.getValue("exactStaticObjectDefinitions").jsonPrimitive.int)
        val texturedCandidates = if (safeMaterials.isEmpty()) emptySet() else audit.getValue("objectReports").jsonArray
            .filter { report ->
                val reasons = report.jsonObject.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
                reasons == listOf("model-conversion")
            }.map { it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int }.toSet()
        val selected = originallySelected + texturedCandidates
        val selectedTargets = selected.mapTo(mutableSetOf()) { objectMap.getValue(it) }
        check(selectedTargets.size == selected.size)
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(landscapeCache.toString(), writable = false).use { staged ->
                    val objects = ArrayList<Asset>(selected.size)
                    val requiredModels = sortedSetOf<Int>()
                    for (id in selected.sorted()) {
                        val destination = objectMap.getValue(id)
                        val donor = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                        check(sha256(donor) == objectHashes.getValue(id)) { "Donor object changed: $id" }
                        check(target.data(Index.OBJECTS, destination ushr 8, destination and 255) == null)
                        check(staged.data(Index.OBJECTS, destination ushr 8, destination and 255) == null)
                        val deps = OsrsObjectDependencies.parse(donor)
                        require(deps.animations.isEmpty() && deps.morphs.isEmpty() && deps.sounds.isEmpty() &&
                            deps.sprites.isEmpty() && deps.mapIcons.isEmpty() && deps.varbits.isEmpty() && deps.varps.isEmpty())
                        requiredModels += deps.models.map { it + 200_000 }
                        val definition = OsrsObjectConverter.convert(destination, donor, modelMap, emptyMap(), objectMap, staticPreview = true)
                        val writer = BufferWriter(donor.size + 1024)
                        with(ObjectEncoder()) { writer.encode(definition) }
                        val bytes = writer.toArray()
                        val readback = ObjectDefinition(destination)
                        ObjectDecoder().readLoop(readback, BufferReader(bytes))
                        check(readback.modelIds.contentDeepEquals(definition.modelIds) && readback.sizeX == definition.sizeX && readback.sizeY == definition.sizeY)
                        objects += Asset(Index.OBJECTS, destination ushr 8, destination and 255, bytes)
                    }
                    val modelAssets = requiredModels.map { id ->
                        val destination = modelMap.getValue(id)
                        check(target.data(Index.MODELS, destination) == null && staged.data(Index.MODELS, destination) == null)
                        val donor = requireNotNull(source.data(Index.MODELS, id))
                        check(sha256(donor) == modelHashes.getValue(id)) { "Donor model changed: $id" }
                        val model = if (donor.size == 18 && donor.all { it == 0.toByte() }) {
                            val template = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                            check(template.vertexCount == 0 && template.faceCount == 0)
                            template.copy(id = destination)
                        } else ModelDecoder.read(destination, donor)
                        val converted = model.copy(faceTextures = model.faceTextures?.map { raw ->
                            if (raw == (-1).toShort()) raw else {
                                val effective = GielenorVisualProfile.runtimeMaterial(raw.toInt() and 65535)
                                require(effective in safeMaterials) { "Unverified material $effective on model $id" }
                                effective.toShort()
                            }
                        }?.toShortArray())
                        val bytes = ModelEncoder.encode(converted)
                        check(ModelEncoder.encode(ModelDecoder.read(destination, bytes)).contentEquals(bytes))
                        Asset(Index.MODELS, destination, 0, bytes)
                    }
                    var keptPlacements = 0
                    var excludedPlacements = 0
                    val landscapes = regions.map { id ->
                        val name = "l${id ushr 8}_${id and 255}"
                        val archive = staged.archiveId(Index.MAPS, name)
                        check(archive >= 0 && target.archiveId(Index.MAPS, name) == -1)
                        val prior = requireNotNull(staged.data(Index.MAPS, name))
                        val filtered = MapDefinition(id)
                        val decoder = object : MapObjectDecoder() {
                            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                if (objectId in selectedTargets) {
                                    filtered.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                                    keptPlacements++
                                } else {
                                    excludedPlacements++
                                }
                            }
                        }
                        check(decoder.decode(prior, ByteArray(16384), id ushr 8, id and 255) == prior.size)
                        val writer = BufferWriter(prior.size + 32)
                        with(MapObjectEncoder()) { writer.encode(filtered) }
                        val bytes = writer.toArray()
                        val readback = MapDefinition(id)
                        val reader = object : MapObjectDecoder() {
                            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                readback.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                            }
                        }
                        check(reader.decode(bytes, ByteArray(16384), id ushr 8, id and 255) == bytes.size)
                        check(readback.objects.map { it.packed }.sorted() == filtered.objects.map { it.packed }.sorted())
                        archive to bytes
                    }
                    check(keptPlacements + excludedPlacements == landscape.getValue("placements").jsonPrimitive.int)
                    Files.createDirectories(output)
                    val packed = output.resolve("packed-cache")
                    Files.createDirectory(packed)
                    Files.list(landscapeCache).use { files ->
                        files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                            .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                    }
                    FileCache(packed.toString(), writable = true).use { result ->
                        modelAssets.forEach { result.write(it.index, it.archive, it.file, it.bytes) }
                        objects.forEach { result.write(it.index, it.archive, it.file, it.bytes) }
                        landscapes.forEach { (archive, bytes) -> result.write(Index.MAPS, archive, 0, bytes) }
                    }
                    FileCache(packed.toString(), writable = false).use { result ->
                        for (asset in modelAssets + objects) {
                            check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                        }
                        val touched = landscapes.map { it.first }.toSet()
                        for ((archive, bytes) in landscapes) check(result.data(Index.MAPS, archive).contentEquals(bytes))
                        for (archive in staged.archiveIds(Index.MAPS)) if (archive !in touched) {
                            check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                                "Unrelated map archive changed: $archive"
                            }
                        }
                        for (archive in target.archiveIds(Index.OBJECTS)) for (file in target.files(Index.OBJECTS, archive)) {
                            check(target.data(Index.OBJECTS, archive, file).contentEquals(result.data(Index.OBJECTS, archive, file))) {
                                "Existing object definition changed: $archive/$file"
                            }
                        }
                        for (archive in target.archiveIds(Index.MODELS)) {
                            check(target.container(Index.MODELS, archive).contentEquals(result.container(Index.MODELS, archive))) {
                                "Existing model changed: $archive"
                            }
                        }
                    }
                    val report = buildJsonObject {
                        put("status", "experimental-static-objects-subset")
                        put("packedCache", packed.toString())
                        put("staticObjectDefinitions", objects.size)
                        put("texturedObjectDefinitionsAdded", texturedCandidates.size)
                        put("verifiedReusableMaterials", safeMaterials.size)
                        put("models", modelAssets.size)
                        put("landscapeRegions", landscapes.size)
                        put("keptPlacements", keptPlacements)
                        put("excludedPlacements", excludedPlacements)
                        put("unrelatedMapArchivesPreserved", staged.archiveIds(Index.MAPS).size - landscapes.size)
                        put("existingObjectDefinitionsPreserved", true)
                        put("existingModelsPreserved", true)
                        put("scopeComplete", false)
                        put("clientVisualValidated", false)
                        put("limitations", "Static subset only. Unsupported placements removed for preview; textured material bytes checked but no client visual test. No animated/morphed objects, NPCs, items, world-map area or gameplay. Never use as runtime cache.")
                    }
                    Files.writeString(output.resolve("preview-report.json"), json.encodeToString(report) + "\n")
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
