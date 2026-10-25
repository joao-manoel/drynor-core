package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A visual-only diagnostic: alias the donor's default morph appearance without claiming dynamic behavior. */
object ZeahMorphVisualPreview {
    fun build(staticRootPath: Path, landscapeRootPath: Path, planPath: Path, morphAuditPath: Path, outputPath: Path) {
        val staticRoot = staticRootPath.toRealPath()
        val landscapeRoot = landscapeRootPath.toRealPath()
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val audit = Json.parseToJsonElement(Files.readString(morphAuditPath)).jsonObject
        require(audit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        require(audit.getValue("staticFallbackAuthorized").jsonPrimitive.content == "false")
        val staticReport = Json.parseToJsonElement(Files.readString(staticRoot.resolve("preview-report.json"))).jsonObject
        require(staticReport.getValue("status").jsonPrimitive.content == "experimental-static-objects-subset")
        val landscapeReport = Json.parseToJsonElement(Files.readString(landscapeRoot.resolve("preview-report.json"))).jsonObject
        require(landscapeReport.getValue("status").jsonPrimitive.content == "experimental-landscapes-without-definitions")
        val staticCachePath = Path.of(staticReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        val landscapeCachePath = Path.of(landscapeReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(staticCachePath == staticRoot.resolve("packed-cache"))
        require(landscapeCachePath == landscapeRoot.resolve("packed-cache"))
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(staticRoot, landscapeRoot, Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath())
            .none { output.startsWith(it) || it.startsWith(output) })
        val targetIds = plan.getValue("sourceObjects").jsonArray.map {
            it.jsonObject.getValue("plannedTargetId").jsonPrimitive.int
        }.toSet()
        val regions = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }
        FileCache(staticCachePath.toString(), writable = false).use { staged ->
            FileCache(landscapeCachePath.toString(), writable = false).use { full ->
                val available = targetIds.filterTo(mutableSetOf()) {
                    staged.data(Index.OBJECTS, it ushr 8, it and 255) != null
                }
                val aliases = audit.getValue("reports").jsonArray.mapNotNull { entry ->
                    val item = entry.jsonObject
                    if (item.getValue("fallbackStatus").jsonPrimitive.content != "terminal") return@mapNotNull null
                    val parent = item.getValue("plannedTargetId").jsonPrimitive.int
                    val child = item.getValue("fallbackTerminalTargetId").jsonPrimitive.int
                    if (parent in available || child !in available) return@mapNotNull null
                    parent to child
                }.toMap()
                require(aliases.isNotEmpty())
                require(aliases.size == audit.getValue("reports").jsonArray.count {
                    val item = it.jsonObject
                    item.getValue("fallbackTerminalTargetId").jsonPrimitive.int in available &&
                        item.getValue("plannedTargetId").jsonPrimitive.int !in available
                })
                val retained = available + aliases.keys
                var kept = 0
                var added = 0
                var excluded = 0
                val landscapes = regions.map { region ->
                    val name = "l${region ushr 8}_${region and 255}"
                    val archive = staged.archiveId(Index.MAPS, name)
                    check(archive >= 0 && archive == full.archiveId(Index.MAPS, name))
                    val donor = requireNotNull(full.data(Index.MAPS, name))
                    val map = MapDefinition(region)
                    val reader = object : MapObjectDecoder() {
                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                            if (objectId in retained) {
                                map.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                                if (objectId in aliases) added++ else kept++
                            } else excluded++
                        }
                    }
                    check(reader.decode(donor, ByteArray(16384), region ushr 8, region and 255) == donor.size)
                    val writer = BufferWriter(donor.size + 32)
                    with(MapObjectEncoder()) { writer.encode(map) }
                    val bytes = writer.toArray()
                    val readback = MapDefinition(region)
                    val verify = object : MapObjectDecoder() {
                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                            readback.objects += MapObject(objectId, localX, localY, level, shape, rotation)
                        }
                    }
                    check(verify.decode(bytes, ByteArray(16384), region ushr 8, region and 255) == bytes.size)
                    check(readback.objects.map { it.packed }.sorted() == map.objects.map { it.packed }.sorted())
                    archive to bytes
                }
                check(kept == staticReport.getValue("keptPlacements").jsonPrimitive.int)
                check(kept + added + excluded == landscapeReport.getValue("placements").jsonPrimitive.int)
                Files.createDirectories(output)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(staticCachePath).use { files ->
                    files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                        .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                }
                FileCache(packed.toString(), writable = true).use { result ->
                    for ((parent, child) in aliases) {
                        val bytes = requireNotNull(staged.data(Index.OBJECTS, child ushr 8, child and 255))
                        check(staged.data(Index.OBJECTS, parent ushr 8, parent and 255) == null)
                        result.write(Index.OBJECTS, parent ushr 8, parent and 255, bytes)
                    }
                    for ((archive, bytes) in landscapes) result.write(Index.MAPS, archive, 0, bytes)
                }
                FileCache(packed.toString(), writable = false).use { result ->
                    for ((parent, child) in aliases) {
                        check(result.data(Index.OBJECTS, parent ushr 8, parent and 255)
                            .contentEquals(staged.data(Index.OBJECTS, child ushr 8, child and 255)))
                    }
                    val touched = landscapes.map { it.first }.toSet()
                    for ((archive, bytes) in landscapes) check(result.data(Index.MAPS, archive).contentEquals(bytes))
                    for (archive in staged.archiveIds(Index.MAPS)) if (archive !in touched) {
                        check(staged.container(Index.MAPS, archive).contentEquals(result.container(Index.MAPS, archive)))
                    }
                    for (archive in staged.archiveIds(Index.OBJECTS)) for (file in staged.files(Index.OBJECTS, archive)) {
                        check(staged.data(Index.OBJECTS, archive, file).contentEquals(result.data(Index.OBJECTS, archive, file)))
                    }
                    for (archive in staged.archiveIds(Index.MODELS)) {
                        check(staged.container(Index.MODELS, archive).contentEquals(result.container(Index.MODELS, archive)))
                    }
                }
                val report = buildJsonObject {
                    put("status", "experimental-morph-visual-diagnostic")
                    put("packedCache", packed.toString())
                    put("objectPlanSha256", sha256(planBytes))
                    put("morphAuditSha256", sha256(Files.readAllBytes(morphAuditPath)))
                    put("visualAliases", aliases.size)
                    put("previousStaticPlacements", kept)
                    put("visualMorphPlacementsAdded", added)
                    put("excludedPlacements", excluded)
                    put("landscapeRegions", landscapes.size)
                    put("scopeComplete", false)
                    put("clientVisualValidated", false)
                    put("gameplayMorphValidated", false)
                    put("limitations", "Only default appearance copied. Dynamic variants, collision, map icons, sounds, animations and gameplay not validated. Never use as runtime cache.")
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
