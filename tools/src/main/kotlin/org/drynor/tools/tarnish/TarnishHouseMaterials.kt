package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Ordinary house materials, excluding OSRS placeholder-bank behavior and unrelated item variants. */
object TarnishHouseMaterials {
    private val roots = linkedMapOf(21804 to "Ancient crystal", 20609 to "Fairy enchantment", 19669 to "Redwood logs")
    private val json = Json { prettyPrint = true }

    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val mapping = linkedMapOf<Int, Int>()
        val modelMap = linkedMapOf<Int, Int>()
        val sourceHashes = linkedMapOf<Int, String>()
        val modelReports = mutableListOf<JsonObject>()
        val expected = mutableListOf<JsonObject>()
        TarnishCache(source).use { donor ->
            val raw = TarnishMaterials.itemRecords(donor)
            FileCache(target.toString(), writable = false).use { native ->
                val template = read(799, requireNotNull(native.data(Index.ITEMS, 799 ushr 8, 799 and 255)))
                require(template.modelId > 0 && native.data(Index.MODELS, template.modelId) != null) { "Native note template 799 is incomplete" }
                var nextItem = 30308
                fun allocate(): Int {
                    while (nextItem in mapping.values || native.data(Index.ITEMS, nextItem ushr 8, nextItem and 255) != null) nextItem++
                    require(nextItem <= 32767) { "No free house-material item ID in native range" }
                    return nextItem++
                }
                for ((sourceId, name) in roots) {
                    val definition = TarnishStamina.parse(sourceId, raw[sourceId]).definition
                    require(definition.name == name && definition.notedTemplateId == -1) { "Unexpected source material $sourceId/${definition.name}" }
                    mapping[sourceId] = allocate()
                    if (definition.noteId >= 0) {
                        val noteId = definition.noteId
                        val note = TarnishStamina.parse(noteId, raw[noteId]).definition
                        require(note.notedTemplateId == 799 && note.noteId == sourceId) { "Unexpected source bank-note link: $sourceId" }
                        mapping[noteId] = allocate()
                    }
                }
                var nextModel = native.lastArchiveId(Index.MODELS) + 1
                for ((sourceId, targetId) in mapping) {
                    val parsed = TarnishStamina.parse(targetId, raw[sourceId])
                    val item = parsed.definition
                    val noted = item.notedTemplateId != -1
                    sourceHashes[sourceId] = TarnishGarden.sha(raw[sourceId])
                    if (!noted) {
                        val modelId = item.modelId
                        item.modelId = modelMap.getOrPut(modelId) {
                            val destination = nextModel++
                            val original = donor.model(modelId)
                            val converted = TarnishLegacyModels.nativeBytes(original)
                            val decoded = ModelDecoder.read(destination, converted)
                            require((decoded.faceTextures ?: shortArrayOf()).none { it.toInt() >= 0 }) { "Material $sourceId model $modelId needs texture mapping" }
                            check(ModelEncoder.encode(decoded).contentEquals(converted)) { "Model $modelId changed during native round trip" }
                            modelReports += buildJsonObject {
                                put("source", modelId); put("target", destination); put("sourceSha256", TarnishGarden.sha(original))
                                put("convertedSha256", TarnishGarden.sha(converted)); put("vertices", decoded.vertexCount); put("faces", decoded.faceCount)
                                put("sourceBytes", original.size); put("convertedBytes", converted.size)
                            }
                            assets += InfernoAssetWriter.Asset(Index.MODELS, destination, 0, converted)
                            destination
                        }
                    }
                    if (item.noteId >= 0) item.noteId = mapping.getValue(item.noteId)
                    val bytes = encode(item)
                    val decoded = read(targetId, bytes)
                    check(encode(decoded).contentEquals(bytes)) { "Item $sourceId native round trip changed bytes" }
                    check(decoded.name == item.name && decoded.noteId == item.noteId && decoded.notedTemplateId == item.notedTemplateId && decoded.modelId == item.modelId)
                    assets += InfernoAssetWriter.Asset(Index.ITEMS, targetId ushr 8, targetId and 255, bytes)
                    expected += buildJsonObject {
                        put("source", sourceId); put("id", targetId); put("name", item.name); put("noted", noted)
                        put("displayName", if (noted) roots.getValue(rawNoteSource(raw[sourceId])) else item.name)
                        put("modelId", item.modelId); put("noteId", item.noteId); put("notedTemplateId", item.notedTemplateId)
                        put("graphicScale", item.graphicScale); put("graphicPitch", item.graphicPitch); put("graphicCameraRoll", item.graphicCameraRoll)
                        put("graphicCameraYaw", item.graphicCameraYaw); put("graphicTranslateX", item.graphicTranslateX); put("graphicTranslateY", item.graphicTranslateY)
                        put("options", JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                        put("sourcePresentation", buildJsonObject { parsed.presentation.forEach { (key, value) -> put(key, value) } })
                    }
                }
            }
        }
        assets.forEach { asset ->
            val path = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(path.parent); Files.write(path, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", "house-materials-native-readback")
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceItemIds", buildJsonObject { mapping.forEach { (a, b) -> put(a.toString(), b) } })
            put("sourceModelIds", buildJsonObject { modelMap.forEach { (a, b) -> put(a.toString(), b) } })
            put("sourceHashes", buildJsonObject { sourceHashes.forEach { (a, b) -> put(a.toString(), b) } })
            put("modelReports", JsonArray(modelReports))
            put("expectedItems", JsonArray(expected)); put("nativeNoteTemplate", 799)
            put("presentationPolicy", "Source inventory framing, model colours and ordinary note links are converted. Source weight/category/shift-click and OSRS bank-placeholder links remain explicit evidence only; native 727 uses its own fields. No substitutions or placeholder-bank implementation are introduced.")
            put("assets", JsonArray(assets.map { a -> buildJsonObject { put("index", a.index); put("archive", a.archive); put("file", a.file); put("sha256", TarnishGarden.sha(a.bytes)) } }))
        }
        Files.writeString(output.resolve("manifest.json"), json.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared house materials: ${roots.size} ordinary items, ${mapping.size - roots.size} notes and ${modelMap.size} models. No cache changed. $output")
    }

    fun apply(output: Path, backup: Path, priorPackages: List<Path>) =
        TarnishIncrementalImport.apply(output, backup, priorPackages, "house-materials-native-readback")

    private fun rawNoteSource(raw: ByteArray) = TarnishStamina.parse(0, raw).definition.noteId
    private fun encode(item: ItemDefinition) = BufferWriter(65536).apply { with(ItemEncoder()) { encode(item) } }.toArray()
    private fun read(id: Int, bytes: ByteArray) = ItemDefinition(id).also { ItemDecoder().readLoop(it, BufferReader(bytes)) }
}
