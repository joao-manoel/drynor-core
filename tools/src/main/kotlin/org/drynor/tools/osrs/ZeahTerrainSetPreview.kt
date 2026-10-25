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
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Terrain-only continuation of the verified definitions preview; never writes the active cache. */
object ZeahTerrainSetPreview {
    fun build(definitionsRoot: Path, auditPath: Path, planPath: Path, outputPath: Path) {
        val json = Json { prettyPrint = true }
        val previewRoot = definitionsRoot.toRealPath()
        val auditBytes = Files.readAllBytes(auditPath)
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        val sourceRoot = Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(audit.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output)) { "Preview output already exists: $output" }
        require(listOf(sourceRoot, targetRoot, previewRoot).none { output.startsWith(it) || it.startsWith(output) })
        require(plan.getValue("scopeComplete").jsonPrimitive.content == "false")
        require(plan.getValue("auditSha256").jsonPrimitive.content == sha256(auditBytes)) { "Floor plan is stale" }
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath() == targetRoot)
        val definitionsReport = Json.parseToJsonElement(Files.readString(previewRoot.resolve("preview-report.json"))).jsonObject
        require(definitionsReport.getValue("status").jsonPrimitive.content == "experimental-definitions-only")
        val definitionsCache = Path.of(definitionsReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(definitionsCache == previewRoot.resolve("packed-cache"))
        val floorFiles = plan.getValue("floorDefinitionsToImport").jsonArray.map { it.jsonObject }
        val regions = audit.getValue("regionReports").jsonArray.map { it.jsonObject }.sortedBy { it.getValue("regionId").jsonPrimitive.int }
        val palettes = plan.getValue("regionPalettes").jsonArray.map { it.jsonObject }
        require(regions.size == audit.getValue("selectedRegionCount").jsonPrimitive.int)
        require(regions.size == plan.getValue("selectedRegionCount").jsonPrimitive.int && regions.size == palettes.size)
        check(regions.map { it.getValue("regionId").jsonPrimitive.int } == palettes.map { it.getValue("sourceRegionId").jsonPrimitive.int })
        check(regions.all { it.getValue("status").jsonPrimitive.content == "decoded" && it.getValue("targetArchive").jsonPrimitive.int == -1 })

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(definitionsCache.toString(), writable = false).use { staged ->
                    for (asset in floorFiles) {
                        val archive = asset.getValue("configArchive").jsonPrimitive.int
                        val file = asset.getValue("fileId").jsonPrimitive.int
                        val bytes = requireNotNull(source.data(Index.CONFIGS, archive, file))
                        check(sha256(bytes) == asset.getValue("sourceSha256").jsonPrimitive.content)
                        check(target.data(Index.CONFIGS, archive, file) == null)
                        check(staged.data(Index.CONFIGS, archive, file).contentEquals(bytes))
                    }
                    for (archive in target.archiveIds(Index.MAPS)) {
                        check(target.container(Index.MAPS, archive).contentEquals(staged.container(Index.MAPS, archive))) {
                            "Definition preview altered existing map archive $archive"
                        }
                    }
                    val terrain = regions.map { region ->
                        val id = region.getValue("regionId").jsonPrimitive.int
                        val name = region.getValue("archiveName").jsonPrimitive.content
                        check(name == "m${id ushr 8}_${id and 255}")
                        check(target.archiveId(Index.MAPS, name) == -1 && staged.archiveId(Index.MAPS, name) == -1)
                        val donor = requireNotNull(source.data(Index.MAPS, name))
                        check(sha256(donor) == region.getValue("sourceSha256").jsonPrimitive.content) { "Donor terrain changed: $name" }
                        val map = LegacyTerrainConversion.decode(donor, id)
                        val palette = palettes.single { it.getValue("sourceRegionId").jsonPrimitive.int == id }
                        for ((field, observed) in listOf("overlays" to "overlayIds", "underlays" to "underlayIds")) {
                            val mapping = palette.getValue(field).jsonArray.map { it.jsonObject }
                            val raw = mapping.map { it.getValue("rawTileId").jsonPrimitive.int }
                            check(raw == region.getValue(observed).jsonArray.map { it.jsonPrimitive.int }) { "Incomplete $field palette: $name" }
                            for (entry in mapping) {
                                val definition = entry.getValue("definitionId").jsonPrimitive.int
                                check(staged.data(Index.CONFIGS, if (field == "overlays") 4 else 1, definition) != null) {
                                    "Missing $field definition $definition for $name"
                                }
                            }
                        }
                        val actualOverlays = sortedSetOf<Int>()
                        val actualUnderlays = sortedSetOf<Int>()
                        for (packed in map.tiles) {
                            val tile = MapTile(packed)
                            if (tile.overlayId > 0) actualOverlays += tile.overlayId
                            if (tile.underlayId > 0) actualUnderlays += tile.underlayId
                        }
                        check(actualOverlays.toList() == region.getValue("overlayIds").jsonArray.map { it.jsonPrimitive.int })
                        check(actualUnderlays.toList() == region.getValue("underlayIds").jsonArray.map { it.jsonPrimitive.int })
                        val writer = BufferWriter(donor.size + 1024)
                        with(MapTileEncoder()) { writer.encode(map) }
                        val bytes = writer.toArray()
                        val decoded = MapDefinition(id)
                        check(MapTileDecoder.decode(bytes, decoded) == bytes.size && decoded.tiles.contentEquals(map.tiles))
                        Triple(id, name, bytes)
                    }
                    Files.createDirectories(output)
                    val packed = output.resolve("packed-cache")
                    Files.createDirectory(packed)
                    Files.list(definitionsCache).use { files ->
                        files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                            .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                    }
                    val firstArchive = staged.lastArchiveId(Index.MAPS) + 1
                    FileCache(packed.toString(), writable = true).use { result ->
                        val table = requireNotNull(result.referenceTable(Index.MAPS))
                        check(table.named)
                        terrain.forEachIndexed { index, (_, name, bytes) ->
                            val archive = firstArchive + index
                            table.archiveOrCreate(archive).nameHash = name.hashCode()
                            result.write(Index.MAPS, archive, 0, bytes)
                        }
                    }
                    FileCache(packed.toString(), writable = false).use { result ->
                        terrain.forEachIndexed { index, (id, name, bytes) ->
                            check(result.archiveId(Index.MAPS, name) == firstArchive + index)
                            check(result.data(Index.MAPS, name).contentEquals(bytes))
                            val decoded = MapDefinition(id)
                            check(MapTileDecoder.decode(bytes, decoded) == bytes.size)
                        }
                        for (asset in floorFiles) {
                            val archive = asset.getValue("configArchive").jsonPrimitive.int
                            val file = asset.getValue("fileId").jsonPrimitive.int
                            check(result.data(Index.CONFIGS, archive, file).contentEquals(staged.data(Index.CONFIGS, archive, file)))
                        }
                        for (archive in target.archiveIds(Index.MAPS)) {
                            check(target.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                                "Existing map archive changed: $archive"
                            }
                        }
                    }
                    Files.copy(planPath, output.resolve("floor-palettes-provisional.json"))
                    val report = buildJsonObject {
                        put("status", "experimental-terrain-and-floors")
                        put("scopeComplete", false)
                        put("packedCache", packed.toString())
                        put("importedTerrainRegions", terrain.size)
                        put("importedFloorDefinitions", floorFiles.size)
                        put("unchangedMapArchivesVerified", target.archiveIds(Index.MAPS).size)
                        put("sourceAndTargetCachesReadOnly", true)
                        put("clientVisualValidated", false)
                        put("landscapeObjectsImported", false)
                        put("limitations", "Terrain and floors only. No world-map area, landscape objects, NPCs, instances or gameplay. Requires provisional client palettes; never use as a runtime cache.")
                    }
                    Files.writeString(output.resolve("preview-report.json"), json.encodeToString(report) + "\n")
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
