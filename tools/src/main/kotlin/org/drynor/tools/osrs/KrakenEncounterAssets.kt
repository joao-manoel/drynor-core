package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Converts the OSRS payloads behind Gielenor's Kraken effects and rewards. */
internal object KrakenEncounterAssets {
    private val items = mapOf(42004 to 30115, 42006 to 30116, 42655 to 30117)
    private val effects = setOf(5156, 5162)

    fun run(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) =
        convert(sourcePath, targetPath, output, install, items, effects, replaceEffects = true)

    fun cerberus(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) =
        convert(sourcePath, targetPath, output, install,
            mapOf(43227 to 30118, 43229 to 30119, 43231 to 30120, 43233 to 30121, 43247 to 30122,
                43235 to 30123, 43237 to 30124, 43239 to 30125, 43241 to 30126, 43243 to 30127, 51031 to 30128),
            (6242..6248).toSet() + setOf(5100, 5101), replaceEffects = false)

    fun bossPets(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) =
        convert(sourcePath, targetPath, output, install, mapOf(
            42646 to 30350, 42654 to 30351, 42647 to 30352, 42644 to 30353,
            42645 to 30354, 42643 to 30355, 42816 to 30356, 52318 to 30357,
            42650 to 30358, 42652 to 30359, 42649 to 30360, 42651 to 30361,
            42653 to 30362, 41995 to 30363,
        ), emptySet(), replaceEffects = false)

    fun infernalTools(targetPath: Path, output: Path, install: Boolean) {
        require(!Files.exists(output))
        val assets = FileCache(targetPath.toString(), writable = false).use { target ->
            fun read(id: Int): ItemDefinition = ItemDefinition(id).also {
                ItemDecoder().readLoop(it, BufferReader(requireNotNull(target.data(Index.ITEMS, id ushr 8, id and 255))))
            }
            val assets = mutableListOf<InfernoAssetWriter.Asset>()
            fun addItem(item: ItemDefinition, replace: Boolean) {
                val id = item.id
                check((target.data(Index.ITEMS, id ushr 8, id and 255) != null) == replace)
                val bytes = encode { with(ItemEncoder()) { it.encode(item) } }
                val decoded = ItemDefinition(id)
                ItemDecoder().readLoop(decoded, BufferReader(bytes))
                check(decoded.name == item.name && decoded.wearPos == 3 &&
                    decoded.primaryMaleModel == item.primaryMaleModel && decoded.primaryFemaleModel == item.primaryFemaleModel &&
                    decoded.options.contentEquals(item.options) && decoded.params == item.params)
                assets.add(InfernoAssetWriter.Asset(Index.ITEMS, id ushr 8, id and 255, bytes, replaceExisting = replace))
            }

            val templates = listOf(read(6739), read(15259), read(30205))
            val names = listOf("Infernal axe", "Infernal pickaxe", "Infernal harpoon")
            for ((offset, template) in templates.withIndex()) {
                val source = read(30126 + offset)
                check(source.name == names[offset] && source.primaryMaleModel > 0 && source.primaryFemaleModel > 0)
                for (depleted in listOf(false, true)) {
                    val item = ItemDefinition(source.id)
                    // Start with the installed art and rebuild both native option arrays.
                    ItemDecoder().readLoop(item, BufferReader(requireNotNull(target.data(Index.ITEMS, source.id ushr 8, source.id and 255))))
                    item.id = if (depleted) source.id + 3 else source.id
                    if (depleted) item.name += " (uncharged)"
                    item.wearPos = 3
                    item.exchangeable = false
                    item.options = arrayOf(null, "Wield", "Check", "Toggle", "Drop")
                    item.params = template.params.orEmpty().filterKeys { it !in setOf(770, 771) } +
                        mapOf(528 to "Check", 529 to "Toggle")
                    addItem(item, replace = !depleted)
                }
            }
            assets
        }
        Files.createDirectory(output)
        for (asset in assets) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
        if (install) {
            InfernoAssetWriter.write(targetPath, assets)
            FileCache(targetPath.toString(), writable = false).use { cache ->
                for (asset in assets) check(asset.bytes.contentEquals(cache.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${assets.size} infernal tool assets (installed=$install)")
    }

    private fun convert(
        sourcePath: Path, targetPath: Path, output: Path, install: Boolean,
        items: Map<Int, Int>, effects: Set<Int>, replaceEffects: Boolean,
    ) {
        require(!Files.exists(output))
        val assets = FileCache(sourcePath.toString(), writable = false).use { donor ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val spots = effects.associateWith { OsrsSpotConverter.parse(it, requireNotNull(donor.data(Index.SPOTANIMS, it ushr 8, it and 255))) }
                val definitions = items.map { (from, to) ->
                    check(target.data(Index.ITEMS, to ushr 8, to and 255) == null) { "Occupied Kraken item $to" }
                    OsrsItemConverter.decode(from, requireNotNull(donor.data(Index.ITEMS, from ushr 8, from and 255)),
                        mutableMapOf(), mutableMapOf()).also { it.id = to }
                }
                val references = definitions.flatMap { listOf(it.modelId, it.primaryMaleModel, it.secondaryMaleModel,
                    it.tertiaryMaleModel, it.primaryFemaleModel, it.secondaryFemaleModel, it.tertiaryFemaleModel) }
                val models = DeterministicIds.plan((references + spots.values.map { it.modelId }).filter { it > 0 }.toSet(),
                    target.archiveIds(Index.MODELS).toSet())
                val animation = if (spots.isEmpty()) ZulrahAnimationAssets.Result(emptyMap(), emptyList(), kotlinx.serialization.json.JsonObject(emptyMap()))
                    else ZulrahAnimationAssets.plan(donor, target, spots.values.map { it.animationId }.toSet())
                val result = animation.assets.toMutableList()
                for ((from, to) in models) {
                    val mesh = ModelDecoder.read(to, requireNotNull(donor.data(Index.MODELS, from)))
                    check(mesh.faceTextures?.all { it == (-1).toShort() } != false) { "Unaudited material on $from" }
                    check(mesh.billboards.isNullOrEmpty() && mesh.particleEmitters.isNullOrEmpty() && mesh.particleEffectors.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(mesh)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    result.add(InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes))
                }
                val effectIds = if (replaceEffects) effects.associateWith { it } else DeterministicIds.plan(effects,
                    target.archiveIds(Index.SPOTANIMS).flatMap { group ->
                        target.files(Index.SPOTANIMS, group).map { (group shl 8) or it }
                    }.toSet())
                for ((origin, spot) in spots) {
                    val id = effectIds.getValue(origin)
                    val converted = OsrsSpotConverter.convert(id, spot, models, animation.sequenceMap)
                    val bytes = encode { with(SpotAnimEncoder()) { it.encode(converted) } }
                    val read = SpotAnimDefinition(id)
                    SpotAnimDecoder().readLoop(read, BufferReader(bytes))
                    check(read.modelId == converted.modelId && read.animationId == converted.animationId)
                    val current = target.data(Index.SPOTANIMS, id ushr 8, id and 255)
                    check(if (replaceEffects) current != null && current.contentEquals(donor.data(Index.SPOTANIMS, id ushr 8, id and 255))
                        else current == null) { "Unexpected projectile state at $id" }
                    result.add(InfernoAssetWriter.Asset(Index.SPOTANIMS, id ushr 8, id and 255, bytes, replaceExisting = replaceEffects))
                }
                for (item in definitions) {
                    fun remap(id: Int) = if (id <= 0) id else models.getValue(id)
                    item.modelId = remap(item.modelId)
                    item.primaryMaleModel = remap(item.primaryMaleModel)
                    item.secondaryMaleModel = remap(item.secondaryMaleModel)
                    item.tertiaryMaleModel = remap(item.tertiaryMaleModel)
                    item.primaryFemaleModel = remap(item.primaryFemaleModel)
                    item.secondaryFemaleModel = remap(item.secondaryFemaleModel)
                    item.tertiaryFemaleModel = remap(item.tertiaryFemaleModel)
                    item.noteId = -1
                    item.notedTemplateId = -1
                    check(item.stackIds == null && item.lendId < 0 && item.primaryMaleDialogueHead < 0 && item.primaryFemaleDialogueHead < 0)
                    if (item.id in 30350..30363) {
                        item.options = arrayOf(null, null, null, null, "Drop")
                        item.exchangeable = false
                    }
                    if (item.id in 30123..30125) configureCerberusBoots(item)
                    if (item.id == 30116) {
                        val whip = ItemDefinition(4151)
                        ItemDecoder().readLoop(whip, BufferReader(requireNotNull(target.data(Index.ITEMS, 4151 ushr 8, 4151 and 255))))
                        item.wearPos = 3
                        item.params = item.params.orEmpty() + mapOf(
                            1 to 90, 641 to 860, 686 to 11, 749 to 0, 750 to 75,
                            644 to (whip.params?.get(644) ?: 234),
                        )
                        item.options = arrayOf(null, "Wield", "Check", "Dissolve", "Drop")
                    }
                    val bytes = encode { with(ItemEncoder()) { it.encode(item) } }
                    val read = ItemDefinition(item.id)
                    ItemDecoder().readLoop(read, BufferReader(bytes))
                    check(read.name == item.name && read.modelId == item.modelId && read.options.contentEquals(item.options))
                    result.add(InfernoAssetWriter.Asset(Index.ITEMS, item.id ushr 8, item.id and 255, bytes))
                }
                Files.createDirectory(output)
                for (asset in result) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
                if (!replaceEffects) Files.writeString(output.resolve("effect-ids.txt"),
                    effectIds.entries.joinToString("\n") { "${it.key}=${it.value}" })
                result
            }
        }
        if (install) {
            InfernoAssetWriter.write(targetPath, assets)
            FileCache(targetPath.toString(), writable = false).use { cache ->
                for (asset in assets) check(asset.bytes.contentEquals(cache.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${assets.size} Kraken encounter assets (installed=$install)")
    }

    private fun configureCerberusBoots(item: ItemDefinition) {
        val (skill, bonuses) = when (item.id) {
            30123 -> 6 to mapOf(3 to 8, 5 to 5, 6 to 5, 7 to 5, 8 to 8, 9 to 5, 685 to 1)
            30124 -> 4 to mapOf(3 to -12, 4 to 12, 5 to 5, 6 to 5, 7 to 5, 8 to 5, 9 to 5, 643 to 10)
            30125 -> 0 to mapOf(0 to 2, 1 to 2, 2 to 2, 3 to -4, 4 to -1, 5 to 22, 6 to 22, 7 to 22, 641 to 50)
            else -> error("Unexpected Cerberus boots: ${item.id}")
        }
        item.wearPos = 10
        item.options = arrayOf(null, "Wear", null, null, "Drop")
        item.params = item.params.orEmpty() + bonuses + mapOf(749 to 1, 750 to 75, 751 to skill, 752 to 75)
    }

    fun cerberusBootStats(targetPath: Path, output: Path, install: Boolean) {
        require(!Files.exists(output))
        val assets = FileCache(targetPath.toString(), writable = false).use { cache ->
            (30123..30125).map { id ->
                val group = id ushr 8
                val file = id and 255
                val item = ItemDefinition(id)
                ItemDecoder().readLoop(item, BufferReader(requireNotNull(cache.data(Index.ITEMS, group, file))))
                check(item.name == listOf("Eternal boots", "Pegasian boots", "Primordial boots")[id - 30123])
                configureCerberusBoots(item)
                val bytes = encode { with(ItemEncoder()) { it.encode(item) } }
                val read = ItemDefinition(id)
                ItemDecoder().readLoop(read, BufferReader(bytes))
                check(read.params == item.params && read.getBonuses().contentEquals(item.getBonuses()) &&
                    read.wearPos == item.wearPos && read.modelId == item.modelId &&
                    read.options.contentEquals(item.options))
                InfernoAssetWriter.Asset(Index.ITEMS, group, file, bytes, replaceExisting = true)
            }
        }
        Files.createDirectory(output)
        for (asset in assets) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
        if (install) {
            InfernoAssetWriter.write(targetPath, assets)
            FileCache(targetPath.toString(), writable = false).use { cache ->
                for (asset in assets) check(asset.bytes.contentEquals(cache.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified three Cerberus boot definitions (installed=$install)")
    }

    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
}
