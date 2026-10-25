package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Counts placements still absent from a diagnostic preview and labels their audited blockers. */
object ZeahRemainingObjectAudit {
    fun audit(previewRootPath: Path, landscapeRootPath: Path, planPath: Path, staticAuditPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val previewRoot = previewRootPath.toRealPath()
        val landscapeRoot = landscapeRootPath.toRealPath()
        val preview = Json.parseToJsonElement(Files.readString(previewRoot.resolve("preview-report.json"))).jsonObject
        val landscape = Json.parseToJsonElement(Files.readString(landscapeRoot.resolve("preview-report.json"))).jsonObject
        val previewCache = Path.of(preview.getValue("packedCache").jsonPrimitive.content).toRealPath()
        val landscapeCache = Path.of(landscape.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(previewCache == previewRoot.resolve("packed-cache") && landscapeCache == landscapeRoot.resolve("packed-cache"))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val staticAudit = Json.parseToJsonElement(Files.readString(staticAuditPath)).jsonObject
        require(staticAudit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        val originByTarget = plan.getValue("sourceObjects").jsonArray.associate {
            it.jsonObject.getValue("plannedTargetId").jsonPrimitive.int to it.jsonObject.getValue("runtimeId").jsonPrimitive.int
        }
        val reasons = staticAudit.getValue("objectReports").jsonArray.associate {
            it.jsonObject.getValue("sourceRuntimeId").jsonPrimitive.int to
                it.jsonObject.getValue("reasons").jsonArray.map { reason -> reason.jsonPrimitive.content }
        }
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
        val missing = sortedMapOf<Int, MutableMap<Int, Int>>()
        var total = 0
        FileCache(previewCache.toString(), writable = false).use { staged ->
            FileCache(landscapeCache.toString(), writable = false).use { full ->
                val available = mutableMapOf<Int, Boolean>()
                for (region in regions) {
                    val name = "l${region ushr 8}_${region and 255}"
                    val bytes = requireNotNull(full.data(Index.MAPS, name))
                    val decoder = object : MapObjectDecoder() {
                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                            if (!available.getOrPut(objectId) { staged.data(Index.OBJECTS, objectId ushr 8, objectId and 255) != null }) {
                                val byRegion = missing.getOrPut(objectId) { sortedMapOf() }
                                byRegion[region] = (byRegion[region] ?: 0) + 1
                                total++
                            }
                        }
                    }
                    check(decoder.decode(bytes, ByteArray(16384), region ushr 8, region and 255) == bytes.size)
                }
            }
        }
        require(total == preview.getValue("excludedPlacements").jsonPrimitive.int)
        val report = buildJsonObject {
            put("schemaVersion", 1)
            put("objectPlanSha256", sha256(planBytes))
            put("previewStatus", preview.getValue("status").jsonPrimitive.content)
            put("excludedPlacements", total)
            put("excludedDefinitions", missing.size)
            put("reports", buildJsonArray { missing.entries.sortedByDescending { it.value.values.sum() }.forEach { (target, owners) -> add(buildJsonObject {
                val origin = requireNotNull(originByTarget[target])
                put("plannedTargetId", target)
                put("sourceRuntimeId", origin)
                put("placements", owners.values.sum())
                put("reasons", buildJsonArray { reasons[origin].orEmpty().forEach { add(JsonPrimitive(it)) } })
                put("regions", buildJsonArray { owners.forEach { (region, count) -> add(buildJsonObject {
                    put("regionId", region)
                    put("placements", count)
                }) } })
            }) } })
            put("writesCache", false)
            put("limitations", "Definition absence in a diagnostic cache is not a decision to remove the object from final content.")
        }
        Files.createDirectories(outputPath.toAbsolutePath().parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
