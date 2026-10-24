package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
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
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.encoder.OverlayEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** One-region, terrain-only proof. It never promotes the packed output into the runtime cache. */
object ZeahFloorPreview {
    private const val REGION = 7951 // Chambers of Xeric room template, m31_15.
    private val newFloorFiles = mapOf(57 to 250, 67 to 251, 71 to 252, 136 to 253)
    private const val ALIAS_SOURCE = 152
    private const val ALIAS_FILE = 249
    private val json = Json { prettyPrint = true }

    fun build(sourcePath: Path, targetPath: Path, auditPath: Path, outputPath: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val targetRoot = targetPath.toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(sourceRoot != targetRoot && !output.startsWith(sourceRoot) && !output.startsWith(targetRoot))
        require(!Files.exists(output)) { "Preview output already exists: $output" }
        val audit = Json.parseToJsonElement(Files.readString(auditPath)).jsonObject
        require(Path.of(audit.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(audit.getValue("targetCache").jsonPrimitive.content).toRealPath() == targetRoot)
        require(audit.getValue("regionReports").jsonArray.any {
            val item = it.jsonObject
            item.getValue("regionId").jsonPrimitive.int == REGION && item.getValue("status").jsonPrimitive.content == "decoded"
        })
        val sourceHashes = cacheHashes(sourceRoot)
        val targetHashes = cacheHashes(targetRoot)
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val region = audit.getValue("regionReports").jsonArray.single { it.jsonObject.getValue("regionId").jsonPrimitive.int == REGION }.jsonObject
                val overlays = audit.getValue("effectiveOverlayReports").jsonArray.associateBy { it.jsonObject.getValue("rawId").jsonPrimitive.int }
                val underlays = audit.getValue("effectiveUnderlayReports").jsonArray.associateBy { it.jsonObject.getValue("rawId").jsonPrimitive.int }
                require(region.getValue("overlayIds").jsonArray.map { it.jsonPrimitive.int }.toSet() ==
                    (newFloorFiles.keys + ALIAS_SOURCE + listOf(17, 30, 37, 42)).toSet()) { "Pilot floor set changed; re-audit first." }
                require(target.files(Index.CONFIGS, 4).none { it in newFloorFiles.values })
                check(source.data(Index.CONFIGS, 4, 1151).contentEquals(target.data(Index.CONFIGS, 4, ALIAS_FILE)))
                for (raw in region.getValue("overlayIds").jsonArray.map { it.jsonPrimitive.int }) {
                    val entry = overlays.getValue(raw).jsonObject
                    val file = entry.getValue("effectiveFileId").jsonPrimitive.int
                    val bytes = requireNotNull(source.data(Index.CONFIGS, 4, file))
                    if (raw !in newFloorFiles && raw != ALIAS_SOURCE) {
                        check(file == raw - 1 && bytes.contentEquals(target.data(Index.CONFIGS, 4, file)))
                    }
                }
                for (raw in region.getValue("underlayIds").jsonArray.map { it.jsonPrimitive.int }) {
                    val entry = underlays.getValue(raw).jsonObject
                    val file = entry.getValue("effectiveFileId").jsonPrimitive.int
                    check(file == raw - 1 && source.data(Index.CONFIGS, 1, file).contentEquals(target.data(Index.CONFIGS, 1, file)))
                }
                val floorAssets = newFloorFiles.map { (raw, targetFile) ->
                    val sourceFile = overlays.getValue(raw).jsonObject.getValue("effectiveFileId").jsonPrimitive.int
                    val sourceBytes = requireNotNull(source.data(Index.CONFIGS, 4, sourceFile))
                    val floor = OverlayDefinition(targetFile)
                    OverlayDecoder().readLoop(floor, BufferReader(sourceBytes))
                    require(floor.texture == -1) { "Textured floor $raw requires material conversion." }
                    val writer = BufferWriter(sourceBytes.size + 32)
                    with(OverlayEncoder()) { writer.encode(floor) }
                    val converted = writer.toArray()
                    val readback = OverlayDefinition(targetFile)
                    OverlayDecoder().readLoop(readback, BufferReader(converted))
                    check(readback == floor && converted.contentEquals(sourceBytes)) { "Floor round-trip changed $raw." }
                    targetFile to converted
                }
                val sourceTerrain = requireNotNull(source.data(Index.MAPS, "m31_15"))
                val terrain = LegacyTerrainConversion.decode(sourceTerrain, REGION)
                var remappedTiles = 0
                for (index in terrain.tiles.indices) {
                    val tile = MapTile(terrain.tiles[index])
                    val floor = when (tile.overlayId) {
                        ALIAS_SOURCE -> ALIAS_FILE + 1
                        in newFloorFiles -> newFloorFiles.getValue(tile.overlayId) + 1
                        else -> tile.overlayId
                    }
                    if (floor != tile.overlayId) remappedTiles++
                    terrain.tiles[index] = MapTile(tile.height, tile.attrOpcode, floor, tile.overlayPath, tile.overlayRotation, tile.settings, tile.underlayId).packed
                }
                val writer = BufferWriter(sourceTerrain.size + 1024)
                with(MapTileEncoder()) { writer.encode(terrain) }
                val terrainBytes = writer.toArray()
                val readback = MapDefinition(REGION)
                check(MapTileDecoder.decode(terrainBytes, readback) == terrainBytes.size)
                check(terrain.tiles.contentEquals(readback.tiles))
                check(target.archiveId(Index.MAPS, "m31_15") == -1)
                val mapArchive = target.lastArchiveId(Index.MAPS) + 1
                Files.createDirectories(output)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(targetRoot).use { paths ->
                    paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it, packed.resolve(it.fileName)) }
                }
                FileCache(packed.toString(), writable = true).use { result ->
                    floorAssets.forEach { (file, bytes) -> result.write(Index.CONFIGS, 4, file, bytes) }
                    val table = requireNotNull(result.referenceTable(Index.MAPS))
                    check(table.named)
                    table.archiveOrCreate(mapArchive).nameHash = "m31_15".hashCode()
                    result.write(Index.MAPS, mapArchive, 0, terrainBytes)
                }
                var unchangedMapArchives = 0
                FileCache(packed.toString(), writable = false).use { result ->
                    check(result.archiveId(Index.MAPS, "m31_15") == mapArchive)
                    check(result.data(Index.MAPS, "m31_15").contentEquals(terrainBytes))
                    floorAssets.forEach { (file, bytes) -> check(result.data(Index.CONFIGS, 4, file).contentEquals(bytes)) }
                    for (file in target.files(Index.CONFIGS, 4)) {
                        check(target.data(Index.CONFIGS, 4, file).contentEquals(result.data(Index.CONFIGS, 4, file))) { "Existing overlay $file changed." }
                    }
                    for (archive in target.archiveIds(Index.MAPS)) {
                        check(target.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                            "Existing map archive $archive changed."
                        }
                        unchangedMapArchives++
                    }
                }
                check(sourceHashes == cacheHashes(sourceRoot) && targetHashes == cacheHashes(targetRoot))
                val report = buildJsonObject {
                    put("status", "experimental-terrain-only")
                    put("regionId", REGION)
                    put("sourceTerrain", "m31_15")
                    put("packedCache", packed.toString())
                    put("mapArchive", mapArchive)
                    put("remappedTiles", remappedTiles)
                    put("overlayMapping", buildJsonArray {
                        (newFloorFiles + (ALIAS_SOURCE to ALIAS_FILE)).toSortedMap().forEach { (raw, file) ->
                            add(buildJsonObject { put("sourceRawId", raw); put("targetTileId", file + 1); put("reusedExisting", raw == ALIAS_SOURCE) })
                        }
                    })
                    put("sourceAndTargetCacheHashesPreserved", true)
                    put("unchangedMapArchivesVerified", unchangedMapArchives)
                    put("terrainAndOverlayReadback", true)
                    put("clientValidated", false)
                    put("gameplayImplemented", false)
                    put("limitations", "One CoX room terrain only; no landscape objects, NPCs, instances, world map, animations or gameplay. Never use as a runtime cache.")
                }
                Files.writeString(output.resolve("preview-report.json"), json.encodeToString(report) + "\n")
            }
        }
    }

    private fun cacheHashes(path: Path): Map<String, String> = Files.list(path).use { paths ->
        paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.sorted().toList().associate {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(it).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            it.fileName.toString() to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
