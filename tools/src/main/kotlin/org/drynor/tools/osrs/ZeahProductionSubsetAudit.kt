package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Proves that a feature preview preserves every existing production cache file. */
object ZeahProductionSubsetAudit {
    fun audit(productionPath: Path, previewPath: Path, keysPath: Path, outputPath: Path, worldMapPreviewPath: Path? = null) {
        require(!Files.exists(outputPath))
        val production = productionPath.toRealPath()
        val preview = previewPath.toRealPath()
        require(production != preview)
        val keys = Json.parseToJsonElement(Files.readString(keysPath)).jsonObject
            .mapKeys { it.key.toInt() }
            .mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.int }.toIntArray() }
        var unchangedArchives = 0
        var changedArchives = 0
        var comparedFiles = 0
        val mismatches = mutableListOf<JsonObject>()
        val approvedMapReplacements = mutableListOf<JsonObject>()
        val approvedMapRoot = worldMapPreviewPath?.toRealPath()
        val allowedMapFiles = setOf(1 to 28, 29 to 4)
        val approvedMap = approvedMapRoot?.resolve("packed-cache")?.toRealPath()
        FileCache(production.toString(), xteas = keys, writable = false).use { live ->
            FileCache(preview.toString(), xteas = keys, writable = false).use { candidate ->
                val mapBaseline = approvedMap?.let { FileCache(it.toString(), xteas = keys, writable = false) }
                try {
                for (index in live.indices().sorted()) for (archive in live.archiveIds(index).sorted()) {
                    if (live.container(index, archive).contentEquals(candidate.container(index, archive))) {
                        unchangedArchives++
                        continue
                    }
                    changedArchives++
                    val files = live.files(index, archive)
                    for (file in files) {
                        comparedFiles++
                        val same = runCatching {
                            live.data(index, archive, file).contentEquals(candidate.data(index, archive, file))
                        }.getOrDefault(false)
                        if (!same) {
                            val record = buildJsonObject {
                                put("index", index); put("archive", archive); put("file", file)
                            }
                            if (mapBaseline != null && index == 23 && archive to file in allowedMapFiles &&
                                candidate.data(index, archive, file).contentEquals(mapBaseline?.data(index, archive, file)))
                                approvedMapReplacements += record
                            else mismatches += record
                        }
                    }
                    if (files.isEmpty()) mismatches += buildJsonObject {
                        put("index", index); put("archive", archive); put("issue", "changed archive has no indexed files")
                    }
                }
                } finally { mapBaseline?.close() }
            }
        }
        val report = buildJsonObject {
            put("status", when {
                mismatches.isNotEmpty() -> "production-files-diverge"
                approvedMapReplacements.isNotEmpty() -> "production-files-preserved-with-zeah-map"
                else -> "production-files-preserved"
            })
            put("productionCache", production.toString())
            put("previewCache", preview.toString())
            put("unchangedArchives", unchangedArchives)
            put("changedArchives", changedArchives)
            put("comparedFilesInChangedArchives", comparedFiles)
            approvedMapRoot?.let { put("approvedWorldMapPreview", it.toString()) }
            put("approvedMapReplacements", JsonArray(approvedMapReplacements))
            put("mismatches", JsonArray(mismatches))
            put("limitations", "File payload preservation does not prove gameplay hooks, rendering, or that newly imported Zeah files are correct.")
        }
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
        println("Production subset: $unchangedArchives unchanged archives, $changedArchives changed archives, $comparedFiles old files compared, ${approvedMapReplacements.size} approved map replacements, ${mismatches.size} mismatches")
    }
}
