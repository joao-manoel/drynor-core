package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path

/** Adds the victory cape to a new copy of an already validated Inferno preview. */
object InfernalCapePreview {
    const val SOURCE_ITEM = 51295
    private val json = Json { prettyPrint = true }
    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()

    fun build(sourcePath: Path, baselinePath: Path, output: Path) {
        require(!Files.exists(output))
        val preview = baselinePath.parent.parent
        require(Files.isRegularFile(preview.resolve("actor-preview-report.json"))) { "Baseline must be an Inferno actor preview arena/packed-cache" }
        require(!output.toAbsolutePath().normalize().startsWith(preview.toAbsolutePath().normalize())) { "Output must be outside baseline preview" }
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val baselineSnapshot = InfernoDependencyPlan.snapshot(baseline)
                val raw = requireNotNull(source.data(Index.ITEMS, SOURCE_ITEM ushr 8, SOURCE_ITEM and 255))
                // Worn appearance uses ushort(16384 + id); keep well below its sentinel and signed boundaries.
                val itemId = (30000..32766).first { baseline.data(Index.ITEMS, it ushr 8, it and 255) == null }
                val sourceLinks = linkedMapOf<Int, Int>()
                val item = OsrsItemConverter.decode(itemId, raw, sourceLinks)
                require(item.name == "Infernal cape" && item.stackable == 0)
                require(item.originalTextureColours == null) { "Additional cape texture remaps need conversion" }
                val sourceModels = listOf(item.modelId, item.primaryMaleModel, item.primaryFemaleModel,
                    item.secondaryMaleModel, item.secondaryFemaleModel, item.tertiaryMaleModel, item.tertiaryFemaleModel).filter { it >= 0 }.distinct().sorted()
                require(item.primaryMaleDialogueHead == -1 && item.primaryFemaleDialogueHead == -1)
                val modelMap = linkedMapOf<Int, Int>()
                val allocated = mutableSetOf<Int>()
                for (id in sourceModels) {
                    val target = generateSequence(id) { it + 1 }.first { it !in allocated && baseline.data(Index.MODELS, it) == null }
                    modelMap[id] = target; allocated.add(target)
                }
                // The donor synthesizes material OSRS+59 from fire-cape material40 and swaps only sprite485.
                val materials = MaterialDecoder().load(baseline)
                val materialId = materials.size
                require(materialId < 32767 && baseline.data(Index.TEXTURES, materialId) == null)
                val spriteId = generateSequence(20318) { it + 1 }.first { baseline.data(Index.GRAPHICS, it) == null }
                require(spriteId <= 65535) { "Cape sprite cannot be represented by texture short" }
                val sprite = requireNotNull(source.data(Index.GRAPHICS, 20318))
                val textureBytes = requireNotNull(source.data(Index.TEXTURES, 40))
                val texture = TextureDefinition(materialId)
                TextureDecoder().readLoop(texture, BufferReader(textureBytes))
                var substituted = 0
                for (operation in texture.operations) {
                    require(operation.type != 36) { "Texture references another texture; closure needs conversion" }
                    if (operation.type in setOf(18, 39)) for (record in operation.opcodes) if (record.opcode == 0) {
                        require(record.value == 485) { "Unexpected source sprite ${record.value}" }
                        record.value = spriteId; substituted++
                    }
                }
                require(substituted == 1) { "Expected one cape sprite substitution, got $substituted" }
                val assets = mutableListOf<InfernoAssetWriter.Asset>()
                for ((id, target) in modelMap) {
                    val model = ModelDecoder.read(target, requireNotNull(source.data(Index.MODELS, id)))
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty()) { "Cape model $id has unresolved footer dependencies" }
                    val textures = model.faceTextures?.map { it.toInt() }?.filter { it != -1 }?.toSet().orEmpty()
                    require(textures.all { it == 59 }) { "Cape model $id has unexpected materials $textures" }
                    val converted = model.copy(faceTextures = model.faceTextures?.map { if (it.toInt() == 59) materialId.toShort() else it }?.toShortArray())
                    val bytes = ModelEncoder.encode(converted)
                    check(ModelEncoder.encode(ModelDecoder.read(target, bytes)).contentEquals(bytes))
                    assets.add(InfernoAssetWriter.Asset(Index.MODELS, target, 0, bytes))
                }
                item.modelId = modelMap.getValue(item.modelId)
                item.primaryMaleModel = modelMap.getValue(item.primaryMaleModel)
                item.primaryFemaleModel = modelMap.getValue(item.primaryFemaleModel)
                fun remap(id: Int) = if (id < 0) id else modelMap.getValue(id)
                item.secondaryMaleModel = remap(item.secondaryMaleModel); item.secondaryFemaleModel = remap(item.secondaryFemaleModel)
                item.tertiaryMaleModel = remap(item.tertiaryMaleModel); item.tertiaryFemaleModel = remap(item.tertiaryFemaleModel)
                item.wearPos = 1
                item.exchangeable = false
                // Donor CustomItems applies these after raw cache decoding. Native equipment reads params.
                item.params = item.params.orEmpty() + mapOf(0 to 4, 1 to 4, 2 to 4, 3 to 1, 4 to 1,
                    5 to 12, 6 to 12, 7 to 12, 8 to 12, 9 to 12, 641 to 80, 11 to 2)
                val itemBytes = encode { with(ItemEncoder()) { it.encode(item) } }
                val readback = ItemDefinition(itemId)
                ItemDecoder().readLoop(readback, BufferReader(itemBytes))
                check(readback == item)
                assets.add(InfernoAssetWriter.Asset(Index.ITEMS, itemId ushr 8, itemId and 255, itemBytes))
                assets.add(InfernoAssetWriter.Asset(Index.GRAPHICS, spriteId, 0, sprite))
                assets.add(InfernoAssetWriter.Asset(Index.TEXTURES, materialId, 0, encode { with(TextureEncoder()) { it.encode(texture) } }))
                val donorMaterial = MaterialDecoder().load(source)[40]
                val extendedMaterials = materials.toList() + donorMaterial.copy(id = materialId)
                val materialBytes = encode { with(MaterialEncoder()) { it.encode(extendedMaterials) } }
                assets.add(InfernoAssetWriter.Asset(Index.TEXTURE_DEFINITIONS, 0, 0, materialBytes, replaceExisting = true))
                stage(output.resolve("reward-source-package"), assets)
                for (asset in assets) {
                    val binary = output.resolve("reward-binary/${asset.index}/${asset.archive}/${asset.file}.bin")
                    Files.createDirectories(binary.parent); Files.write(binary, asset.bytes)
                }
                Files.walk(preview).use { paths -> paths.forEach { path ->
                    val destination = output.resolve(preview.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
                } }
                val packed = output.resolve("arena/packed-cache")
                InfernoAssetWriter.write(packed, assets)
                var unchanged = 0
                FileCache(packed.toString(), writable = false).use { cache ->
                    for (asset in assets) check(cache.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                    val resultMaterials = MaterialDecoder().load(cache)
                    check(resultMaterials.toList() == extendedMaterials)
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index to archive !in touched) {
                            check(baseline.container(index, archive).contentEquals(cache.container(index, archive)))
                            unchanged++
                        } else if (index != Index.TEXTURE_DEFINITIONS) for (file in baseline.files(index, archive)) {
                            check(baseline.data(index, archive, file).contentEquals(cache.data(index, archive, file)))
                        }
                    }
                }
                check(sourceSnapshot == InfernoDependencyPlan.snapshot(source))
                check(baselineSnapshot == InfernoDependencyPlan.snapshot(baseline))
                val actorPath = output.resolve("actor-preview-report.json")
                val actor = Json.parseToJsonElement(Files.readString(actorPath)).jsonObject.toMutableMap()
                actor["packedCache"] = JsonPrimitive(packed.toString())
                Files.writeString(actorPath, json.encodeToString(JsonObject.serializer(), JsonObject(actor)))
                val report = buildJsonObject {
                    put("sourceItemId", SOURCE_ITEM); put("targetItemId", itemId); put("packedCache", packed.toString())
                    put("name", item.name); put("equipmentSlot", item.wearPos); put("exchangeable", item.exchangeable)
                    put("models", buildJsonObject { for ((a,b) in modelMap) put(a.toString(), b) })
                    put("materialId", materialId); put("spriteId", spriteId); put("sourceSpriteId", 20318)
                    put("textureTrailerHex", texture.trailer.orEmpty())
                    put("omittedSourcePlaceholderLinks", buildJsonObject { sourceLinks.forEach { (key,value) -> put(key.toString(), value) } })
                    put("resources", buildJsonArray { for (asset in assets) add(buildJsonObject {
                        put("index", asset.index); put("archive", asset.archive); put("file", asset.file)
                        put("binary", "reward-binary/${asset.index}/${asset.archive}/${asset.file}.bin")
                    }) })
                    put("itemParams", buildJsonObject { item.params!!.forEach { (key,value) -> put(key.toString(), JsonPrimitive(value.toString())) } })
                    put("editableRoundtripVerified", true); put("unchangedBaselineArchivesVerified", unchanged)
                    put("originalMaterialsPreserved", materials.size); put("animatedTexturePreserved", true)
                    put("sourceMaterialId", 40); put("nativeMaterial40MatchesDonor", materials[40] == donorMaterial)
                    put("sourceMaterial", buildJsonObject {
                        put("textureSpeedU", donorMaterial.textureSpeedU.toInt()); put("textureSpeedV", donorMaterial.textureSpeedV.toInt())
                        put("effectId", donorMaterial.effectId.toInt()); put("effectParam1", donorMaterial.effectParam1.toInt())
                        put("effectParam2", donorMaterial.effectParam2); put("effectCombiner", donorMaterial.effectCombiner)
                    })
                    put("renderingValidated", false)
                }
                Files.writeString(output.resolve("inferno-reward-report.json"), json.encodeToString(JsonObject.serializer(), report))
                println("Infernal cape imported and verified: $report")
            }
        }
    }

    private fun stage(root: Path, assets: List<InfernoAssetWriter.Asset>) {
        for ((address, group) in assets.groupBy { it.index to it.archive }) {
            val (index, archive) = address
            val directory = root.resolve(IndexNames.name(index))
            val ordered = group.sortedBy { it.file }
            val ids = ordered.map { it.file }.toIntArray()
            val data = ordered.map { it.bytes }
            val metadata = ArchiveMetadata().also { it.fileIds(ids) }
            val codec = SourceCodecs.codec(index)
            val unpacked = codec.unpack(directory, SourceArchive(index, archive, null, ids, ArchiveGroup.join(data), data, emptyList(), metadata))
            check(unpacked.pristine.isEmpty()) { "Reward editable fallback: $address" }
            for (file in unpacked.files) {
                val path = directory.resolve(file.path); Files.createDirectories(path.parent); Files.write(path, file.bytes)
            }
            val compiled = codec.pack(directory, archive, metadata)
            check(compiled.fileIds.contentEquals(ids))
            val result = ArchiveGroup.split(compiled.group, ids).files
            check(result.indices.all { result[it].contentEquals(data[it]) }) { "Reward editable roundtrip failed: $address" }
            Files.writeString(directory.resolve("$archive.metadata.json"), metadata.write())
        }
    }
}
