package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import org.drynor.tools.rs3port.SkeletonCache
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Passive native asset package only. No combat, drops, stats overrides or live cache writes. */
object ZulrahActorPreview {
    internal val npcIds = setOf(22042,22043,22044,22045)
    private val spotIds = setOf(6044,6046,6047)
    private const val CLOUD_ID = 111700
    internal val actionSequences = setOf(22408,25068,25069,25070,25071,25072,25806)

    fun build(sourcePath: Path,inputPreviewRoot: Path,outputRoot: Path) {
        val sourcePathReal = sourcePath.toRealPath()
        val input = inputPreviewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(input) && !output.startsWith(sourcePathReal))
        val manifest = Json.parseToJsonElement(Files.readString(input.resolve("zulrah-static-preview.json"))).jsonObject
        require(manifest["status"]?.jsonPrimitive?.content == "experimental-zulrah-static")
        val baselinePath = input.resolve(manifest.getValue("packedCache").jsonPrimitive.content).normalize().toRealPath()
        require(baselinePath.startsWith(input) && baselinePath != sourcePathReal)
        FileCache(sourcePathReal.toString(),writable=false).use { source ->
            FileCache(baselinePath.toString(),writable=false).use { baseline ->
                val beforeSource = InfernoDependencyPlan.snapshot(source)
                val beforeTarget = InfernoDependencyPlan.snapshot(baseline)
                val npcData = npcIds.associateWith { requireNotNull(source.data(Index.NPCS,it ushr 7,it and 127)) }
                val npcDeps = npcData.mapValues { OsrsNpcDependencies.parse(it.value) }
                require(npcDeps.values.all { it.morphs.isEmpty() && it.textures.isEmpty() })
                val spots = spotIds.associateWith { OsrsSpotConverter.parse(it,requireNotNull(source.data(Index.SPOTANIMS,it ushr 8,it and 255))) }
                val cloudData = requireNotNull(source.data(Index.OBJECTS,CLOUD_ID ushr 8,CLOUD_ID and 255))
                val cloudDeps = OsrsObjectDependencies.parse(cloudData)
                require(cloudDeps.morphs.isEmpty() && cloudDeps.textures.isEmpty() && cloudDeps.sounds.isEmpty())
                val modelIds = (npcDeps.values.flatMap { it.models.map { raw -> raw+200000 } } + spots.values.map { it.modelId } + cloudDeps.models.map { it+200000 }).toSet()
                val models = DeterministicIds.plan(modelIds,baseline.archiveIds(Index.MODELS).toSet())
                val npcs = DeterministicIds.plan(npcIds,occupied(baseline,Index.NPCS,7))
                require(npcs.values.all { it <= 32767 }) { "NPC preview client id limit exceeded" }
                val spotMap = DeterministicIds.plan(spotIds,occupied(baseline,Index.SPOTANIMS,8))
                val cloudMap = DeterministicIds.plan(setOf(CLOUD_ID),occupied(baseline,Index.OBJECTS,8))
                require(cloudMap.values.all { it <= 0x1ffff })
                val sequences = (actionSequences + npcDeps.values.flatMap { it.animations.map { raw -> raw+20000 } } + spots.values.mapNotNull { it.animationId.takeIf { id -> id >= 0 } } + cloudDeps.animations.map { it+20000 }).toSet()
                val animation = ZulrahAnimationAssets.plan(source,baseline,sequences)
                val assets = animation.assets.toMutableList()
                val warnings = mutableListOf<JsonObject>()
                val basMap = sortedMapOf<Int,Int>()
                val usedBas = baseline.files(Index.CONFIGS,Config.RENDER_ANIMATIONS).toMutableSet()
                for ((sourceId,id) in npcs.toSortedMap()) {
                    val basId = (0..65534).first { it !in usedBas }
                    usedBas.add(basId); basMap[sourceId]=basId
                    val converted = OsrsNpcConverter.convert(id,npcData.getValue(sourceId),models,animation.sequenceMap,basId)
                    val bytes = encode { with(NPCEncoder()) { it.encode(converted.npc) } }
                    val read = NPCDefinition(id)
                    NPCDecoder().readLoop(read,BufferReader(bytes))
                    check(read.modelIds.contentEquals(converted.npc.modelIds) && read.renderEmote == basId && read.size == converted.npc.size)
                    val basBytes = encode { with(BASEncoder()) { it.encode(converted.bas) } }
                    val bas = BASDefinition(basId)
                    BASDecoder().readLoop(bas,BufferReader(basBytes))
                    check(bas.standAnimation == converted.bas.standAnimation && bas.walkAnimation == converted.bas.walkAnimation)
                    assets.add(InfernoAssetWriter.Asset(Index.NPCS,id ushr 7,id and 127,bytes))
                    assets.add(InfernoAssetWriter.Asset(Index.CONFIGS,Config.RENDER_ANIMATIONS,basId,basBytes))
                    warnings.add(buildJsonObject { put("sourceNpcId",sourceId); put("targetNpcId",id); put("warnings",JsonArray(converted.warnings.map(::JsonPrimitive))) })
                }
                for ((origin,id) in spotMap.toSortedMap()) {
                    val spot = OsrsSpotConverter.convert(id,spots.getValue(origin),models,animation.sequenceMap)
                    val bytes = encode { with(SpotAnimEncoder()) { it.encode(spot) } }
                    val decoded = SpotAnimDefinition(id)
                    SpotAnimDecoder().readLoop(decoded,BufferReader(bytes))
                    check(decoded.modelId == spot.modelId && decoded.animationId == spot.animationId)
                    assets.add(InfernoAssetWriter.Asset(Index.SPOTANIMS,id ushr 8,id and 255,bytes))
                }
                val cloud = OsrsObjectConverter.convert(cloudMap.getValue(CLOUD_ID),cloudData,models,animation.sequenceMap,cloudMap)
                val cloudBytes = encode { with(ObjectEncoder()) { it.encode(cloud) } }
                val cloudRead = ObjectDefinition(cloud.id)
                ObjectDecoder().readLoop(cloudRead,BufferReader(cloudBytes))
                check(cloudRead.modelIds.contentDeepEquals(cloud.modelIds) && cloudRead.animations.contentEquals(cloud.animations))
                assets.add(InfernoAssetWriter.Asset(Index.OBJECTS,cloud.id ushr 8,cloud.id and 255,cloudBytes))
                for ((origin,id) in models.toSortedMap()) {
                    val raw = requireNotNull(source.data(Index.MODELS,origin))
                    val model = ModelDecoder.read(id,raw)
                    require(model.faceTextures?.all { it == (-1).toShort() } != false)
                    require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(id,bytes)).contentEquals(bytes))
                    assets.add(InfernoAssetWriter.Asset(Index.MODELS,id,0,bytes))
                }
                validateFreshAssets(baseline,assets)
                Files.createDirectories(output)
                writeBinary(output.resolve("actors"),assets)
                stage(output.resolve("actor-source-package"),assets,baseline)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(baselinePath).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it,packed.resolve(it.fileName)) } }
                InfernoAssetWriter.write(packed,assets)
                var unchanged = 0
                FileCache(packed.toString(),writable=false).use { result ->
                    for(asset in assets) check(result.data(asset.index,asset.archive,asset.file).contentEquals(asset.bytes))
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for(index in baseline.indices()) for(archive in baseline.archiveIds(index)) {
                        if(index to archive !in touched) { check(baseline.container(index,archive).contentEquals(result.container(index,archive))); unchanged++ }
                        else for(file in baseline.files(index,archive)) check(baseline.data(index,archive,file).contentEquals(result.data(index,archive,file)))
                    }
                }
                check(beforeSource == InfernoDependencyPlan.snapshot(source) && beforeTarget == InfernoDependencyPlan.snapshot(baseline))
                fun ids(values: Map<Int,Int>) = buildJsonObject { values.toSortedMap().forEach { (from,to) -> put(from.toString(),to) } }
                val report = buildJsonObject {
                    put("status","experimental-zulrah-actors"); put("packedCache","packed-cache")
                    put("sourceCache",sourcePathReal.toString()); put("inputPreview",input.toString())
                    put("ids",buildJsonObject { put("npcs",ids(npcs)); put("animations",ids(animation.sequenceMap)); put("spots",ids(spotMap)); put("objects",ids(cloudMap)); put("bas",ids(basMap)); put("models",ids(models)) })
                    put("animation",animation.report); put("npcWarnings",JsonArray(warnings))
                    put("addedAssets",assets.size); put("unchangedArchivesVerified",unchanged)
                    put("editableRoundtripVerified",true); put("renderingValidated",false); put("gameplayImplemented",false)
                    put("limitations","Passive actor assets only. Sequence sounds/equipment and NPC Java gameplay overrides are not installed; no encounter/rewards/venom enabled.")
                }
                val json = Json { prettyPrint=true }
                Files.writeString(output.resolve("zulrah-actor-preview.json"),json.encodeToString(JsonObject.serializer(),report)+"\n",CREATE_NEW)
                Files.writeString(output.resolve("zulrah-static-preview.json"),json.encodeToString(JsonObject.serializer(),JsonObject(manifest.toMutableMap().apply { put("packedCache",JsonPrimitive("packed-cache")) }))+"\n",CREATE_NEW)
                println("Native Zulrah actors verified: $output")
            }
        }
    }

    internal fun validateFreshAssets(cache: Cache,assets: List<InfernoAssetWriter.Asset>) {
        require(assets.map { Triple(it.index,it.archive,it.file) }.distinct().size == assets.size) { "Duplicate asset address" }
        for(asset in assets) require(!asset.replaceExisting && asset.file !in cache.files(asset.index,asset.archive)) { "Actor asset would overwrite ${asset.index}/${asset.archive}/${asset.file}" }
    }
    private fun occupied(cache: Cache,index: Int,bits: Int)=cache.archiveIds(index).flatMap { archive -> cache.files(index,archive).map { (archive shl bits) or it } }.toSet()
    private fun encode(block: (Writer)->Unit)=BufferWriter(1 shl 20).also(block).toArray()

    private fun writeBinary(root: Path,assets: List<InfernoAssetWriter.Asset>) {
        for(asset in assets) {
            val name = when(asset.index) {
                Index.ANIMATION_SKELETONS -> "bases/${asset.archive}.bin"
                Index.ANIMATION_FRAMES -> "frames/${asset.archive}/${asset.file}.bin"
                Index.ANIMATIONS -> "sequences/${(asset.archive shl 7) or asset.file}.bin"
                Index.NPCS -> "npcs/${(asset.archive shl 7) or asset.file}.bin"
                Index.SPOTANIMS -> "spots/${(asset.archive shl 8) or asset.file}.bin"
                Index.OBJECTS -> "objects/${(asset.archive shl 8) or asset.file}.bin"
                Index.MODELS -> "models/${asset.archive}.bin"
                Index.CONFIGS -> { require(asset.archive == Config.RENDER_ANIMATIONS); "bas/${asset.file}.bin" }
                else -> error("Unexpected actor asset index ${asset.index}")
            }
            val path=root.resolve(name); Files.createDirectories(path.parent); Files.write(path,asset.bytes,CREATE_NEW)
        }
    }

    internal fun stage(root: Path,assets: List<InfernoAssetWriter.Asset>,baseline: Cache) {
        val skeletons = assets.filter { it.index == Index.ANIMATION_SKELETONS }.associate { it.archive to it.bytes }.toMutableMap()
        for(frame in assets.filter { it.index == Index.ANIMATION_FRAMES }) {
            val base=((frame.bytes[1].toInt() and 255) shl 8) or (frame.bytes[2].toInt() and 255)
            if(base !in skeletons) skeletons[base]=requireNotNull(baseline.data(Index.ANIMATION_SKELETONS,base))
        }
        AnimationFrameCodec.reset(); AnimationFrameCodec.cache={SkeletonCache(skeletons)}
        try {
            for((address,group) in assets.groupBy { it.index to it.archive }) {
                val(index,archive)=address
                val ordered=group.sortedBy { it.file }; val ids=ordered.map { it.file }.toIntArray(); val data=ordered.map { it.bytes }
                val metadata=ArchiveMetadata().also { it.fileIds(ids) }; val directory=root.resolve(IndexNames.name(index)); val codec=SourceCodecs.codec(index)
                val chunks=if(index == Index.ANIMATION_FRAMES && data.size > 1) listOf(IntArray(data.size){4},IntArray(data.size){data[it][3].toInt() and 255},IntArray(data.size){data[it].size-4-(data[it][3].toInt() and 255)}) else emptyList()
                val unpacked=codec.unpack(directory,SourceArchive(index,archive,null,ids,ArchiveGroup.join(data,chunks.ifEmpty { null }),data,chunks,metadata))
                check(unpacked.pristine.isEmpty()) { "Actor editable fallback $address" }
                for(file in unpacked.files) { val path=directory.resolve(file.path); Files.createDirectories(path.parent); Files.write(path,file.bytes,CREATE_NEW) }
                val packed=codec.pack(directory,archive,metadata); check(packed.fileIds.contentEquals(ids))
                val read=ArchiveGroup.split(packed.group,packed.fileIds).files
                check(read.indices.all { read[it].contentEquals(data[it]) }) { "Actor editable roundtrip mismatch $address" }
                Files.writeString(directory.resolve("$archive.metadata.json"),metadata.write(),CREATE_NEW)
            }
        } finally { AnimationFrameCodec.reset() }
    }
}
