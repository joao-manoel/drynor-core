package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Read-only hybrid-profile inventory. Native bridge/environment conversion is deliberately separate. */
object ZulrahInventory {
    internal val regions = listOf(8751, 259, 9007, 9008, 9263, 9264)
    // Settings.OSRS_MAP_IDS: 9263/9264 and the runtime region 259 are absent.
    internal fun objectOffset(region: Int) = if (region in setOf(8751, 9007, 9008)) 100000 else 0

    fun inspect(source: Cache, target: Cache, sourcePath: Path, targetPath: Path): JsonObject {
        val keysPath = Path.of("../drynor-world-server/data/map/xteaKeys.json").toAbsolutePath().normalize()
        require(Files.isRegularFile(keysPath)) { "Run from core; missing target world XTEAs: $keysPath" }
        val keys = Json.parseToJsonElement(Files.readString(keysPath)).jsonObject
        val objectIds = sortedSetOf<Int>()
        val maps = regions.map { region -> buildJsonObject {
            put("regionId", region)
            put("sourceOsrs", objectOffset(region) != 0)
            for (prefix in listOf("m", "l")) {
                val name = "$prefix${region ushr 8}_${region and 255}"
                val targetKeys = keys[region.toString()]?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()
                put(if (prefix == "m") "terrain" else "landscape", buildJsonObject {
                    put("name", name)
                    var original: ByteArray? = null
                    for ((label, cache) in listOf("source" to source, "target" to target)) {
                        put(label, buildJsonObject {
                            val archive = cache.archiveId(Index.MAPS, name)
                            put("archive", archive)
                            if (archive < 0) put("status", "missing") else try {
                                var mode = "plaintext"
                                val bytes = try { requireNotNull(cache.data(Index.MAPS, archive)) }
                                catch (failure: Exception) {
                                    if (label != "target" || prefix != "l" || targetKeys == null) throw failure
                                    mode = "world-xtea"
                                    requireNotNull(cache.data(Index.MAPS, archive, xtea = targetKeys))
                                }
                                put("readMode", mode)
                                put("bytes", bytes.size)
                                put("sha256", hash(bytes))
                                if (label == "source") original = bytes
                                else original?.let { put("equalSource", it.contentEquals(bytes)) }
                                if (prefix == "m") {
                                    val end = LegacyTerrainScan.validate(bytes)
                                    val map = MapDefinition(region)
                                    check(MapTileDecoder.decode(bytes, map) == end)
                                    put("tileBytes", end)
                                    put("environmentTailBytes", bytes.size - end)
                                    put("environmentTailSha256", hash(bytes.copyOfRange(end, bytes.size)))
                                    put("environmentConversionImplemented", bytes.size == end)
                                    put("overlayIdsRaw", numbers(map.tiles.map { MapTile(it).overlayId }.filter { it != 0 }.toSortedSet()))
                                    put("underlayIdsRaw", numbers(map.tiles.map { MapTile(it).underlayId }.filter { it != 0 }.toSortedSet()))
                                } else {
                                    val ids = sortedSetOf<Int>()
                                    var count = 0
                                    val decoder = object : MapObjectDecoder() {
                                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                            require(level in 0..3 && shape in 0..22)
                                            ids.add(objectId + if (label == "source") objectOffset(region) else 0)
                                            count++
                                        }
                                    }
                                    val end = decoder.decode(bytes, ByteArray(16384), region ushr 8, region and 255)
                                    require(bytes.drop(end).all { it == 0.toByte() }) { "Unknown landscape tail" }
                                    if (label == "source" && objectOffset(region) != 0) objectIds.addAll(ids)
                                    put("placements", count)
                                    put("objectDefinitionIds", numbers(ids))
                                }
                                put("status", "inspected")
                            } catch (error: Exception) {
                                put("status", "error")
                                put("error", "${error.javaClass.simpleName}: ${error.message}")
                            }
                        })
                    }
                })
            }
        } }
        objectIds.addAll(listOf(110068, 111700)) // Boat and gas cloud are also referenced by gameplay.
        val pending = java.util.TreeSet(objectIds)
        val visited = sortedSetOf<Int>()
        val objects = mutableListOf<JsonObject>()
        while (pending.isNotEmpty()) {
            val id = pending.pollFirst()
            if (!visited.add(id)) continue
            objects.add(definition(source, target, Index.OBJECTS, id, 8) { bytes ->
                val dependencies = OsrsObjectDependencies.parse(bytes)
                pending.addAll(dependencies.morphs.map { it + 100000 }.filter { it !in visited })
                buildJsonObject {
                    put("name", dependencies.name)
                    put("models", numbers(dependencies.models.map { it + 200000 }))
                    put("animations", numbers(dependencies.animations.map { it + 20000 }))
                    put("morphs", numbers(dependencies.morphs.map { it + 100000 }))
                    put("rawTextures", numbers(dependencies.textures))
                    put("rawSounds", numbers(dependencies.sounds))
                    put("rawSprites", numbers(dependencies.sprites))
                    put("rawMapIcons", numbers(dependencies.mapIcons))
                }
            })
        }
        val models = sortedSetOf<Int>()
        val sequences = sortedSetOf<Int>()
        objects.forEach { entry -> entry["dependencies"]?.jsonObject?.let { deps ->
            models.addAll(deps.getValue("models").jsonArray.map { it.jsonPrimitive.int })
            sequences.addAll(deps.getValue("animations").jsonArray.map { it.jsonPrimitive.int })
        } }
        val npcs = (22042..22045).map { id -> definition(source, target, Index.NPCS, id, 7) { bytes ->
            val npc = OsrsNpcDependencies.parse(bytes)
            models.addAll(npc.models.map { it + 200000 })
            sequences.addAll(npc.animations.map { it + 20000 })
            buildJsonObject {
                put("name", npc.name)
                put("models", numbers(npc.models.map { it + 200000 }))
                put("animations", numbers(npc.animations.map { it + 20000 }))
            }
        } }
        val items = listOf(42922, 42924, 42926, 42927, 42929, 42931, 42934).map { id -> definition(source, target, Index.ITEMS, id, 8) { bytes ->
            val links = linkedMapOf<Int, Int>()
            val item = OsrsItemConverter.decode(id, bytes, links)
            val itemModels = listOf(item.modelId, item.primaryMaleModel, item.secondaryMaleModel, item.tertiaryMaleModel,
                item.primaryFemaleModel, item.secondaryFemaleModel, item.tertiaryFemaleModel).filter { it > 0 }.toSet()
            models.addAll(itemModels)
            buildJsonObject {
                put("name", item.name)
                put("models", numbers(itemModels))
                put("rawRetextureFrom", numbers(item.originalTextureColours?.map { it.toInt() and 65535 }.orEmpty()))
                put("rawRetextureTo", numbers(item.modifiedTextureColours?.map { it.toInt() and 65535 }.orEmpty()))
                put("placeholderLinksByOpcode", buildJsonObject { links.forEach { (opcode, link) -> put(opcode.toString(), link) } })
            }
        } }
        val modelReports = models.map { id -> asset(id) {
            val bytes = requireNotNull(source.data(Index.MODELS, id)) { "Missing model" }
            val model = requireNotNull(ModelDecoder.decode(id, bytes)) {
                "No model geometry; bytes=${bytes.size}, allZero=${bytes.all { it == 0.toByte() }}, sha256=${hash(bytes)}"
            }
            buildJsonObject {
                put("sha256", hash(bytes))
                put("rawMaterialIds", numbers(model.faceTextures?.filter { it != (-1).toShort() }?.map { it.toInt() and 65535 }?.toSet().orEmpty()))
                put("particleEmitters", numbers(model.particleEmitters?.map { it.particleId }.orEmpty()))
                put("particleEffectors", numbers(model.particleEffectors?.map { it.effectorId }.orEmpty()))
                put("billboards", numbers(model.billboards?.map { it.billboardId }.orEmpty()))
            }
        } }
        val frames = sortedSetOf<Long>()
        val forcedFrames = sortedSetOf<Long>()
        val sequenceReports = sequences.map { id -> asset(id) {
            val bytes = requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127)) { "Missing sequence" }
            val sequence = OsrsSequenceDependencies.parse(bytes)
            val packed = sequence.frameIds.map { it.toLong() and 0xffffffffL }
            frames.addAll(packed)
            if (GielenorVisualProfile.forceOsrsSkeleton(id)) forcedFrames.addAll(packed)
            buildJsonObject {
                put("sha256", hash(bytes))
                put("rawPackedFrames", JsonArray(packed.map(::JsonPrimitive)))
                put("sounds", numbers(sequence.sounds))
                put("equipmentEncoded", numbers(sequence.equipment))
            }
        } }
        val bases = sortedMapOf<Int, Int>()
        val frameReports = frames.map { packed -> asset(packed) {
            val archive = (packed ushr 16).toInt() + 5000
            val file = (packed and 65535).toInt()
            val bytes = requireNotNull(source.data(Index.ANIMATION_FRAMES, archive, file)) { "Missing frame $archive/$file" }
            val frame = OsrsFrameDependencies.parseFrame(bytes)
            val base = GielenorVisualProfile.sourceBaseId(frame.baseId, packed in forcedFrames)
            require(base != 0) { "Native base 0 requires native grammar" }
            bases[base] = maxOf(bases[base] ?: 0, frame.transformCount)
            buildJsonObject {
                put("sourceArchive", archive)
                put("sourceFile", file)
                put("sourceBase", base)
                put("sha256", hash(bytes))
            }
        } }
        val baseReports = bases.map { (id, required) -> asset(id) {
            val bytes = requireNotNull(source.data(Index.ANIMATION_SKELETONS, id)) { "Missing base" }
            val base = OsrsFrameDependencies.parseBase(bytes)
            require(base.transformCount >= required) { "Frame exceeds base transforms" }
            buildJsonObject { put("transforms", base.transformCount); put("sha256", hash(bytes)) }
        } }
        return buildJsonObject {
            put("schemaVersion", 1)
            put("package", "zulrah")
            put("sourceCache", sourcePath.toString())
            put("targetCache", targetPath.toString())
            put("targetXteaSource", keysPath.toString())
            put("writesCache", false)
            put("dependencyClosureComplete", false)
            put("regions", JsonArray(maps))
            put("osrsObjectsIncludingMorphs", JsonArray(objects))
            put("npcCandidates", JsonArray(npcs))
            // InventoryOptionsHandler recipes corroborate these hybrid item IDs.
            put("itemCandidates", JsonArray(items))
            put("models", JsonArray(modelReports))
            put("sequences", JsonArray(sequenceReports))
            put("frames", JsonArray(frameReports))
            put("bases", JsonArray(baseReports))
            put("limitations", JsonArray(listOf(
                "Native-region object definitions are not interpreted as OSRS.",
                "Environment tails are fingerprinted, not converted or accepted as compatible.",
                "Object morph and declared idle/walk sequence closure are structurally inspected; combat sequences, effects, floors, sounds, overrides and item links remain unresolved.",
                "Presence and target occupancy do not allocate IDs or prove client compatibility."
            ).map(::JsonPrimitive)))
        }
    }

    internal fun asset(id: Number, inspect: () -> JsonObject) = buildJsonObject {
        put("id", JsonPrimitive(id))
        try {
            put("dependencies", inspect())
            put("status", "inspected")
        } catch (error: Exception) {
            put("status", "error")
            put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun definition(source: Cache, target: Cache, index: Int, id: Int, shift: Int, parse: (ByteArray) -> JsonObject) = buildJsonObject {
        put("id", id)
        put("index", index)
        val archive = id ushr shift
        val file = id and ((1 shl shift) - 1)
        put("archive", archive)
        put("file", file)
        put("targetIdOccupied", target.files(index, archive).contains(file))
        try {
            val data = requireNotNull(source.data(index, archive, file)) { "Missing definition" }
            put("sha256", hash(data))
            put("bytes", data.size)
            put("dependencies", parse(data))
            put("status", "inspected")
        } catch (error: Exception) {
            put("status", "error")
            put("error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun numbers(ids: Collection<Int>) = JsonArray(ids.map(::JsonPrimitive))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
