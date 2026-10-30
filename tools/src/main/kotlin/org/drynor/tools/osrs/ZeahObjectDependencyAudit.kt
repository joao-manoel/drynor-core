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
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.buffer.read.BufferReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Strictly decodes donor object definitions to expose asset closure before conversion. */
object ZeahObjectDependencyAudit {
    fun audit(sourcePath: Path, planPath: Path, outputPath: Path, targetPath: Path? = null) {
        require(!Files.exists(outputPath))
        val plan = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        require(Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourcePath.toRealPath())
        val objects = plan.getValue("sourceObjects").jsonArray
        val models = sortedSetOf<Int>()
        val animations = sortedSetOf<Int>()
        val morphs = sortedSetOf<Int>()
        val textures = sortedSetOf<Int>()
        val sounds = sortedSetOf<Int>()
        val sprites = sortedSetOf<Int>()
        val mapIcons = sortedSetOf<Int>()
        var parsed = 0
        FileCache(sourcePath.toString(), writable = false).use { source ->
            val reports = buildJsonArray {
                for (entry in objects) {
                    val item = entry.jsonObject
                    val id = item.getValue("runtimeId").jsonPrimitive.content.toInt()
                    add(buildJsonObject {
                        put("runtimeId", id)
                        put("plannedTargetId", item.getValue("plannedTargetId").jsonPrimitive.content.toInt())
                        try {
                            val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                            require(hash(bytes) == item.getValue("sourceSha256").jsonPrimitive.content) { "Donor definition changed" }
                            val deps = OsrsObjectDependencies.parse(bytes)
                            parsed++
                            put("status", "parsed")
                            put("name", deps.name)
                            fun ids(values: Set<Int>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
                            put("modelsRaw", ids(deps.models)); models.addAll(deps.models.map { it + 200_000 })
                            put("animationsRaw", ids(deps.animations)); animations.addAll(deps.animations.map { it + 20_000 })
                            put("morphsRaw", ids(deps.morphs)); morphs.addAll(deps.morphs)
                            textures.addAll(deps.textures)
                            sounds.addAll(deps.sounds)
                            sprites.addAll(deps.sprites)
                            mapIcons.addAll(deps.mapIcons)
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
            var legacyFrames = 0
            var nativeFrames = 0
            var missingFrames = 0
            var undecodableFrames = 0
            val missingFrameIds = sortedSetOf<Int>()
            val frameFailures = buildJsonArray {
                frameIds.forEach { id ->
                    try {
                        val bytes = source.data(Index.ANIMATION_FRAMES, id ushr 16, id and 65535)
                        if (bytes == null) {
                            missingFrames++
                            missingFrameIds.add(id)
                            add(buildJsonObject {
                                put("id", id)
                                put("reason", "missing-source-frame")
                                put("sequenceIds", buildJsonArray {
                                    frameSequences.getValue(id).forEach { add(JsonPrimitive(it)) }
                                })
                            })
                            return@forEach
                        }
                        try {
                            baseIds.add(OsrsFrameDependencies.parseFrame(bytes).baseId)
                            legacyFrames++
                        } catch (legacyError: Exception) {
                            val native = AnimationFrameDefinition(id)
                            AnimationFrameDecoder().readLoop(native, BufferReader(bytes))
                            baseIds.add(native.baseId)
                            nativeFrames++
                        }
                    } catch (error: Exception) {
                        undecodableFrames++
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
                                    put("sha256", hash(bytes))
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
                put("objectPlan", planPath.toString())
                put("objectsRequested", objects.size)
                put("objectsParsed", parsed)
                put("objectReports", reports)
                fun count(name: String, values: Set<Int>) = put(name, values.size)
                count("uniqueRuntimeModelIds", models)
                count("uniqueRuntimeAnimationIds", animations)
                count("uniqueRawMorphIds", morphs)
                count("uniqueTextureIds", textures)
                count("uniqueSoundIds", sounds)
                count("uniqueSpriteIds", sprites)
                count("uniqueMapIconIds", mapIcons)
                put("uniqueFrameIds", frameIds.size)
                put("uniqueBaseIds", baseIds.size)
                put("legacyOsrsFramesDecoded", legacyFrames)
                put("nativeFramesDecoded", nativeFrames)
                put("missingSourceFrames", missingFrames)
                put("undecodableFrames", undecodableFrames)
                put("targetCache", targetPath?.toString() ?: "")
                put("missingSourceFrameTargetReports", targetFrameReports)
                put("sequenceReadFailures", sequenceFailures)
                put("frameReadFailures", frameFailures)
                put("missingSourceBaseIds", buildJsonArray {
                    baseIds.forEach { id ->
                        if (source.data(Index.ANIMATION_SKELETONS, id) == null) add(JsonPrimitive(id))
                    }
                })
                put("missingSourceModelIds", buildJsonArray {
                    models.forEach { id -> if (source.data(Index.MODELS, id) == null) add(JsonPrimitive(id)) }
                })
                put("missingSourceAnimationIds", buildJsonArray {
                    animations.forEach { id ->
                        if (source.data(Index.ANIMATIONS, id ushr 7, id and 127) == null) add(JsonPrimitive(id))
                    }
                })
                put("modelDecodeFailures", buildJsonArray {
                    models.forEach { id ->
                        try {
                            val bytes = requireNotNull(source.data(Index.MODELS, id))
                            if (!(bytes.size == 18 && bytes.all { it == 0.toByte() })) ModelDecoder.read(id, bytes)
                        } catch (error: Exception) {
                            add(buildJsonObject {
                                put("id", id)
                                put("error", "${error.javaClass.simpleName}: ${error.message}")
                            })
                        }
                    }
                })
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
