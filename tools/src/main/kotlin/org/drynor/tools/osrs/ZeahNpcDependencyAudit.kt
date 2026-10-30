package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.buffer.read.BufferReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Strict source dependency probe for OSRS-offset NPCs selected by Zeah's spawn inventory. */
object ZeahNpcDependencyAudit {
    fun audit(sourcePath: Path, spawnReportPath: Path, outputPath: Path, targetPath: Path? = null) {
        require(!Files.exists(outputPath))
        val inventory = Json.parseToJsonElement(Files.readString(spawnReportPath)).jsonObject
        require(Path.of(inventory.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourcePath.toRealPath())
        val selected = inventory.getValue("donorGuardEligibleNpcIds").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val queue = java.util.TreeSet(selected.filter { it >= 20000 })
        val visited = sortedSetOf<Int>()
        val models = sortedSetOf<Int>()
        val animations = sortedSetOf<Int>()
        val animationNpcs = sortedMapOf<Int, MutableSet<Int>>()
        val textures = sortedSetOf<Int>()
        val headIcons = sortedSetOf<Int>()
        FileCache(sourcePath.toString(), writable = false).use { source ->
            val reports = buildJsonArray {
                while (queue.isNotEmpty()) {
                    val id = queue.pollFirst()
                    if (!visited.add(id)) continue
                    add(buildJsonObject {
                        put("id", id)
                        try {
                            val bytes = requireNotNull(source.data(Index.NPCS, id ushr 7, id and 127)) { "Missing NPC definition" }
                            val deps = OsrsNpcDependencies.parse(bytes)
                            val morphs = deps.morphs.map { it + 20000 }
                            queue.addAll(morphs.filter { it !in visited })
                            models.addAll(deps.models.map { it + 200000 })
                            deps.animations.forEach { rawId ->
                                val animationId = rawId + 20000
                                animations.add(animationId)
                                animationNpcs.getOrPut(animationId) { sortedSetOf() }.add(id)
                            }
                            textures.addAll(deps.textures)
                            headIcons.addAll(deps.headIcons)
                            put("status", "parsed")
                            put("name", deps.name)
                            put("morphIds", buildJsonArray { morphs.sorted().forEach { add(JsonPrimitive(it)) } })
                            put("modelIds", buildJsonArray { deps.models.sorted().forEach { add(JsonPrimitive(it + 200000)) } })
                            put("animationIds", buildJsonArray { deps.animations.sorted().forEach { add(JsonPrimitive(it + 20000)) } })
                        } catch (error: Exception) {
                            put("status", "blocked")
                            put("error", "${error.javaClass.simpleName}: ${error.message}")
                        }
                    })
                }
            }
            val frameIds = sortedSetOf<Int>()
            val frameSequences = sortedMapOf<Int, MutableSet<Int>>()
            val sequenceFailures = buildJsonArray {
                animations.forEach { id ->
                    try {
                        val bytes = requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127))
                        OsrsSequenceDependencies.parse(bytes).frameIds.forEach { frameId ->
                            frameIds.add(frameId)
                            frameSequences.getOrPut(frameId) { sortedSetOf() }.add(id)
                        }
                    } catch (error: Exception) {
                        add(buildJsonObject { put("id", id); put("error", "${error.javaClass.simpleName}: ${error.message}") })
                    }
                }
            }
            val baseIds = sortedSetOf<Int>()
            var nativeFrames = 0
            var legacyFrames = 0
            val missingFrameIds = sortedSetOf<Int>()
            val frameFailures = buildJsonArray {
                frameIds.forEach { id ->
                    // Gielenor stores OSRS frame sets 5000 archives above the sequence's packed reference.
                    val sourceArchive = (id ushr 16) + 5000
                    val bytes = source.data(Index.ANIMATION_FRAMES, sourceArchive, id and 65535)
                    if (bytes == null) {
                        missingFrameIds.add(id)
                        add(buildJsonObject {
                            put("id", id)
                            put("sourceArchive", sourceArchive)
                            put("reason", "missing-source-frame")
                            put("sequenceIds", buildJsonArray {
                                frameSequences.getValue(id).forEach { add(JsonPrimitive(it)) }
                            })
                        })
                        return@forEach
                    }
                    try {
                        try {
                            val rawBase = OsrsFrameDependencies.parseFrame(bytes).baseId
                            val forceOsrs = frameSequences.getValue(id).any(GielenorVisualProfile::forceOsrsSkeleton)
                            baseIds.add(GielenorVisualProfile.sourceBaseId(rawBase, forceOsrs))
                            legacyFrames++
                        } catch (_: Exception) {
                            val native = AnimationFrameDefinition((sourceArchive shl 16) or (id and 65535))
                            AnimationFrameDecoder().readLoop(native, BufferReader(bytes))
                            baseIds.add(native.baseId)
                            nativeFrames++
                        }
                    } catch (error: Exception) {
                        add(buildJsonObject { put("id", id); put("error", "${error.javaClass.simpleName}: ${error.message}") })
                    }
                }
            }
            val targetFrameReports = buildJsonArray {
                if (targetPath != null) {
                    FileCache(targetPath.toString(), writable = false).use { target ->
                        missingFrameIds.forEach { id ->
                            add(buildJsonObject {
                                put("id", id)
                                val bytes = target.data(Index.ANIMATION_FRAMES, id ushr 16, id and 65535)
                                if (bytes == null) {
                                    put("status", "missing-target-frame")
                                } else {
                                    try {
                                        val native = AnimationFrameDefinition(id)
                                        AnimationFrameDecoder().readLoop(native, BufferReader(bytes))
                                        put("status", "native-decoded")
                                        put("baseId", native.baseId)
                                    } catch (error: Exception) {
                                        put("status", "target-decode-failed")
                                        put("error", "${error.javaClass.simpleName}: ${error.message}")
                                    }
                                }
                            })
                        }
                    }
                }
            }
            val result = buildJsonObject {
                put("schemaVersion", 1)
                put("scopeComplete", false)
                put("sourceCache", sourcePath.toString())
                put("targetCache", targetPath?.toString() ?: "")
                put("spawnReport", spawnReportPath.toString())
                put("directOsrsNpcCount", selected.count { it >= 20000 })
                put("nativeRangeNpcCount", selected.count { it < 20000 })
                put("npcClosureCount", visited.size)
                put("npcReports", reports)
                put("animationNpcOwners", buildJsonArray {
                    animationNpcs.forEach { (animationId, owners) ->
                        add(buildJsonObject {
                            put("animationId", animationId)
                            put("npcIds", buildJsonArray { owners.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                })
                put("uniqueModelIds", models.size)
                put("uniqueAnimationIds", animations.size)
                put("uniqueFrameIds", frameIds.size)
                put("uniqueBaseIds", baseIds.size)
                put("nativeFramesDecoded", nativeFrames)
                put("legacyFramesDecoded", legacyFrames)
                put("sequenceFailures", sequenceFailures)
                put("frameFailures", frameFailures)
                put("missingSourceFrameTargetReports", targetFrameReports)
                put("missingSourceBases", buildJsonArray {
                    baseIds.forEach { if (source.data(Index.ANIMATION_SKELETONS, it) == null) add(JsonPrimitive(it)) }
                })
                put("uniqueTextureIds", textures.size)
                put("uniqueHeadIconIds", headIcons.size)
                put("missingSourceModels", buildJsonArray {
                    models.forEach { if (source.data(Index.MODELS, it) == null) add(JsonPrimitive(it)) }
                })
                put("missingSourceAnimations", buildJsonArray {
                    animations.forEach { if (source.data(Index.ANIMATIONS, it ushr 7, it and 127) == null) add(JsonPrimitive(it)) }
                })
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
        }
    }
}
