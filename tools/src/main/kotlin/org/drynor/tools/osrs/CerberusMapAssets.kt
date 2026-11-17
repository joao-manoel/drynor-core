package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Builds terrain and placement payloads. Includes scenery definitions and animation dependencies. */
internal object CerberusMapAssets {
    private val regions = listOf(4883, 5139, 5140, 5395)

    fun stage(planPath: Path, output: Path, baseline: Path? = null) {
        require(!Files.exists(output))
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        check(plan.getValue("selectedOsrsRegions").jsonArray.map { it.jsonPrimitive.int } == regions)
        val sourcePath = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetPath = (baseline ?: Path.of(plan.getValue("targetCache").jsonPrimitive.content)).toRealPath()
        val outputPath = output.toAbsolutePath().normalize()
        require(!outputPath.startsWith(sourcePath) && !outputPath.startsWith(targetPath))

        val payloads = mutableMapOf<String, ByteArray>()
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val idMap = plan.getValue("sourceObjects").jsonArray.associate { entry ->
                    val row = entry.jsonObject
                    val donor = row.getValue("runtimeId").jsonPrimitive.int
                    val native = row.getValue("plannedTargetId").jsonPrimitive.int
                    val definition = requireNotNull(source.data(Index.OBJECTS, donor ushr 8, donor and 255))
                    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(definition))
                    check(hash == row.getValue("sourceSha256").jsonPrimitive.content)
                    check(native in 0..0x1ffff && target.data(Index.OBJECTS, native ushr 8, native and 255) == null)
                    donor to native
                }
                check(idMap.values.toSet().size == idMap.size)
                val overlay = requireNotNull(source.data(Index.CONFIGS, 4, 1127))
                check(overlay.contentEquals(target.data(Index.CONFIGS, 4, 1127)))
                val overlayId = (254 downTo 0).first { target.data(Index.CONFIGS, 4, it) == null }
                payloads["${Index.CONFIGS}-4-$overlayId.bin"] = overlay
                val seen = mutableSetOf<Int>()
                for (region in regions) {
                    val suffix = "${region ushr 8}_${region and 255}"
                    val terrainName = "m$suffix"
                    val landscapeName = "l$suffix"
                    check(target.archiveId(Index.MAPS, terrainName) == -1 && target.archiveId(Index.MAPS, landscapeName) == -1)
                    val terrain = LegacyTerrainConversion.decode(requireNotNull(source.data(Index.MAPS, terrainName)), region)
                    for (index in terrain.tiles.indices) {
                        val tile = MapTile(terrain.tiles[index])
                        // The Gielenor client resolves raw overlay 128 to definition 1127.
                        if (tile.overlayId == 128) {
                            terrain.tiles[index] = MapTile(tile.height, tile.attrOpcode, overlayId + 1,
                                tile.overlayPath, tile.overlayRotation, tile.settings, tile.underlayId).packed
                        }
                    }
                    val bytes = encode { with(MapTileEncoder()) { it.encode(terrain) } }
                    val decoded = MapDefinition(region)
                    check(MapTileDecoder.decode(bytes, decoded) == bytes.size && decoded.tiles.contentEquals(terrain.tiles))
                    payloads["$terrainName.bin"] = bytes

                    val placements = readObjects(region, requireNotNull(source.data(Index.MAPS, landscapeName)))
                    val native = MapDefinition(region)
                    for (placement in placements.objects) {
                        val donor = placement.id + 100_000
                        seen += donor
                        native.objects += MapObject(idMap.getValue(donor), placement.x, placement.y, placement.level,
                            placement.shape, placement.rotation)
                    }
                    val landscape = encode { with(MapObjectEncoder()) { it.encode(native) } }
                    check(readObjects(region, landscape).objects.map { it.packed }.sorted() == native.objects.map { it.packed }.sorted())
                    payloads["$landscapeName.bin"] = landscape
                    println("$suffix: ${native.objects.size} placements and ${terrain.tiles.size} terrain tiles verified")
                }
                check(seen == idMap.keys)
                val assets = scenery(source, target, idMap)
                ZulrahActorPreview.validateFreshAssets(target, assets)
                for (asset in assets) {
                    check(payloads.putIfAbsent("${asset.index}-${asset.archive}-${asset.file}.bin", asset.bytes) == null)
                }
                Files.createDirectory(output)
                for ((name, bytes) in payloads) Files.write(output.resolve(name), bytes)
                Files.writeString(output.resolve("object-ids.txt"), idMap.entries.joinToString("\n") { "${it.key}=${it.value}" })
                println("Staged ${payloads.size} payloads with overlay $overlayId")
            }
        }
    }

    fun install(input: Path, cachePath: Path, backupPath: Path, previous: Path? = null) {
        require(cachePath.toRealPath() != backupPath.toRealPath())
        val assets = FileCache(cachePath.toString(), writable = false).use { target ->
            val files = Files.list(input).use { stream ->
                stream.filter { it.fileName.toString().matches(Regex("\\d+-\\d+-\\d+\\.bin")) }.sorted().toList()
            }
            val definitions = files.map { file ->
                val (index, archive, id) = file.fileName.toString().removeSuffix(".bin").split('-').map(String::toInt)
                val before = previous?.resolve(file.fileName)?.takeIf(Files::exists)
                val current = target.data(index, archive, id)
                check(if (before == null) current == null else Files.readAllBytes(before).contentEquals(current)) {
                    "Unexpected existing file ${file.fileName}"
                }
                InfernoAssetWriter.Asset(index, archive, id, Files.readAllBytes(file), replaceExisting = before != null)
            }
            check(definitions.count { it.index == Index.OBJECTS } == 96)
            var nextMap = target.lastArchiveId(Index.MAPS) + 1
            val maps = regions.flatMap { region ->
                val suffix = "${region ushr 8}_${region and 255}"
                listOf("m$suffix", "l$suffix").map { name ->
                    val bytes = Files.readAllBytes(input.resolve("$name.bin"))
                    val archive = target.archiveId(Index.MAPS, name)
                    if (previous == null) {
                        check(archive == -1)
                        InfernoAssetWriter.Asset(Index.MAPS, nextMap++, 0, bytes, archiveName = name)
                    } else {
                        check(archive >= 0 && bytes.contentEquals(target.data(Index.MAPS, archive, 0)))
                        InfernoAssetWriter.Asset(Index.MAPS, archive, 0, bytes, replaceExisting = true)
                    }
                }
            }
            definitions + maps
        }
        val changedAssets = FileCache(cachePath.toString(), writable = false).use { current ->
            assets.filter { !it.bytes.contentEquals(current.data(it.index, it.archive, it.file)) }
        }
        check(changedAssets.isNotEmpty())
        InfernoAssetWriter.write(cachePath, changedAssets)
        FileCache(backupPath.toString(), writable = false).use { backup ->
            FileCache(cachePath.toString(), writable = false).use { result ->
                for (asset in assets) {
                    check(asset.bytes.contentEquals(result.data(asset.index, asset.archive, asset.file)))
                    asset.archiveName?.let { check(result.archiveId(asset.index, it) == asset.archive) }
                }
                val changed = assets.groupBy { it.index to it.archive }
                var unchanged = 0
                for (index in backup.indices()) {
                    for (archive in backup.archiveIds(index)) {
                        if (index to archive !in changed) {
                            check(backup.container(index, archive).contentEquals(result.container(index, archive))) {
                                "Unexpected change to $index/$archive"
                            }
                            unchanged++
                        } else {
                            for (file in backup.files(index, archive)) {
                                check(backup.data(index, archive, file).contentEquals(result.data(index, archive, file))) {
                                    "Unexpected change to $index/$archive/$file"
                                }
                            }
                        }
                    }
                }
                println("Verified ${assets.size} installed files and $unchanged unchanged cache archives")
            }
        }
    }

    private fun scenery(source: Cache, target: Cache, ids: Map<Int, Int>): List<InfernoAssetWriter.Asset> {
        val definitions = ids.keys.associateWith { requireNotNull(source.data(Index.OBJECTS, it ushr 8, it and 255)) }
        val deps = definitions.values.map(OsrsObjectDependencies::parse)
        check(deps.all { it.morphs.isEmpty() && it.textures.isEmpty() && it.sounds.isEmpty() && it.mapIcons.isEmpty() })
        val scenes = deps.flatMap { it.sprites }.toSet()
        for (scene in scenes) {
            val config = requireNotNull(source.data(Index.CONFIGS, 34, scene))
            check(config.contentEquals(target.data(Index.CONFIGS, 34, scene))) { "Divergent mapscene $scene" }
            val (_, sprites) = ZeahIconRemapPreview.remapConfig(34, config, emptyMap())
            for (sprite in sprites.filter { it >= 0 }) {
                val bytes = requireNotNull(source.data(Index.GRAPHICS, sprite))
                check(bytes.contentEquals(target.data(Index.GRAPHICS, sprite))) { "Divergent mapscene sprite $sprite" }
            }
        }
        val sequences = deps.flatMap { it.animations.map { raw -> raw + 20_000 } }.toSet()
        val animations = ZulrahAnimationAssets.plan(source, target, sequences, sceneOnlySequences = sequences)
        val assets = animations.assets.toMutableList()
        val models = DeterministicIds.plan(
            deps.flatMap { it.models.map { raw -> raw + 200_000 } }.toSet(),
            target.archiveIds(Index.MODELS).toSet(),
        )
        val origins = models.entries.associate { (origin, native) -> native to origin }
        val variants = linkedMapOf<Pair<Int, Int>, Int>()
        val usedOrigins = mutableSetOf<Int>()
        var nextModel = maxOf(target.lastArchiveId(Index.MODELS), models.values.max()) + 1
        for ((origin, id) in ids) {
            val definition = OsrsObjectConverter.convert(
                id, definitions.getValue(origin), models, animations.sequenceMap, ids, verifiedMapsceneIds = scenes,
            )
            definition.modelIds?.forEachIndexed { row, rowModels ->
                val shape = requireNotNull(definition.modelTypes)[row].toInt()
                for (slot in rowModels.indices) {
                    val sourceModel = origins.getValue(rowModels[slot])
                    // Gielenor applies both adjustments to the mesh after upscaling.
                    val offset = (if (shape == 22) -4 else 1) - slot
                    rowModels[slot] = variants.getOrPut(sourceModel to offset) {
                        if (usedOrigins.add(sourceModel)) models.getValue(sourceModel) else nextModel++
                    }
                }
            }
            val bytes = encode { with(ObjectEncoder()) { it.encode(definition) } }
            val decoded = ObjectDefinition(id)
            ObjectDecoder().readLoop(decoded, BufferReader(bytes))
            check(encode { with(ObjectEncoder()) { it.encode(decoded) } }.contentEquals(bytes)) { "Object round trip failed for $id" }
            assets += InfernoAssetWriter.Asset(Index.OBJECTS, id ushr 8, id and 255, bytes)
        }
        for ((variant, id) in variants) {
            val (origin, offset) = variant
            val donor = requireNotNull(source.data(Index.MODELS, origin))
            val model = if (donor.size == 18 && donor.all { it == 0.toByte() }) {
                val empty = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                check(empty.vertexCount == 0 && empty.faceCount == 0)
                empty.copy(id = id)
            } else ModelDecoder.read(id, donor)
            check(model.faceTextures?.all { it == (-1).toShort() } != false)
            check(model.billboards.isNullOrEmpty() && model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty())
            val translated = if (offset == 0 || model.vertexCount == 0) model else model.copy(
                version = maxOf(model.version, ModelDecoder.UPSCALE_BELOW_VERSION),
                newFormat = true,
                footerFlags = model.footerFlags or ModelDecoder.FLAG_VERSION,
                vertexY = IntArray(model.vertexCount) { model.vertexY[it] + offset },
                vertexFlags = null,
            )
            val bytes = ModelEncoder.encode(translated)
            val readback = ModelDecoder.read(id, bytes)
            check(readback.vertexX.contentEquals(model.vertexX) && readback.vertexZ.contentEquals(model.vertexZ))
            check(readback.vertexY.contentEquals(translated.vertexY)) { "Y translation lost on model $origin" }
            check(ModelEncoder.encode(readback).contentEquals(bytes))
            assets += InfernoAssetWriter.Asset(Index.MODELS, id, 0, bytes)
        }
        println("Verified ${variants.size} scenery model variants")
        return assets
    }

    private fun readObjects(region: Int, bytes: ByteArray): MapDefinition {
        val map = MapDefinition(region)
        val decoder = object : MapObjectDecoder() {
            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                map.objects += MapObject(objectId, localX, localY, level, shape, rotation)
            }
        }
        check(decoder.decode(bytes, ByteArray(16384), region ushr 8, region and 255) == bytes.size)
        return map
    }

    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
}
