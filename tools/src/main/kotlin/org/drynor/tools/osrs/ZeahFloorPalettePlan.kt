package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only, deterministic source-region floor manifest for the scene and world-map consumers. */
object ZeahFloorPalettePlan {
    fun plan(auditPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath)) { "Plan already exists: $outputPath" }
        val auditBytes = Files.readAllBytes(auditPath)
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        require(!audit.getValue("scopeComplete").jsonPrimitive.content.toBoolean()) { "A complete scope needs its own final allocation review." }
        val regions = audit.getValue("regionReports").jsonArray.map { it.jsonObject }
            .sortedBy { it.getValue("regionId").jsonPrimitive.int }
        check(regions.size == audit.getValue("selectedRegionCount").jsonPrimitive.int)
        check(regions.all { it.getValue("status").jsonPrimitive.content == "decoded" })

        fun definitions(field: String): Map<Int, JsonObject> = audit.getValue(field).jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("rawId").jsonPrimitive.int }
            .also { entries ->
                check(entries.size == audit.getValue(field).jsonArray.size) { "Duplicate raw floor ID in $field" }
                entries.values.forEach { entry ->
                    check(entry.getValue("sourceStatus").jsonPrimitive.content == "present")
                    check(entry.getValue("sourceSha256").jsonPrimitive.content.length == 64)
                    val status = entry.getValue("targetStatus").jsonPrimitive.content
                    check(status == "missing" || status == "present" && entry.getValue("identicalBytes").jsonPrimitive.content.toBoolean()) {
                        "Existing Drynor floor differs from donor: ${entry.getValue("effectiveFileId")}"
                    }
                }
            }

        val overlays = definitions("effectiveOverlayReports")
        val underlays = definitions("effectiveUnderlayReports")
        val regionIds = regions.map { it.getValue("regionId").jsonPrimitive.int }
        check(regionIds.distinct().size == regionIds.size)

        val result = buildJsonObject {
            put("schemaVersion", 1)
            put("scopeComplete", false)
            put("auditSha256", sha256(auditBytes))
            put("sourceCache", audit.getValue("sourceCache"))
            put("targetCache", audit.getValue("targetCache"))
            put("selectedRegionCount", regions.size)
            put("identityForUnlistedRegions", true)
            put("regionPalettes", buildJsonArray {
                for (region in regions) {
                    val id = region.getValue("regionId").jsonPrimitive.int
                    add(buildJsonObject {
                        put("sourceRegionId", id)
                        put("overlays", mapping(region, "overlayIds", overlays))
                        put("underlays", mapping(region, "underlayIds", underlays))
                    })
                }
            })
            put("floorDefinitionsToImport", buildJsonArray {
                for ((archive, records) in listOf(4 to overlays, 1 to underlays)) {
                    records.values.filter { it.getValue("targetStatus").jsonPrimitive.content == "missing" }
                        .sortedBy { it.getValue("effectiveFileId").jsonPrimitive.int }
                        .forEach { entry ->
                            add(buildJsonObject {
                                put("configArchive", archive)
                                put("fileId", entry.getValue("effectiveFileId"))
                                put("sourceSha256", entry.getValue("sourceSha256"))
                            })
                        }
                }
            })
            put("clientVisualValidated", false)
            put("worldMapAreaConverted", false)
        }
        Files.createDirectories(outputPath.toAbsolutePath().parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
    }

    private fun mapping(region: JsonObject, idsField: String, definitions: Map<Int, JsonObject>) = buildJsonArray {
        val ids = region.getValue(idsField).jsonArray.map { it.jsonPrimitive.int }
        check(ids.distinct().size == ids.size)
        for (raw in ids.sorted()) {
            val entry = definitions[raw] ?: error("Missing audited floor $raw in ${region.getValue("regionId")}")
            add(buildJsonObject {
                put("rawTileId", raw)
                put("definitionId", entry.getValue("effectiveFileId"))
            })
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
