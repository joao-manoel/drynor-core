package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.GraphicEncoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded transfer into the canonical cache. Call only after stopping readers and creating a rollback backup. */
object DonatorAssets {
    val sourceFrames = intArrayOf(10, 11, 12, 13, 14, 21)
    private val directSources = intArrayOf(25425, 25426, 25427, 25428, 25429, 25493)
    private val upgradeSources = intArrayOf(25437, 25438, 25439, 25440, 25494)
    private val names = listOf("Donator", "Super Donator", "Extreme Donator", "Legendary Donator", "VIP Donator", "Supreme Donator")
    private val colours = shortArrayOf(20416, 22451, 11224, 22181, 22449, 22305, 21435, 9164, 11092, 9152, 7087, -32715, -32690, 7997, 8117, -32707, -32698, 22464)

    fun run(args: List<String>) {
        require(args.size == 4 && args[0] in listOf("audit", "apply")) {
            "cache donator-assets <audit|apply> <gielenor-packed> <drynor-packed> <report.json>"
        }
        val apply = args[0] == "apply"
        val donor = Path.of(args[1]).toRealPath()
        val target = Path.of(args[2]).toRealPath()
        require(donor != target)
        val output = Path.of(args[3]).toAbsolutePath()
        require(!Files.exists(output)) { "Do not overwrite an existing import report" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        var graphicId = -1
        var expectedFrames = emptyList<IndexedGraphic>()
        var modelTarget = -1
        var modelSource = -1
        var baselineIconHash = ""
        var donorIconHash = ""
        var donorModelHash = ""
        FileCache(donor.toString()).use { source -> FileCache(target.toString()).use { baseline ->
            graphicId = baseline.archiveId(Index.GRAPHICS, "name_icons")
            val donorGraphic = source.archiveId(Index.GRAPHICS, "name_icons")
            require(graphicId >= 0 && donorGraphic >= 0)
            val before = requireNotNull(baseline.data(Index.GRAPHICS, graphicId, 0))
            val donorIcons = requireNotNull(source.data(Index.GRAPHICS, donorGraphic, 0))
            baselineIconHash = hash(before)
            donorIconHash = hash(donorIcons)
            val original = graphic(graphicId, before)
            val imported = graphic(donorGraphic, donorIcons)
            require(original.graphics!!.size == 8) { "Expected original eight Drynor name icons; already imported or divergent cache" }
            val combined = mergeIcons(original, imported)
            expectedFrames = original.graphics!!.toList() + sourceFrames.map { imported.graphics!![it] }
            val iconWriter = BufferWriter(1 shl 20)
            with(GraphicEncoder()) { iconWriter.encode(combined) }
            val iconBytes = iconWriter.toArray()
            verifyFrames(expectedFrames, graphic(graphicId, iconBytes))
            assets += InfernoAssetWriter.Asset(Index.GRAPHICS, graphicId, 0, iconBytes, true)

            val base = OsrsItemConverter.decode(43190, requireNotNull(source.data(Index.ITEMS, 43190 ushr 8, 43190 and 255)), linkedMapOf(), linkedMapOf())
            require(base.name == "Old school bond")
            require(listOf(base.primaryMaleModel, base.secondaryMaleModel, base.tertiaryMaleModel,
                base.primaryFemaleModel, base.secondaryFemaleModel, base.tertiaryFemaleModel).all { it == -1 })
            modelSource = base.modelId
            val rawModel = requireNotNull(source.data(Index.MODELS, modelSource, 0))
            donorModelHash = hash(rawModel)
            val model = ModelDecoder.read(modelSource, rawModel)
            require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Bond model has unresolved textures" }
            require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
            val modelBytes = ModelEncoder.encode(model)
            check(ModelEncoder.encode(ModelDecoder.read(modelSource, modelBytes)).contentEquals(modelBytes))
            val existing = baseline.data(Index.MODELS, modelSource, 0)
            modelTarget = if (existing.contentEquals(modelBytes)) modelSource
                else DeterministicIds.plan(setOf(modelSource), baseline.archiveIds(Index.MODELS).toSet()).getValue(modelSource)
            if (!existing.contentEquals(modelBytes)) assets += InfernoAssetWriter.Asset(Index.MODELS, modelTarget, 0, modelBytes)
            for (position in 0 until 11) {
                val id = 30250 + position
                require(baseline.data(Index.ITEMS, id ushr 8, id and 255) == null) { "Occupied target item $id" }
                val tier = if (position < 6) position else position - 5
                val item = base.copy(id = id, name = (if (position < 6) "" else "Upgrade ") + names[tier] + " bond",
                    modelId = modelTarget, options = arrayOf("Redeem", null, null, null, "Drop"),
                    cost = 0, exchangeable = position < 6, members = false, stackable = 0,
                    originalColours = colours.copyOf(), modifiedColours = recolours(tier),
                    noteId = -1, notedTemplateId = -1, lendId = -1, lendTemplateId = -1,
                    stackIds = null, stackAmounts = null, params = null, wearPos = -1, wearPos2 = -1, wearPos3 = -1)
                item.opcodeOrder = null
                val writer = BufferWriter(1 shl 20)
                with(ItemEncoder()) { writer.encode(item) }
                val bytes = writer.toArray()
                val read = ItemDefinition(id)
                ItemDecoder().readLoop(read, BufferReader(bytes))
                check(read.name == item.name && read.modelId == modelTarget && read.options.contentEquals(item.options) && read.cost == 0 && read.exchangeable == item.exchangeable)
                assets += InfernoAssetWriter.Asset(Index.ITEMS, id ushr 8, id and 255, bytes)
            }
        } }
        if (apply) {
            InfernoAssetWriter.write(target, assets)
            FileCache(target.toString()).use { result ->
                for (asset in assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                verifyFrames(expectedFrames, graphic(graphicId, requireNotNull(result.data(Index.GRAPHICS, graphicId, 0))))
            }
        }
        val report = buildJsonObject {
            put("status", if (apply) "imported-readback-verified" else "audit-only")
            put("donor", donor.toString()); put("target", target.toString())
            put("originalNameIconsSha256", baselineIconHash); put("sourceNameIconsSha256", donorIconHash)
            put("sourceBondModelSha256", donorModelHash); put("sourceBondModel", modelSource); put("targetBondModel", modelTarget)
            put("spriteArchive", graphicId)
            put("icons", buildJsonArray { sourceFrames.forEachIndexed { i, source -> add(buildJsonObject { put("sourceFrame", source); put("targetFrame", 8 + i); put("wireCrown", 4 + i) }) } })
            put("bonds", buildJsonArray { for (i in 0 until 11) add(buildJsonObject { put("sourceClientOverride", if (i < 6) directSources[i] else upgradeSources[i - 6]); put("target", 30250 + i); put("tier", if (i < 6) i + 1 else i - 4); put("upgrade", i >= 6) }) })
            put("assets", buildJsonArray { assets.forEach { add(buildJsonObject { put("index", it.index); put("archive", it.archive); put("file", it.file); put("sha256", hash(it.bytes)) }) } })
        }
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("Donator assets: ${if (apply) "imported" else "audited"} ${assets.size} bounded assets; $output")
    }

    internal fun recolours(tier: Int): ShortArray = ShortArray(colours.size) { index ->
        val base = when (tier) { 0 -> if (index < 11) 359770 else 22424; 1 -> 22424; 2 -> 926; 3 -> 127; 4 -> 10349; 5 -> 6075; else -> error("Invalid tier") }
        (base + index * 10).toShort()
    }

    internal fun mergeIcons(original: GraphicDefinition, donor: GraphicDefinition): GraphicDefinition {
        require(original.width == donor.width && original.height == donor.height) { "Icon canvases differ; existing text metrics must remain unchanged" }
        val frames = requireNotNull(original.graphics).toList() + sourceFrames.map { requireNotNull(donor.graphics).getOrNull(it) ?: error("Missing source icon $it") }
        val palette = linkedMapOf(0 to 0)
        val rasters = frames.map { frame -> ByteArray(frame.raster.size) { pixel ->
            val index = frame.raster[pixel].toInt() and 255
            if (index == 0) 0 else palette.getOrPut(frame.palette[index]) { palette.size }.toByte()
        } }
        require(palette.size <= 256) { "Merged icon palette exceeds native byte indices" }
        val colours = palette.keys.toIntArray()
        return original.copy(graphics = frames.mapIndexed { index, frame -> frame.copy(alpha = frame.alpha?.copyOf()).apply {
            raster = rasters[index]
            this.palette = colours.copyOf()
        } }.toTypedArray(), rawPalette = colours)
    }

    private fun graphic(id: Int, bytes: ByteArray) = GraphicDefinition(id).also { GraphicDecoder().readLoop(it, BufferReader(bytes)) }
    private fun pixels(frame: IndexedGraphic) = IntArray(frame.raster.size) { i ->
        val index = frame.raster[i].toInt() and 255
        if (index == 0) 0 else (((frame.alpha?.get(i)?.toInt()?.and(255) ?: 255) shl 24) or frame.palette[index])
    }
    private fun verifyFrames(expected: List<IndexedGraphic>, result: GraphicDefinition) {
        require(result.graphics!!.size == expected.size)
        expected.forEachIndexed { i, before ->
            val after = result.graphics!![i]
            check(before.offsetX == after.offsetX && before.offsetY == after.offsetY && before.width == after.width && before.height == after.height && pixels(before).contentEquals(pixels(after))) { "Name icon frame $i changed pixels or placement" }
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
