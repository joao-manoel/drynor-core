package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Converts the Kraken cave to a bounded native asset patch. */
internal object KrakenCaveAssets {
    private const val region = 9116

    fun convert(sourcePath: Path, targetPath: Path, output: Path) {
        require(!Files.exists(output))
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val original = readObjects(requireNotNull(source.data(Index.MAPS, "l35_156")))
                val originals = original.objects.map { it.id + 100_000 }.toSortedSet()
                check(original.objects.size == 3592 && originals.size == 33)
                val objectBytes = originals.associateWith { requireNotNull(source.data(Index.OBJECTS, it ushr 8, it and 255)) }
                val dependencies = objectBytes.mapValues { OsrsObjectDependencies.parse(it.value) }
                val occupiedObjects = target.archiveIds(Index.OBJECTS).flatMap { archive ->
                    target.files(Index.OBJECTS, archive).map { (archive shl 8) or it }
                }.toSet()
                val objectIds = ZeahPlacementIds.plan(originals, occupiedObjects)
                check(objectIds.values.all { it in 0..0x1ffff })
                val modelIds = DeterministicIds.plan(
                    dependencies.values.flatMap { deps -> deps.models.map { it + 200_000 } }.toSet(),
                    target.archiveIds(Index.MODELS).toSet(),
                )
                val animations = ZulrahAnimationAssets.plan(
                    source, target, dependencies.values.flatMap { deps -> deps.animations.map { it + 20_000 } }.toSet(),
                )
                val assets = animations.assets.toMutableList()
                val graphics = Index.GRAPHICS
                val configs = Index.CONFIGS
                val sourceIcon = requireNotNull(source.data(graphics, 1295))
                val iconGraphic = (32766 downTo 1).first { target.data(graphics, it) == null }
                val iconConfig = (65534 downTo 1).first { target.data(configs, 36, it) == null }
                val (configBytes, references) = ZeahIconRemapPreview.remapConfig(
                    36, requireNotNull(source.data(configs, 36, 567)), mapOf(1295 to iconGraphic),
                )
                check(references == setOf(1295))
                assets += InfernoAssetWriter.Asset(graphics, iconGraphic, 0, sourceIcon)
                assets += InfernoAssetWriter.Asset(configs, 36, iconConfig, configBytes)

                // The donor scene configs and their sprites were audited as identical to Drynor.
                val scenes = setOf(7, 11, 12, 22, 25, 46)
                check(dependencies.values.flatMap { it.sprites }.toSet() == scenes)
                for (scene in scenes) check(requireNotNull(source.data(configs, 34, scene)).contentEquals(target.data(configs, 34, scene)))
                val originalModels = modelIds.entries.associate { (sourceId, destination) -> destination to sourceId }
                val variants = mutableMapOf<Pair<Int, Int>, Int>()
                var nextModel = maxOf(target.lastArchiveId(Index.MODELS), modelIds.values.max()) + 1
                for ((origin, id) in objectIds) {
                    val def = OsrsObjectConverter.convert(
                        id, objectBytes.getValue(origin), modelIds, animations.sequenceMap, objectIds,
                        verifiedMapsceneIds = scenes, auditedMapIconIds = mapOf(12 to iconConfig),
                    )
                    def.modelIds?.forEachIndexed { row, models ->
                        val shape = requireNotNull(def.modelTypes)[row].toInt()
                        for (index in models.indices) {
                            val sourceId = originalModels.getValue(models[index])
                            // Gielenor offsets the upscaled mesh by shape and by its index in the group.
                            val offset = (if (shape == 22) -4 else 1) - index
                            val key = sourceId to offset
                            models[index] = variants.getOrPut(key) {
                                if (variants.keys.none { it.first == sourceId }) modelIds.getValue(sourceId) else nextModel++
                            }
                        }
                    }
                    val bytes = encode { with(ObjectEncoder()) { it.encode(def) } }
                    val readback = ObjectDefinition(id)
                    ObjectDecoder().readLoop(readback, BufferReader(bytes))
                    check(encode { with(ObjectEncoder()) { it.encode(readback) } }.contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.OBJECTS, id ushr 8, id and 255, bytes)
                }
                for ((variant, id) in variants) {
                    val (origin, offset) = variant
                    val bytes = requireNotNull(source.data(Index.MODELS, origin))
                    val model = if (bytes.size == 18 && bytes.all { it == 0.toByte() }) {
                        val empty = ModelDecoder.read(1077, requireNotNull(target.data(Index.MODELS, 1077)))
                        check(empty.vertexCount == 0 && empty.faceCount == 0)
                        empty.copy(id = id)
                    } else ModelDecoder.read(id, bytes)
                    check(model.faceTextures?.all { it == (-1).toShort() } != false)
                    check(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val translated = if (model.vertexCount == 0) model else {
                        check(model.textureTriangleCount == 0 && model.faceRenderTypes?.all { it.toInt() in 0..1 } != false)
                        model.copy(
                            version = ModelDecoder.UPSCALE_BELOW_VERSION,
                            newFormat = true,
                            footerFlags = model.footerFlags or ModelDecoder.FLAG_VERSION,
                            vertexY = IntArray(model.vertexCount) { model.vertexY[it] + offset },
                            vertexFlags = null,
                        )
                    }
                    val encoded = ModelEncoder.encode(translated)
                    val readback = ModelDecoder.read(id, encoded)
                    check(readback.vertexX.contentEquals(model.vertexX) && readback.vertexZ.contentEquals(model.vertexZ))
                    check(readback.vertexY.contentEquals(translated.vertexY))
                    check(readback.faceColors.contentEquals(model.faceColors))
                    check(ModelEncoder.encode(readback).contentEquals(encoded))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, id, 0, encoded)
                }

                val terrain = LegacyTerrainConversion.decode(requireNotNull(source.data(Index.MAPS, "m35_156")), region)
                // Gielenor retains overlay 1091 after applying its client-side OSRS palette.
                val overlay = requireNotNull(source.data(configs, 4, 1091))
                check(overlay.contentEquals(byteArrayOf(5, 1, -1, 0, -1, 7, 0, 0, 0, 0)))
                check(target.data(configs, 4, 250) == null)
                assets += InfernoAssetWriter.Asset(configs, 4, 250, overlay)
                for (index in terrain.tiles.indices) {
                    val tile = MapTile(terrain.tiles[index])
                    if (tile.overlayId == 92) {
                        terrain.tiles[index] = MapTile(tile.height, tile.attrOpcode, 251, tile.overlayPath,
                            tile.overlayRotation, tile.settings, tile.underlayId).packed
                    }
                }
                for ((archive, files) in mapOf(4 to listOf(6, 36, 41), 1 to listOf(48, 52, 53, 70))) {
                    for (id in files) check(requireNotNull(source.data(configs, archive, id)).contentEquals(target.data(configs, archive, id)))
                }
                removeOverlappingWater(terrain)
                val terrainBytes = encode { with(MapTileEncoder()) { it.encode(terrain) } }
                val underwaterBytes = ZulrahWaterPreview.encode(ZulrahWaterPreview.underwater(terrain))
                val underwater = ZulrahWaterPreview.decodeStrict(underwaterBytes, region)
                check(ZulrahWaterPreview.encode(underwater).contentEquals(underwaterBytes))
                val decoded = MapDefinition(region)
                check(MapTileDecoder.decode(terrainBytes, decoded) == terrainBytes.size && decoded.tiles.contentEquals(terrain.tiles))
                val locations = MapDefinition(region)
                for (loc in original.objects) {
                    locations.objects += MapObject(objectIds.getValue(loc.id + 100_000), loc.x, loc.y, loc.level, loc.shape, loc.rotation)
                }
                val landscapeBytes = encode { with(MapObjectEncoder()) { it.encode(locations) } }
                check(readObjects(landscapeBytes).objects.map { it.packed }.sorted() == locations.objects.map { it.packed }.sorted())

                Files.createDirectory(output)
                for (asset in assets) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
                for ((name, bytes) in mapOf("m35_156" to terrainBytes, "l35_156" to landscapeBytes, "um35_156" to underwaterBytes)) {
                    check(target.archiveId(Index.MAPS, name) < 0)
                    Files.write(output.resolve("$name.bin"), bytes)
                }
                Files.writeString(output.resolve("object-ids.txt"), objectIds.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
        }
    }

    /** Caller supplies the offline cache after making a rollback backup. */
    fun install(input: Path, targetPath: Path, previous: Path? = null) {
        val assets = FileCache(targetPath.toString(), writable = false).use { target ->
            fun needsWrite(staged: Path, current: ByteArray?, replacement: ByteArray): Boolean {
                val original = previous?.resolve(staged.fileName)?.takeIf(Files::exists)
                require(if (original == null) current == null else Files.readAllBytes(original).contentEquals(current)) {
                    "Unexpected existing payload for ${staged.fileName}"
                }
                return !replacement.contentEquals(current)
            }

            val groups = Files.list(input).use { stream ->
                stream.filter { it.fileName.toString().matches(Regex("\\d+-\\d+-\\d+\\.bin")) }.sorted().toList()
            }
            check(groups.size >= 100)
            val definitions = groups.mapNotNull { file ->
                val (index, archive, id) = file.fileName.toString().removeSuffix(".bin").split('-').map(String::toInt)
                val current = target.data(index, archive, id)
                val bytes = Files.readAllBytes(file)
                if (needsWrite(file, current, bytes)) InfernoAssetWriter.Asset(index, archive, id, bytes, current != null)
                else null
            }
            var nextMap = target.lastArchiveId(Index.MAPS) + 1
            val maps = listOf("m35_156", "l35_156", "um35_156").mapNotNull { name ->
                val file = input.resolve("$name.bin")
                val archive = target.archiveId(Index.MAPS, name)
                val current = if (archive >= 0) requireNotNull(target.data(Index.MAPS, archive, 0)) else null
                val bytes = Files.readAllBytes(file)
                if (!needsWrite(file, current, bytes)) null
                else if (archive >= 0) InfernoAssetWriter.Asset(Index.MAPS, archive, 0, bytes, replaceExisting = true)
                else InfernoAssetWriter.Asset(Index.MAPS, nextMap++, 0, bytes, archiveName = name)
            }
            definitions + maps
        }
        InfernoAssetWriter.write(targetPath, assets)
        FileCache(targetPath.toString(), writable = false).use { target ->
            for (asset in assets) {
                check(asset.bytes.contentEquals(target.data(asset.index, asset.archive, asset.file)))
                asset.archiveName?.let { check(target.archiveId(asset.index, it) == asset.archive) }
            }
        }
        println("Verified ${assets.size} updated Kraken cave cache files")
    }

    private fun removeOverlappingWater(terrain: MapDefinition) {
        val planeSize = MapDefinition.SIZE * MapDefinition.SIZE
        var removed = 0
        var moved = 0
        for (index in 0 until planeSize) {
            val ground = MapTile(terrain.tiles[index])
            val upper = MapTile(terrain.tiles[index + planeSize])
            if (upper.overlayId != 7) continue
            check(upper.height == 1 && upper.settings == 8 && upper.underlayId == 0)
            val x = index / MapDefinition.SIZE
            val y = index % MapDefinition.SIZE
            check(x + 1 < MapDefinition.SIZE && y + 1 < MapDefinition.SIZE)
            check(intArrayOf(0, 1, MapDefinition.SIZE, MapDefinition.SIZE + 1).all { corner ->
                MapTile(terrain.tiles[index + planeSize + corner]).height == 1
            }) { "Water surfaces have different heights at $x,$y" }
            when (ground.overlayId) {
                7 -> {
                    // Full tiles cover every shape. These two diagonal pairs also have
                    // the upper triangle fully contained in the ground water mesh.
                    check(ground.overlayPath == 0 ||
                        ground.attrOpcode == 9 && upper.attrOpcode == 15 ||
                        ground.attrOpcode == 6 && upper.attrOpcode == 13)
                    removed++
                }
                37 -> {
                    // The dark floor and upper water occupy complementary triangles.
                    // Keep the water triangle on plane zero so it shares the pool lighting.
                    check(ground.overlayPath == 1 && upper.overlayPath == 1 &&
                        (ground.overlayRotation + 2) and 3 == upper.overlayRotation)
                    terrain.tiles[index] = MapTile(ground.height, upper.attrOpcode, upper.overlayId,
                        upper.overlayPath, upper.overlayRotation, ground.settings, ground.underlayId).packed
                    moved++
                }
                else -> error("Unexpected floor under Kraken water at $x,$y")
            }
            terrain.tiles[index + planeSize] = MapTile(height = upper.height, settings = upper.settings).packed
        }
        check(removed == 70 && moved == 25) { "Kraken water layout changed in the donor cache" }
        println("Removed $removed duplicate water overlays and moved $moved shoreline triangles")
    }

    private fun readObjects(bytes: ByteArray): MapDefinition {
        val map = MapDefinition(region)
        val decoder = object : MapObjectDecoder() {
            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                map.objects += MapObject(objectId, localX, localY, level, shape, rotation)
            }
        }
        check(decoder.decode(bytes, ByteArray(16384), region ushr 8, region and 255) == bytes.size)
        return map
    }

    private fun encode(block: (Writer) -> Unit): ByteArray = BufferWriter(1 shl 20).also(block).toArray()
}
