package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Audits opcode 78/79 synth dependencies without writing a cache or accepting a sound substitution. */
object ZeahObjectSoundAudit {
    fun audit(planPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val objects = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val users = sortedMapOf<Int, MutableList<Int>>()
                var objectsWithSounds = 0
                for (record in objects) {
                    val objectId = record.getValue("runtimeId").jsonPrimitive.int
                    val data = requireNotNull(source.data(Index.OBJECTS, objectId ushr 8, objectId and 255))
                    check(sha256(data) == record.getValue("sourceSha256").jsonPrimitive.content)
                    val sounds = OsrsObjectDependencies.parse(data).sounds
                    if (sounds.isNotEmpty()) objectsWithSounds++
                    for (soundId in sounds) {
                        users.getOrPut(soundId) { mutableListOf() } += objectId
                    }
                }
                var identical = 0
                var sourceRoundtrip = 0
                val reports = buildJsonArray {
                    for ((soundId, objectIds) in users) {
                        // Index 4 is one sound per archive, file 0; this is also how the client requests it.
                        val from = source.data(Index.SYNTH_SOUNDS, soundId, 0)
                        val to = target.data(Index.SYNTH_SOUNDS, soundId, 0)
                        val same = from != null && to != null && from.contentEquals(to)
                        if (same) identical++
                        var roundtrip = false
                        var decodeError: String? = null
                        if (from != null) {
                            try {
                                val definition = SynthSoundDefinition(soundId)
                                SynthSoundDecoder().read(definition, from)
                                val writer = BufferWriter(from.size + 32)
                                with(SynthSoundEncoder()) { writer.encode(definition) }
                                roundtrip = writer.toArray().contentEquals(from)
                                if (roundtrip) sourceRoundtrip++
                            } catch (error: Exception) {
                                decodeError = error.message?.take(160) ?: error.javaClass.simpleName
                            }
                        }
                        add(buildJsonObject {
                            put("soundId", soundId)
                            put("objectRuntimeIds", buildJsonArray { objectIds.sorted().forEach { add(JsonPrimitive(it)) } })
                            put("sourceSha256", from?.let(::sha256) ?: "missing")
                            put("targetSha256", to?.let(::sha256) ?: "missing")
                            put("identicalAtSameId", same)
                            put("sourceNativeRoundtrip", roundtrip)
                            put("sourceDecodeError", decodeError ?: "")
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("objectPlanSha256", sha256(planBytes))
                    put("objectsWithSounds", objectsWithSounds)
                    put("soundIds", users.size)
                    put("soundsIdenticalAtSameId", identical)
                    put("sourceSoundsNativeRoundtrip", sourceRoundtrip)
                    put("reports", reports)
                    put("writesCache", false)
                    put("clientAudioValidated", false)
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
