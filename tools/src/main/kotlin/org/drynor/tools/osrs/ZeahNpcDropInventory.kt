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
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Reads Gielenor's authoritative packed NPC drop table without changing its economy. */
object ZeahNpcDropInventory {
    fun inventory(sourceRoot: Path, spawnReportPath: Path, npcDependencyPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val spawns = Json.parseToJsonElement(Files.readString(spawnReportPath)).jsonObject
        val npcDeps = Json.parseToJsonElement(Files.readString(npcDependencyPath)).jsonObject
        val selected = spawns.getValue("donorGuardEligibleNpcIds").jsonArray.mapTo(sortedSetOf()) { it.jsonPrimitive.content.toInt() }
        selected.addAll(npcDeps.getValue("npcReports").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() })
        val sourceCachePath = Path.of(spawns.getValue("sourceCache").jsonPrimitive.content)
        val targetCachePath = Path.of(spawns.getValue("targetCache").jsonPrimitive.content)
        val path = sourceRoot.resolve("data/npc/packedDrops.d")
        val bytes = Files.readAllBytes(path)
        val buffer = ByteBuffer.wrap(bytes)
        val items = sortedSetOf<Int>()
        var totalTables = 0
        var selectedTables = 0
        val tables = buildJsonArray {
            while (buffer.hasRemaining()) {
                require(buffer.remaining() >= 6) { "Truncated drop-table header at ${buffer.position()}" }
                val npcId = buffer.int
                val rareTable = buffer.get().toInt() == 1
                val size = buffer.get().toInt() and 255
                require(buffer.remaining() >= size * 11) { "Truncated drop table for NPC $npcId" }
                totalTables++
                val relevant = npcId in selected
                val entries = if (relevant) buildJsonArray {
                    repeat(size) {
                        val itemId = buffer.short.toInt() and 65535
                        val minimum = buffer.int
                        val maximum = buffer.int
                        val rarity = buffer.get().toInt() and 255
                        items.add(itemId)
                        add(buildJsonObject {
                            put("itemId", itemId)
                            put("minimum", minimum)
                            put("maximum", maximum)
                            put("rarity", rarity)
                        })
                    }
                } else {
                    buffer.position(buffer.position() + size * 11)
                    null
                }
                if (entries != null) {
                    selectedTables++
                    add(buildJsonObject {
                        put("npcId", npcId)
                        put("rareTable", rareTable)
                        put("entries", entries)
                    })
                }
            }
        }
        FileCache(sourceCachePath.toString(), writable = false).use { source ->
            FileCache(targetCachePath.toString(), writable = false).use { target ->
                val definitions = buildJsonArray {
                    items.forEach { id ->
                        val sourceBytes = source.data(Index.ITEMS, id ushr 8, id and 255)
                        val targetBytes = target.data(Index.ITEMS, id ushr 8, id and 255)
                        add(buildJsonObject {
                            put("id", id)
                            put("sourceStatus", if (sourceBytes == null) "missing" else "present")
                            put("targetStatus", if (targetBytes == null) "missing" else "present")
                            put("identicalBytes", sourceBytes != null && targetBytes != null && sourceBytes.contentEquals(targetBytes))
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("sourceDropFile", path.toString())
                    put("sourceDropSha256", sha256(bytes))
                    put("spawnReport", spawnReportPath.toString())
                    put("npcDependencyReport", npcDependencyPath.toString())
                    put("totalDonorTables", totalTables)
                    put("selectedNpcIds", selected.size)
                    put("selectedTables", selectedTables)
                    put("uniqueItemIds", items.size)
                    put("tables", tables)
                    put("itemDefinitionReports", definitions)
                    put("runtimeFilterApplied", false)
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
