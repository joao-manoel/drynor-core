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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Finds byte-identical target sprites at another ID before considering an import. */
object ZeahIconAliasAudit {
    fun audit(planPath: Path, iconAuditPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val icons = Json.parseToJsonElement(Files.readString(iconAuditPath)).jsonObject
        require(icons.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val blockedFiles = icons.getValue("iconClosures").jsonArray.filter {
            it.jsonObject.getValue("safeByteReuse").jsonPrimitive.content == "false"
        }.flatMap { closure -> closure.jsonObject.getValue("graphics").jsonArray.filter {
            it.jsonObject.getValue("sourcePresent").jsonPrimitive.content == "true" &&
                it.jsonObject.getValue("sameBytes").jsonPrimitive.content == "false"
        }.map { it.jsonObject.getValue("fileId").jsonPrimitive.int } }.toSet()
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val desired = blockedFiles.associateWith { id -> sha256(requireNotNull(source.data(Index.GRAPHICS, id))) }
                val hashes = desired.values.toSet()
                val aliases = hashes.associateWith { sortedSetOf<Int>() }
                for (id in target.archiveIds(Index.GRAPHICS)) {
                    val bytes = target.data(Index.GRAPHICS, id) ?: continue
                    val hash = sha256(bytes)
                    aliases[hash]?.add(id)
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("objectPlanSha256", sha256(planBytes))
                    put("iconAuditSha256", sha256(Files.readAllBytes(iconAuditPath)))
                    put("blockedSpriteFiles", blockedFiles.size)
                    put("blockedFilesWithExistingAlias", desired.count { (_, hash) -> aliases.getValue(hash).isNotEmpty() })
                    put("reports", buildJsonArray { desired.toSortedMap().forEach { (id, hash) -> add(buildJsonObject {
                        put("sourceGraphicsFile", id)
                        put("sourceSha256", hash)
                        put("identicalTargetGraphicsFiles", buildJsonArray { aliases.getValue(hash).forEach { add(JsonPrimitive(it)) } })
                    }) } })
                    put("writesCache", false)
                    put("clientVisualValidated", false)
                    put("limitations", "Byte aliases alone do not prove a native mapscene/MEC config exists or that it renders correctly.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
