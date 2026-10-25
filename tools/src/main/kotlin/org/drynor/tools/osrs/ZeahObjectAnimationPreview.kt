package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Stages only audited object animation assets in a fresh diagnostic cache, without changing objects. */
object ZeahObjectAnimationPreview {
    fun build(staticRootPath: Path, planPath: Path, auditPath: Path, outputPath: Path) {
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val auditBytes = Files.readAllBytes(auditPath)
        val audit = Json.parseToJsonElement(auditBytes.decodeToString()).jsonObject
        require(audit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        val sequenceIds = audit.getValue("reports").jsonArray.filter { record ->
            val item = record.jsonObject
            item.getValue("nativeConversionProbePassed").jsonPrimitive.content == "true" &&
                item.getValue("sequenceSoundIds").jsonPrimitive.int == 0 &&
                item.getValue("equipmentOverrides").jsonPrimitive.int == 0
        }.mapTo(sortedSetOf()) { it.jsonObject.getValue("sequenceRuntimeId").jsonPrimitive.int }
        require(sequenceIds.size == audit.getValue("sequencesPassingNativeProbe").jsonPrimitive.int)
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val staticRoot = staticRootPath.toRealPath()
        val staticReport = Json.parseToJsonElement(Files.readString(staticRoot.resolve("preview-report.json"))).jsonObject
        require(staticReport.getValue("status").jsonPrimitive.content == "experimental-static-objects-subset")
        val staticCachePath = Path.of(staticReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(staticCachePath == staticRoot.resolve("packed-cache"))
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(sourceRoot, targetRoot, staticRoot).none { output.startsWith(it) || it.startsWith(output) })
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(staticCachePath.toString(), writable = false).use { staged ->
                    val indexes = listOf(Index.ANIMATIONS, Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS)
                    for (index in indexes) for (archive in target.archiveIds(index)) {
                        check(target.container(index, archive).contentEquals(staged.container(index, archive))) {
                            "Static preview changed baseline animation index $index archive $archive"
                        }
                    }
                    // Uses the same physical OSRS +5000 frame-set and skeleton rules as the audited Zulrah bridge.
                    val planned = ZulrahAnimationAssets.plan(source, target, sequenceIds)
                    check(planned.sequenceMap.keys == sequenceIds)
                    Files.createDirectories(output)
                    val packed = output.resolve("packed-cache")
                    Files.createDirectory(packed)
                    Files.list(staticCachePath).use { files ->
                        files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                            .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                    }
                    FileCache(packed.toString(), writable = true).use { result ->
                        for (asset in planned.assets) {
                            check(staged.data(asset.index, asset.archive, asset.file) == null)
                            result.write(asset.index, asset.archive, asset.file, asset.bytes)
                        }
                    }
                    FileCache(packed.toString(), writable = false).use { result ->
                        for (asset in planned.assets) {
                            check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes)) {
                                "Animation asset failed readback ${asset.index}/${asset.archive}/${asset.file}"
                            }
                        }
                        for (index in indexes) for (archive in staged.archiveIds(index)) {
                            for (file in staged.files(index, archive)) {
                                check(staged.data(index, archive, file).contentEquals(result.data(index, archive, file))) {
                                    "Existing animation asset changed: $index/$archive/$file"
                                }
                            }
                        }
                        for (archive in staged.archiveIds(Index.MAPS)) {
                            check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive))) {
                                "Map archive changed: $archive"
                            }
                        }
                        for (archive in staged.archiveIds(Index.OBJECTS)) for (file in staged.files(Index.OBJECTS, archive)) {
                            check(staged.data(Index.OBJECTS, archive, file).contentEquals(result.data(Index.OBJECTS, archive, file))) {
                                "Object definition changed: $archive/$file"
                            }
                        }
                        for (archive in staged.archiveIds(Index.MODELS)) {
                            check(staged.container(Index.MODELS, archive).contentEquals(result.container(Index.MODELS, archive))) {
                                "Model archive changed: $archive"
                            }
                        }
                    }
                    val report = buildJsonObject {
                        put("status", "experimental-object-animation-assets-only")
                        put("packedCache", packed.toString())
                        put("objectPlanSha256", sha256(planBytes))
                        put("animationAuditSha256", sha256(auditBytes))
                        put("sourceStaticPreview", staticRoot.toString())
                        put("sequenceAssets", sequenceIds.size)
                        put("assetFilesWritten", planned.assets.size)
                        put("animationMappings", planned.report)
                        put("existingAnimationAssetsPreserved", true)
                        put("existingMapsObjectsModelsPreserved", true)
                        put("scopeComplete", false)
                        put("clientAnimationValidated", false)
                        put("limitations", "Animation assets are staged, but object definitions still lack animation references. Two blocked sequences and dynamic morphs remain. Never use as runtime cache.")
                    }
                    Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
