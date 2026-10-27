package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Compares every asset named by the production raid-equipment binding with a Zeah preview. */
object ZeahEquipmentParityAudit {
    fun audit(productionPath: Path, previewPath: Path, equipmentReportPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val production = productionPath.toRealPath()
        val preview = previewPath.toRealPath()
        require(production != preview)
        val equipmentReport = Json.parseToJsonElement(Files.readString(equipmentReportPath)).jsonObject
        require(equipmentReport.getValue("status").jsonPrimitive.content == "experimental-osrs-raid-equipment")
        require(equipmentReportPath.parent.resolve(equipmentReport.getValue("packedCache").jsonPrimitive.content).toRealPath() == production)
        val ids = equipmentReport.getValue("ids").jsonObject
        val checks = listOf(
            "items" to { id: Int -> Triple(Index.ITEMS, id ushr 8, id and 255) },
            "models" to { id: Int -> Triple(Index.MODELS, id, 0) },
            "animations" to { id: Int -> Triple(Index.ANIMATIONS, id ushr 7, id and 127) },
            "spots" to { id: Int -> Triple(Index.SPOTANIMS, id ushr 8, id and 255) },
            "bas" to { id: Int -> Triple(Index.CONFIGS, Config.RENDER_ANIMATIONS, id) },
        )
        val mismatches = mutableListOf<JsonObject>()
        val counts = sortedMapOf<String, Int>()
        FileCache(production.toString(), writable = false).use { live ->
            FileCache(preview.toString(), writable = false).use { candidate ->
                for ((group, address) in checks) {
                    val values = ids.getValue(group).jsonObject.values.map { it.jsonPrimitive.int }.distinct()
                    counts[group] = values.size
                    for (id in values) {
                        val (index, archive, file) = address(id)
                        val current = live.data(index, archive, file)
                        val proposed = candidate.data(index, archive, file)
                        if (current == null || !current.contentEquals(proposed)) {
                            mismatches += buildJsonObject {
                                put("group", group); put("targetId", id)
                                put("productionSha256", current?.let(::hash) ?: "missing")
                                put("previewSha256", proposed?.let(::hash) ?: "missing")
                            }
                        }
                    }
                }
                // Imported Zeah sequences may share frame/skeleton archives with existing weapons.
                // Preserve every production file in these two indexes, including files outside
                // the directly mapped equipment animation set.
                for ((group, index) in listOf(
                    "animationFrames" to Index.ANIMATION_FRAMES,
                    "animationSkeletons" to Index.ANIMATION_SKELETONS,
                )) {
                    var checked = 0
                    for (archive in live.archiveIds(index)) for (file in live.files(index, archive)) {
                        val current = live.data(index, archive, file)
                        val proposed = candidate.data(index, archive, file)
                        checked++
                        if (current == null || !current.contentEquals(proposed)) {
                            mismatches += buildJsonObject {
                                put("group", group); put("archive", archive); put("file", file)
                                put("productionSha256", current?.let(::hash) ?: "missing")
                                put("previewSha256", proposed?.let(::hash) ?: "missing")
                            }
                        }
                    }
                    counts[group] = checked
                }
            }
        }
        val report = buildJsonObject {
            put("status", if (mismatches.isEmpty()) "equipment-direct-assets-match" else "equipment-direct-assets-diverge")
            put("productionCache", production.toString())
            put("previewCache", preview.toString())
            put("equipmentReportSha256", hash(Files.readAllBytes(equipmentReportPath)))
            put("checked", buildJsonObject { counts.forEach { (group, count) -> put(group, count) } })
            put("mismatches", JsonArray(mismatches))
            put("limitations", "Mapped raid-equipment assets and all production frame/skeleton files only; other production content and runtime hooks require separate checks.")
        }
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
        println("Raid equipment assets and native animation dependencies: ${counts.values.sum()} checked, ${mismatches.size} mismatches")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
