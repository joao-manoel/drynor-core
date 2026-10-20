package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.encoder.OverlayEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object ZulrahStaticPreview {
    internal val osrsRegions = listOf(8494,8495,8496,8750,8751,9006,9007,9008)
    private val json = Json { prettyPrint = true }
    private data class Address(val index: Int, val archive: Int, val file: Int)
    private data class Asset(val address: Address, val bytes: ByteArray, val name: String? = null)
    internal fun overlayFile(raw: Int): Int {
        require(raw in setOf(2,6,22,42,81,85,131,153,161)) { "Unverified overlay $raw" }
        return when (raw) { 6 -> 111; 85 -> 113; else -> raw - 1 }
    }
    internal fun underlayFile(raw: Int): Int {
        require(raw in setOf(18,19,48,49,50,53,56,57,61,62,63,65,111))
        return raw - 1
    }
    fun build(sourcePath: Path, targetPath: Path, output: Path) {
        require(!Files.exists(output))
        val worldKeys = Json.parseToJsonElement(Files.readString(Path.of("../drynor-world-server/data/map/xteaKeys.json"))).jsonObject
        fun landscapeKeys(name: String?): IntArray? {
            if (name == null || !name.startsWith("l")) return null
            val coordinates = name.substring(1).split('_')
            require(coordinates.size == 2)
            val region = (coordinates[0].toInt() shl 8) or coordinates[1].toInt()
            return worldKeys[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
        }
        val baselineHashes = cacheHashes(targetPath)
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val targetSnapshot = InfernoDependencyPlan.snapshot(target)
                val locations = sortedMapOf<Int, MapDefinition>()
                val ids = sortedSetOf<Int>()
                for (region in osrsRegions) {
                    val map = MapDefinition(region)
                    val bytes = requireNotNull(source.data(Index.MAPS, "l${region ushr 8}_${region and 255}"))
                    val decoder = object : MapObjectDecoder() {
                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                            require(level in 0..3 && shape in 0..22)
                            ids.add(objectId + 100000)
                            map.objects.add(MapObject(objectId + 100000,localX,localY,level,shape,rotation))
                        }
                    }
                    check(decoder.decode(bytes,ByteArray(16384),region ushr 8,region and 255) == bytes.size)
                    locations[region] = map
                }
                val deps = ids.associateWith { OsrsObjectDependencies.parse(requireNotNull(source.data(Index.OBJECTS,it ushr 8,it and 255))) }
                val modelIds = deps.values.flatMap { it.models.map { id -> id + 200000 } }.toSet()
                val modelMap = DeterministicIds.plan(modelIds,target.archiveIds(Index.MODELS).toSet())
                val occupied = target.archiveIds(Index.OBJECTS).flatMap { a -> target.files(Index.OBJECTS,a).map { (a shl 8) or it } }.toSet()
                val objectMap = DeterministicIds.plan(ids,occupied)
                require(objectMap.values.all { it in 0..0x1ffff })
                val animationMap = deps.values.flatMap { it.animations.map { id -> id + 20000 } }.associateWith { it }
                val definitions = sortedMapOf<Int,ObjectDefinition>()
                val omissions = ArrayList<JsonObject>()
                val assets = ArrayList<Asset>()
                for ((id,dependency) in deps) {
                    val blocked = dependency.models.map { it + 200000 }.filter { it in setOf(201077,201105) }
                    if (dependency.morphs.isNotEmpty() || blocked.isNotEmpty()) {
                        omissions.add(buildJsonObject { put("sourceObjectId",id); put("reason",if (blocked.isNotEmpty()) "Unresolved nonempty model grammar $blocked" else "Unresolved morph selector"); put("omittedPlacements",locations.values.sumOf { m -> m.objects.count { it.id == id } }) })
                        continue
                    }
                    val definition = OsrsObjectConverter.convert(objectMap.getValue(id),requireNotNull(source.data(Index.OBJECTS,id ushr 8,id and 255)),modelMap,animationMap,objectMap,staticPreview = true)
                    definitions[id] = definition
                    val bytes = encode { with(ObjectEncoder()) { it.encode(definition) } }
                    val read = ObjectDefinition(definition.id)
                    ObjectDecoder().readLoop(read,BufferReader(bytes))
                    check(read.modelIds.contentDeepEquals(definition.modelIds) && read.sizeX == definition.sizeX && read.sizeY == definition.sizeY && read.solid == definition.solid && read.blocksSky == definition.blocksSky)
                    assets.add(Asset(Address(Index.OBJECTS,definition.id ushr 8,definition.id and 255),bytes))
                    if (dependency.animations.isNotEmpty() || dependency.sounds.isNotEmpty() || dependency.sprites.isNotEmpty() || dependency.mapIcons.isNotEmpty()) omissions.add(buildJsonObject { put("sourceObjectId",id); put("reason","Static preview disables animations, ambient sounds and map icons") })
                }
                val needed = definitions.values.flatMap { it.modelIds?.flatMap(IntArray::toList).orEmpty() }.toSortedSet()
                val reverse = modelMap.entries.associate { it.value to it.key }
                val normalized = ArrayList<JsonObject>()
                for (id in needed) {
                    val origin = reverse.getValue(id)
                    val bytes = requireNotNull(source.data(Index.MODELS,origin))
                    val model = if (bytes.size == 18 && bytes.all { it == 0.toByte() }) {
                        val template = ModelDecoder.read(1077,requireNotNull(target.data(Index.MODELS,1077)))
                        check(template.vertexCount == 0 && template.faceCount == 0 && template.particleEmitters.isNullOrEmpty() && template.particleEffectors.isNullOrEmpty() && template.billboards.isNullOrEmpty())
                        normalized.add(buildJsonObject { put("sourceModelId",origin); put("targetModelId",id); put("empty727Template",1077) })
                        template.copy(id=id)
                    } else ModelDecoder.read(id,bytes)
                    require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Uncalibrated materials $origin" }
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val encoded = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(id,encoded)).contentEquals(encoded))
                    assets.add(Asset(Address(Index.MODELS,id,0),encoded))
                }
                var nextMap = target.lastArchiveId(Index.MAPS) + 1
                val replacements = sortedSetOf<Int>()
                val regionReports = ArrayList<JsonObject>()
                for ((region,original) in locations) {
                    val suffix = "${region ushr 8}_${region and 255}"
                    val terrain = LegacyTerrainConversion.decode(requireNotNull(source.data(Index.MAPS,"m$suffix")),region)
                    for (i in terrain.tiles.indices) {
                        val tile = MapTile(terrain.tiles[i])
                        val overlay = if (tile.overlayId == 0) 0 else overlayFile(tile.overlayId) + 1
                        val underlay = if (tile.underlayId == 0) 0 else underlayFile(tile.underlayId) + 1
                        if (overlay != 0) check(source.data(Index.CONFIGS,4,overlay-1).contentEquals(target.data(Index.CONFIGS,4,overlay-1))) { "Effective floor differs $overlay" }
                        if (underlay != 0) check(source.data(Index.CONFIGS,1,underlay-1).contentEquals(target.data(Index.CONFIGS,1,underlay-1))) { "Effective underlay differs $underlay" }
                        terrain.tiles[i] = MapTile(tile.height,tile.attrOpcode,overlay,tile.overlayPath,tile.overlayRotation,tile.settings,underlay).packed
                    }
                    val locs = MapDefinition(region)
                    for (loc in original.objects) if (loc.id in definitions) locs.objects.add(MapObject(objectMap.getValue(loc.id),loc.x,loc.y,loc.level,loc.shape,loc.rotation))
                    val terrainBytes = encode { with(MapTileEncoder()) { it.encode(terrain) } }
                    val terrainRead = MapDefinition(region)
                    check(MapTileDecoder.decode(terrainBytes,terrainRead) == terrainBytes.size)
                    check(terrain.tiles.contentEquals(terrainRead.tiles))
                    val locBytes = encode { with(MapObjectEncoder()) { it.encode(locs) } }
                    for ((prefix,bytes) in listOf("m" to terrainBytes,"l" to locBytes)) {
                        val name = "$prefix$suffix"
                        val existing = target.archiveId(Index.MAPS,name)
                        if (existing >= 0) { require(region in setOf(8496,9008)); replacements.add(existing) }
                        assets.add(Asset(Address(Index.MAPS,if(existing >= 0) existing else nextMap++,0),bytes,name))
                    }
                    regionReports.add(buildJsonObject { put("regionId",region); put("originalPlacements",original.objects.size); put("previewPlacements",locs.objects.size); put("omittedPlacements",original.objects.size-locs.objects.size) })
                }
                Files.createDirectories(output)
                val compiled = stage(output.resolve("source-package"),assets)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(targetPath).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it,packed.resolve(it.fileName)) } }
                FileCache(packed.toString(),writable=true).use { cache -> for (asset in compiled) {
                    val a = asset.address
                    asset.name?.let { requireNotNull(cache.referenceTable(a.index)).archiveOrCreate(a.archive).nameHash = it.hashCode() }
                    cache.write(a.index,a.archive,a.file,asset.bytes,landscapeKeys(asset.name))
                } }
                var checked = 0
                FileCache(packed.toString(),writable=false).use { cache ->
                    for(asset in compiled) check(cache.data(asset.address.index,asset.address.archive,asset.address.file,landscapeKeys(asset.name)).contentEquals(asset.bytes))
                    val touched = compiled.map { it.address.index to it.address.archive }.toSet()
                    for(index in target.indices()) for(archive in target.archiveIds(index)) {
                        if(index to archive !in touched) { check(target.container(index,archive).contentEquals(cache.container(index,archive))); checked++ }
                        else if(!(index == Index.MAPS && archive in replacements)) for(file in target.files(index,archive)) check(target.data(index,archive,file).contentEquals(cache.data(index,archive,file)))
                    }
                }
                check(sourceSnapshot == InfernoDependencyPlan.snapshot(source) && targetSnapshot == InfernoDependencyPlan.snapshot(target))
                check(baselineHashes == cacheHashes(targetPath))
                val report = buildJsonObject {
                    put("status","experimental-zulrah-static"); put("packedCache","packed-cache")
                    put("sourceCache",sourcePath.toString()); put("baselineCache",targetPath.toString())
                    put("regions",JsonArray(regionReports)); put("objects",definitions.size); put("models",needed.size)
                    put("unchangedArchivesVerified",checked); put("baselineFileHashesVerified",true)
                    put("omissions",JsonArray(omissions)); put("normalizedEmptyModels",JsonArray(normalized))
                    put("renderingValidated",false); put("gameplayImplemented",false)
                    put("limitations","Static diagnostic: native9263/9264 unchanged. No NPCs/items/combat/animations/ambient sounds/map icons; unresolved morphs and nonempty models explicitly omitted.")
                }
                Files.writeString(output.resolve("zulrah-static-preview.json"),json.encodeToString(JsonObject.serializer(),report)+"\n")
                println("Zulrah static preview verified: $output")
            }
        }
    }
    private fun stage(root: Path, assets: List<Asset>): List<Asset> {
        val result = ArrayList<Asset>()
        for ((key, group) in assets.groupBy { it.address.index to it.address.archive }.toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })) {
            val (index, archive) = key
            val ordered = group.sortedBy { it.address.file }
            val directory = root.resolve(IndexNames.name(index))
            val codec = SourceCodecs.codec(index)
            val metadata = ArchiveMetadata(nameHash = ordered.first().name?.hashCode() ?: 0)
            metadata.fileIds(ordered.map { it.address.file }.toIntArray())
            val data = ordered.map { it.bytes }
            val unpacked = codec.unpack(directory, SourceArchive(index, archive, ordered.first().name, metadata.fileIds(), ArchiveGroup.join(data), data, emptyList(), metadata))
            check(unpacked.pristine.isEmpty()) { "Converted archive needs pristine fallback: $index/$archive" }
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
            }
            val packed = codec.pack(directory, archive, metadata)
            val files = ArchiveGroup.split(packed.group, packed.fileIds).files
            for ((position, fileId) in packed.fileIds.withIndex()) {
                val original = ordered.single { it.address.file == fileId }
                check(files[position].contentEquals(original.bytes)) { "Editable source roundtrip failed: $index/$archive/$fileId" }
                result.add(original.copy(bytes = files[position]))
            }
            Files.writeString(directory.resolve("$archive.metadata.json"), metadata.write())
        }
        Files.writeString(root.resolve("README.txt"), "Intermediate editable representation only, not a complete SourceTree. These files were compiled and verified during creation. preview-zulrah-static regenerates from the origin; it does not rebuild manual edits here. Do not pack this directory as a full cache.\n")
        return result
    }

    private fun encode(block: (Writer) -> Unit): ByteArray = BufferWriter(1 shl 20).also(block).toArray()

    private fun cacheHashes(path: Path): Map<String, String> = Files.list(path).use { paths ->
        paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.sorted().toList().associate {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(it).use { stream -> val buffer = ByteArray(65536); while (true) { val read = stream.read(buffer); if (read == -1) break; digest.update(buffer, 0, read) } }
            it.fileName.toString() to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
