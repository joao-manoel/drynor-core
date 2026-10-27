package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Builds opt-in surface spawns only for NPCs backed by the passive preview or identical native definitions. */
object ZeahNpcSpawnPreview {
    fun build(mapPreviewRoot: Path, npcPreviewRoot: Path, spawnPath: Path, idPlanPath: Path, outputRoot: Path) {
        val mapRoot = mapPreviewRoot.toRealPath()
        val npcRoot = npcPreviewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(mapRoot) && !output.startsWith(npcRoot))
        val mapReport = Json.parseToJsonElement(Files.readString(mapRoot.resolve("preview-report.json"))).jsonObject
        val npcReport = Json.parseToJsonElement(Files.readString(npcRoot.resolve("preview-report.json"))).jsonObject
        require(mapReport.getValue("status").jsonPrimitive.content == "experimental-zeah-worldmap-diagnostic")
        require(npcReport.getValue("status").jsonPrimitive.content == "experimental-zeah-passive-npcs")
        require(npcReport.getValue("mapPreviewSha256").jsonPrimitive.content == hash(Files.readAllBytes(mapRoot.resolve("preview-report.json"))))
        val planBytes = Files.readAllBytes(idPlanPath)
        val spawnBytes = Files.readAllBytes(spawnPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val inventory = Json.parseToJsonElement(spawnBytes.decodeToString()).jsonObject
        require(npcReport.getValue("idPlanSha256").jsonPrimitive.content == hash(planBytes))
        require(plan.getValue("spawnReportSha256").jsonPrimitive.content == hash(spawnBytes))
        val mapRegions = mapReport.getValue("regions").jsonArray.map { it.jsonObject.getValue("regionId").jsonPrimitive.int }.toSet()
        val imported = npcReport.getValue("npcIds").jsonArray.map { it.jsonPrimitive.int }.toSet()
        val mappings = plan.getValue("npcMappings").jsonArray.associate { row ->
            row.jsonObject.getValue("sourceId").jsonPrimitive.int to row.jsonObject.getValue("targetId").jsonPrimitive.int
        }
        val identicalNative = plan.getValue("nativeRangeReports").jsonArray.map { it.jsonObject }
            .filter { it.getValue("status").jsonPrimitive.content == "byte-identical" }
            .map { it.getValue("id").jsonPrimitive.int }.toSet()
        val sourceSpawns = inventory.getValue("spawns").jsonArray.map { it.jsonObject }
        val eligible = sourceSpawns.filter { it.getValue("donorGuardAllows").jsonPrimitive.boolean &&
            it.getValue("regionId").jsonPrimitive.int in mapRegions }
        val selected = eligible.mapNotNull { source ->
            val id = source.getValue("id").jsonPrimitive.int
            val target = if (id in identicalNative) id else mappings[id]?.takeIf { it in imported }
            target?.let { source to it }
        }.distinctBy { (source, target) -> listOf(target, source.getValue("x").jsonPrimitive.int,
            source.getValue("y").jsonPrimitive.int, source.getValue("plane").jsonPrimitive.int) }
        require(selected.isNotEmpty())
        val outputSpawns = buildJsonArray {
            selected.forEach { (source, target) ->
                add(buildJsonObject {
                    put("comment", "Zeah passive visual preview")
                    put("npcId", target)
                    put("tile", buildJsonObject {
                        put("x", source.getValue("x").jsonPrimitive.int)
                        put("y", source.getValue("y").jsonPrimitive.int)
                        put("plane", source.getValue("plane").jsonPrimitive.int)
                    })
                })
            }
        }
        Files.createDirectories(output)
        Files.writeString(output.resolve("npc-spawns.json"), Json { prettyPrint = true }.encodeToString(outputSpawns) + "\n", CREATE_NEW)
        val report = buildJsonObject {
            put("status", "experimental-zeah-surface-spawns")
            put("npcPreviewSha256", hash(Files.readAllBytes(npcRoot.resolve("preview-report.json"))))
            put("spawnReportSha256", hash(spawnBytes)); put("idPlanSha256", hash(planBytes))
            put("surfaceRegions", mapRegions.size); put("eligibleSourceSpawns", eligible.size)
            put("selectedSpawns", selected.size); put("excludedSpawns", eligible.size - selected.size)
            put("npcSpawnsSha256", hash(Files.readAllBytes(output.resolve("npc-spawns.json"))))
            put("limitations", "Passive local surface preview only. Caves, instances, blocked NPCs, combat, drops and source-specific behavior are not imported.")
        }
        Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
