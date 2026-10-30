package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.TextureDecoder
import java.nio.file.Files
import java.nio.file.Path

/** Read-only comparison around a visual floor defect; no packed cache is modified. */
object ZeahFloorProbe {
    fun run(sourcePath: Path, previewPath: Path, palettePath: Path, x: Int, y: Int) {
        val region = ((x ushr 6) shl 8) or (y ushr 6)
        val entry = Json.parseToJsonElement(Files.readString(palettePath)).jsonObject.getValue("regionPalettes").jsonArray
            .single { it.jsonObject.getValue("sourceRegionId").jsonPrimitive.int == region }.jsonObject
        val overlays = entry.getValue("overlays").jsonArray.associate { it.jsonObject.getValue("rawTileId").jsonPrimitive.int to it.jsonObject.getValue("definitionId").jsonPrimitive.int }
        val underlays = entry.getValue("underlays").jsonArray.associate { it.jsonObject.getValue("rawTileId").jsonPrimitive.int to it.jsonObject.getValue("definitionId").jsonPrimitive.int }
        val name = "m${region ushr 8}_${region and 255}"
        FileCache(sourcePath.toRealPath().toString(), writable = false).use { source ->
            FileCache(previewPath.toRealPath().toString(), writable = false).use { preview ->
                val staged = MapDefinition(region)
                val donorBytes = requireNotNull(source.data(Index.MAPS, name))
                val stagedBytes = requireNotNull(preview.data(Index.MAPS, name))
                val donor = LegacyTerrainConversion.decode(donorBytes, region)
                require(MapTileDecoder.decode(stagedBytes, staged) <= stagedBytes.size)
                println("Zeah floor probe x=$x y=$y region=$region raw/mapped floor IDs")
                val overlayIds = mutableSetOf<Int>()
                val underlayIds = mutableSetOf<Int>()
                val materialIds = mutableSetOf<Int>()
                for (tx in (x and 63) - 2..(x and 63) + 2) for (ty in (y and 63) - 2..(y and 63) + 2) {
                    if (tx !in 0..63 || ty !in 0..63) continue
                    val i = (tx shl 6) or ty
                    val a = MapTile(donor.tiles[i])
                    val b = MapTile(staged.tiles[i])
                    if (a.overlayId > 0) overlayIds += overlays[a.overlayId] ?: (a.overlayId - 1)
                    if (a.underlayId > 0) underlayIds += underlays[a.underlayId] ?: (a.underlayId - 1)
                    println("$tx,$ty height=${a.height}/${b.height} settings=${a.settings}/${b.settings} overlay=${a.overlayId}->${overlays[a.overlayId] ?: (a.overlayId - 1)}/${b.overlayId} shape=${a.overlayPath},${a.overlayRotation}/${b.overlayPath},${b.overlayRotation} underlay=${a.underlayId}->${underlays[a.underlayId] ?: (a.underlayId - 1)}/${b.underlayId}")
                }
                for (id in overlayIds.sorted()) {
                    fun decode(cache: Cache): OverlayDefinition? = cache.data(Index.CONFIGS, 4, id)?.let {
                        OverlayDefinition(id).also { result -> OverlayDecoder().readLoop(result, BufferReader(it)) }
                    }
                    val a = decode(source)
                    val b = decode(preview)
                    if (a != null && a.texture >= 0) materialIds += a.texture
                    println("overlay definition $id sourceTexture=${a?.texture} previewTexture=${b?.texture} sameBytes=${source.data(Index.CONFIGS, 4, id)?.contentEquals(preview.data(Index.CONFIGS, 4, id))}")
                }
                for (id in underlayIds.sorted()) {
                    fun decode(cache: Cache): UnderlayDefinition? = cache.data(Index.CONFIGS, 1, id)?.let {
                        UnderlayDefinition(id).also { result -> UnderlayDecoder().readLoop(result, BufferReader(it)) }
                    }
                    val a = decode(source)
                    val b = decode(preview)
                    if (a != null && a.texture >= 0) materialIds += a.texture
                    println("underlay definition $id sourceTexture=${a?.texture} previewTexture=${b?.texture} sameBytes=${source.data(Index.CONFIGS, 1, id)?.contentEquals(preview.data(Index.CONFIGS, 1, id))}")
                }
                val sourceMaterials = MaterialDecoder().load(source)
                val previewMaterials = MaterialDecoder().load(preview)
                for (id in materialIds.sorted()) {
                    println("material $id graphEqual=${source.data(Index.TEXTURES, id)?.contentEquals(preview.data(Index.TEXTURES, id))} metadataEqual=${sourceMaterials.getOrNull(id) == previewMaterials.getOrNull(id)}")
                    val seen = mutableSetOf<Int>()
                    fun visit(textureId: Int) {
                        if (!seen.add(textureId)) return
                        val raw = source.data(Index.TEXTURES, textureId) ?: return
                        println("texture graph $textureId equal=${raw.contentEquals(preview.data(Index.TEXTURES, textureId))}")
                        val graph = TextureDefinition(textureId)
                        TextureDecoder().readLoop(graph, BufferReader(raw))
                        for (operation in graph.operations) {
                            if (operation.type == 36) for (record in operation.opcodes) if (record.opcode == 0) visit(requireNotNull(record.value))
                            if (operation.type in setOf(18, 39)) for (record in operation.opcodes) if (record.opcode == 0) {
                                val sprite = requireNotNull(record.value)
                                println("texture sprite $sprite equal=${source.data(Index.GRAPHICS, sprite)?.contentEquals(preview.data(Index.GRAPHICS, sprite))}")
                            }
                        }
                    }
                    visit(id)
                }
                val keyFile = Path.of("../drynor-world-server/data/map/xteaKeys.json")
                val keys = Json.parseToJsonElement(Files.readString(keyFile)).jsonObject
                val key = keys[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
                val landscape = requireNotNull(preview.data(Index.MAPS, "l${region ushr 8}_${region and 255}", key))
                val objectIds = sortedSetOf<Int>()
                val objects = object : MapObjectDecoder() {
                    override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                        if (level == 0 && kotlin.math.abs(localX - (x and 63)) <= 3 && kotlin.math.abs(localY - (y and 63)) <= 3) {
                            objectIds += objectId
                            println("object id=$objectId at=$localX,$localY shape=$shape rotation=$rotation")
                        }
                    }
                }
                require(objects.decode(landscape, ByteArray(16384), region ushr 8, region and 255) == landscape.size)
                for (id in objectIds) {
                    val data = preview.data(Index.OBJECTS, id ushr 8, id and 255) ?: continue
                    val definition = ObjectDefinition(id)
                    ObjectDecoder().readLoop(definition, BufferReader(data))
                    println("object definition $id name=${definition.name} models=${definition.modelIds?.joinToString { it.joinToString(",") }} contour=${definition.contouredGround} offsetY=${definition.offsetY} size=${definition.sizeX}x${definition.sizeY}")
                    for (modelId in definition.modelIds?.flatMap { it.toList() }?.distinct().orEmpty()) {
                        val raw = preview.data(Index.MODELS, modelId) ?: continue
                        val model = ModelDecoder.decode(modelId, raw) ?: continue
                        println("model $modelId yRange=${model.vertexY.minOrNull()}..${model.vertexY.maxOrNull()} faces=${model.faceCount}")
                    }
                }
            }
        }
    }
}
