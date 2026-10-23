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

/** Unions verified region inventories before assigning any destination object IDs. */
object ZeahObjectIdPlan {
    fun build(targetPath: Path, outputPath: Path, reports: List<Path>) {
        require(reports.isNotEmpty() && !Files.exists(outputPath))
        val canonicalTarget = targetPath.toRealPath()
        val definitions = sortedMapOf<Int, String>()
        val regions = sortedSetOf<Int>()
        var sourceCache: String? = null
        var settingsHash: String? = null
        for (reportPath in reports) {
            val report = Json.parseToJsonElement(Files.readString(reportPath)).jsonObject
            require(report.getValue("targetCache").jsonPrimitive.content.let { Path.of(it).toRealPath() } == canonicalTarget) {
                "Region report uses another Drynor cache: $reportPath"
            }
            val source = report.getValue("sourceCache").jsonPrimitive.content
            val settings = report.getValue("sourceSettingsSha256").jsonPrimitive.content
            require(sourceCache == null || sourceCache == source)
            require(settingsHash == null || settingsHash == settings)
            sourceCache = source
            settingsHash = settings
            report.getValue("regions").jsonArray.forEach { entry ->
                val region = entry.jsonObject
                if (region.getValue("donorOsrsMapId").jsonPrimitive.content == "true" &&
                    region.getValue("landscape").jsonObject.getValue("sourceStatus").jsonPrimitive.content == "readable"
                ) regions.add(region.getValue("regionId").jsonPrimitive.content.toInt())
            }
            report.getValue("osrsObjectDefinitions").jsonArray.forEach { entry ->
                val definition = entry.jsonObject
                val id = definition.getValue("runtimeId").jsonPrimitive.content.toInt()
                require(definition.getValue("sourceStatus").jsonPrimitive.content == "readable") { "Missing source object $id" }
                val sha = definition.getValue("sourceSha256").jsonPrimitive.content
                val previous = definitions.putIfAbsent(id, sha)
                require(previous == null || previous == sha) { "Conflicting source object $id" }
            }
        }
        val directlyPlaced = definitions.size
        FileCache(sourceCache!!, writable = false).use { source ->
            val pending = ArrayDeque(definitions.keys)
            val parsed = mutableSetOf<Int>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!parsed.add(id)) continue
                val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255)) {
                    "Missing donor morph object $id"
                }
                val sha = hash(bytes)
                require(definitions[id] == sha) { "Donor object differs from region inventory: $id" }
                for (rawMorph in OsrsObjectDependencies.parse(bytes).morphs) {
                    val morphId = rawMorph + 100_000
                    if (morphId !in definitions) {
                        val morphBytes = requireNotNull(source.data(Index.OBJECTS, morphId ushr 8, morphId and 255)) {
                            "Missing donor morph object $morphId (referenced by $id)"
                        }
                        definitions[morphId] = hash(morphBytes)
                    }
                    pending.addLast(morphId)
                }
            }
        }
        FileCache(targetPath.toString(), writable = false).use { target ->
            val occupied = target.archiveIds(Index.OBJECTS)
                .flatMap { archive -> target.files(Index.OBJECTS, archive).map { file -> (archive shl 8) or file } }
                .toSet()
            val plan = DeterministicIds.plan(definitions.keys, occupied)
            val result = buildJsonObject {
                put("schemaVersion", 1)
                put("status", "read-only-provisional-plan")
                put("scopeComplete", false)
                put("sourceCache", sourceCache!!)
                put("sourceSettingsSha256", settingsHash!!)
                put("targetCache", canonicalTarget.toString())
                put("inputReports", buildJsonArray { reports.forEach { add(JsonPrimitive(it.toAbsolutePath().normalize().toString())) } })
                put("selectedOsrsRegions", buildJsonArray { regions.forEach { add(JsonPrimitive(it)) } })
                put("sourceObjectDefinitions", definitions.size)
                put("directlyPlacedObjectDefinitions", directlyPlaced)
                put("morphClosureAddedDefinitions", definitions.size - directlyPlaced)
                put("sourceObjects", buildJsonArray {
                    definitions.forEach { (id, sha) -> add(buildJsonObject {
                        put("runtimeId", id)
                        put("sourceSha256", sha)
                        put("plannedTargetId", plan.getValue(id))
                    }) }
                })
                put("occupiedTargetObjectIds", occupied.size)
                put("remaps", buildJsonArray {
                    plan.forEach { (sourceId, targetId) ->
                        if (sourceId != targetId) add(buildJsonObject {
                            put("sourceRuntimeId", sourceId)
                            put("sourceSha256", definitions.getValue(sourceId))
                            put("plannedTargetId", targetId)
                        })
                    }
                })
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
