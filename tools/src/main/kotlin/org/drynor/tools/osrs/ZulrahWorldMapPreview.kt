package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.WorldMapDefinition
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.WorldMapDetailsDecoder
import world.gregs.voidps.buffer.read.BufferReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Bounded native world map patch from converted native scene data; no source OSRS namespace at runtime. */
object ZulrahWorldMapPreview {
    fun build(previewRoot: Path, outputRoot: Path) {
        val root = previewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!output.startsWith(root) && !Files.exists(output)) { "Output must be new and outside preview" }
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("zulrah-static-preview.json"))).jsonObject
        require(manifest["status"]?.jsonPrimitive?.content == "experimental-zulrah-static")
        val packed = root.resolve(manifest.getValue("packedCache").jsonPrimitive.content).normalize().toRealPath()
        require(packed.startsWith(root))
        val importedRegions = manifest.getValue("regions").jsonArray.map { it.jsonObject.getValue("regionId").jsonPrimitive.int }.distinct().sorted()
        val allowed = setOf(8494,8495,8496,8750,8751,8752,9006,9007,9008,9263,9264)
        require(importedRegions.containsAll(listOf(8495,8751,9007,9008)) && importedRegions.all { it in allowed }) {
            "Unexpected Zulrah map selection: $importedRegions"
        }
        // These ocean squares have no identity section in main/details. Their display coordinates
        // belong to the relocated Soul Wars map; patching them would overwrite that island.
        val unmappedOcean = importedRegions.filter { it in setOf(8494,8750,9006) }
        val regions = importedRegions - unmappedOcean.toSet()
        val xteaPath = Path.of("../drynor-world-server/data/map/xteaKeys.json")
        val keys = Json.parseToJsonElement(Files.readString(xteaPath)).jsonObject
        FileCache(packed.toString(),writable=false).use { baseline ->
            val snapshot = InfernoDependencyPlan.snapshot(baseline)
            val detailsArchive = baseline.archiveId(Index.WORLD_MAP,"details")
            val details = WorldMapDefinition()
            WorldMapDetailsDecoder().readLoop(details,BufferReader(requireNotNull(baseline.data(Index.WORLD_MAP,detailsArchive,28))))
            require(details.map == "main")
            for (region in regions) {
                val x = (region ushr 8) * 64
                val y = (region and 255) * 64
                require(details.sections.orEmpty().any {
                    it.level == 0 && it.minX == it.startX && it.minY == it.startY && it.maxX == it.endX && it.maxY == it.endY &&
                        x >= it.minX && x + 63 <= it.maxX && y >= it.minY && y + 63 <= it.maxY
                }) { "Region $region has no verified identity world map section" }
            }
            val archive = baseline.archiveId(Index.WORLD_MAP,"main")
            val file = baseline.fileId(Index.WORLD_MAP,archive,"area")
            require(archive == 29 && file == 4) { "Unverified native main/area address $archive/$file" }
            val original = requireNotNull(baseline.data(Index.WORLD_MAP,archive,file))
            val parsed = WorldMapAreaRecords.parse(original)
            val reports = mutableListOf<JsonObject>()
            val replacements = regions.associateWith { region ->
                val name = "${region ushr 8}_${region and 255}"
                val terrain = MapDefinition(region)
                val terrainBytes = requireNotNull(baseline.data(Index.MAPS,"m$name"))
                val terrainEnd = MapTileDecoder.decode(terrainBytes,terrain)
                require(terrainEnd == terrainBytes.size || region in setOf(8752,9263,9264)) {
                    "Unexpected terrain environment in $region"
                }
                val underlays = IntArray(4096)
                val overlays = IntArray(4096)
                val shapes = IntArray(4096)
                fun tile(plane: Int,x: Int,y: Int) = MapTile(terrain.tiles[(plane shl 12) or (x shl 6) or y])
                for (x in 0..63) for (y in 0..63) {
                    val i = (x shl 6) or y
                    val t = tile(if(tile(1,x,y).settings and 2 != 0) 1 else 0,x,y)
                    underlays[i] = t.underlayId
                    overlays[i] = t.overlayId
                    shapes[i] = (t.overlayRotation shl 6) or t.overlayPath
                }
                val objects = List(4096) { mutableListOf<Pair<Int,Int>>() }
                val xtea = keys[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
                val landscape = requireNotNull(baseline.data(Index.MAPS,"l$name",xtea))
                val decoder = object : MapObjectDecoder() {
                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                        val plane = level - if (tile(1,localX,localY).settings and 2 != 0) 1 else 0
                        if (plane == 0) objects[(localX shl 6) or localY].add(objectId to ((rotation shl 6) or shape))
                    }
                }
                require(decoder.decode(landscape,ByteArray(16384),region ushr 8,region and 255) == landscape.size)
                val encoded = WorldMapAreaRecords.region(region,underlays,overlays,shapes,objects)
                reports.add(buildJsonObject {
                    put("regionId",region); put("oldRecords",parsed.records.count { it.region == region })
                    put("newTiles",4096); put("objects",objects.sumOf { it.size })
                    put("paintedTiles",underlays.indices.count { underlays[it] != 0 || overlays[it] != 0 })
                })
                encoded
            }
            val patched = parsed.patch(replacements)
            val readback = WorldMapAreaRecords.parse(patched)
            check(readback.header.contentEquals(parsed.header))
            val untouched = parsed.records.filter { it.region !in regions }
            val afterUntouched = readback.records.filter { it.region !in regions }
            check(untouched.size == afterUntouched.size && untouched.indices.all { untouched[it].region == afterUntouched[it].region && untouched[it].raw.contentEquals(afterUntouched[it].raw) })
            Files.createDirectories(output)
            val destinationPath = output.resolve("packed-cache")
            Files.createDirectory(destinationPath)
            Files.list(packed).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it,destinationPath.resolve(it.fileName)) } }
            FileCache(destinationPath.toString(),writable=true).use { it.write(Index.WORLD_MAP,archive,file,patched) }
            var unchanged = 0
            FileCache(destinationPath.toString(),writable=false).use { result ->
                check(result.data(Index.WORLD_MAP,archive,file).contentEquals(patched))
                for (index in baseline.indices()) for (a in baseline.archiveIds(index)) {
                    if (index != Index.WORLD_MAP || a != archive) { check(baseline.container(index,a).contentEquals(result.container(index,a))); unchanged++ }
                    else for (f in baseline.files(index,a)) if(f != file) check(baseline.data(index,a,f).contentEquals(result.data(index,a,f)))
                }
            }
            check(snapshot == InfernoDependencyPlan.snapshot(baseline))
            val report = JsonObject(manifest.toMutableMap().apply {
                put("packedCache",JsonPrimitive("packed-cache"))
                put("worldMap",buildJsonObject {
                    put("status","native-area-patched"); put("index",Index.WORLD_MAP); put("archive",archive); put("file",file)
                    put("regions",JsonArray(reports)); put("untouchedRecordsVerified",untouched.size); put("unchangedArchivesVerified",unchanged)
                    put("sceneOnlyOceanRegions",JsonArray(unmappedOcean.map(::JsonPrimitive)))
                    put("beforeSha256",hash(original)); put("afterSha256",hash(patched)); put("clientValidated",false)
                })
            })
            Files.writeString(output.resolve("zulrah-static-preview.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n",CREATE_NEW)
            Files.write(output.resolve("worldmap-main-area-before.dat"),original,CREATE_NEW)
            Files.write(output.resolve("worldmap-main-area-after.dat"),patched,CREATE_NEW)
            println("Native Zulrah world map preview verified: $output")
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
