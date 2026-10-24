package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.buffer.write.BufferWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only floor and environment survey before a connected Zeah map conversion. */
object ZeahTerrainAudit {
    fun audit(sourcePath: Path, planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourcePath.toRealPath())
        val targetPath = Path.of(plan.getValue("targetCache").jsonPrimitive.content)
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.content.toInt() }.sorted()
        val overlays = sortedSetOf<Int>()
        val underlays = sortedSetOf<Int>()
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val reports = buildJsonArray {
                    for (region in regions) {
                        val name = "m${region ushr 8}_${region and 255}"
                        add(buildJsonObject {
                            put("regionId", region)
                            put("archiveName", name)
                            put("targetArchive", target.archiveId(Index.MAPS, name))
                            try {
                                val bytes = requireNotNull(source.data(Index.MAPS, name)) { "Missing donor terrain" }
                                val map = MapDefinition(region)
                                val tileEnd = MapTileDecoder.decode(bytes, map)
                                val normalized = LegacyTerrainConversion.decode(bytes, region)
                                val normalizedTiles = map.tiles.indices.count { map.tiles[it] != normalized.tiles[it] }
                                val writer = BufferWriter(bytes.size + 1024)
                                with(MapTileEncoder()) { writer.encode(normalized) }
                                val packed = writer.toArray()
                                val readback = MapDefinition(region)
                                check(MapTileDecoder.decode(packed, readback) == packed.size)
                                check(normalized.tiles.contentEquals(readback.tiles))
                                put("status", "decoded")
                                put("sourceSha256", sha256(bytes))
                                put("sourceBytes", bytes.size)
                                put("environmentTailBytes", bytes.size - tileEnd)
                                put("heightNormalizedTiles", normalizedTiles)
                                put("nativeTileRoundTrip", true)
                                val localOverlays = sortedSetOf<Int>()
                                val localUnderlays = sortedSetOf<Int>()
                                for (packed in map.tiles) {
                                    val tile = MapTile(packed)
                                    if (tile.overlayId > 0) localOverlays.add(tile.overlayId)
                                    if (tile.underlayId > 0) localUnderlays.add(tile.underlayId)
                                }
                                overlays.addAll(localOverlays)
                                underlays.addAll(localUnderlays)
                                put("overlayIds", buildJsonArray { localOverlays.forEach { add(JsonPrimitive(it)) } })
                                put("underlayIds", buildJsonArray { localUnderlays.forEach { add(JsonPrimitive(it)) } })
                            } catch (error: Exception) {
                                put("status", "blocked")
                                put("error", "${error.javaClass.simpleName}: ${error.message}")
                            }
                        })
                    }
                }
                fun floorReports(ids: Set<Int>, archive: Int) = buildJsonArray {
                    ids.forEach { rawId ->
                        val fileId = rawId - 1
                        val sourceBytes = source.data(Index.CONFIGS, archive, fileId)
                        val targetBytes = target.data(Index.CONFIGS, archive, fileId)
                        add(buildJsonObject {
                            put("rawId", rawId)
                            put("sourceStatus", if (sourceBytes == null) "missing" else "present")
                            put("targetStatus", if (targetBytes == null) "missing" else "present")
                            if (sourceBytes != null) put("sourceSha256", sha256(sourceBytes))
                            if (targetBytes != null) put("targetSha256", sha256(targetBytes))
                            put("identicalBytes", sourceBytes != null && targetBytes != null && sourceBytes.contentEquals(targetBytes))
                        })
                    }
                }
                val result = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("sourceCache", sourcePath.toString())
                    put("targetCache", targetPath.toString())
                    put("objectPlan", planPath.toString())
                    put("selectedRegionCount", regions.size)
                    put("regionReports", reports)
                    put("overlayReports", floorReports(overlays, 4))
                    put("underlayReports", floorReports(underlays, 1))
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
