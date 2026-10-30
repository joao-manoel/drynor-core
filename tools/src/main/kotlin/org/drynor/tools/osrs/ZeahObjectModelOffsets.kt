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
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Read-only inventory of Gielenor's object-model Y translations in the selected Zeah plan. */
object ZeahObjectModelOffsets {
    private const val REVIEWED_DONOR_CLIENT_SHA256 = "81efb092cb0ae745b2aa2f528a840bce17175f3dfe498a90774bc4f7d9920b95"

    fun audit(planPath: Path, donorClientPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val donorClient = donorClientPath.toRealPath()
        val clientCode = Files.readString(donorClient)
        require(sha256(Files.readAllBytes(donorClient)) == REVIEWED_DONOR_CLIENT_SHA256) {
            "Gielenor object-model rules changed; review the translation table before auditing"
        }
        require("class64.translate(0, i_43_ == 22 ? -4 : 1, 0)" in clientCode)
        require("id >= 113705 && id <= 113714" in clientCode)
        require("id >= 137670 && id <= 137950" in clientCode)
        val source = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val selected = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        val modelOwners = sortedMapOf<Int, MutableSet<Int>>()
        val entries = ArrayList<Triple<Int, Int, Set<Int>>>()
        FileCache(source.toString(), writable = false).use { cache ->
            for (entry in selected) {
                val id = entry.getValue("runtimeId").jsonPrimitive.int
                val target = entry.getValue("plannedTargetId").jsonPrimitive.int
                val bytes = requireNotNull(cache.data(Index.OBJECTS, id ushr 8, id and 255))
                require(sha256(bytes) == entry.getValue("sourceSha256").jsonPrimitive.content) { "Donor object changed: $id" }
                val models = OsrsObjectDependencies.parse(bytes).models.map { it + 200_000 }.toSortedSet()
                models.forEach { modelOwners.getOrPut(it) { sortedSetOf() }.add(id) }
                entries += Triple(id, target, models)
            }
        }
        val affected = entries.filter { (id) -> id >= 100_000 }
        val exceptions = affected.filter { (id) -> extraY(id, 22) != 0 || extraY(id, 10) != 0 }
        val report = buildJsonObject {
            put("status", "read-only-model-offset-audit")
            put("objectPlanSha256", sha256(planBytes))
            put("donorClient", donorClient.toString())
            put("donorClientSha256", sha256(Files.readAllBytes(donorClient)))
            put("selectedObjects", entries.size)
            put("osrsOffsetObjects", affected.size)
            put("exceptionObjects", exceptions.size)
            put("modelOwners", modelOwners.size)
            put("sharedModels", modelOwners.count { it.value.size > 1 })
            put("selectedMappings", buildJsonArray {
                entries.forEach { (id, target) ->
                    add(buildJsonObject { put("sourceRuntimeId", id); put("plannedTargetId", target) })
                }
            })
            put("exceptions", buildJsonArray {
                exceptions.forEach { (id, target, models) ->
                    add(buildJsonObject {
                        put("sourceRuntimeId", id)
                        put("plannedTargetId", target)
                        put("shape22Y", -4 + extraY(id, 22))
                        put("shape10Y", 1 + extraY(id, 10))
                        put("models", buildJsonArray { models.forEach { add(JsonPrimitive(it)) } })
                        put("sharedModelOwners", buildJsonArray {
                            models.filter { modelOwners.getValue(it).size > 1 }.forEach { model ->
                                add(buildJsonObject {
                                    put("model", model)
                                    put("owners", buildJsonArray { modelOwners.getValue(model).forEach { add(JsonPrimitive(it)) } })
                                })
                            }
                        })
                    })
                }
            })
        }
        Files.createDirectories(outputPath.toAbsolutePath().parent)
        Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), report))
    }

    private fun extraY(id: Int, shape: Int): Int = when {
        id == 132104 || id == 131670 -> -1
        id in 132171..132180 -> -4
        id in 113705..113714 || id == 130034 -> -15
        id in 137670..137950 -> -42
        id == 136199 || id == 136200 || id == 130032 -> -12
        id in 131486..131553 && shape == 10 -> -12
        id == 129820 -> -18
        id in 129880..129888 || id == 129823 -> -2
        id in 130078..130090 -> -4
        id == 129734 -> -22
        else -> 0
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
