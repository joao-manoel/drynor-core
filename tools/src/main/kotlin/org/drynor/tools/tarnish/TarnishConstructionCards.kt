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

/** Real donor furniture icons, with native 6312/6313 construction requirement parameters. */
object TarnishConstructionCards {
    private const val STATUS = "construction-cards-native-readback"
    internal data class Card(val source: Int, val name: String, val objectId: Int, val level: Int, val materials: List<Pair<Int, Int>>, val requirements: String)
    internal val cards = listOf(
        Card(20639, "Restoration pool", 120004, 65, listOf(3420 to 5, 1929 to 5, 566 to 1000, 559 to 1000), "5 limestone bricks, 5 buckets of water, 1000 soul runes, 1000 body runes"),
        Card(20640, "Revitalisation pool", 120005, 70, listOf(30300 to 10), "Restoration pool; 10 stamina potions (4)"),
        Card(20641, "Rejuvenation pool", 120006, 80, listOf(2434 to 10), "Revitalisation pool; 10 prayer potions (4)"),
        Card(20642, "Fancy rejuvenation pool", 120007, 85, listOf(3024 to 10, 8786 to 2), "Rejuvenation pool; 10 super restores (4), 2 marble blocks"),
        Card(20643, "Ornate rejuvenation pool", 120008, 90, listOf(30006 to 10, 8784 to 5, 565 to 1000), "Fancy rejuvenation pool; 10 anti-venoms (4), 5 gold leaves, 1000 blood runes"),
        Card(21788, "Obelisk", 120009, 80, listOf(30308 to 4, 8786 to 4), "4 ancient crystals, 4 marble blocks"),
        Card(20636, "Fairy ring", 120010, 85, listOf(6004 to 10, 30310 to 1), "10 bittercap mushrooms, 1 fairy enchantment; watering can with water"),
    )

    internal fun parameters(card: Card): Map<Int, Any> = buildMap {
        put(23, card.level)
        put(2522, card.requirements)
        card.materials.forEachIndexed { index, material -> put(211 + index * 2, material.first); put(212 + index * 2, material.second) }
    }

    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val expected = mutableListOf<JsonObject>()
        val itemIds = linkedMapOf<Int, Int>()
        val modelIds = linkedMapOf<Int, Int>()
        val sourceHashes = linkedMapOf<Int, String>()
        TarnishCache(source).use { donor ->
            val records = TarnishMaterials.itemRecords(donor)
            FileCache(target.toString(), writable = false).use { native ->
                var nextItem = 30313
                var nextModel = native.lastArchiveId(Index.MODELS) + 1
                for (card in cards) {
                    while (native.data(Index.ITEMS, nextItem ushr 8, nextItem and 255) != null) nextItem++
                    require(nextItem <= 32767)
                    val targetId = nextItem++
                    val raw = records[card.source]
                    val item = TarnishStamina.parse(targetId, raw).definition
                    require(item.name == card.name && item.noteId == -1 && item.notedTemplateId == -1)
                    require(item.originalTextureColours == null) { "Card ${card.source} needs item texture remapping" }
                    itemIds[card.source] = targetId
                    sourceHashes[card.source] = TarnishGarden.sha(raw)
                    item.modelId = modelIds.getOrPut(item.modelId) {
                        val modelId = nextModel++
                        val bytes = TarnishLegacyModels.nativeBytes(donor.model(item.modelId))
                        val model = ModelDecoder.read(modelId, bytes)
                        require((model.faceTextures ?: shortArrayOf()).none { it.toInt() >= 0 }) { "Card ${card.source} needs model texture remapping" }
                        check(ModelEncoder.encode(model).contentEquals(bytes))
                        assets += InfernoAssetWriter.Asset(Index.MODELS, modelId, 0, bytes)
                        modelId
                    }
                    card.materials.forEach { (id, _) -> require(native.data(Index.ITEMS, id ushr 8, id and 255) != null) { "Missing recipe material $id" } }
                    item.params = parameters(card)
                    val bytes = encode(item)
                    val decoded = ItemDefinition(targetId).also { ItemDecoder().readLoop(it, BufferReader(bytes)) }
                    check(encode(decoded).contentEquals(bytes) && decoded.params == item.params && decoded.modelId == item.modelId)
                    assets += InfernoAssetWriter.Asset(Index.ITEMS, targetId ushr 8, targetId and 255, bytes)
                    expected += buildJsonObject {
                        put("source", card.source); put("id", targetId); put("name", item.name); put("modelId", item.modelId)
                        put("objectId", card.objectId); put("constructionLevel", card.level); put("requirements", card.requirements)
                        put("noteId", -1); put("notedTemplateId", -1)
                        put("graphicScale", item.graphicScale); put("graphicPitch", item.graphicPitch)
                        put("graphicCameraRoll", item.graphicCameraRoll); put("graphicCameraYaw", item.graphicCameraYaw)
                        put("graphicTranslateX", item.graphicTranslateX); put("graphicTranslateY", item.graphicTranslateY)
                        put("materials", JsonArray(card.materials.map { (id, count) -> buildJsonObject { put("id", id); put("count", count) } }))
                    }
                }
            }
        }
        assets.forEach { asset ->
            val path = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(path.parent); Files.write(path, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", STATUS)
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceItemIds", buildJsonObject { itemIds.forEach { (a, b) -> put(a.toString(), b) } })
            put("sourceModelIds", buildJsonObject { modelIds.forEach { (a, b) -> put(a.toString(), b) } })
            put("sourceHashes", buildJsonObject { sourceHashes.forEach { (a, b) -> put(a.toString(), b) } })
            put("expectedItems", JsonArray(expected))
            put("parameterPolicy", "Native packed scripts 6312/6313: level 23, full English requirements 2522, item/count pairs 211..222. Object IDs are manifest metadata only.")
            put("assets", JsonArray(assets.map { a -> buildJsonObject { put("index", a.index); put("archive", a.archive); put("file", a.file); put("sha256", TarnishGarden.sha(a.bytes)) } }))
        }
        Files.writeString(output.resolve("manifest.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared ${cards.size} real furniture cards with ${modelIds.size} models; no cache changed. $output")
    }

    fun apply(output: Path, backup: Path, priorPackages: List<Path>) = TarnishIncrementalImport.apply(output, backup, priorPackages, STATUS)
    private fun encode(item: ItemDefinition) = BufferWriter(65536).apply { with(ItemEncoder()) { encode(item) } }.toArray()
}
