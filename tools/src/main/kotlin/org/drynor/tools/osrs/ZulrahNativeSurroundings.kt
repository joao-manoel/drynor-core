package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The three surrounding squares already use native terrain, not Gielenor's OSRS floor offsets. */
object ZulrahNativeSurroundings {
    private val regions = listOf(8752, 9263, 9264)
    private val json = Json { prettyPrint = true }
    private data class Patch(val archive: Int, val name: String, val bytes: ByteArray, val keys: IntArray? = null)

    fun build(sourcePath: Path, inputPreviewRoot: Path, outputRoot: Path) {
        val root = inputPreviewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(root))
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("zulrah-static-preview.json"))).jsonObject
        require(manifest["status"]?.jsonPrimitive?.content == "experimental-zulrah-static")
        val input = root.resolve(manifest.getValue("packedCache").jsonPrimitive.content).normalize().toRealPath()
        require(input.startsWith(root))
        val keyMap = Json.parseToJsonElement(Files.readString(Path.of("../drynor-world-server/data/map/xteaKeys.json"))).jsonObject
        val reports = mutableListOf<JsonObject>()
        val patches = mutableListOf<Patch>()
        val definitions = sortedSetOf<Int>()
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(input.toString(), writable = false).use { baseline ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val baselineSnapshot = InfernoDependencyPlan.snapshot(baseline)
                for (region in regions) {
                    val suffix = "${region ushr 8}_${region and 255}"
                    val terrain = requireNotNull(source.data(Index.MAPS, "m$suffix"))
                    val native = decodeTerrain(terrain, region)
                    val baselineTerrain = requireNotNull(baseline.data(Index.MAPS, "m$suffix"))
                    val sourceTileEnd = MapTileDecoder.decode(terrain, MapDefinition(region))
                    val baselineTileEnd = MapTileDecoder.decode(baselineTerrain, MapDefinition(region))
                    require(terrain.copyOfRange(sourceTileEnd, terrain.size).contentEquals(baselineTerrain.copyOfRange(baselineTileEnd, baselineTerrain.size))) { "Environment dependencies changed in $region" }
                    val overlays = sortedSetOf<Int>()
                    val underlays = sortedSetOf<Int>()
                    for (value in native.tiles) {
                        val tile = MapTile(value)
                        if (tile.overlayId > 0) overlays.add(tile.overlayId - 1)
                        if (tile.underlayId > 0) underlays.add(tile.underlayId - 1)
                    }
                    for (id in overlays) same(source, baseline, Index.CONFIGS, 4, id)
                    for (id in underlays) same(source, baseline, Index.CONFIGS, 1, id)
                    val keys = keyMap[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
                    val sourceLocs = requireNotNull(source.data(Index.MAPS, "l$suffix"))
                    val originalLocs = requireNotNull(baseline.data(Index.MAPS, "l$suffix", keys))
                    val locations = decodeLocations(sourceLocs, region)
                    definitions.addAll(locations.map { it.id })
                    if (region != 9263) require(sourceLocs.contentEquals(originalLocs)) { "Unexpected landscape change in $region" }
                    val archive = baseline.archiveId(Index.MAPS, "m$suffix")
                    require(archive >= 0)
                    patches.add(Patch(archive, "m$suffix", terrain))
                    if (region == 9263) patches.add(Patch(baseline.archiveId(Index.MAPS, "l$suffix").also { require(it >= 0) }, "l$suffix", sourceLocs, keys))
                    reports.add(buildJsonObject {
                        put("regionId", region); put("previewPlacements", locations.size)
                        put("originalPlacements", decodeLocations(originalLocs, region).size)
                        put("omittedPlacements", 0); put("terrainSha256", hash(terrain))
                        put("terrainEnvironmentOpcodes", JsonArray(native.effects.map { JsonPrimitive(it.opcode) }))
                        put("environmentBytesIdenticalToDrynor", true); put("landscapeReplaced", region == 9263); put("existingLandscapeEncryptionRetained", keys != null)
                    })
                }
                // Native objects already exist. Keep their exact Drynor bytes, including gameplay
                // customizations; prove geometry/collision parity instead of importing the hybrid format.
                val pending = ArrayDeque(definitions)
                val audited = sortedSetOf<Int>()
                val models = sortedSetOf<Int>()
                val metadataDifferences = sortedSetOf<Int>()
                while (pending.isNotEmpty()) {
                    val id = pending.removeFirst()
                    if (!audited.add(id)) continue
                    val original = requireNotNull(baseline.data(Index.OBJECTS, id ushr 8, id and 255))
                    val origin = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                    val local = ObjectDefinition(id)
                    ObjectDecoder().readLoop(local, BufferReader(original))
                    if (!origin.contentEquals(original)) {
                        requireEquivalentNativeDefinition(id, origin, original)
                        metadataDifferences.add(id)
                    }
                    local.transforms?.filter { it >= 0 }?.forEach(pending::addLast)
                    local.modelIds?.flatMap(IntArray::toList)?.let(models::addAll)
                }
                for (id in models) {
                    same(source, baseline, Index.MODELS, id, 0)
                    ModelDecoder.read(id, requireNotNull(baseline.data(Index.MODELS, id)))
                }
                Files.createDirectories(output)
                for (patch in patches) stage(output.resolve("source-package"), patch)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(input).use { paths -> paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it, packed.resolve(it.fileName)) } }
                FileCache(packed.toString(), writable = true).use { cache ->
                    for (patch in patches) cache.write(Index.MAPS, patch.archive, 0, patch.bytes, patch.keys)
                }
                var unchanged = 0
                FileCache(packed.toString(), writable = false).use { cache ->
                    for (patch in patches) check(cache.data(Index.MAPS, patch.archive, 0, patch.keys).contentEquals(patch.bytes))
                    val touched = patches.map { it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index == Index.MAPS && archive in touched) continue
                        check(baseline.container(index, archive).contentEquals(cache.container(index, archive)))
                        unchanged++
                    }
                }
                check(sourceSnapshot == InfernoDependencyPlan.snapshot(source) && baselineSnapshot == InfernoDependencyPlan.snapshot(baseline))
                val regionReports = manifest.getValue("regions").jsonArray.filter { it.jsonObject.getValue("regionId").jsonPrimitive.int !in regions } + reports
                val updated = JsonObject(manifest.toMutableMap().apply {
                    put("packedCache", JsonPrimitive("packed-cache"))
                    put("regions", JsonArray(regionReports))
                    put("nativeSurroundings", buildJsonObject {
                        put("regions", JsonArray(reports)); put("nativeObjectsReused", audited.size); put("nativeModelsIdentical", models.size)
                        put("nativeMetadataDifferencesRetained", JsonArray(metadataDifferences.map(::JsonPrimitive)))
                        put("unchangedArchivesVerified", unchanged); put("nativeDefinitionPolicy", "Retain Drynor native definitions; source opcode 44 customization masks and 190 cursor metadata are not imported. All other canonical fields match.")
                        put("environmentValidated", true); put("editableMapRoundTripsVerified", true)
                    })
                    put("renderingValidated", JsonPrimitive(false))
                    put("limitations", JsonPrimitive("Static diagnostic: eight converted OSRS regions and three adapted native regions. No Zulrah NPCs/items/combat; OSRS object animations, sounds, icons and unresolved objects remain explicitly omitted. Native Drynor object definitions and environments preserved."))
                })
                Files.writeString(output.resolve("zulrah-static-preview.json"), json.encodeToString(JsonObject.serializer(), updated) + "\n")
                println("Native Zulrah surroundings verified: $output; objects=${audited.size}, models=${models.size}, unchangedArchives=$unchanged")
            }
        }
    }

    internal fun decodeTerrain(bytes: ByteArray, region: Int): MapDefinition {
        val map = MapTileDecoder.decodeArchive(bytes, region)
        val writer = BufferWriter(bytes.size + 1024)
        with(MapTileEncoder()) { writer.encode(map) }
        require(writer.toArray().contentEquals(bytes)) { "Native terrain/environment round trip failed in $region" }
        return map
    }

    internal fun decodeLocations(bytes: ByteArray, region: Int): List<MapObject> {
        val result = mutableListOf<MapObject>()
        val end = object : MapObjectDecoder() {
            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                require(objectId in 0..0x1ffff && level in 0..3 && shape in 0..22)
                result.add(MapObject(objectId, localX, localY, level, shape, rotation))
            }
        }.decode(bytes, ByteArray(16384), region ushr 8, region and 255)
        require(bytes.copyOfRange(end, bytes.size).all { it == 0.toByte() }) { "Unknown landscape tail $region" }
        return result
    }

    /** Comparison only: discarded opcode 44 is Gielenor's dynamic recolour slot mask. */
    internal fun requireEquivalentNativeDefinition(id: Int, source: ByteArray, native: ByteArray) {
        val offsets = mutableListOf<Int>()
        ObjectDecoder().readLoopTraced(ObjectDefinition(id), BufferReader(native)) { _, position -> offsets.add(position) }
        offsets.add(native.size)
        var position = 0
        for (i in 0 until offsets.size - 1) {
            while (position < source.size && source[position].toInt() and 255 == 44) {
                require(position + 3 <= source.size) { "Truncated source mask $id" }
                position += 3
            }
            val record = native.copyOfRange(offsets[i], offsets[i + 1])
            val mapped = if (record.size == 4 && record[0].toInt() == 99 && record[1].toInt() == 0 &&
                position < source.size && source[position].toInt() and 255 == 190) {
                byteArrayOf(190.toByte(), record[2], record[3])
            } else record
            require(position + mapped.size <= source.size && source.copyOfRange(position, position + mapped.size).contentEquals(mapped)) {
                "Native object difference requires explicit adaptation: $id at source byte $position"
            }
            position += mapped.size
        }
        require(position == source.size) { "Unexpected source definition tail $id" }
    }
    private fun same(source: Cache, baseline: Cache, index: Int, archive: Int, file: Int) {
        val bytes = requireNotNull(source.data(index, archive, file))
        require(bytes.contentEquals(baseline.data(index, archive, file))) { "Native dependency differs $index/$archive/$file" }
    }

    private fun stage(root: Path, patch: Patch) {
        val directory = root.resolve("maps")
        val metadata = ArchiveMetadata(nameHash = patch.name.hashCode())
        metadata.fileIds(intArrayOf(0))
        val codec = SourceCodecs.codec(Index.MAPS)
        val source = codec.unpack(directory, SourceArchive(Index.MAPS, patch.archive, patch.name, intArrayOf(0), patch.bytes, listOf(patch.bytes), emptyList(), metadata))
        require(source.pristine.isEmpty()) { "Map requires opaque fallback ${patch.name}" }
        for (file in source.files) {
            val path = directory.resolve(file.path)
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
        }
        val compiled = codec.pack(directory, patch.archive, metadata)
        check(ArchiveGroup.split(compiled.group, compiled.fileIds).files.single().contentEquals(patch.bytes))
        Files.writeString(directory.resolve("${patch.archive}.metadata.json"), metadata.write())
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
