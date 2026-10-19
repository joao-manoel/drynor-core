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

/** Builds an editable overlay package, then applies its recompiled bytes only to a fresh packed copy. */
object StaticInfernoPreview {
    private val json = Json { prettyPrint = true }
    private data class Address(val index: Int, val archive: Int, val file: Int)
    private data class Asset(val address: Address, val bytes: ByteArray, val name: String? = null)

    fun build(sourcePath: Path, targetPath: Path, output: Path) {
        require(!Files.exists(output)) { "Preview output must be new." }
        val originalHashes = cacheHashes(targetPath)
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val targetSnapshot = InfernoDependencyPlan.snapshot(target)
                val inventory = InfernoInventory.inspect(source, target, sourcePath, targetPath)
                val plan = InfernoDependencyPlan.inspect(source, target, inventory)
                val modelMap = mapping(plan, "models")
                val objectMap = mapping(plan, "objects")
                val animationMap = mapping(plan, "animations")
                val omitted = ArrayList<JsonObject>()
                val definitions = sortedMapOf<Int, ObjectDefinition>()
                val assets = ArrayList<Asset>()
                val normalizedModels = ArrayList<JsonObject>()
                for ((id, destinationId) in objectMap) {
                    val data = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                    val dependencies = OsrsObjectDependencies.parse(data)
                    if (dependencies.morphs.isNotEmpty()) {
                        omitted.add(buildJsonObject { put("sourceObjectId", id); put("reason", "Morph selector semantics unresolved; placements omitted from static preview.") })
                        continue
                    }
                    val definition = OsrsObjectConverter.convert(destinationId, data, modelMap, animationMap, objectMap, staticPreview = true)
                    definitions[destinationId] = definition
                    val encoded = encode { with(ObjectEncoder()) { it.encode(definition) } }
                    val decoded = ObjectDefinition(destinationId)
                    ObjectDecoder().readLoop(decoded, BufferReader(encoded))
                    check(decoded.modelIds.contentDeepEquals(definition.modelIds) && decoded.sizeX == definition.sizeX && decoded.sizeY == definition.sizeY) { "Object readback failed: $destinationId" }
                    check(decoded.offsetX == definition.offsetX && decoded.offsetY == definition.offsetY && decoded.offsetZ == definition.offsetZ && decoded.solid == definition.solid && decoded.blocksSky == definition.blocksSky) { "Object transform/collision readback failed: $destinationId" }
                    assets.add(Asset(Address(Index.OBJECTS, destinationId ushr 8, destinationId and 255), encoded))
                    if (dependencies.animations.isNotEmpty() || dependencies.sprites.isNotEmpty() || dependencies.mapIcons.isNotEmpty() || dependencies.sounds.isNotEmpty()) {
                        omitted.add(buildJsonObject { put("sourceObjectId", id); put("reason", "Static preview disables animation, mapscene/map icons and ambient sounds.") })
                    }
                }
                val neededModels = definitions.values.flatMap { it.modelIds?.flatMap(IntArray::toList).orEmpty() }.toSortedSet()
                val reverseModels = modelMap.entries.associate { it.value to it.key }
                for (id in neededModels) {
                    val sourceId = reverseModels.getValue(id)
                    val data = requireNotNull(source.data(Index.MODELS, sourceId))
                    val model = if (data.size == 18 && data.all { it == 0.toByte() }) {
                        val placeholder = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                        check(placeholder.vertexCount == 0 && placeholder.faceCount == 0 && placeholder.particleEmitters.isNullOrEmpty() && placeholder.particleEffectors.isNullOrEmpty() && placeholder.billboards.isNullOrEmpty()) { "Native placeholder has geometry/attachments." }
                        normalizedModels.add(buildJsonObject { put("sourceModelId", sourceId); put("targetModelId", id); put("empty727Template", 1077) })
                        placeholder.copy(id = id)
                    } else ModelDecoder.read(id, data)
                    require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Textured model $sourceId requires material conversion." }
                    val encoded = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(id, encoded)).contentEquals(encoded))
                    assets.add(Asset(Address(Index.MODELS, id, 0), encoded))
                }
                val floorMapping = plan.getValue("proposedFloorMapping").jsonArray.map { it.jsonObject }
                val underlays = floorMapping.filter { it.getValue("type").jsonPrimitive.content == "underlay" }.associate { it.getValue("rawTileId").jsonPrimitive.int to it.getValue("targetTileId").jsonPrimitive.int }
                val overlays = floorMapping.filter { it.getValue("type").jsonPrimitive.content == "overlay" }.associate { it.getValue("rawTileId").jsonPrimitive.int to it.getValue("targetTileId").jsonPrimitive.int }
                for ((raw, tileId) in overlays) {
                    if (target.files(Index.CONFIGS, 4).contains(tileId - 1)) {
                        val effective = requireNotNull(source.data(Index.CONFIGS, 4, GielenorVisualProfile.effectiveInfernoOverlayFile(raw)))
                        check(effective.contentEquals(target.data(Index.CONFIGS, 4, tileId - 1))) { "Overlay reuse differs from baseline." }
                        continue
                    }
                    val sourceId = GielenorVisualProfile.effectiveInfernoOverlayFile(raw)
                    val definition = OverlayDefinition(tileId - 1)
                    OverlayDecoder().readLoop(definition, BufferReader(requireNotNull(source.data(Index.CONFIGS, 4, sourceId))))
                    require(definition.texture == -1) { "Textured floor requires material conversion." }
                    // Preserve RGB and opcode metadata; source and target grammars for these records agree.
                    val encoded = encode { with(OverlayEncoder()) { it.encode(definition) } }
                    assets.add(Asset(Address(Index.CONFIGS, 4, tileId - 1), encoded))
                }
                val terrainBytes = requireNotNull(source.data(Index.MAPS, "m35_82"))
                val terrain = LegacyTerrainConversion.decode(terrainBytes, 9042)
                for (index in terrain.tiles.indices) {
                    val tile = MapTile(terrain.tiles[index])
                    val overlay = if (tile.overlayId == 0) 0 else overlays.getValue(tile.overlayId)
                    val underlay = if (tile.underlayId == 0) 0 else underlays.getValue(tile.underlayId)
                    require(underlay in 0..174 && overlay in 0..255)
                    terrain.tiles[index] = MapTile(tile.height, tile.attrOpcode, overlay, tile.overlayPath, tile.overlayRotation, tile.settings, underlay).packed
                }
                val locs = MapDefinition(9042)
                var originalPlacements = 0
                val locations = requireNotNull(source.data(Index.MAPS, "l35_82"))
                val reader = object : MapObjectDecoder() {
                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                        originalPlacements++
                        val id = objectMap.getValue(objectId + 100000)
                        require(id in 0..0x1ffff) { "Object ID $id exceeds native MapObject storage." }
                        if (id in definitions) locs.objects.add(MapObject(id, localX, localY, level, shape, rotation))
                    }
                }
                check(reader.decode(locations, ByteArray(16384), 35, 82) == locations.size)
                val mapArchive = target.lastArchiveId(Index.MAPS) + 1
                assets.add(Asset(Address(Index.MAPS, mapArchive, 0), encode { with(MapTileEncoder()) { it.encode(terrain) } }, "m35_82"))
                assets.add(Asset(Address(Index.MAPS, mapArchive + 1, 0), encode { with(MapObjectEncoder()) { it.encode(locs) } }, "l35_82"))
                require(target.archiveId(Index.MAPS, "m35_82") == -1 && target.archiveId(Index.MAPS, "l35_82") == -1) { "Arena already exists in target." }
                for (asset in assets) require(!target.files(asset.address.index, asset.address.archive).contains(asset.address.file)) { "Refusing existing resource overwrite: ${asset.address}" }

                Files.createDirectories(output)
                val packagePath = output.resolve("source-package")
                val compiled = stage(packagePath, assets)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(targetPath).use { paths -> paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it, packed.resolve(it.fileName)) } }
                FileCache(packed.toString(), writable = true).use { destination ->
                    for (asset in compiled) {
                        val address = asset.address
                        asset.name?.let { name ->
                            val table = requireNotNull(destination.referenceTable(address.index))
                            check(table.named)
                            table.archiveOrCreate(address.archive).nameHash = name.hashCode()
                        }
                        destination.write(address.index, address.archive, address.file, asset.bytes)
                    }
                }
                var checkedArchives = 0
                FileCache(packed.toString(), writable = false).use { result ->
                    for (asset in compiled) check(result.data(asset.address.index, asset.address.archive, asset.address.file).contentEquals(asset.bytes)) { "Packed readback failed: ${asset.address}" }
                    check(result.archiveId(Index.MAPS, "m35_82") == mapArchive && result.archiveId(Index.MAPS, "l35_82") == mapArchive + 1)
                    val touched = compiled.map { it.address.index to it.address.archive }.toSet()
                    for (index in target.indices()) for (archive in target.archiveIds(index)) {
                        if (index to archive !in touched) {
                            check(target.container(index, archive).contentEquals(result.container(index, archive))) { "Unrelated archive changed: $index/$archive" }
                            checkedArchives++
                        } else for (file in target.files(index, archive)) {
                            check(target.data(index, archive, file).contentEquals(result.data(index, archive, file))) { "Existing file changed: $index/$archive/$file" }
                        }
                    }
                }
                check(sourceSnapshot == InfernoDependencyPlan.snapshot(source) && targetSnapshot == InfernoDependencyPlan.snapshot(target))
                check(originalHashes == cacheHashes(targetPath)) { "Original target cache files changed." }
                val report = buildJsonObject {
                    put("status", "experimental-static-preview")
                    put("sourceCache", sourcePath.toString()); put("baselineCache", targetPath.toString())
                    put("sourcePackage", packagePath.toString()); put("packedCache", packed.toString())
                    put("objectDefinitions", definitions.size); put("models", neededModels.size)
                    put("originalPlacements", originalPlacements); put("previewPlacements", locs.objects.size)
                    put("omittedPlacements", originalPlacements - locs.objects.size)
                    put("unchangedArchivesVerified", checkedArchives)
                    put("originalPackedCacheHashesVerified", true)
                    put("renderingValidated", false); put("gameplayImplemented", false)
                    put("omissions", JsonArray(omitted))
                    put("normalizedEmptyModels", JsonArray(normalizedModels))
                    put("visualLimitations", "No NPCs, animated objects, mapscene icons, custom runtime shape offsets or Gielenor atmosphere fallback. Morph placements are omitted, not replaced with invented geometry.")
                }
                Files.writeString(output.resolve("preview-report.json"), json.encodeToString(JsonObject.serializer(), report) + "\n")
                Files.writeString(output.resolve("dependency-plan.json"), json.encodeToString(JsonObject.serializer(), plan) + "\n")
                println("Static preview built and packed readback verified: $output")
            }
        }
    }

    private fun mapping(plan: JsonObject, name: String): Map<Int, Int> = plan.getValue("proposedIds").jsonObject.getValue(name).jsonArray.associate {
        val entry = it.jsonObject
        entry.getValue("source").jsonPrimitive.int to entry.getValue("target").jsonPrimitive.int
    }.toSortedMap()

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
        Files.writeString(root.resolve("README.txt"), "Intermediate editable representation only, not a complete SourceTree. These files were compiled and verified during creation. preview-inferno regenerates from the origin; it does not rebuild manual edits here. Do not pack this directory as a full cache.\n")
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
