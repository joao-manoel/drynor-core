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
                val targetFloorsWithinByte = mapOf(1 to (0..254).mapNotNull { id ->
                    target.data(Index.CONFIGS, 1, id)?.let { id to it }
                }, 4 to (0..254).mapNotNull { id ->
                    target.data(Index.CONFIGS, 4, id)?.let { id to it }
                })
                fun floorReports(ids: Set<Int>, archive: Int, effective: (Int) -> Int = { it - 1 }) = buildJsonArray {
                    ids.forEach { rawId ->
                        val fileId = effective(rawId)
                        val sourceBytes = source.data(Index.CONFIGS, archive, fileId)
                        val targetBytes = target.data(Index.CONFIGS, archive, fileId)
                        add(buildJsonObject {
                            put("rawId", rawId)
                            put("effectiveFileId", fileId)
                            put("sourceStatus", if (sourceBytes == null) "missing" else "present")
                            put("targetStatus", if (targetBytes == null) "missing" else "present")
                            if (sourceBytes != null) put("sourceSha256", sha256(sourceBytes))
                            if (targetBytes != null) put("targetSha256", sha256(targetBytes))
                            put("identicalBytes", sourceBytes != null && targetBytes != null && sourceBytes.contentEquals(targetBytes))
                            put("sameByteTargetFilesWithinByte", buildJsonArray {
                                if (sourceBytes != null) targetFloorsWithinByte.getValue(archive).forEach { (id, bytes) ->
                                    if (sourceBytes.contentEquals(bytes)) add(JsonPrimitive(id))
                                }
                            })
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
                    put("effectiveOverlayReports", floorReports(overlays, 4, ::gielenorOverlayFile))
                    put("effectiveUnderlayReports", floorReports(underlays, 1, ::gielenorUnderlayFile))
                    put("freeOverlayFilesWithinByte", buildJsonArray {
                        val occupied = target.files(Index.CONFIGS, 4).toSet()
                        (0..254).filter { it !in occupied }.forEach { add(JsonPrimitive(it)) }
                    })
                    put("freeUnderlayFilesWithinByte", buildJsonArray {
                        val occupied = target.files(Index.CONFIGS, 1).toSet()
                        (0..254).filter { it !in occupied }.forEach { add(JsonPrimitive(it)) }
                    })
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Class240 adds 1000 to OSRS terrain IDs; Class375 then resolves these exceptions. */
    private fun gielenorOverlayFile(raw: Int): Int {
        val offsetId = 999 + raw
        return when (offsetId) {
            1005 -> 111
            1084 -> 113
            1051 -> 97
            in retainedOsrsOverlays -> offsetId
            else -> raw - 1
        }
    }

    /** Class451 keeps OSRS underlay 1026; all other OSRS IDs return to the native index. */
    private fun gielenorUnderlayFile(raw: Int): Int = if (raw == 27) 1026 else raw - 1

    private val retainedOsrsOverlays = setOf(
        1110, 1111, 1112, 1071, 1065, 1066, 1070, 1127, 1072, 1091,
        1098, 1095, 1113, 1056, 1060, 1061, 1151, 1135, 1120,
    )
}
