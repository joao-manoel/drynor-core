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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Strictly decodes donor object definitions to expose asset closure before conversion. */
object ZeahObjectDependencyAudit {
    fun audit(sourcePath: Path, planPath: Path, outputPath: Path) {
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
