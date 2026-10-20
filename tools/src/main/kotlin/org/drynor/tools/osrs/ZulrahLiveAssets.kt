package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.*
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Adds animated map locations and Inferno pet assets to a new immutable preview. */
object ZulrahLiveAssets {
    internal fun restoreAnimation(definition:ObjectDefinition,animation:Int) {
        require(animation in 0..65534)
        definition.singleAnimation=animation
        definition.animations=intArrayOf(animation)
        definition.percents=null
        definition.rawPercents=null
    }
    private fun encode(block:(Writer)->Unit)=BufferWriter(1 shl 20).also(block).toArray()
    private fun occupied(cache:Cache,index:Int,bits:Int)=cache.archiveIds(index).flatMap { a -> cache.files(index,a).map { (a shl bits) or it } }.toSet()
    private fun locations(cache:Cache,region:Int,source:Boolean,keys:Map<Int,IntArray> = emptyMap()):List<MapObject> {
        val result=mutableListOf<MapObject>()
        val bytes=requireNotNull(cache.data(Index.MAPS,"l${region ushr 8}_${region and 255}",if(source)null else keys[region])) { "Missing landscape $region source=$source" }
        val decoder=object:MapObjectDecoder() {
            override fun add(objectId:Int,localX:Int,localY:Int,level:Int,shape:Int,rotation:Int,regionTileX:Int,regionTileY:Int) {
                result.add(MapObject(objectId+(if(source) 100000 else 0),localX,localY,level,shape,rotation))
            }
        }
        check(decoder.decode(bytes,ByteArray(16384),region ushr 8,region and 255)==bytes.size)
        return result
    }
    fun build(sourcePath:Path,inputRoot:Path,outputRoot:Path) {
        val sourcePathReal=sourcePath.toRealPath();val input=inputRoot.toRealPath();val output=outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(input) && !output.startsWith(sourcePathReal))
        require(Json.parseToJsonElement(Files.readString(input.resolve("zulrah-full-content.json"))).jsonObject.getValue("status").jsonPrimitive.content=="experimental-zulrah-full-content")
        val keys=Json.parseToJsonElement(Files.readString(Path.of("../drynor-world-server/data/map/xteaKeys.json"))).jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonArray.map { value -> value.jsonPrimitive.int }.toIntArray() }
        FileCache(sourcePathReal.toString(),writable=false).use { source -> FileCache(input.resolve("packed-cache").toString(),xteas=keys,writable=false).use { baseline ->
            val beforeSource=InfernoDependencyPlan.snapshot(source);val beforeTarget=InfernoDependencyPlan.snapshot(baseline)
            val objectMap=sortedMapOf<Int,Int>();val animationByObject=sortedMapOf<Int,Int>();val omissions=mutableListOf<JsonObject>()
            for(region in ZulrahStaticPreview.osrsRegions+9042) {
                val donor=locations(source,region,true)
                val target=locations(baseline,region,false,keys).groupBy { listOf(it.x,it.y,it.level,it.shape,it.rotation) }
                for(loc in donor) {
                    val deps=OsrsObjectDependencies.parse(requireNotNull(source.data(Index.OBJECTS,loc.id ushr 8,loc.id and 255)))
                    if(deps.animations.isEmpty()) continue
                    require(deps.animations.size==1) { "Unexpected animation grammar for object ${loc.id}" }
                    val matches=target[listOf(loc.x,loc.y,loc.level,loc.shape,loc.rotation)].orEmpty()
                    if(matches.size!=1) {
                        omissions.add(buildJsonObject { put("region",region);put("sourceObject",loc.id);put("reason","No unique existing native placement") });continue
                    }
                    val previous=objectMap.putIfAbsent(loc.id,matches.single().id)
                    require(previous==null || previous==matches.single().id)
                    animationByObject[loc.id]=deps.animations.single()+20000
                }
            }
            val petIds=setOf(27675,28011)
            val petData=petIds.associateWith { requireNotNull(source.data(Index.NPCS,it ushr 7,it and 127)) }
            val petDeps=petData.mapValues { OsrsNpcDependencies.parse(it.value) }
            require(petDeps.values.all { it.morphs.isEmpty() && it.textures.isEmpty() })
            val items=sortedMapOf<Int,ItemDefinition>();val pending=java.util.TreeSet(setOf(51291,52319))
            while(pending.isNotEmpty()) {
                val id=pending.pollFirst();if(id in items)continue
                require(items.size<20)
                val item=OsrsItemConverter.decode(id,requireNotNull(source.data(Index.ITEMS,id ushr 8,id and 255)),mutableMapOf(),mutableMapOf())
                require(item.primaryMaleModel<0 && item.primaryFemaleModel<0 && item.stackIds==null)
                items[id]=item
                pending.addAll(listOf(item.noteId,item.notedTemplateId,item.lendId,item.lendTemplateId).filter { it>=0 && it !in items })
            }
            val taken=occupied(baseline,Index.ITEMS,8).toMutableSet()
            val itemMap=items.keys.associateWith { (30000..32766).first { it !in taken }.also(taken::add) }
            println("Inferno pet item mapping: $itemMap")
            val npcMap=DeterministicIds.plan(petIds,occupied(baseline,Index.NPCS,7))
            require(npcMap.values.all { it<=32767 })
            val modelIds=(items.values.map { it.modelId }+petDeps.values.flatMap { it.models.map { raw -> raw+200000 } }).filter { it>0 }.toSet()
            val models=DeterministicIds.plan(modelIds,baseline.archiveIds(Index.MODELS).toSet())
            val sequenceIds=animationByObject.values.toSet()+petDeps.values.flatMap { it.animations.map { raw -> raw+20000 } }
            // Scene ObjectType animation never reads player hand replacements. Keep this exception
            // separate from actor sequences so the same sequence cannot lose equipment in player use.
            val petSequences=petDeps.values.flatMap { it.animations.map { raw -> raw+20000 } }.toSet()
            val sceneOnly=animationByObject.values.toSet()-petSequences
            val animation=ZulrahAnimationAssets.plan(source,baseline,sequenceIds,sceneOnlySequences=sceneOnly)
            val assets=animation.assets.toMutableList()
            for((from,to) in models) {
                val model=ModelDecoder.read(to,requireNotNull(source.data(Index.MODELS,from)))
                require(model.faceTextures?.all { it==(-1).toShort() }!=false && model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty())
                val bytes=ModelEncoder.encode(model);check(ModelEncoder.encode(ModelDecoder.read(to,bytes)).contentEquals(bytes))
                assets.add(InfernoAssetWriter.Asset(Index.MODELS,to,0,bytes))
            }
            val basMap=sortedMapOf<Int,Int>();val usedBas=baseline.files(Index.CONFIGS,Config.RENDER_ANIMATIONS).toMutableSet()
            for((from,to) in npcMap) {
                val bas=(0..65534).first { it !in usedBas };usedBas.add(bas);basMap[from]=bas
                val converted=OsrsNpcConverter.convert(to,petData.getValue(from),models,animation.sequenceMap,bas)
                assets.add(InfernoAssetWriter.Asset(Index.NPCS,to ushr 7,to and 127,encode { with(NPCEncoder()) { it.encode(converted.npc) } }))
                assets.add(InfernoAssetWriter.Asset(Index.CONFIGS,Config.RENDER_ANIMATIONS,bas,encode { with(BASEncoder()) { it.encode(converted.bas) } }))
            }
            for((from,item) in items) {
                item.id=itemMap.getValue(from);item.modelId=models.getValue(item.modelId)
                fun link(id:Int)=if(id<0)id else itemMap.getValue(id)
                item.noteId=link(item.noteId);item.notedTemplateId=link(item.notedTemplateId);item.lendId=link(item.lendId);item.lendTemplateId=link(item.lendTemplateId)
                val bytes=encode { with(ItemEncoder()) { it.encode(item) } };val read=ItemDefinition(item.id);ItemDecoder().readLoop(read,BufferReader(bytes));check(read==item)
                assets.add(InfernoAssetWriter.Asset(Index.ITEMS,item.id ushr 8,item.id and 255,bytes))
            }
            val changed=mutableListOf<JsonObject>();val reusedModels=sortedSetOf<Int>()
            for((from,to) in objectMap) {
                val original=requireNotNull(baseline.data(Index.OBJECTS,to ushr 8,to and 255))
                val item=ObjectDefinition(to);ObjectDecoder().readLoop(item,BufferReader(original))
                val existingModels=item.modelIds.orEmpty().flatMap { it.toList() }.distinct()
                reusedModels.addAll(existingModels)
                val sourceModels=OsrsObjectDependencies.parse(requireNotNull(source.data(Index.OBJECTS,from ushr 8,from and 255))).models.map { it+200000 }
                val encodedNative=existingModels.map { id -> ModelEncoder.encode(ModelDecoder.read(id,requireNotNull(baseline.data(Index.MODELS,id)))) }
                for(id in sourceModels) {
                    val donor=ModelEncoder.encode(ModelDecoder.read(id,requireNotNull(source.data(Index.MODELS,id))))
                    check(encodedNative.any { it.contentEquals(donor) }) { "Static model geometry or skinning differs from donor for object $from/model $id" }
                }
                val old=item.animations?.toList()
                val originalSingle=item.singleAnimation;val originalPercents=item.percents;val originalRawPercents=item.rawPercents
                val canonicalOriginal=encode { with(ObjectEncoder()) { it.encode(item) } }
                val nativeAnimation=animation.sequenceMap.getValue(animationByObject.getValue(from))
                restoreAnimation(item,nativeAnimation)
                val bytes=encode { with(ObjectEncoder()) { it.encode(item) } }
                val read=ObjectDefinition(to);ObjectDecoder().readLoop(read,BufferReader(bytes));check(read.animations.contentEquals(intArrayOf(nativeAnimation)))
                read.singleAnimation=originalSingle;read.animations=old?.toIntArray();read.percents=originalPercents;read.rawPercents=originalRawPercents
                check(encode { with(ObjectEncoder()) { it.encode(read) } }.contentEquals(canonicalOriginal)) { "Non-animation object fields changed for $to" }
                assets.add(InfernoAssetWriter.Asset(Index.OBJECTS,to ushr 8,to and 255,bytes,replaceExisting=true))
                changed.add(buildJsonObject { put("source",from);put("target",to);put("name",item.name);put("sourceAnimation",animationByObject.getValue(from));put("nativeAnimation",nativeAnimation);put("previousAnimations",old?.let { JsonArray(it.map(::JsonPrimitive)) }?:JsonNull) })
            }
            Files.createDirectories(output)
            Files.walk(input).use { paths -> paths.forEach { path -> val target=output.resolve(input.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target)else Files.copy(path,target) } }
            ZulrahActorPreview.stage(output.resolve("live-source-package"),assets,baseline)
            for(asset in assets) {
                val id=(asset.archive shl (if(asset.index==Index.NPCS || asset.index==Index.ANIMATIONS)7 else 8)) or asset.file
                val relative=when(asset.index) {
                    Index.ITEMS->"items/$id.bin";Index.NPCS->"npcs/$id.bin";Index.OBJECTS->"objects/$id.bin";Index.ANIMATIONS->"sequences/$id.bin"
                    Index.MODELS->"models/${asset.archive}.bin";Index.CONFIGS->"bas/${asset.file}.bin";Index.ANIMATION_SKELETONS->"bases/${asset.archive}.bin";Index.ANIMATION_FRAMES->"frames/${asset.archive}/${asset.file}.bin"
                    else->error("Unexpected live asset ${asset.index}")
                }
                val path=output.resolve("live/$relative");Files.createDirectories(path.parent);Files.write(path,asset.bytes,CREATE_NEW)
            }
            for(id in reusedModels) {
                val path=output.resolve("live/object-models/$id.bin");Files.createDirectories(path.parent)
                Files.write(path,requireNotNull(baseline.data(Index.MODELS,id)),CREATE_NEW)
            }
            InfernoAssetWriter.write(output.resolve("packed-cache"),assets)
            var unchanged=0
            FileCache(output.resolve("packed-cache").toString(),writable=false).use { result ->
                for(asset in assets)check(result.data(asset.index,asset.archive,asset.file).contentEquals(asset.bytes))
                val addresses=assets.map { Triple(it.index,it.archive,it.file) }.toSet();val groups=assets.map { it.index to it.archive }.toSet()
                for(index in baseline.indices())for(archive in baseline.archiveIds(index)) {
                    if(index to archive !in groups){check(baseline.container(index,archive).contentEquals(result.container(index,archive)));unchanged++}
                    else for(file in baseline.files(index,archive))if(Triple(index,archive,file) !in addresses)check(baseline.data(index,archive,file).contentEquals(result.data(index,archive,file)))
                }
            }
            check(beforeSource==InfernoDependencyPlan.snapshot(source) && beforeTarget==InfernoDependencyPlan.snapshot(baseline))
            fun ids(map:Map<Int,Int>)=buildJsonObject { map.toSortedMap().forEach { (a,b)->put(a.toString(),b) } }
            val report=buildJsonObject {
                put("status","experimental-zulrah-live-assets");put("packedCache","packed-cache");put("inputPreview",input.toString());put("rawFolder","live")
                put("ids",buildJsonObject { put("items",ids(itemMap));put("npcs",ids(npcMap));put("bas",ids(basMap));put("animations",ids(animation.sequenceMap));put("objects",ids(objectMap));put("models",ids(models)) })
                put("reusedObjectModels",JsonArray(reusedModels.map(::JsonPrimitive)));put("objectGeometryAndSkinningMatchSource",true);put("objectNonAnimationFieldsPreserved",true);put("animatedObjects",JsonArray(changed));put("omissions",JsonArray(omissions));put("animation",animation.report);put("addedOrReplacedAssets",assets.size);put("unchangedArchivesVerified",unchanged)
                put("editableRoundtripVerified",true);put("renderingValidated",false);put("limitations","Restores source animation for existing placed map objects. Existing ambient sounds and map icons remain unchanged; originally omitted objects are not invented.")
            }
            Files.writeString(output.resolve("zulrah-live-assets.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n",CREATE_NEW)
            println("Native live locations and Inferno pets verified: $output")
        } }
    }
}
