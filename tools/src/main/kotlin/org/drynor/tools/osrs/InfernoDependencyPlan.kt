package org.drynor.tools.osrs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.security.MessageDigest

/** Proposed IDs belong only to the fingerprinted target snapshot; this does not reserve or write them. */
object InfernoDependencyPlan {
    fun inspect(source: Cache, target: Cache, inventory: JsonObject): JsonObject {
        val problems = sortedSetOf<String>()
        val modelIds = sortedSetOf<Int>()
        val animationIds = sortedSetOf<Int>()
        val textures = sortedSetOf<Int>()
        val sounds = sortedSetOf<Int>()
        val varbits = sortedSetOf<Int>()
        val varps = sortedSetOf<Int>()
        val objectIds = inventory.getValue("regions").jsonArray.first().jsonObject
            .getValue("landscape").jsonObject["objectDefinitionIds"]?.jsonArray
            ?.map { it.jsonPrimitive.int }.orEmpty().toSortedSet()
        if (objectIds.isEmpty()) problems.add("Arena objects unavailable; see inventory errors.")
        objectIds.addAll(130283..130287)
        objectIds.addAll(listOf(130345, 130346)) // Zuk's animated prison walls.
        val objects = ArrayList<JsonObject>()
        val visitedObjects = sortedSetOf<Int>()
        val queue = java.util.TreeSet(objectIds)
        while (queue.isNotEmpty()) {
            val id = queue.pollFirst()
            if (!visitedObjects.add(id)) continue
            objects.add(inspectDefinition(source, Index.OBJECTS, id, 8, problems) { data ->
                val definition = OsrsObjectDependencies.parse(data)
                modelIds.addAll(definition.models.map { it + 200000 })
                animationIds.addAll(definition.animations.map { it + 20000 })
                val morphs = definition.morphs.map { it + 100000 }
                queue.addAll(morphs.filter { it !in visitedObjects })
                textures.addAll(definition.textures)
                sounds.addAll(definition.sounds)
                varbits.addAll(definition.varbits)
                varps.addAll(definition.varps)
                if (definition.sprites.isNotEmpty() || definition.mapIcons.isNotEmpty()) problems.add("Object mapscene sprites/map icons require profile-specific resolution.")
                buildJsonObject {
                    put("name", definition.name)
                    put("models", numbers(definition.models.map { it + 200000 }))
                    put("animations", numbers(definition.animations.map { it + 20000 }))
                    put("morphs", numbers(morphs))
                    put("rawTextureIds", numbers(definition.textures))
                    put("rawSoundIds", numbers(definition.sounds))
                    put("rawVarbits", numbers(definition.varbits))
                    put("rawVarps", numbers(definition.varps))
                    put("rawMapsceneSprites", numbers(definition.sprites))
                    put("rawMapIcons", numbers(definition.mapIcons))
                }
            })
        }
        val npcs = ArrayList<JsonObject>()
        val visitedNpcs = sortedSetOf<Int>()
        val npcQueue = java.util.TreeSet((27690..27709).toList())
        while (npcQueue.isNotEmpty()) {
            val id = npcQueue.pollFirst()
            if (!visitedNpcs.add(id)) continue
            npcs.add(inspectDefinition(source, Index.NPCS, id, 7, problems) { data ->
                val definition = OsrsNpcDependencies.parse(data)
                modelIds.addAll(definition.models.map { it + 200000 })
                animationIds.addAll(definition.animations.map { it + 20000 })
                val morphs = definition.morphs.map { it + 20000 }
                npcQueue.addAll(morphs.filter { it !in visitedNpcs })
                textures.addAll(definition.textures)
                varbits.addAll(definition.varbits)
                varps.addAll(definition.varps)
                if (definition.headIcons.isNotEmpty()) problems.add("NPC head icons need sprite-table semantic mapping.")
                buildJsonObject {
                    put("name", definition.name)
                    put("models", numbers(definition.models.map { it + 200000 }))
                    put("animations", numbers(definition.animations.map { it + 20000 }))
                    put("morphs", numbers(morphs))
                    put("rawTextureIds", numbers(definition.textures))
                    put("rawVarbits", numbers(definition.varbits))
                    put("rawVarps", numbers(definition.varps))
                    put("rawHeadIcons", numbers(definition.headIcons))
                }
            })
        }
        // Gielenor's combat definitions and inferno combat scripts, not only NPC idle/walk BAS.
        animationIds.addAll(listOf(27574, 27575, 27576, 27578, 27579, 27580,
            27581, 27582, 27583, 27584, 27585, 27597, 27598, 27599,
            27604, 27605, 27606, 27607, 27610, 27612, 27613,
            27590, 27591, 27592, 27593, 27594, 22635, 22637, 22638, 22639,
            27561, 27562, 27563, 27565, 27566, 22865, 22868, 22869))
        val spotIds = setOf(5157, 5448, 5451, 5559, 6375, 6376, 6377, 6378, 6382)
        for (id in spotIds) {
            val spot = OsrsSpotConverter.parse(id, requireNotNull(source.data(Index.SPOTANIMS, id ushr 8, id and 255)))
            modelIds.add(spot.modelId)
            if (spot.animationId != -1) animationIds.add(spot.animationId)
        }
        val models = modelIds.map { id ->
            inspectDefinition(source, Index.MODELS, id, null, problems) { data ->
                if (data.size == 18 && data.all { it == 0.toByte() }) {
                    problems.add("Empty 18-byte legacy models require an explicit 727 placeholder normalization/reuse rule.")
                    return@inspectDefinition buildJsonObject {
                        put("geometry", "empty-legacy-placeholder")
                        put("vertices", 0)
                        put("faces", 0)
                        put("requires727Normalization", true)
                        put("renderingValidated", false)
                    }
                }
                // Newer OSRS markers are not one of the two 727 layouts. Do not feed them to the old reader.
                require(data.size >= 20) { "Model shorter than supported footer." }
                val penultimate = data[data.size - 2].toInt() and 255
                val last = data.last().toInt() and 255
                require(penultimate != 255 || last !in 253..254) { "Unsupported OSRS model marker FF ${last.toString(16)}" }
                val model = requireNotNull(ModelDecoder.decode(id, data)) { "727 probe returned no geometry." }
                require(model.faceA.all { it in 0 until model.vertexCount } && model.faceB.all { it in 0 until model.vertexCount } && model.faceC.all { it in 0 until model.vertexCount }) { "Invalid geometry indices." }
                val roundTrip = ModelEncoder.encode(model).contentEquals(data)
                if (!roundTrip) problems.add("Model $id is not byte-exact with the 727 codec; conversion requires investigation.")
                val materials = model.faceTextures?.filter { it != (-1).toShort() }?.map { it.toInt() and 65535 }?.toSortedSet().orEmpty()
                textures.addAll(materials)
                if (!model.particleEmitters.isNullOrEmpty() || !model.particleEffectors.isNullOrEmpty() || !model.billboards.isNullOrEmpty()) problems.add("Model attachments require particle/billboard dependency expansion.")
                buildJsonObject {
                    put("vertices", model.vertexCount)
                    put("faces", model.faceCount)
                    put("version", model.version)
                    put("codec727ByteExact", roundTrip)
                    put("rawMaterialIds", numbers(materials))
                    put("renderingValidated", false)
                }
            }
        }
        val frameReferences = sortedSetOf<Long>()
        val forcedFrames = sortedSetOf<Long>()
        val animations = animationIds.map { id ->
            inspectDefinition(source, Index.ANIMATIONS, id, 7, problems) { data ->
                val definition = OsrsSequenceDependencies.parse(data)
                // Keep the original 32-bit packed value unsigned in JSON and while sorting.
                frameReferences.addAll(definition.frameIds.map { it.toLong() and 0xffffffffL })
                if (GielenorVisualProfile.forceOsrsSkeleton(id)) forcedFrames.addAll(definition.frameIds.map { it.toLong() and 0xffffffffL })
                sounds.addAll(definition.sounds)
                if (definition.equipment.any { it != 0 }) problems.add("Animation equipment overrides need item dependency expansion.")
                buildJsonObject {
                    put("rawPackedFrames", JsonArray(definition.frameIds.map { it.toLong() and 0xffffffffL }.sorted().map(::JsonPrimitive)))
                    put("rawSoundIds", numbers(definition.sounds))
                    put("equipmentEncoded", numbers(definition.equipment))
                    put("closureStatus", "frame-presence-inspected-bases-pending")
                }
            }
        }
        val baseIds = sortedSetOf<Int>()
        val frameTransformCounts = sortedMapOf<Int, Int>()
        val frames = frameReferences.map { packed ->
            val archive = (packed ushr 16).toInt() + 5000
            val file = (packed and 65535).toInt()
            buildJsonObject {
                put("rawPackedFrame", packed)
                put("sourceArchive", archive)
                put("sourceFile", file)
                try {
                    val data = requireNotNull(source.data(Index.ANIMATION_FRAMES, archive, file)) { "Missing frame" }
                    put("bytes", data.size)
                    put("sha256", sha256(data))
                    val frame = OsrsFrameDependencies.parseFrame(data)
                    val baseId = GielenorVisualProfile.sourceBaseId(frame.baseId, packed in forcedFrames)
                    require(baseId != 0) { "Frame uses native base 0: inspect with 727 base grammar, not legacy OSRS." }
                    baseIds.add(baseId)
                    frameTransformCounts[baseId] = maxOf(frameTransformCounts[baseId] ?: 0, frame.transformCount)
                    put("sourceBaseId", baseId)
                    put("transformCount", frame.transformCount)
                    put("status", "inspected-legacy-osrs")
                } catch (error: Exception) {
                    val message = "Frame $archive/$file: ${error.message}"
                    problems.add(message)
                    put("status", "error")
                    put("error", message)
                }
            }
        }
        val bases = baseIds.map { id ->
            inspectDefinition(source, Index.ANIMATION_SKELETONS, id, null, problems) { data ->
                val base = OsrsFrameDependencies.parseBase(data)
                require(base.transformCount >= frameTransformCounts.getValue(id)) { "Frame references transforms beyond base size." }
                buildJsonObject {
                    put("transformCount", base.transformCount)
                    put("profile", "legacy-osrs")
                    put("requires727Conversion", true)
                }
            }
        }
        val arenaTerrain = inventory.getValue("regions").jsonArray.first().jsonObject.getValue("terrain").jsonObject
        val floors = buildJsonArray {
            for ((field, archive) in listOf("underlayIdsRaw" to 1, "overlayIdsRaw" to 4)) {
                for (value in arenaTerrain[field]?.jsonArray.orEmpty()) {
                    val tileId = value.jsonPrimitive.int
                    val file = tileId - 1 + 1000
                    add(buildJsonObject {
                        put("type", if (archive == 1) "underlay" else "overlay")
                        put("rawTileId", tileId)
                        put("sourceIndex", Index.CONFIGS)
                        put("sourceArchive", archive)
                        put("sourceFile", file)
                        try {
                            val data = requireNotNull(source.data(Index.CONFIGS, archive, file)) { "Missing floor definition" }
                            put("bytes", data.size)
                            put("sha256", sha256(data))
                            put("byteIdenticalTargetFileCandidates", numbers(target.files(Index.CONFIGS, archive).filter {
                                target.data(Index.CONFIGS, archive, it)?.contentEquals(data) == true
                            }))
                            val effectiveFile = if (archive == 1) GielenorVisualProfile.effectiveUnderlayFile(tileId) else GielenorVisualProfile.effectiveInfernoOverlayFile(tileId)
                            val effective = requireNotNull(source.data(Index.CONFIGS, archive, effectiveFile)) { "Missing client-effective floor definition" }
                            put("clientEffectiveSourceFile", effectiveFile)
                            put("clientEffectiveSha256", sha256(effective))
                            put("clientEffectiveByteIdenticalTargetCandidates", numbers(target.files(Index.CONFIGS, archive).filter {
                                target.data(Index.CONFIGS, archive, it)?.contentEquals(effective) == true
                            }))
                            put("status", "present-not-converted")
                        } catch (error: Exception) {
                            val message = "Floor $field/$tileId: ${error.message}"
                            problems.add(message)
                            put("status", "error")
                            put("error", message)
                        }
                    })
                }
            }
        }
        val optionalMaps = buildJsonArray {
            for (region in listOf("35_82", "71_82")) for (prefix in listOf("um", "ul")) {
                val name = "$prefix$region"
                val archive = source.archiveId(Index.MAPS, name)
                add(buildJsonObject { put("name", name); put("archive", archive); put("present", archive >= 0) })
                if (archive >= 0) problems.add("Optional map $name exists; underwater dependencies need inspection before import.")
            }
        }
        val freeOverlayFiles = (0..254).filter { it !in target.files(Index.CONFIGS, 4) }.iterator()
        val floorMapping = buildJsonArray {
            for (floor in floors.map { it.jsonObject }) {
                add(buildJsonObject {
                    put("rawTileId", floor.getValue("rawTileId"))
                    put("type", floor.getValue("type"))
                    put("visualPolicy", "gielenor-client-effective")
                    val candidates = floor["clientEffectiveByteIdenticalTargetCandidates"]?.jsonArray.orEmpty().map { it.jsonPrimitive.int }
                    if (candidates.isNotEmpty()) {
                        val chosen = candidates.min()
                        put("targetDefinitionId", chosen)
                        put("targetTileId", chosen + 1)
                        put("action", "reuse-byte-identical-definition")
                    } else if (floor.getValue("type").jsonPrimitive.contentOrNull == "overlay" && freeOverlayFiles.hasNext()) {
                        val chosen = freeOverlayFiles.next()
                        put("targetDefinitionId", chosen)
                        put("targetTileId", chosen + 1)
                        put("action", "convert-into-unoccupied-overlay-slot")
                    } else {
                        put("action", "unresolved")
                        problems.add("Floor mapping lacks a proven reusable definition or a free compatible slot.")
                    }
                    put("reserved", false)
                })
            }
        }
        problems.add("Frames/bases require 727 conversion and calibration; gameplay-only sequences/effects have not been fully resolved.")
        problems.add("Infernal Cape, pet and gameplay-only effects still need dependency parsing.")
        problems.add("Floor definitions and tile IDs require semantic conversion; free-slot capacity alone is not a mapping.")
        if (textures.isNotEmpty()) problems.add("OSRS texture/material IDs need profile calibration and sprite dependency expansion.")
        if (sounds.isNotEmpty()) problems.add("OSRS ambient sounds need source-to-hybrid address verification.")
        if (varbits.isNotEmpty() || varps.isNotEmpty()) problems.add("Morph variables need semantic mapping; IDs must not be copied directly.")
        return buildJsonObject {
            put("schemaVersion", 1)
            put("package", "inferno")
            put("gielenorClientReferenceCommit", GielenorVisualProfile.CLIENT_COMMIT)
            put("clientRuntimeRules", buildJsonObject {
                put("animationTranslationMultiplier", 4)
                put("animationRotationMultiplierBefore727Rotation", 16)
                put("npcStance", "Synthesized from idle/walk at runtime; must generate explicit BAS or equivalent adapter.")
                put("objectOffsets", "OSRS shape 22 Y=-4, other shapes Y=+1, model-index translation and additional object exceptions require explicit conversion policy.")
                put("conditionalZukShieldObject", buildJsonObject {
                    put("id", 10050); put("model", 233036); put("animation", 27567); put("translateY", 12)
                    put("usageConfirmedByInfernoGameplay", false)
                })
                put("infernalCapeMaterial", buildJsonObject {
                    put("runtimeId", 2710); put("backingTextureId", 40); put("replacedSprite", 485); put("replacementSprite", 20318)
                    put("status", "synthesized-in-client-not-a-direct-cache-resource")
                })
            })
            put("floorVisualPolicies", "Raw OSRS and Gielenor client-effective floors are reported separately; visual policy must be explicit before importing.")
            put("status", "blocked-for-import")
            put("writesCache", false)
            put("dependencyClosureComplete", false)
            put("allocationConstraints", "Proposals check occupancy and Int overflow only; consumer field widths must be validated before encoding.")
            put("fingerprintScope", "Index-255 reference tables plus per-resource payload hashes; not a full cache digest.")
            put("inventory", inventory)
            put("sourceSnapshot", snapshot(source))
            put("targetSnapshot", snapshot(target))
            put("objects", JsonArray(objects))
            put("npcs", JsonArray(npcs))
            put("models", JsonArray(models))
            put("animations", JsonArray(animations))
            put("frames", JsonArray(frames))
            put("bases", JsonArray(bases))
            put("floors", floors)
            put("proposedFloorMapping", floorMapping)
            put("optionalMaps", optionalMaps)
            put("rawTextureMaterialCandidates", numbers(textures))
            put("runtimeMaterialCandidates", numbers(textures.map(GielenorVisualProfile::runtimeMaterial).toSet()))
            put("rawSoundCandidates", numbers(sounds))
            put("proposedIds", buildJsonObject {
                put("objects", allocation(target, Index.OBJECTS, visitedObjects, 8))
                put("npcs", allocation(target, Index.NPCS, visitedNpcs, 7))
                put("models", allocation(target, Index.MODELS, modelIds, null))
                put("animations", allocation(target, Index.ANIMATIONS, animationIds, 7))
                put("spots", allocation(target, Index.SPOTANIMS, spotIds, 8))
                put("frameSets", allocation(target, Index.ANIMATION_FRAMES, frameReferences.map { (it ushr 16).toInt() + 5000 }.toSet(), null))
                put("bases", allocation(target, Index.ANIMATION_SKELETONS, baseIds, null))
            })
            put("floorCapacity", buildJsonObject {
                put("underlayFreeTileIds", numbers((1..174).filter { it - 1 !in target.files(Index.CONFIGS, 1) }))
                put("overlayFreeTileIds", numbers((1..255).filter { it - 1 !in target.files(Index.CONFIGS, 4) }))
                put("status", "capacity-only-no-floor-mapping")
            })
            put("blockers", JsonArray(problems.map(::JsonPrimitive)))
        }
    }

    private fun inspectDefinition(source: Cache, index: Int, id: Int, shift: Int?, problems: MutableSet<String>, parse: (ByteArray) -> JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("index", index)
        try {
            val data = requireNotNull(source.data(index, if (shift == null) id else id ushr shift, if (shift == null) 0 else id and ((1 shl shift) - 1))) { "Missing definition" }
            put("sha256", sha256(data))
            put("bytes", data.size)
            put("dependencies", parse(data))
            put("status", "inspected")
        } catch (error: Exception) {
            val message = "index=$index id=$id: ${error.javaClass.simpleName}: ${error.message}"
            problems.add(message)
            put("status", "error")
            put("error", message)
        }
    }

    private fun allocation(cache: Cache, index: Int, ids: Set<Int>, shift: Int?): JsonArray {
        val occupied = cache.archiveIds(index).flatMap { archive -> if (shift == null) listOf(archive) else cache.files(index, archive).map { (archive shl shift) or it } }.toSet()
        val plan = DeterministicIds.plan(ids, occupied)
        return buildJsonArray {
            for ((source, destination) in plan) add(buildJsonObject { put("source", source); put("target", destination); put("reserved", false) })
        }
    }

    internal fun snapshot(cache: Cache) = buildJsonObject {
        for (index in cache.indices().sorted()) cache.container(255, index)?.let { put(index.toString(), sha256(it)) }
    }

    private fun numbers(ids: Collection<Int>) = JsonArray(ids.sorted().map(::JsonPrimitive))
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

internal object DeterministicIds {
    fun plan(sourceIds: Set<Int>, occupied: Set<Int>): Map<Int, Int> {
        require(sourceIds.all { it >= 0 } && occupied.all { it >= 0 }) { "Negative resource ID" }
        val result = sortedMapOf<Int, Int>()
        val used = occupied.toMutableSet()
        // Preserve every free source ID before assigning replacements, so one replacement cannot steal another source's ID.
        for (id in sourceIds.sorted()) if (id !in occupied) { result[id] = id; used.add(id) }
        var next = (used.maxOrNull() ?: -1).toLong() + 1
        for (id in sourceIds.sorted()) if (id in occupied) {
            require(next <= Int.MAX_VALUE) { "Resource ID range exhausted" }
            val assigned = next++.toInt()
            result[id] = assigned
        }
        return result
    }
}
