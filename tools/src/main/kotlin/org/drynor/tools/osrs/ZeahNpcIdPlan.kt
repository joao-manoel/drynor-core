package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Plans NPC IDs without changing either cache or assuming that matching numeric IDs mean matching NPCs. */
object ZeahNpcIdPlan {
    private const val MAX_NPC_ID = 32767

    internal fun allocate(sourceIds: Set<Int>, occupied: Set<Int>): Map<Int, Int> {
        require(sourceIds.all { it in 0..MAX_NPC_ID } && occupied.all { it >= 0 })
        val reserved = occupied.filterTo(mutableSetOf()) { it <= MAX_NPC_ID }
        val result = sortedMapOf<Int, Int>()
        for (id in sourceIds.sorted()) {
            if (id !in reserved) {
                result[id] = id
                reserved += id
            }
        }
        var free = MAX_NPC_ID
        for (id in sourceIds.sorted()) {
            if (id in result) continue
            while (free in reserved) free--
            require(free >= 0) { "No unused native NPC ID remains" }
            result[id] = free
            reserved += free
        }
        check(result.size == sourceIds.size && result.values.toSet().size == sourceIds.size)
        return result
    }

    fun plan(sourcePath: Path, targetPath: Path, spawnPath: Path, dependencyPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val sourceRoot = sourcePath.toRealPath()
        val targetRoot = targetPath.toRealPath()
        val spawnBytes = Files.readAllBytes(spawnPath)
        val dependencyBytes = Files.readAllBytes(dependencyPath)
        val spawn = Json.parseToJsonElement(spawnBytes.decodeToString()).jsonObject
        val dependencies = Json.parseToJsonElement(dependencyBytes.decodeToString()).jsonObject
        require(Path.of(spawn.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(dependencies.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        require(Path.of(dependencies.getValue("spawnReport").jsonPrimitive.content).toRealPath() == spawnPath.toRealPath())
        require(dependencies.getValue("frameFailures").jsonArray.isEmpty())
        require(dependencies.getValue("sequenceFailures").jsonArray.isEmpty())
        val closure = dependencies.getValue("npcReports").jsonArray.map { it.jsonObject }
        require(closure.all { it.getValue("status").jsonPrimitive.content == "parsed" })
        val osrsIds = closure.map { it.getValue("id").jsonPrimitive.int }.toSet()
        require(osrsIds.size == closure.size && osrsIds.all { it >= 20000 })
        val eligible = spawn.getValue("donorGuardEligibleNpcIds").jsonArray.map { it.jsonPrimitive.int }.toSet()
        require(eligible.filter { it >= 20000 }.all { it in osrsIds })
        val nativeIds = eligible.filter { it < 20000 }.sorted()

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val occupied = target.archiveIds(Index.NPCS).flatMap { archive ->
                    target.files(Index.NPCS, archive).map { (archive shl 7) or it }
                }.toSet()
                val mappings = allocate(osrsIds, occupied)
                val nativeReports = nativeIds.map { id ->
                    val from = source.data(Index.NPCS, id ushr 7, id and 127)
                    val to = target.data(Index.NPCS, id ushr 7, id and 127)
                    buildJsonObject {
                        put("id", id)
                        put("status", if (from != null && to != null && from.contentEquals(to)) "byte-identical" else "requires-review")
                        put("sourceSha256", from?.let(::hash))
                        put("targetSha256", to?.let(::hash))
                    }
                }
                val result = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("writesCache", false)
                    put("sourceCache", sourceRoot.toString())
                    put("targetCache", targetRoot.toString())
                    put("spawnReportSha256", hash(spawnBytes))
                    put("dependencyReportSha256", hash(dependencyBytes))
                    put("occupiedTargetIds", occupied.size)
                    put("osrsNpcCount", mappings.size)
                    put("remappedNpcCount", mappings.count { it.key != it.value })
                    put("nativeRangeNpcCount", nativeReports.size)
                    put("nativeRangeByteIdentical", nativeReports.count { it["status"]?.jsonPrimitive?.content == "byte-identical" })
                    put("npcMappings", buildJsonArray {
                        mappings.forEach { (from, to) ->
                            val bytes = requireNotNull(source.data(Index.NPCS, from ushr 7, from and 127))
                            add(buildJsonObject {
                                put("sourceId", from)
                                put("targetId", to)
                                put("sourceSha256", hash(bytes))
                            })
                        }
                    })
                    put("nativeRangeReports", JsonArray(nativeReports))
                    put("limitations", "Provisional ID plan only. Convert NPC definitions, models, animations and morphs before spawning; review native-range mismatches.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW)
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
