package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Native equipment/consumable package built on the validated map and actor preview. */
object ZulrahItemsPreview {
    internal val initialItems = ((42905..42919).toList() + (42922..42934).toList() + listOf(42936,42938)).toSet()
    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
    private fun models(item: ItemDefinition) = listOf(item.modelId,item.primaryMaleModel,item.secondaryMaleModel,item.tertiaryMaleModel,
        item.primaryFemaleModel,item.secondaryFemaleModel,item.tertiaryFemaleModel,item.primaryMaleDialogueHead,item.secondaryMaleDialogueHead,
        item.primaryFemaleDialogueHead,item.secondaryFemaleDialogueHead).filter { it > 0 }
    private fun links(item: ItemDefinition) = listOf(item.noteId,item.notedTemplateId,item.lendId,item.lendTemplateId) + (item.stackIds ?: intArrayOf()).filter { it > 0 }

    fun build(sourcePath: Path,inputRoot: Path,outputRoot: Path, full: Boolean = false) {
        val origin = sourcePath.toRealPath(); val input = inputRoot.toRealPath(); val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(input) && !output.startsWith(origin))
        val marker = Json.parseToJsonElement(Files.readString(input.resolve("zulrah-actor-preview.json"))).jsonObject
        require(marker.getValue("status").jsonPrimitive.content == "experimental-zulrah-actors")
        val baselinePath = input.resolve(marker.getValue("packedCache").jsonPrimitive.content).normalize().toRealPath()
        require(baselinePath.startsWith(input))
        FileCache(origin.toString(),writable=false).use { source -> FileCache(baselinePath.toString(),writable=false).use { baseline ->
            val sourceSnapshot = InfernoDependencyPlan.snapshot(source); val baselineSnapshot = InfernoDependencyPlan.snapshot(baseline)
            val items = sortedMapOf<Int,ItemDefinition>(); val omitted = sortedMapOf<Int,Map<Int,Int>>(); val presentation = sortedMapOf<Int,Map<Int,Int>>()
            val previous = if(full) Json.parseToJsonElement(Files.readString(input.resolve("zulrah-item-preview.json"))).jsonObject else null
            fun previousIds(kind: String) = previous?.getValue("ids")?.jsonObject?.get(kind)?.jsonObject.orEmpty().mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
            val existingItems = previousIds("items")
            val pending = java.util.TreeSet(if(full) setOf(41791,41905,41907,41908,42899,42900,42902,42904,42921,42939,42940,52731) else initialItems)
            while(pending.isNotEmpty()) {
                val id = pending.pollFirst(); if(id in items || id in existingItems) continue
                require(items.size < 256) { "Item dependency closure limit exceeded" }
                val unused = linkedMapOf<Int,Int>(); val ignored = linkedMapOf<Int,Int>()
                val bytes = requireNotNull(source.data(Index.ITEMS,id ushr 8,id and 255)) { "Missing donor item $id" }
                val item = try { OsrsItemConverter.decode(id,bytes,unused,ignored) } catch(e: Exception) { throw IllegalArgumentException("Source item $id: ${e.message}",e) }
                items[id] = item; if(unused.isNotEmpty()) omitted[id] = unused; if(ignored.isNotEmpty()) presentation[id]=ignored
                pending.addAll(links(item).filter { it >= 0 && it !in items })
            }
            val occupiedItems = baseline.archiveIds(Index.ITEMS).flatMap { a -> baseline.files(Index.ITEMS,a).map { (a shl 8) or it } }.toMutableSet()
            val itemMap = existingItems + items.keys.associateWith { _ -> (30000..32766).first { it !in occupiedItems }.also { occupiedItems.add(it) } }
            println("Zulrah item allocation: " + items.map { (id,item) -> "$id=${itemMap.getValue(id)}:${item.name}" }.joinToString())
            val boatData=if(full) requireNotNull(source.data(Index.OBJECTS,110068 ushr 8,110068 and 255)) else null
            val boatDeps=boatData?.let(OsrsObjectDependencies::parse)
            require(boatDeps == null || boatDeps.morphs.isEmpty() && boatDeps.textures.isEmpty() && boatDeps.sounds.isEmpty())
            val boatMap=if(full) mapOf(110068 to 110068) else emptyMap()
            if(boatData != null) {
                val nativeBoat=ObjectDefinition(110068)
                ObjectDecoder().readLoop(nativeBoat,BufferReader(requireNotNull(baseline.data(Index.OBJECTS,110068 ushr 8,110068 and 255))))
                require(boatDeps!!.models.size == 1)
                val nativeModels=requireNotNull(nativeBoat.modelIds).flatMap { it.toList() }.distinct()
                require(nativeModels.size == 1)
                val sourceModel=boatDeps.models.single()+200000
                val actualModel=nativeModels.single()
                val donor=ModelDecoder.read(actualModel,requireNotNull(source.data(Index.MODELS,sourceModel)))
                val existing=ModelDecoder.read(actualModel,requireNotNull(baseline.data(Index.MODELS,actualModel)))
                check(ModelEncoder.encode(donor).contentEquals(ModelEncoder.encode(existing))) { "Existing boat geometry differs from donor" }
                val expectedBoat=OsrsObjectConverter.convert(110068,boatData,mapOf(sourceModel to actualModel),emptyMap(),boatMap)
                check(nativeBoat.name == expectedBoat.name && nativeBoat.sizeX == expectedBoat.sizeX && nativeBoat.sizeY == expectedBoat.sizeY && nativeBoat.options.contentEquals(expectedBoat.options))
                check(nativeBoat.modelIds.contentDeepEquals(expectedBoat.modelIds) && nativeBoat.modelTypes.contentEquals(expectedBoat.modelTypes))
                check(nativeBoat.solid == expectedBoat.solid && nativeBoat.block == expectedBoat.block)
            }
            val petData = (if(full) setOf(22130,22131,22132) else emptySet()).associateWith { requireNotNull(source.data(Index.NPCS,it ushr 7,it and 127)) }
            val petDeps = petData.mapValues { OsrsNpcDependencies.parse(it.value) }
            require(petDeps.values.all { it.morphs.isEmpty() && it.textures.isEmpty() })
            val petMap = DeterministicIds.plan(petData.keys,baseline.archiveIds(Index.NPCS).flatMap { a -> baseline.files(Index.NPCS,a).map { (a shl 7) or it } }.toSet())
            require(petMap.values.all { it in 0..32767 } && boatMap.values.all { it in 0..0x1ffff })
            val sourceSpots = (if(full) setOf(6251,6252,6253,6369) else setOf(6043)).associateWith { OsrsSpotConverter.parse(it,requireNotNull(source.data(Index.SPOTANIMS,it ushr 8,it and 255))) }
            val spotMap = DeterministicIds.plan(sourceSpots.keys,baseline.archiveIds(Index.SPOTANIMS).flatMap { a -> baseline.files(Index.SPOTANIMS,a).map { (a shl 8) or it } }.toSet())
            val animation = ZulrahAnimationAssets.plan(source,baseline,(if(full) emptySet() else setOf(25061)) + boatDeps?.animations.orEmpty().map { it+20000 } + petDeps.values.flatMap { it.animations.map { raw -> raw+20000 } } + sourceSpots.values.mapNotNull { it.animationId.takeIf { id -> id >= 0 } },if(full) emptySet() else setOf(25061))
            val modelMap = DeterministicIds.plan((items.values.flatMap(::models)  + petDeps.values.flatMap { it.models.map { raw -> raw+200000 } } + sourceSpots.values.map { it.modelId }).toSet(),baseline.archiveIds(Index.MODELS).toSet())
            val assets = animation.assets.toMutableList()
            
            val usedBas=baseline.files(Index.CONFIGS,Config.RENDER_ANIMATIONS).toMutableSet()
            val petBas=sortedMapOf<Int,Int>()
            for((from,to) in petMap) {
                val basId=(0..65534).first { it !in usedBas }; usedBas.add(basId); petBas[from]=basId
                val pet=OsrsNpcConverter.convert(to,petData.getValue(from),modelMap,animation.sequenceMap,basId)
                assets.add(InfernoAssetWriter.Asset(Index.NPCS,to ushr 7,to and 127,encode { with(NPCEncoder()) { it.encode(pet.npc) } }))
                assets.add(InfernoAssetWriter.Asset(Index.CONFIGS,Config.RENDER_ANIMATIONS,basId,encode { with(BASEncoder()) { it.encode(pet.bas) } }))
            }
            val occupiedModels=(baseline.archiveIds(Index.MODELS).toSet()+modelMap.values).toMutableSet()
            val wornVariants=mutableListOf<JsonObject>()
            for((from,to) in modelMap) {
                val model = ModelDecoder.read(to,requireNotNull(source.data(Index.MODELS,from)))
                require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Item model $from has unresolved texture" }
                require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty()) { "Item model $from has footer dependencies" }
                val bytes = ModelEncoder.encode(model); check(ModelEncoder.encode(ModelDecoder.read(to,bytes)).contentEquals(bytes))
                assets.add(InfernoAssetWriter.Asset(Index.MODELS,to,0,bytes))
            }
            fun remapModel(id: Int) = if(id <= 0) id else modelMap.getValue(id)
            fun remapItem(id: Int) = if(id < 0) id else itemMap.getValue(id)
            val expected = buildJsonArray {
                for((from,item) in items) {
                    item.id = itemMap.getValue(from)
                    item.modelId=remapModel(item.modelId); item.primaryMaleModel=remapModel(item.primaryMaleModel); item.secondaryMaleModel=remapModel(item.secondaryMaleModel)
                    item.tertiaryMaleModel=remapModel(item.tertiaryMaleModel); item.primaryFemaleModel=remapModel(item.primaryFemaleModel); item.secondaryFemaleModel=remapModel(item.secondaryFemaleModel)
                    item.tertiaryFemaleModel=remapModel(item.tertiaryFemaleModel); item.primaryMaleDialogueHead=remapModel(item.primaryMaleDialogueHead); item.secondaryMaleDialogueHead=remapModel(item.secondaryMaleDialogueHead)
                    item.primaryFemaleDialogueHead=remapModel(item.primaryFemaleDialogueHead); item.secondaryFemaleDialogueHead=remapModel(item.secondaryFemaleDialogueHead)
                    item.noteId=remapItem(item.noteId); item.notedTemplateId=remapItem(item.notedTemplateId); item.lendId=remapItem(item.lendId); item.lendTemplateId=remapItem(item.lendTemplateId)
                    item.stackIds=item.stackIds?.map { if(it == 0) 0 else itemMap.getValue(it) }?.toIntArray()
                    applyNativeOverrides(from,item)
                    if(full && from in setOf(41791,41905,41907,41908,42899,42900,42902,42904,52731)) {
                        val referenceId=if(from in setOf(41905,41907,41908,42899,42900)) 22494 else if(from == 52731) 11367 else 15486
                        val reference=ItemDefinition(referenceId)
                        ItemDecoder().readLoop(reference,BufferReader(requireNotNull(baseline.data(Index.ITEMS,referenceId ushr 8,referenceId and 255))))
                        item.params=item.params.orEmpty() + mapOf(644 to reference.getRenderAnimId(),686 to reference.getParamVal(686))
                        require(baseline.data(Index.CONFIGS,Config.RENDER_ANIMATIONS,reference.getRenderAnimId()) != null)
                    }
                    if(from in setOf(42926,42931) || full && item.wearPos == 3) {
                        fun worn(id: Int,female: Boolean): Int {
                            if(id < 0) return id
                            val target=(200000..Int.MAX_VALUE).first { it !in occupiedModels }; occupiedModels.add(target)
                            val original=ModelDecoder.read(target,assets.single { it.index == Index.MODELS && it.archive == id }.bytes)
                            val weapon=item.wearPos == 3
                            val dx=if(weapon) -10 else 0
                            val dy=if(weapon) { if(female) 30 else 35 } else { if(from == 42931) -10 else -20 }
                            val dz=if(weapon) -20 else 15
                            val converted=translateWorn(original,target,dx,dy,dz)
                            val bytes=ModelEncoder.encode(converted); val decoded=ModelDecoder.read(target,bytes)
                            check(decoded.vertexX.contentEquals(converted.vertexX) && decoded.vertexY.contentEquals(converted.vertexY) && decoded.vertexZ.contentEquals(converted.vertexZ))
                            assets.add(InfernoAssetWriter.Asset(Index.MODELS,target,0,bytes))
                            wornVariants.add(buildJsonObject { put("sourceItem",from); put("female",female); put("baseNativeModel",id); put("target",target); put("x",dx); put("y",dy); put("z",dz) })
                            return target
                        }
                        item.primaryMaleModel=worn(item.primaryMaleModel,false); item.primaryFemaleModel=worn(item.primaryFemaleModel,true)
                    }
                    val bytes = encode { with(ItemEncoder()) { it.encode(item) } }
                    val decoded=ItemDefinition(item.id); ItemDecoder().readLoop(decoded,BufferReader(bytes)); check(decoded == item)
                    assets.add(InfernoAssetWriter.Asset(Index.ITEMS,item.id ushr 8,item.id and 255,bytes))
                    add(buildJsonObject { put("source",from); put("target",item.id); put("name",item.name); put("wearPos",item.wearPos); put("wearPos2",item.wearPos2)
                        put("options",JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull })); put("params",buildJsonObject { item.params.orEmpty().forEach { (k,v) -> put(k.toString(),JsonPrimitive(v.toString())) } }) })
                }
            }
            for((from,to) in spotMap) {
                val spot=OsrsSpotConverter.convert(to,sourceSpots.getValue(from),modelMap,animation.sequenceMap)
                assets.add(InfernoAssetWriter.Asset(Index.SPOTANIMS,to ushr 8,to and 255,encode { with(SpotAnimEncoder()) { it.encode(spot) } }))
            }
            ZulrahActorPreview.validateFreshAssets(baseline,assets)
            Files.createDirectories(output)
            Files.walk(input).use { paths -> paths.forEach { path -> val target=output.resolve(input.relativize(path)); if(Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path,target) } }
            writeBinary(output.resolve("items"),assets)
            ZulrahActorPreview.stage(output.resolve(if(full) "full-item-source-package" else "item-source-package"),assets,baseline)
            val packed=output.resolve("packed-cache"); InfernoAssetWriter.write(packed,assets)
            var unchanged=0
            FileCache(packed.toString(),writable=false).use { result ->
                for(asset in assets) check(result.data(asset.index,asset.archive,asset.file).contentEquals(asset.bytes))
                val touched=assets.map { it.index to it.archive }.toSet()
                for(index in baseline.indices()) for(archive in baseline.archiveIds(index)) {
                    if(index to archive !in touched) { check(baseline.container(index,archive).contentEquals(result.container(index,archive))); unchanged++ }
                    else for(file in baseline.files(index,archive)) check(baseline.data(index,archive,file).contentEquals(result.data(index,archive,file)))
                }
            }
            check(sourceSnapshot == InfernoDependencyPlan.snapshot(source) && baselineSnapshot == InfernoDependencyPlan.snapshot(baseline))
            fun ids(map: Map<Int,Int>)=buildJsonObject { map.toSortedMap().forEach { (a,b) -> put(a.toString(),b) } }
            val report=buildJsonObject {
                put("status","experimental-zulrah-items"); put("packedCache","packed-cache"); put("sourceCache",origin.toString()); put("inputPreview",input.toString())
                put("ids",buildJsonObject { put("items",ids(itemMap)); put("animations",ids(previousIds("animations") + animation.sequenceMap)); put("spots",ids(previousIds("spots") + spotMap)); put("models",ids(previousIds("models") + modelMap)); put("npcs",ids(petMap)); put("bas",ids(petBas)); put("objects",ids(boatMap)) })
                put("expectedItems",JsonArray(previous?.getValue("expectedItems")?.jsonArray.orEmpty() + expected)); put("animation",animation.report); put("addedAssets",assets.size); put("unchangedArchivesVerified",unchanged)
                put("wornModelTranslations",JsonArray(previous?.get("wornModelTranslations")?.jsonArray.orEmpty() + wornVariants)); put("wornAvatarProfile","Native 727 avatar: donor CustomItems.modifyModel OLD_ITEMS=false branch")
                put("editableRoundtripVerified",true); put("renderingValidated",false)
                put("omittedPlaceholderLinks",buildJsonObject { previous?.get("omittedPlaceholderLinks")?.jsonObject?.forEach { (key,value) -> put(key,value) }; omitted.forEach { (id,links) -> put(id.toString(),buildJsonObject { links.forEach { (op,value) -> put(op.toString(),value) } }) } })
                put("donorIgnoredOffsets",buildJsonObject { previous?.get("donorIgnoredOffsets")?.jsonObject?.forEach { (key,value) -> put(key,value) }; presentation.forEach { (id,fields) -> put(id.toString(),buildJsonObject { fields.forEach { (op,value) -> put(op.toString(),value) } }) } })
                put("limitations","Native item visuals and translated donor overrides; gameplay is separate. Placeholder links omitted deliberately, matching the donor client. Native note and stack links are preserved.")
            }
            Files.writeString(output.resolve("zulrah-item-preview.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            if(full) Files.writeString(output.resolve("zulrah-full-content.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
                put("status","experimental-zulrah-full-content"); put("objects",ids(boatMap)); put("npcs",ids(petMap)); put("packedCache","packed-cache"); put("existingBoatNativeDefinitionAndModelVerified",true)
            })+"\n",CREATE_NEW)
            println("Native Zulrah items verified: $output")
        } }
    }

    internal fun applyNativeOverrides(sourceId: Int,item: ItemDefinition) {
        // Equipment options move 451..454 to 528..531; the ranged style enum is 13 natively (18 in the donor).
        val params=item.params.orEmpty().mapKeys { (key,_) -> if(key in 451..454) key+77 else key }.toMutableMap()
        if(sourceId in setOf(41791,41905,41907,41908,42899,42900,42902,42904)) {
            item.wearPos=3
            params.putAll(mapOf(3 to (if(sourceId in setOf(41905,41907,41908)) 15 else if(sourceId == 42902 || sourceId == 41791) 17 else 25),
                5 to 2,6 to 3,7 to 1,8 to 15,14 to 4,749 to 6,750 to 75))
            if(sourceId in setOf(41791,42902,42904)) {
                params.putAll(mapOf(687 to 1,0 to 55,1 to 70,5 to 0,6 to 3,7 to 3,8 to 17,641 to 720,685 to 15,751 to 0,752 to 75))
            }
        }
        if(sourceId == 52731) {
            item.wearPos=3
            params.putAll(mapOf(0 to 55,1 to 55,2 to 55,5 to -15,6 to -15,7 to -12,9 to -15,641 to 600,14 to 4,749 to 0,750 to 60,687 to 1))
        }
        if(sourceId == 42926) { item.wearPos=3; item.wearPos2=5 }
        if(sourceId == 42926) {
            params.putAll(mapOf(4 to 60,643 to 400,14 to 3,644 to 28,749 to 4,750 to 75,686 to 13,687 to 1))
        }
        if(sourceId == 42931) { item.wearPos=0; item.wearPos2=8; item.wearPos3=11 }
        if(sourceId == 42931) {
            params.putAll(mapOf(3 to -5,4 to -5,5 to 52,6 to 55,7 to 58,8 to 0,9 to 50,641 to 50,749 to 1,750 to 75))
        }
        if(sourceId in setOf(42922,42924,42927,42929,42932)) item.cost=300
        if(sourceId == 42938) item.cost=5000
        item.params=params.takeIf { it.isNotEmpty() }
    }

    internal fun translateWorn(model: ModelDefinition,target: Int,x: Int,y: Int,z: Int): ModelDefinition = model.copy(
        id=target,version=maxOf(13,model.version),newFormat=true,footerFlags=model.footerFlags or 8,vertexFlags=null,
        vertexX=model.vertexX.map { it+x }.toIntArray(),vertexY=model.vertexY.map { it+y }.toIntArray(),vertexZ=model.vertexZ.map { it+z }.toIntArray(),
    )

    private fun writeBinary(root: Path,assets: List<InfernoAssetWriter.Asset>) {
        for(asset in assets) {
            val name=when(asset.index) {
                Index.OBJECTS -> "objects/${(asset.archive shl 8) or asset.file}.bin"
                Index.NPCS -> "npcs/${(asset.archive shl 7) or asset.file}.bin"
                Index.CONFIGS -> "bas/${asset.file}.bin"
                Index.ITEMS -> "items/${(asset.archive shl 8) or asset.file}.bin"
                Index.MODELS -> "models/${asset.archive}.bin"
                Index.ANIMATIONS -> "sequences/${(asset.archive shl 7) or asset.file}.bin"
                Index.ANIMATION_FRAMES -> "frames/${asset.archive}/${asset.file}.bin"
                Index.ANIMATION_SKELETONS -> "bases/${asset.archive}.bin"
                Index.SPOTANIMS -> "spots/${(asset.archive shl 8) or asset.file}.bin"
                else -> error("Unexpected item asset ${asset.index}")
            }
            val path=root.resolve(name); Files.createDirectories(path.parent); Files.write(path,asset.bytes,CREATE_NEW)
        }
    }
}
