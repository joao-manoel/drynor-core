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

/** Records the donor's forced-selector fallback; makes no static-flattening decision. */
object ZeahMorphAudit {
    fun audit(planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val objects = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        val hashes = objects.associate { it.getValue("runtimeId").jsonPrimitive.int to it.getValue("sourceSha256").jsonPrimitive.content }
        val targetIds = objects.associate { it.getValue("runtimeId").jsonPrimitive.int to it.getValue("plannedTargetId").jsonPrimitive.int }
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            val dependencies = objects.associate { record ->
                val id = record.getValue("runtimeId").jsonPrimitive.int
                val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                check(sha256(bytes) == hashes.getValue(id))
                id to OsrsObjectDependencies.parse(bytes)
            }
            val morphIds = dependencies.filterValues { it.morphSelector != null }.keys
            val placements = morphIds.associateWith { sortedMapOf<Int, Int>() }.toMutableMap()
            for (region in regions) {
                val name = "l${region ushr 8}_${region and 255}"
                val bytes = requireNotNull(source.data(Index.MAPS, name))
                val decoder = object : MapObjectDecoder() {
                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                        val byRegion = placements[objectId + 100_000] ?: return
                        byRegion[region] = (byRegion[region] ?: 0) + 1
                    }
                }
                check(decoder.decode(bytes, ByteArray(16384), region ushr 8, region and 255) <= bytes.size)
            }
            fun terminal(start: Int): Pair<Int?, String> {
                val seen = mutableSetOf<Int>()
                var current = start
                while (true) {
                    if (!seen.add(current)) return null to "cycle"
                    val deps = dependencies[current] ?: return null to "child-outside-plan"
                    val selector = deps.morphSelector ?: return current to "terminal"
                    val child = selector.variants.lastOrNull() ?: -1
                    if (child < 0) return null to "fallback-invisible"
                    current = child + 100_000
                }
            }
            var placed = 0
            val reports = buildJsonArray {
                for (id in morphIds.sorted()) {
                    val selector = requireNotNull(dependencies.getValue(id).morphSelector)
                    val owners = placements.getValue(id)
                    val count = owners.values.sum()
                    placed += count
                    val (resolved, status) = terminal(id)
                    add(buildJsonObject {
                        put("sourceRuntimeId", id)
                        put("plannedTargetId", targetIds.getValue(id))
                        put("opcode", selector.opcode)
                        put("rawVarbitIgnoredByDonor", selector.rawVarbit)
                        put("rawVarpIgnoredByDonor", selector.rawVarp)
                        put("donorFallbackRawChild", selector.variants.lastOrNull() ?: -1)
                        put("defaultRawChild", selector.default)
                        put("fallbackTerminalRuntimeId", resolved ?: -1)
                        put("fallbackTerminalTargetId", resolved?.let { targetIds[it] } ?: -1)
                        put("fallbackStatus", status)
                        put("placements", count)
                        put("regions", buildJsonArray {
                            owners.forEach { (region, amount) -> add(buildJsonObject { put("regionId", region); put("placements", amount) }) }
                        })
                        put("variants", buildJsonArray { selector.variants.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            }
            val report = buildJsonObject {
                put("schemaVersion", 1)
                put("scopeComplete", false)
                put("objectPlanSha256", sha256(planBytes))
                put("morphDefinitions", morphIds.size)
                put("directPlacements", placed)
                put("regionsWithMorphs", placements.values.flatMap { it.keys }.toSet().size)
                put("reports", reports)
                put("writesCache", false)
                put("staticFallbackAuthorized", false)
                put("limitations", "Donor client/server force selectors to -1 and can override per object. This read-only report does not authorize flattening or removing gameplay morphs.")
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
