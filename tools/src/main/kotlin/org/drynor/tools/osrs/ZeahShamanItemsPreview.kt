package org.drynor.tools.osrs

import kotlinx.serialization.json.*
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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Isolated item dependency closure for Shaman rewards; does not enable drops. */
object ZeahShamanItemsPreview {
    private val rewardIds = setOf(43383, 43393, 43576)
    private val craftingIds = setOf(43385, 43387, 43389)

    fun build(sourcePath: Path, inputRoot: Path, equipmentReportPath: Path, outputRoot: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val input = inputRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(sourceRoot) && !output.startsWith(input))
        val baseReport = Json.parseToJsonElement(Files.readString(input.resolve("preview-report.json"))).jsonObject
        require(baseReport.getValue("shamanCombatAssetCount").jsonPrimitive.int == 159)
        require(Path.of(baseReport.getValue("sourceCache").jsonPrimitive.content).toRealPath() == sourceRoot)
        val equipmentBytes = Files.readAllBytes(equipmentReportPath)
        val equipment = Json.parseToJsonElement(equipmentBytes.decodeToString()).jsonObject
        require(equipment.getValue("status").jsonPrimitive.content == "experimental-osrs-raid-equipment")
        val reusedTemplate = equipment.getValue("ids").jsonObject.getValue("items").jsonObject.getValue("30799").jsonPrimitive.int
        val baselinePath = input.resolve("packed-cache").toRealPath()
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val baselineSnapshot = InfernoDependencyPlan.snapshot(baseline)
                val items = sortedMapOf<Int, ItemDefinition>()
                val omittedPlaceholders = sortedMapOf<Int, Map<Int, Int>>()
                val presentationOffsets = sortedMapOf<Int, Map<Int, Int>>()
                val pending = java.util.TreeSet(rewardIds + craftingIds)
                while (pending.isNotEmpty()) {
                    val id = pending.pollFirst()
                    if (id in items || id == 30799) continue
                    require(items.size < 64) { "Shaman item closure exceeded 64 definitions" }
                    val bytes = requireNotNull(source.data(Index.ITEMS, id ushr 8, id and 255)) { "Missing source item $id" }
                    val placeholders = linkedMapOf<Int, Int>()
                    val offsets = linkedMapOf<Int, Int>()
                    val item = OsrsItemConverter.decode(id, bytes, placeholders, offsets)
                    items[id] = item
                    if (placeholders.isNotEmpty()) omittedPlaceholders[id] = placeholders
                    if (offsets.isNotEmpty()) presentationOffsets[id] = offsets
                    val links = listOf(item.noteId, item.notedTemplateId, item.lendId, item.lendTemplateId) +
                        (item.stackIds?.toList() ?: emptyList())
                    pending.addAll(links.filter { it >= 30000 && it !in items })
                }
                val occupiedItems = baseline.archiveIds(Index.ITEMS)
                    .flatMap { archive -> baseline.files(Index.ITEMS, archive).map { (archive shl 8) or it } }.toMutableSet()
                require(reusedTemplate in occupiedItems) { "Production note template missing from Shaman baseline" }
                val stableItems = mapOf(43383 to 30100, 43384 to 30101, 43393 to 30102,
                    43576 to 30103, 43577 to 30104)
                require(stableItems.keys.all { it in items } && stableItems.values.none { it in occupiedItems })
                occupiedItems.addAll(stableItems.values)
                val added = items.keys.filterNot(stableItems::containsKey).associateWith { _ ->
                    (30105..32766).first { it !in occupiedItems }.also(occupiedItems::add)
                }
                val itemMap = mapOf(30799 to reusedTemplate) + stableItems + added
                val modelIds = items.values.flatMap(::models).toSet()
                val modelMap = DeterministicIds.plan(modelIds, baseline.archiveIds(Index.MODELS).toSet())
                val assets = mutableListOf<InfernoAssetWriter.Asset>()
                for ((from, to) in modelMap.toSortedMap()) {
                    val model = ModelDecoder.read(to, requireNotNull(source.data(Index.MODELS, from)) { "Missing model $from" })
                    require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Unresolved texture in model $from" }
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes)
                }
                val expected = buildJsonArray {
                    for ((from, item) in items) {
                        when (from) {
                            43383 -> item.options[0] = "Craft"
                            43385, 43387, 43389 -> {
                                item.wearPos = when (from) { 43385 -> 0; 43387 -> 4; else -> 7 }
                                item.options[0] = "Wear"
                                val magicAttack = when (from) { 43385 -> 3; 43387 -> 12; else -> 8 }
                                val magicDefence = when (from) { 43385 -> 3; 43387 -> 10; else -> 7 }
                                item.params = item.params.orEmpty() + mapOf(3 to magicAttack, 8 to magicDefence,
                                    749 to 6, 750 to 20, 751 to 1, 752 to 10)
                            }
                            43393 -> {
                                item.wearPos = 2
                                item.options = arrayOf("Wear", "Rub", "Check", "Uncharge", "Drop")
                                item.exchangeable = false
                                item.params = item.params.orEmpty() + mapOf(3 to 3, 9 to 1,
                                    528 to "Xeric's Lookout", 529 to "Xeric's Glade", 530 to "Xeric's Inferno",
                                    531 to "Xeric's Heart", 1211 to "Xeric's Honour", 533 to "Check")
                            }
                            43576 -> {
                                item.wearPos = 3
                                item.options[0] = "Wield"
                                item.params = item.params.orEmpty() + mapOf(0 to -4, 1 to -4, 2 to 95, 3 to -4,
                                    14 to 4, 641 to 850, 749 to 2, 750 to 60, 686 to 10, 687 to 1)
                            }
                        }
                        item.id = itemMap.getValue(from)
                        item.modelId = model(item.modelId, modelMap)
                        item.primaryMaleModel = model(item.primaryMaleModel, modelMap)
                        item.secondaryMaleModel = model(item.secondaryMaleModel, modelMap)
                        item.tertiaryMaleModel = model(item.tertiaryMaleModel, modelMap)
                        item.primaryFemaleModel = model(item.primaryFemaleModel, modelMap)
                        item.secondaryFemaleModel = model(item.secondaryFemaleModel, modelMap)
                        item.tertiaryFemaleModel = model(item.tertiaryFemaleModel, modelMap)
                        item.primaryMaleDialogueHead = model(item.primaryMaleDialogueHead, modelMap)
                        item.secondaryMaleDialogueHead = model(item.secondaryMaleDialogueHead, modelMap)
                        item.primaryFemaleDialogueHead = model(item.primaryFemaleDialogueHead, modelMap)
                        item.secondaryFemaleDialogueHead = model(item.secondaryFemaleDialogueHead, modelMap)
                        item.noteId = link(item.noteId, itemMap)
                        item.notedTemplateId = link(item.notedTemplateId, itemMap)
                        item.lendId = link(item.lendId, itemMap)
                        item.lendTemplateId = link(item.lendTemplateId, itemMap)
                        item.stackIds = item.stackIds?.map { if (it == 0) 0 else itemMap.getValue(it) }?.toIntArray()
                        val writer = BufferWriter(1 shl 20)
                        with(ItemEncoder()) { writer.encode(item) }
                        val bytes = writer.toArray()
                        val read = ItemDefinition(item.id)
                        ItemDecoder().readLoop(read, BufferReader(bytes))
                        check(read.name == item.name && read.modelId == item.modelId && read.wearPos == item.wearPos &&
                            read.options.contentEquals(item.options) && read.params == item.params &&
                            read.noteId == item.noteId && read.notedTemplateId == item.notedTemplateId)
                        assets += InfernoAssetWriter.Asset(Index.ITEMS, item.id ushr 8, item.id and 255, bytes)
                        add(buildJsonObject { put("source", from); put("target", item.id); put("name", item.name) })
                    }
                }
                ZulrahActorPreview.validateFreshAssets(baseline, assets)
                Files.createDirectories(output)
                ZulrahActorPreview.stage(output.resolve("editable-assets"), assets, baseline)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(baselinePath).use { paths -> paths.filter { it.fileName.toString().startsWith("main_file_cache.") }
                    .forEach { Files.copy(it, packed.resolve(it.fileName)) } }
                InfernoAssetWriter.write(packed, assets)
                var unchanged = 0
                FileCache(packed.toString(), writable = false).use { result ->
                    for (asset in assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index to archive !in touched) {
                            check(baseline.container(index, archive).contentEquals(result.container(index, archive)))
                            unchanged++
                        } else for (file in baseline.files(index, archive))
                            check(baseline.data(index, archive, file).contentEquals(result.data(index, archive, file)))
                    }
                }
                check(sourceSnapshot == InfernoDependencyPlan.snapshot(source) && baselineSnapshot == InfernoDependencyPlan.snapshot(baseline))
                val report = buildJsonObject {
                    put("status", "experimental-zeah-shaman-items")
                    put("sourceCache", sourceRoot.toString())
                    put("inputPreview", input.toString())
                    put("inputReportSha256", hash(Files.readAllBytes(input.resolve("preview-report.json"))))
                    put("equipmentReportSha256", hash(equipmentBytes))
                    put("reusedProductionNoteTemplate", reusedTemplate)
                    put("packedCache", "packed-cache")
                    put("rewards", JsonArray(expected.filter { it.jsonObject.getValue("source").jsonPrimitive.int in rewardIds }))
                    put("craftingProducts", JsonArray(expected.filter { it.jsonObject.getValue("source").jsonPrimitive.int in craftingIds }))
                    put("items", expected)
                    put("itemCount", items.size)
                    put("modelCount", modelMap.size)
                    put("unchangedArchivesVerified", unchanged)
                    put("omittedDonorPlaceholderLinks", buildJsonObject { omittedPlaceholders.forEach { (id, links) ->
                        put(id.toString(), buildJsonObject { links.forEach { (opcode, linked) -> put(opcode.toString(), linked) } })
                    } })
                    put("donorPresentationOffsets", buildJsonObject { presentationOffsets.forEach { (id, offsets) ->
                        put(id.toString(), buildJsonObject { offsets.forEach { (opcode, offset) -> put(opcode.toString(), offset) } })
                    } })
                    put("dropsEnabled", false)
                    put("clientValidated", false)
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    private fun models(item: ItemDefinition) = listOf(item.modelId, item.primaryMaleModel, item.secondaryMaleModel,
        item.tertiaryMaleModel, item.primaryFemaleModel, item.secondaryFemaleModel, item.tertiaryFemaleModel,
        item.primaryMaleDialogueHead, item.secondaryMaleDialogueHead, item.primaryFemaleDialogueHead,
        item.secondaryFemaleDialogueHead).filter { it > 0 }
    private fun model(id: Int, map: Map<Int, Int>) = if (id <= 0) id else map.getValue(id)
    private fun link(id: Int, map: Map<Int, Int>) = if (id < 0) id else map.getValue(id)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
