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

    fun run(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) {
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
                val animation = ZulrahAnimationAssets.plan(donor, target, spots.values.map { it.animationId }.toSet())
                val result = animation.assets.toMutableList()
                for ((from, to) in models) {
                    val mesh = ModelDecoder.read(to, requireNotNull(donor.data(Index.MODELS, from)))
                    check(mesh.faceTextures?.all { it == (-1).toShort() } != false) { "Unaudited material on $from" }
                    check(mesh.billboards.isNullOrEmpty() && mesh.particleEmitters.isNullOrEmpty() && mesh.particleEffectors.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(mesh)
                    check(ModelEncoder.encode(ModelDecoder.read(to, bytes)).contentEquals(bytes))
                    result.add(InfernoAssetWriter.Asset(Index.MODELS, to, 0, bytes))
                }
                for ((id, spot) in spots) {
                    val converted = OsrsSpotConverter.convert(id, spot, models, animation.sequenceMap)
                    val bytes = encode { with(SpotAnimEncoder()) { it.encode(converted) } }
                    val read = SpotAnimDefinition(id)
                    SpotAnimDecoder().readLoop(read, BufferReader(bytes))
                    check(read.modelId == converted.modelId && read.animationId == converted.animationId)
                    val current = requireNotNull(target.data(Index.SPOTANIMS, id ushr 8, id and 255))
                    check(current.contentEquals(donor.data(Index.SPOTANIMS, id ushr 8, id and 255))) { "Unexpected projectile already at $id" }
                    result.add(InfernoAssetWriter.Asset(Index.SPOTANIMS, id ushr 8, id and 255, bytes, replaceExisting = true))
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

    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
}
