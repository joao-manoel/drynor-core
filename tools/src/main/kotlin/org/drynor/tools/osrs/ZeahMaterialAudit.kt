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
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder
import world.gregs.voidps.cache.definition.decoder.TextureDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Checks the actual 727 texture graph, material metadata and sprite payloads before reuse. */
object ZeahMaterialAudit {
    fun audit(planPath: Path, objectAuditPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val objectAudit = Json.parseToJsonElement(Files.readString(objectAuditPath)).jsonObject
        require(objectAudit.getValue("objectPlanSha256").jsonPrimitive.content == sha256(planBytes))
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val materials = objectAudit.getValue("modelReports").jsonArray
            .flatMap { it.jsonObject.getValue("materials").jsonArray }
            .map { it.jsonObject.getValue("effectiveId").jsonPrimitive.int }
            .toSortedSet()
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val sourceMaterials = MaterialDecoder().load(source)
                val targetMaterials = MaterialDecoder().load(target)
                val reports = buildJsonArray {
                    for (id in materials) {
                        val blockers = mutableListOf<String>()
                        val sourceTexture = source.data(Index.TEXTURES, id)
                        val targetTexture = target.data(Index.TEXTURES, id)
                        if (sourceTexture == null || targetTexture == null || !sourceTexture.contentEquals(targetTexture)) blockers += "texture-graph-differs"
                        val sourceMetadata = sourceMaterials.getOrNull(id)
                        val targetMetadata = targetMaterials.getOrNull(id)
                        if (sourceMetadata == null || targetMetadata == null || !sourceMetadata.defined || !targetMetadata.defined || sourceMetadata != targetMetadata) blockers += "material-metadata-differs"
                        val spriteIds = sortedSetOf<Int>()
                        val nestedIds = sortedSetOf<Int>()
                        val seen = mutableSetOf<Int>()
                        fun visit(textureId: Int) {
                            if (!seen.add(textureId)) return
                            val from = source.data(Index.TEXTURES, textureId)
                            val to = target.data(Index.TEXTURES, textureId)
                            val fromMetadata = sourceMaterials.getOrNull(textureId)
                            val toMetadata = targetMaterials.getOrNull(textureId)
                            if (from == null || to == null || !from.contentEquals(to) ||
                                fromMetadata == null || toMetadata == null || !fromMetadata.defined || fromMetadata != toMetadata) {
                                blockers += "texture-closure-$textureId-differs"
                                return
                            }
                            try {
                                val graph = TextureDefinition(textureId)
                                TextureDecoder().readLoop(graph, BufferReader(from))
                                for (operation in graph.operations) {
                                    if (operation.type == 36) for (record in operation.opcodes) if (record.opcode == 0) {
                                        val nested = requireNotNull(record.value)
                                        nestedIds += nested
                                        visit(nested)
                                    }
                                    if (operation.type in setOf(18, 39)) for (record in operation.opcodes) if (record.opcode == 0) {
                                        spriteIds += requireNotNull(record.value)
                                    }
                                }
                            } catch (error: Exception) {
                                blockers += "texture-decode-$textureId:${error.message?.take(100) ?: error.javaClass.simpleName}"
                            }
                        }
                        visit(id)
                        for (sprite in spriteIds) {
                            val from = source.data(Index.GRAPHICS, sprite)
                            val to = target.data(Index.GRAPHICS, sprite)
                            if (from == null || to == null || !from.contentEquals(to)) blockers += "sprite-$sprite-differs"
                        }
                        add(buildJsonObject {
                            put("effectiveMaterialId", id)
                            put("sourceTextureSha256", sourceTexture?.let(::sha256) ?: "missing")
                            put("targetTextureSha256", targetTexture?.let(::sha256) ?: "missing")
                            put("metadataEqual", sourceMetadata != null && sourceMetadata == targetMetadata)
                            put("spriteIds", buildJsonArray { spriteIds.forEach { add(JsonPrimitive(it)) } })
                            put("nestedMaterialIds", buildJsonArray { nestedIds.forEach { add(JsonPrimitive(it)) } })
                            put("safeToReuse", blockers.isEmpty())
                            put("blockers", buildJsonArray { blockers.distinct().forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("objectPlanSha256", sha256(planBytes))
                    put("objectAuditSha256", sha256(Files.readAllBytes(objectAuditPath)))
                    put("materialsAudited", materials.size)
                    put("materialsReusable", reports.count { it.jsonObject.getValue("safeToReuse").jsonPrimitive.content == "true" })
                    put("reports", reports)
                    put("writesCache", false)
                    put("clientVisualValidated", false)
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
