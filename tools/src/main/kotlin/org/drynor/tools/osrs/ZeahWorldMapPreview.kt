package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.WorldMapDefinition
import world.gregs.voidps.cache.definition.data.WorldMapSection
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.WorldMapDetailsDecoder
import world.gregs.voidps.cache.definition.encoder.WorldMapDetailsEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Isolated 727 map-area preview for the selected Zeah surface. Caves and instances stay out of main. */
object ZeahWorldMapPreview {
    fun build(previewRoot: Path, planPath: Path, outputRoot: Path) {
        val root = previewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(root))
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("preview-report.json"))).jsonObject
        require(manifest["status"]?.jsonPrimitive?.content == "experimental-morph-visual-diagnostic")
        require(manifest["unresolvedPlacements"]?.jsonPrimitive?.int == 0)
        val packed = root.resolve("packed-cache").toRealPath()
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        require(plan["status"]?.jsonPrimitive?.content == "read-only-provisional-plan")
        require(hash(planBytes) == manifest["objectPlanSha256"]?.jsonPrimitive?.content) { "Object plan changed after the visual preview" }
        val selected = plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int }.toSet()
        val regions = selected.filter { (it ushr 8) in 18..33 && (it and 255) in 52..65 }.sorted()
        require(regions.size >= 100 && regions.size < selected.size && 6200 in regions) { "Unexpected Zeah surface selection: ${regions.size}" }
        val keys = Json.parseToJsonElement(Files.readString(Path.of("../drynor-world-server/data/map/xteaKeys.json"))).jsonObject

        FileCache(packed.toString(), writable = false).use { baseline ->
            val detailsArchive = baseline.archiveId(Index.WORLD_MAP, "details")
            val detailsId = 28
            val detailsBefore = requireNotNull(baseline.data(Index.WORLD_MAP, detailsArchive, detailsId))
            val details = WorldMapDefinition()
            WorldMapDetailsDecoder().readLoop(details, BufferReader(detailsBefore))
            require(details.map == "main")
            val existing = details.sections.orEmpty().toList()
            fun identity(region: Int): Boolean {
                val x = (region ushr 8) * 64
                val y = (region and 255) * 64
                return existing.any { it.level == 0 && it.minX == it.startX && it.minY == it.startY &&
                    it.maxX == it.endX && it.maxY == it.endY &&
                    x >= it.minX && x + 63 <= it.maxX && y >= it.minY && y + 63 <= it.maxY }
            }
            val missing = regions.filterNot(::identity)
            // A row run is a precise identity section: no cave, instance or unselected map square is added.
            val added = mutableListOf<WorldMapSection>()
            for ((rx, column) in missing.groupBy { it ushr 8 }) {
                var run = mutableListOf<Int>()
                fun flush() {
                    if (run.isEmpty()) return
                    val x = rx * 64
                    val minY = run.first() * 64
                    val maxY = run.last() * 64 + 63
                    added += WorldMapSection(0, x, minY, x + 63, maxY, x, minY, x + 63, maxY)
                    run = mutableListOf()
                }
                for (ry in column.map { it and 255 }.sorted()) {
                    if (run.isNotEmpty() && ry != run.last() + 1) flush()
                    run += ry
                }
                flush()
            }
            require(existing.size + added.size <= 255) { "Main map section capacity exceeded" }
            details.sections = (existing + added).toMutableList()
            val writer = BufferWriter(detailsBefore.size + added.size * 17 + 32)
            with(WorldMapDetailsEncoder()) { writer.encode(details) }
            val detailsAfter = writer.toArray()
            val decoded = WorldMapDefinition()
            WorldMapDetailsDecoder().readLoop(decoded, BufferReader(detailsAfter))
            check(decoded.sections == details.sections)
            check(decoded == details)

            val archive = baseline.archiveId(Index.WORLD_MAP, "main")
            val file = baseline.fileId(Index.WORLD_MAP, archive, "area")
            require(archive == 29 && file == 4)
            val original = requireNotNull(baseline.data(Index.WORLD_MAP, archive, file))
            val parsed = WorldMapAreaRecords.parse(original)
            val reports = mutableListOf<JsonObject>()
            val replacements = regions.associateWith { region ->
                val name = "${region ushr 8}_${region and 255}"
                val terrain = MapDefinition(region)
                val terrainBytes = requireNotNull(baseline.data(Index.MAPS, "m$name")) { "Missing terrain $region" }
                val terrainEnd = MapTileDecoder.decode(terrainBytes, terrain)
                require(terrainEnd <= terrainBytes.size)
                fun tile(plane: Int, x: Int, y: Int) = MapTile(terrain.tiles[(plane shl 12) or (x shl 6) or y])
                val underlays = IntArray(4096)
                val overlays = IntArray(4096)
                val shapes = IntArray(4096)
                for (x in 0..63) for (y in 0..63) {
                    val i = (x shl 6) or y
                    val t = tile(if (tile(1, x, y).settings and 2 != 0) 1 else 0, x, y)
                    underlays[i] = t.underlayId
                    overlays[i] = t.overlayId
                    shapes[i] = (t.overlayRotation shl 6) or t.overlayPath
                }
                val objects = List(4096) { mutableListOf<Pair<Int, Int>>() }
                val key = keys[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
                val landscape = requireNotNull(baseline.data(Index.MAPS, "l$name", key)) { "Missing landscape $region" }
                val decoder = object : MapObjectDecoder() {
                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                        val plane = level - if (tile(1, localX, localY).settings and 2 != 0) 1 else 0
                        if (plane == 0) objects[(localX shl 6) or localY].add(objectId to ((rotation shl 6) or shape))
                    }
                }
                require(decoder.decode(landscape, ByteArray(16384), region ushr 8, region and 255) == landscape.size)
                reports += buildJsonObject {
                    put("regionId", region)
                    put("oldRecords", parsed.records.count { it.region == region })
                    put("paintedTiles", underlays.indices.count { underlays[it] != 0 || overlays[it] != 0 })
                    put("objects", objects.sumOf { it.size })
                }
                WorldMapAreaRecords.region(region, underlays, overlays, shapes, objects)
            }
            val patched = parsed.patch(replacements)
            val readback = WorldMapAreaRecords.parse(patched)
            check(readback.header.contentEquals(parsed.header))
            val untouched = parsed.records.filter { it.region !in regions }
            val afterUntouched = readback.records.filter { it.region !in regions }
            check(untouched.size == afterUntouched.size && untouched.indices.all {
                untouched[it].region == afterUntouched[it].region && untouched[it].raw.contentEquals(afterUntouched[it].raw)
            })

            Files.createDirectories(output)
            val destination = output.resolve("packed-cache")
            Files.createDirectory(destination)
            Files.list(packed).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                .forEach { Files.copy(it, destination.resolve(it.fileName)) } }
            FileCache(destination.toString(), writable = true).use {
                it.write(Index.WORLD_MAP, archive, file, patched)
                it.write(Index.WORLD_MAP, detailsArchive, detailsId, detailsAfter)
            }
            var unchanged = 0
            FileCache(destination.toString(), writable = false).use { result ->
                check(result.data(Index.WORLD_MAP, archive, file).contentEquals(patched))
                check(result.data(Index.WORLD_MAP, detailsArchive, detailsId).contentEquals(detailsAfter))
                for (index in baseline.indices()) for (a in baseline.archiveIds(index)) {
                    if (index != Index.WORLD_MAP || a !in setOf(archive, detailsArchive)) {
                        check(baseline.container(index, a).contentEquals(result.container(index, a)))
                        unchanged++
                    } else for (f in baseline.files(index, a)) {
                        if (a == archive && f == file || a == detailsArchive && f == detailsId) continue
                        check(baseline.data(index, a, f).contentEquals(result.data(index, a, f)))
                    }
                }
            }
            Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(
                JsonObject.serializer(), buildJsonObject {
                    put("status", "experimental-zeah-worldmap-diagnostic")
                    put("packedCache", "packed-cache")
                    put("sourcePreviewSha256", hash(Files.readAllBytes(root.resolve("preview-report.json"))))
                    put("objectPlanSha256", hash(planBytes))
                    put("surfaceRegions", regions.size)
                    put("detailsSectionsAdded", added.size)
                    put("areaRecordsPreserved", untouched.size)
                    put("unchangedArchivesVerified", unchanged)
                    put("regions", JsonArray(reports))
                    put("clientValidated", false)
                    put("limitations", "Surface world map only. Caves, instances, NPCs, items, live morphs and gameplay incomplete.")
                }
            ) + "\n", CREATE_NEW)
            println("Zeah surface world map preview verified: $output (${regions.size} regions, ${added.size} sections)")
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
