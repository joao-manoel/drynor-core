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

/** Follows note, stack, lend and placeholder links of OSRS items named in selected NPC drops. */
object ZeahItemDependencyAudit {
    fun audit(sourcePath: Path, dropReportPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val drops = Json.parseToJsonElement(Files.readString(dropReportPath)).jsonObject
        val direct = drops.getValue("itemDefinitionReports").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content.toInt()
        }.filter { it >= 30000 }
        val queue = java.util.TreeSet(direct)
        val visited = sortedSetOf<Int>()
        val models = sortedSetOf<Int>()
        FileCache(sourcePath.toString(), writable = false).use { source ->
            val reports = buildJsonArray {
                while (queue.isNotEmpty()) {
                    val id = queue.pollFirst()
                    if (!visited.add(id)) continue
                    add(buildJsonObject {
                        put("id", id)
                        try {
                            val bytes = requireNotNull(source.data(Index.ITEMS, id ushr 8, id and 255)) { "Missing OSRS item" }
                            val placeholders = linkedMapOf<Int, Int>()
                            val presentation = linkedMapOf<Int, Int>()
                            val item = OsrsItemConverter.decode(id, bytes, placeholders, presentation)
                            val links = sortedSetOf<Int>()
                            for (linked in listOf(item.noteId, item.notedTemplateId, item.lendId, item.lendTemplateId)) {
                                if (linked >= 30000) links.add(linked)
                            }
                            item.stackIds?.filterTo(links) { it >= 30000 }
                            links.addAll(placeholders.values)
                            queue.addAll(links.filter { it !in visited })
                            val itemModels = listOf(item.modelId, item.primaryMaleModel, item.secondaryMaleModel,
                                item.tertiaryMaleModel, item.primaryFemaleModel, item.secondaryFemaleModel,
                                item.tertiaryFemaleModel, item.primaryMaleDialogueHead, item.secondaryMaleDialogueHead,
                                item.primaryFemaleDialogueHead, item.secondaryFemaleDialogueHead).filter { it > 0 }
                            models.addAll(itemModels)
                            put("status", "parsed")
                            put("name", item.name)
                            put("modelIds", buildJsonArray { itemModels.distinct().sorted().forEach { add(JsonPrimitive(it)) } })
                            put("linkedItemIds", buildJsonArray { links.forEach { add(JsonPrimitive(it)) } })
                            put("placeholderOpcodes", buildJsonArray { placeholders.keys.sorted().forEach { add(JsonPrimitive(it)) } })
                            put("presentationOffsets", buildJsonObject { presentation.forEach { (opcode, offset) -> put(opcode.toString(), offset) } })
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
                put("dropReport", dropReportPath.toString())
                put("directOsrsItemCount", direct.size)
                put("itemClosureCount", visited.size)
                put("itemReports", reports)
                put("uniqueModelIds", models.size)
                put("missingSourceModels", buildJsonArray {
                    models.forEach { if (source.data(Index.MODELS, it) == null) add(JsonPrimitive(it)) }
                })
                put("modelDecodeFailures", buildJsonArray {
                    models.forEach { id ->
                        try {
                            val bytes = requireNotNull(source.data(Index.MODELS, id))
                            if (!(bytes.size == 18 && bytes.all { it == 0.toByte() })) ModelDecoder.read(id, bytes)
                        } catch (error: Exception) {
                            add(buildJsonObject { put("id", id); put("error", "${error.javaClass.simpleName}: ${error.message}") })
                        }
                    }
                })
            }
            Files.createDirectories(outputPath.toAbsolutePath().parent)
            Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n", CREATE_NEW, WRITE)
        }
    }
}
