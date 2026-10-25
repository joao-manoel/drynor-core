package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Remapped placements only. Models, object definitions and gameplay remain intentionally absent. */
object ZeahLandscapePreview {
    fun build(terrainRootPath: Path, planPath: Path, outputPath: Path) {
        val json = Json { prettyPrint = true }
        val terrainRoot = terrainRootPath.toRealPath()
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        val preview = Json.parseToJsonElement(Files.readString(terrainRoot.resolve("preview-report.json"))).jsonObject
        require(preview.getValue("status").jsonPrimitive.content == "experimental-terrain-and-floors")
        require(plan.getValue("scopeComplete").jsonPrimitive.content == "false")
        require(plan.getValue("maxPlacementObjectId").jsonPrimitive.int == ZeahPlacementIds.MAX_ID)
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val terrainCache = Path.of(preview.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(terrainCache == terrainRoot.resolve("packed-cache"))
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(sourceRoot, targetRoot, terrainRoot).none { output.startsWith(it) || it.startsWith(output) })
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
        require(regions.size == preview.getValue("importedTerrainRegions").jsonPrimitive.int && regions == regions.sorted())
        val mapped = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }.associate { entry ->
            val sourceId = entry.getValue("runtimeId").jsonPrimitive.int
            val destinationId = entry.getValue("plannedTargetId").jsonPrimitive.int
            require(sourceId >= 100_000 && destinationId in 0..ZeahPlacementIds.MAX_ID)
            sourceId to (destinationId to entry.getValue("sourceSha256").jsonPrimitive.content)
        }
        check(mapped.size == plan.getValue("sourceObjectDefinitions").jsonPrimitive.int)
        check(mapped.values.map { it.first }.toSet().size == mapped.size)
        val expectedHashes = mutableMapOf<Int, String>()
        for (file in plan.getValue("inputReports").jsonArray) {
            val report = Json.parseToJsonElement(Files.readString(Path.of(file.jsonPrimitive.content))).jsonObject
            require(Path.of(report.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
            for (entry in report.getValue("regions").jsonArray) {
                val region = entry.jsonObject
                val id = region.getValue("regionId").jsonPrimitive.int
                if (id !in regions) continue
                val landscape = region.getValue("landscape").jsonObject
                require(landscape.getValue("sourceStatus").jsonPrimitive.content == "readable")
                val hash = landscape.getValue("sourceSha256").jsonPrimitive.content
                val previous = expectedHashes.putIfAbsent(id, hash)
                require(previous == null || previous == hash)
            }
        }
        check(expectedHashes.keys == regions.toSet())

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(terrainCache.toString(), writable = false).use { staged ->
                    var placementCount = 0
                    val referenced = sortedSetOf<Int>()
                    val landscapes = regions.map { id ->
                        val name = "l${id ushr 8}_${id and 255}"
                        check(target.archiveId(Index.MAPS, name) == -1 && staged.archiveId(Index.MAPS, name) == -1)
                        check(staged.archiveId(Index.MAPS, "m${id ushr 8}_${id and 255}") >= 0)
                        val donor = requireNotNull(source.data(Index.MAPS, name)) { "Missing donor landscape $name" }
                        check(sha256(donor) == expectedHashes.getValue(id)) { "Donor landscape changed: $name" }
                        val definition = MapDefinition(id)
                        val decoder = object : MapObjectDecoder() {
                            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                val runtimeId = objectId + 100_000
                                val targetId = mapped[runtimeId]?.first ?: error("Missing global object mapping $runtimeId in $name")
                                require(localX in 0..63 && localY in 0..63 && level in 0..3 && shape in 0..22 && rotation in 0..3)
                                definition.objects.add(MapObject(targetId, localX, localY, level, shape, rotation))
                                referenced += runtimeId
                                placementCount++
                            }
                        }
                        val end = decoder.decode(donor, ByteArray(16384), id ushr 8, id and 255)
                        check(donor.drop(end).all { it == 0.toByte() })
                        val writer = BufferWriter(donor.size + 4096)
                        with(MapObjectEncoder()) { writer.encode(definition) }
                        val bytes = writer.toArray()
                        val decoded = MapDefinition(id)
                        val readback = object : MapObjectDecoder() {
                            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                decoded.objects.add(MapObject(objectId, localX, localY, level, shape, rotation))
                            }
                        }
                        check(readback.decode(bytes, ByteArray(16384), id ushr 8, id and 255) == bytes.size)
                        check(decoded.objects.map { it.packed }.sorted() == definition.objects.map { it.packed }.sorted())
                        name to bytes
                    }
                    for (runtimeId in referenced) {
                        val donor = requireNotNull(source.data(Index.OBJECTS, runtimeId ushr 8, runtimeId and 255))
                        check(sha256(donor) == mapped.getValue(runtimeId).second) { "Donor object changed: $runtimeId" }
                    }
                    Files.createDirectories(output)
                    val packed = output.resolve("packed-cache")
                    Files.createDirectory(packed)
                    Files.list(terrainCache).use { files ->
                        files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                            .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                    }
                    val firstArchive = staged.lastArchiveId(Index.MAPS) + 1
                    FileCache(packed.toString(), writable = true).use { result ->
                        val table = requireNotNull(result.referenceTable(Index.MAPS))
                        check(table.named)
                        landscapes.forEachIndexed { index, (name, bytes) ->
                            val archive = firstArchive + index
                            table.archiveOrCreate(archive).nameHash = name.hashCode()
                            result.write(Index.MAPS, archive, 0, bytes)
                        }
                    }
                    FileCache(packed.toString(), writable = false).use { result ->
                        landscapes.forEachIndexed { index, (name, bytes) ->
                            check(result.archiveId(Index.MAPS, name) == firstArchive + index)
                            check(result.data(Index.MAPS, name).contentEquals(bytes))
                        }
                        for (archive in staged.archiveIds(Index.MAPS)) {
                            check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                                "Existing terrain or map archive changed: $archive"
                            }
                        }
                    }
                    val report = buildJsonObject {
                        put("status", "experimental-landscapes-without-definitions")
                        put("scopeComplete", false)
                        put("packedCache", packed.toString())
                        put("landscapeRegions", landscapes.size)
                        put("placements", placementCount)
                        put("referencedDonorObjectDefinitionsVerified", referenced.size)
                        put("boundedGlobalMappings", mapped.size)
                        put("existingMapArchivesPreserved", staged.archiveIds(Index.MAPS).size)
                        put("objectDefinitionsImported", false)
                        put("clientVisualValidated", false)
                        put("limitations", "Placements only over staged terrain. Missing object definitions, models, animations, NPCs, items, world-map area and gameplay. Never use as runtime cache.")
                    }
                    Files.writeString(output.resolve("preview-report.json"), json.encodeToString(report) + "\n")
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
