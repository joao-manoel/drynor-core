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

/** One isolated item closure for ordinary Zeah cave NPC drops. No drop table is enabled here. */
internal object ZeahCaveDropItemsPreview {
    private val donorIds = sortedSetOf(
        53499, 53510, 53512, 53514, 53522,
        42701, 50849, 51009, 51028, 52780, 52783, 52786, 52804,
        52951, 52954, 52975, 52988,
    )
    private val excludedCustom = setOf(18778, 24154)

    fun build(sourcePath: Path, previewRoot: Path, outputRoot: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val input = previewRoot.toRealPath()
        val baselinePath = input.resolve("packed-cache").toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(sourceRoot) && !output.startsWith(input))
        require(donorIds.intersect(excludedCustom).isEmpty())
        val previous = Json.parseToJsonElement(Files.readString(input.resolve("preview-report.json"))).jsonObject
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val baselineSnapshot = InfernoDependencyPlan.snapshot(baseline)
                val items = sortedMapOf<Int, ItemDefinition>()
                val placeholders = sortedMapOf<Int, Map<Int, Int>>()
                val presentation = sortedMapOf<Int, Map<Int, Int>>()
                val pending = java.util.TreeSet(donorIds)
                while (pending.isNotEmpty()) {
                    val from = pending.pollFirst()
                    if (from in items) continue
                    require(items.size < 96) { "Zeah cave item closure exceeded 96 definitions" }
                    val raw = requireNotNull(source.data(Index.ITEMS, from ushr 8, from and 255)) { "Missing donor item $from" }
                    val omitted = linkedMapOf<Int, Int>()
                    val offsets = linkedMapOf<Int, Int>()
                    val item = try {
                        OsrsItemConverter.decode(from, raw, omitted, offsets)
                    } catch (cause: Exception) {
                        throw IllegalStateException("Cannot convert donor item $from", cause)
                    }
                    if (from in donorIds) require(item.name.isNotBlank() && item.name != "null") { "Unnamed donor item $from" }
                    if (from == 53514) require(item.name.equals("Tattered temple page", true))
                    items[from] = item
                    if (omitted.isNotEmpty()) placeholders[from] = omitted
                    if (offsets.isNotEmpty()) presentation[from] = offsets
                    val links = listOf(item.noteId, item.notedTemplateId, item.lendId, item.lendTemplateId) +
                        (item.stackIds?.toList() ?: emptyList())
                    pending.addAll(links.filter { it >= 30000 && it !in items && it != 30799 })
                }
                val occupiedItems = baseline.archiveIds(Index.ITEMS)
                    .flatMap { archive -> baseline.files(Index.ITEMS, archive).map { (archive shl 8) or it } }.toMutableSet()
                val noteTemplate = 30058
                require(noteTemplate in occupiedItems) { "Native note template missing" }
                val itemMap = sortedMapOf<Int, Int>()
                itemMap[30799] = noteTemplate
                for (from in items.keys) {
                    val to = (30200..32766).first { it !in occupiedItems }
                    occupiedItems += to
                    itemMap[from] = to
                }
                val sourceModels = items.values.flatMap(::models).toSet()
                val modelMap = DeterministicIds.plan(sourceModels, baseline.archiveIds(Index.MODELS).toSet())
                val assets = mutableListOf<InfernoAssetWriter.Asset>()
                for ((from, to) in modelMap.toSortedMap()) {
                    val model = ModelDecoder.read(to, requireNotNull(source.data(Index.MODELS, from)) { "Missing donor model $from" })
                    require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Unresolved texture in model $from" }
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes)
                }
                val expected = buildJsonArray {
                    for ((from, item) in items) {
                        when (from) {
                            50849 -> equip(item, 3, "Wield", mapOf(4 to 36, 643 to 470, 749 to 4, 750 to 61))
                            51009 -> equip(item, 3, "Wield", mapOf(0 to 65, 1 to 55, 2 to -2, 6 to 2,
                                641 to 630, 749 to 0, 750 to 60))
                            51028 -> equip(item, 3, "Wield", mapOf(0 to 38, 1 to 32, 6 to 1,
                                641 to 420, 749 to 0, 750 to 60))
                            52804 -> equip(item, 3, "Wield", mapOf(4 to 28, 643 to 300, 749 to 4, 750 to 60))
                            52951 -> equip(item, 10, "Wear", mapOf(0 to 3, 3 to 3, 4 to 5,
                                5 to 10, 6 to 10, 7 to 10, 8 to 5, 9 to 5,
                                749 to 1, 750 to 70, 751 to 6, 752 to 70, 753 to 4, 754 to 70, 755 to 18, 756 to 44))
                            52954 -> equip(item, 10, "Wear", mapOf(11 to 5, 749 to 5, 750 to 60))
                            52975 -> equip(item, 12, "Wear", mapOf(0 to 4, 1 to 4, 2 to 4, 3 to 6, 4 to 4,
                                5 to 4, 6 to 4, 7 to 4, 8 to 6, 9 to 4, 641 to 40))
                            53522 -> {
                                require(item.name.equals("Mask of ranul", true))
                                equip(item, 0, "Wear", emptyMap())
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
                            read.noteId == item.noteId && read.notedTemplateId == item.notedTemplateId) {
                            "Item readback mismatch $from -> ${item.id}: wear ${item.wearPos}/${read.wearPos}, " +
                                "params ${item.params}/${read.params}, options ${item.options.contentToString()}/${read.options.contentToString()}"
                        }
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
                    previous.forEach { (key, value) -> put(key, value) }
                    put("packedCache", "packed-cache")
                    put("caveDropItemInputCache", baselinePath.toString())
                    put("caveDropItemCount", items.size)
                    put("caveDropItemModelCount", modelMap.size)
                    put("caveDropItemMap", buildJsonObject { itemMap.filterKeys { it != 30799 }.forEach { (from, to) -> put(from.toString(), to) } })
                    put("caveDropItems", expected)
                    put("caveDropItemOmittedPlaceholderLinks", buildJsonObject { placeholders.forEach { (id, links) ->
                        put(id.toString(), buildJsonObject { links.forEach { (opcode, linked) -> put(opcode.toString(), linked) } })
                    } })
                    put("caveDropItemDonorPresentationOffsets", buildJsonObject { presentation.forEach { (id, offsets) ->
                        put(id.toString(), buildJsonObject { offsets.forEach { (opcode, offset) -> put(opcode.toString(), offset) } })
                    } })
                    put("caveDropItemUnchangedArchivesVerified", unchanged)
                    put("caveDropItemNativeReadback", true)
                    put("caveDropItemClientValidated", false)
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

    private fun equip(item: ItemDefinition, slot: Int, option: String, bonuses: Map<Int, Int>) {
        require(item.name.isNotBlank() && item.name != "null")
        item.wearPos = slot
        item.options[0] = option
        for (index in 1 until item.options.size) {
            if (item.options[index]?.equals(option, ignoreCase = true) == true) item.options[index] = null
        }
        if (bonuses.isNotEmpty()) item.params = item.params.orEmpty() + bonuses
    }
}
