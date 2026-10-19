package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only discovery for Gielenor's hybrid cache, not a generic OSRS definition decoder. */
fun runOsrs(args: List<String>) {
    require(args.size == 4 && args[0] in setOf("inventory-inferno", "plan-inferno", "preview-inferno", "audit-actors-inferno", "preview-actors-inferno", "reward-inferno")) {
        "osrs <inventory-inferno|plan-inferno|preview-inferno|audit-actors-inferno|preview-actors-inferno|reward-inferno> <gielenor-packed-cache> <drynor-packed-cache> <new-output>"
    }
    val source = Path.of(args[1]).toRealPath()
    val target = Path.of(args[2]).toRealPath()
    val output = Path.of(args[3]).toAbsolutePath().normalize()
    require(source != target) { "Source and target caches must differ." }
    require(!output.startsWith(source) && !output.startsWith(target)) { "Report must be outside both caches." }
    require(!Files.exists(output)) { "Report already exists: $output" }
    for (directory in listOf(source, target)) {
        require(Files.isRegularFile(directory.resolve("main_file_cache.dat2"))) { "Missing packed cache: $directory" }
    }
    if (args[0] == "preview-inferno") {
        StaticInfernoPreview.build(source, target, output)
        return
    }
    if (args[0] == "audit-actors-inferno") {
        InfernoActorAudit.build(source, target, output)
        return
    }
    if (args[0] == "preview-actors-inferno") {
        InfernoActorPreview.build(source, target, output)
        return
    }
    if (args[0] == "reward-inferno") {
        InfernalCapePreview.build(source, target, output)
        return
    }
    FileCache(source.toString(), writable = false).use { origin ->
        FileCache(target.toString(), writable = false).use { destination ->
            val sourceBefore = InfernoDependencyPlan.snapshot(origin)
            val targetBefore = InfernoDependencyPlan.snapshot(destination)
            val inventory = InfernoInventory.inspect(origin, destination, source, target)
            val report = if (args[0] == "plan-inferno") InfernoDependencyPlan.inspect(origin, destination, inventory) else inventory
            check(sourceBefore == InfernoDependencyPlan.snapshot(origin)) { "Source reference tables changed during inspection; retry with a stable cache." }
            check(targetBefore == InfernoDependencyPlan.snapshot(destination)) { "Target reference tables changed during inspection; retry with a stable cache." }
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", CREATE_NEW, WRITE)
        }
    }
    println("Read-only Inferno inventory: $output")
}

object InfernoInventory {
    // Evidence: Gielenor Inferno.java, MapInstance.java, Region.java and Settings.OSRS_MAP_IDS.
    private val regions = listOf(35 to 82, 71 to 82)
    private const val OBJECT_OFFSET = 100000

    fun inspect(source: Cache, target: Cache, sourcePath: Path, targetPath: Path): JsonObject {
        val objects = sortedSetOf<Int>()
        val regionReports = regions.map { (x, y) ->
            inspectRegion(source, target, x, y, objects)
        }
        // Pillars are spawned by gameplay rather than placed in the landscape.
        objects.addAll(130283..130287)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("package", "inferno")
            put("sourceProfile", "gielenor-718-hybrid-osrs")
            put("originalOsrsArenaRegion", 9043)
            put("sourceCache", sourcePath.toString())
            put("targetCache", targetPath.toString())
            put("writesCache", false)
            put("dependencyClosureComplete", false)
            put("regions", JsonArray(regionReports))
            put("objectDefinitions", definitions(source, target, Index.OBJECTS, objects, OBJECT_OFFSET))
            put("npcDefinitions", definitions(source, target, Index.NPCS, (27690..27709).toList(), 20000, sourceShift = 7))
            put("itemDefinitions", definitions(source, target, Index.ITEMS, listOf(51295), 30000))
            put("limitations", buildJsonArray {
                add(JsonPrimitive("NPC IDs 27690..27709 are candidates; presence does not prove usage by gameplay."))
                add(JsonPrimitive("Definition bytes are fingerprinted, not decoded with 727 grammar; transitive dependencies remain unresolved."))
                add(JsonPrimitive("Terrain parsing covers legacy tile records only; trailing environment bytes require separate analysis."))
                add(JsonPrimitive("Landscape reads try plaintext only; encrypted or incompatible archives are reported as errors, never treated as empty."))
                add(JsonPrimitive("Target ID occupancy is not an ID allocation plan and does not establish compatibility."))
                add(JsonPrimitive("Source asset provenance and permission must be confirmed before copying assets."))
            })
        }
    }

    private fun inspectRegion(source: Cache, target: Cache, x: Int, y: Int, objects: MutableSet<Int>): JsonObject = buildJsonObject {
        put("regionId", (x shl 8) or y)
        put("regionX", x)
        put("regionY", y)
        put("role", if (x == 35) "arena" else "entrance")
        for (prefix in listOf("m", "l")) {
            val name = "$prefix${x}_$y"
            put(if (prefix == "m") "terrain" else "landscape", buildJsonObject {
                put("name", name)
                val archive = source.archiveId(Index.MAPS, name)
                put("sourceArchive", archive)
                put("targetArchive", target.archiveId(Index.MAPS, name))
                if (archive < 0) {
                    put("status", "missing")
                } else {
                    try {
                        val data = requireNotNull(source.data(Index.MAPS, archive)) { "Unreadable archive (possibly encrypted)." }
                        put("bytes", data.size)
                        put("sha256", sha256(data))
                        if (prefix == "m") {
                            val strictEnd = LegacyTerrainScan.validate(data)
                            val map = MapDefinition()
                            val end = MapTileDecoder.decode(data, map)
                            check(end == strictEnd) { "Tile decoder disagrees with strict scan." }
                            val overlays = map.tiles.map { MapTile(it).overlayId }.filter { it != 0 }.toSortedSet()
                            val underlays = map.tiles.map { MapTile(it).underlayId }.filter { it != 0 }.toSortedSet()
                            put("tileBytesConsumed", end)
                            put("trailingBytes", data.size - end)
                            put("overlayIdsRaw", numbers(overlays))
                            put("underlayIdsRaw", numbers(underlays))
                        } else {
                            var count = 0
                            val localObjects = sortedSetOf<Int>()
                            val decoder = object : MapObjectDecoder() {
                                override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                    require(level in 0..3 && shape in 0..22) { "Invalid landscape placement: plane=$level shape=$shape" }
                                    localObjects.add(objectId + if (x == 35) OBJECT_OFFSET else 0)
                                    count++
                                }
                            }
                            val end = decoder.decode(data, ByteArray(16384), x, y)
                            require(data.drop(end).all { it == 0.toByte() }) { "Unrecognized landscape trailing bytes." }
                            objects.addAll(localObjects)
                            put("placements", count)
                            put("objectDefinitionIds", numbers(localObjects))
                            put("objectIdOffsetApplied", if (x == 35) OBJECT_OFFSET else 0)
                            put("trailingBytes", data.size - end)
                        }
                        put("status", "inspected")
                    } catch (error: Exception) {
                        put("status", "error")
                        put("error", "${error.javaClass.simpleName}: ${error.message}")
                    }
                }
            })
        }
    }

    private fun definitions(source: Cache, target: Cache, index: Int, ids: Collection<Int>, offset: Int, sourceShift: Int = 8): JsonArray = buildJsonArray {
        for (id in ids.sorted()) {
            add(buildJsonObject {
                put("id", id)
                if (id >= offset) put("osrsIdCandidate", id - offset)
                put("index", index)
                // Gielenor NPC definitions use 128-file groups; objects/items use 256.
                val archive = id ushr sourceShift
                val file = id and ((1 shl sourceShift) - 1)
                put("archive", archive)
                put("file", file)
                val targetShift = if (index == Index.NPCS) 7 else 8
                put("targetIdOccupied", target.files(index, id ushr targetShift).contains(id and ((1 shl targetShift) - 1)))
                try {
                    val data = source.data(index, archive, file)
                    put("status", if (data == null) "missing" else "present")
                    if (data != null) {
                        put("bytes", data.size)
                        put("sha256", sha256(data))
                    }
                } catch (error: Exception) {
                    put("status", "error")
                    put("error", "${error.javaClass.simpleName}: ${error.message}")
                }
            })
        }
    }

    private fun numbers(values: Collection<Int>) = JsonArray(values.map(::JsonPrimitive))

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** Check every tile terminator before using the permissive 727 terrain decoder. */
internal object LegacyTerrainScan {
    fun validate(data: ByteArray): Int {
        var position = 0
        repeat(4 * 64 * 64) { tile ->
            while (true) {
                require(position < data.size) { "Truncated terrain at tile $tile, byte $position." }
                val opcode = data[position++].toInt() and 255
                if (opcode == 0) break
                if (opcode <= 49) {
                    require(position < data.size) { "Missing terrain payload at tile $tile, byte $position." }
                    position++
                }
                if (opcode == 1) break
            }
        }
        return position
    }
}
