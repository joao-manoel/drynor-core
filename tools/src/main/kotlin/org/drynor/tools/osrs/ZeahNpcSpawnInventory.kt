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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only inventory of the spawn lists actually loaded by Gielenor's NPCSpawns. */
object ZeahNpcSpawnInventory {
    private data class Spawn(val id: Int, val x: Int, val y: Int, val plane: Int, val source: String, val line: Int) {
        val regionId: Int get() = ((x ushr 6) shl 8) or (y ushr 6)
    }

    fun inventory(sourceRoot: Path, planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        val selectedRegions = plan.getValue("selectedOsrsRegions").jsonArray.mapTo(sortedSetOf()) { it.jsonPrimitive.content.toInt() }
        val spawns = mutableListOf<Spawn>()
        val issues = mutableListOf<String>()
        val nonBooleanAttackFlags = mutableListOf<String>()
        val files = listOf("data/npc/spawnsList.txt", "data/npc/customSpawnsList.txt")
        val fileHashes = sortedMapOf<String, String>()
        for (file in files) {
            val path = sourceRoot.resolve(file)
            val bytes = Files.readAllBytes(path)
            fileHashes[file] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            Files.readAllLines(path).forEachIndexed { index, raw ->
                if (raw.startsWith("//") || raw.startsWith("RSBOT")) return@forEachIndexed
                val parts = raw.split(" - ", limit = 2)
                val fields = parts.getOrNull(1)?.split(" ", limit = 5)
                val id = parts.firstOrNull()?.toIntOrNull()
                val x = fields?.getOrNull(0)?.toIntOrNull()
                val y = fields?.getOrNull(1)?.toIntOrNull()
                val plane = fields?.getOrNull(2)?.toIntOrNull()
                if (id == null || x == null || y == null || plane == null || fields.size !in setOf(3, 5)) {
                    issues.add("$file:${index + 1}: invalid spawn syntax")
                    return@forEachIndexed
                }
                if (fields.size == 5 && fields[4] !in setOf("true", "false")) {
                    // Gielenor's Boolean.parseBoolean silently treats these tokens as false.
                    nonBooleanAttackFlags.add("$file:${index + 1}")
                }
                spawns.add(Spawn(id, x, y, plane, file, index + 1))
            }
        }
        val selected = spawns.filter { it.regionId in selectedRegions }
        val npcGapExceptions = setOf(16001, 16000, 16006, 16008, 16018, 16020, 16025, 16026)
        fun donorGuardAllows(id: Int) = id >= 0 && (id !in 15941..20000 || id in npcGapExceptions)
        val runtimeEligible = selected.filter { donorGuardAllows(it.id) }
        val npcIds = selected.map { it.id }.distinct().sorted()
        val sourceCachePath = sourceRoot.resolve("data/cache")
        val targetCachePath = Path.of(plan.getValue("targetCache").jsonPrimitive.content)
        val definitionReports = buildJsonArray {
            FileCache(sourceCachePath.toString(), writable = false).use { source ->
                FileCache(targetCachePath.toString(), writable = false).use { target ->
                    npcIds.forEach { id ->
                        val sourceBytes = source.data(Index.NPCS, id ushr 7, id and 127)
                        val targetBytes = target.data(Index.NPCS, id ushr 7, id and 127)
                        add(buildJsonObject {
                            put("id", id)
                            put("sourceStatus", if (sourceBytes == null) "missing" else "present")
                            put("targetStatus", if (targetBytes == null) "missing" else "present")
                            if (sourceBytes != null) put("sourceSha256", sha256(sourceBytes))
                            if (targetBytes != null) put("targetSha256", sha256(targetBytes))
                            put("identicalBytes", sourceBytes != null && targetBytes != null && sourceBytes.contentEquals(targetBytes))
                        })
                    }
                }
            }
        }
        val result = buildJsonObject {
            put("schemaVersion", 1)
            put("scopeComplete", false)
            put("sourceRoot", sourceRoot.toString())
            put("sourceCache", sourceCachePath.toString())
            put("targetCache", targetCachePath.toString())
            put("objectPlan", planPath.toString())
            put("selectedRegionCount", selectedRegions.size)
            put("parseableSpawnCountAllRegions", spawns.size)
            put("selectedSpawnCount", selected.size)
            put("donorGuardEligibleSpawnCount", runtimeEligible.size)
            put("donorGuardSkippedSpawnCount", selected.size - runtimeEligible.size)
            put("selectedNpcIds", buildJsonArray { npcIds.forEach { add(JsonPrimitive(it)) } })
            put("donorGuardEligibleNpcIds", buildJsonArray {
                runtimeEligible.map { it.id }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("npcDefinitionReports", definitionReports)
            put("sourceFiles", buildJsonObject { fileHashes.forEach { (name, hash) -> put(name, hash) } })
            put("syntaxIssues", buildJsonArray { issues.forEach { add(JsonPrimitive(it)) } })
            put("nonBooleanAttackFlags", buildJsonArray { nonBooleanAttackFlags.forEach { add(JsonPrimitive(it)) } })
            put("regions", buildJsonArray {
                selected.groupBy { it.regionId }.toSortedMap().forEach { (regionId, regionSpawns) ->
                    add(buildJsonObject {
                        put("regionId", regionId)
                        put("spawnCount", regionSpawns.size)
                        put("npcIds", buildJsonArray {
                            regionSpawns.map { it.id }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
            put("spawns", buildJsonArray {
                selected.forEach { spawn ->
                    add(buildJsonObject {
                        put("id", spawn.id)
                        put("x", spawn.x)
                        put("y", spawn.y)
                        put("plane", spawn.plane)
                        put("regionId", spawn.regionId)
                        put("donorGuardAllows", donorGuardAllows(spawn.id))
                        put("source", spawn.source)
                        put("line", spawn.line)
                    })
                }
            })
        }
        Files.createDirectories(outputPath.toAbsolutePath().parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
