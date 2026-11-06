package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.nio.file.Files
import java.nio.file.Path

/** Bounded gallery furniture import. Source selectors are resolved; transport and spellbooks belong to the world. */
object TarnishGallery {
    private const val STATUS="gallery-native-readback"
    internal data class Card(val source:Int,val target:Int,val name:String,val level:Int,val materials:List<Pair<Int,Int>>,val text:String)
    internal fun cardParams(card:Card):Map<Int,Any> = buildMap {
        put(23,card.level);put(2522,card.text)
        card.materials.forEachIndexed { i,p -> put(211+i*2,p.first);put(212+i*2,p.second) }
    }
    private val selectedObjects=linkedMapOf(29140 to 120013,29142 to 120014,29147 to 120015,29148 to 120016,
        31858 to 120017,37492 to 120018,37501 to 120019,37520 to 120020)

    fun prepare(source:Path,target:Path,output:Path) {
        require(!Files.exists(output)) { "Output exists: $output" }
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        val models=linkedMapOf<Int,Int>()
        val itemIds=linkedMapOf<Int,Int>()
        val sourceHashes=sortedMapOf<String,String>()
        val expectedObjects=mutableListOf<JsonObject>()
        val expectedItems=mutableListOf<JsonObject>()
        val nativeReuse=mutableListOf<JsonObject>()
        val animations=linkedMapOf<Int,Int>()
        val groups=linkedMapOf<Int,Int>()
        val bases=linkedMapOf<Int,Int>()
        val sourceMap=selectedObjects+mapOf(29150 to 120017,29154 to 120018,29155 to 120019,29156 to 120020)
        TarnishCache(source).use { donor ->
            val objects=donor.objects()
            val sourceItems=TarnishMaterials.itemRecords(donor)
            FileCache(target.toString(),writable=false).use { native ->
                selectedObjects.values.forEach { require(native.data(Index.OBJECTS,it ushr 8,it and 255)==null) { "Object occupied: $it" } }
                var nextModel=native.lastArchiveId(Index.MODELS)+1
                fun addModel(sourceId:Int):Int = models.getOrPut(sourceId) {
                    val raw=donor.model(sourceId)
                    sourceHashes["model/$sourceId"]=TarnishGarden.sha(raw)
                    val bytes=TarnishLegacyModels.nativeBytes(raw)
                    val id=nextModel++
                    val model=ModelDecoder.read(id,bytes)
                    require(model.faceTextures?.none { it.toInt()>=0 }!=false) { "Gallery model $sourceId requires material mapping" }
                    check(ModelEncoder.encode(model).contentEquals(bytes))
                    assets+=InfernoAssetWriter.Asset(Index.MODELS,id,0,bytes)
                    id
                }
                importAnimations(donor,native,assets,sourceHashes,animations,groups,bases)
                for((sourceId,id) in selectedObjects) {
                    val raw=objects[sourceId]
                    sourceHashes["object/$sourceId"]=TarnishGarden.sha(raw)
                    val dependencies=OsrsObjectDependencies.parse(raw)
                    require(dependencies.morphs.isEmpty() && dependencies.textures.isEmpty() && dependencies.sounds.isEmpty() && dependencies.sprites.isEmpty() && dependencies.mapIcons.isEmpty())
                    dependencies.models.forEach(::addModel)
                    val obj=TarnishObjectConverter.convert(id,raw,models,animations,sourceMap)
                    obj.options=arrayOfNulls(6)
                    obj.rawOptions=null
                    obj.options!![5]="Examine"
                    when(sourceId) {
                        29140,29142 -> obj.options!![4]="Build"
                        29147,29148,31858 -> {
                            obj.options!![0]="Venerate";obj.options!![1]="Switch-spellbook";obj.options!![4]="Remove"
                            if(sourceId!=31858)obj.options!![3]="Upgrade"
                            if(sourceId==31858)obj.singleAnimation=animations.getValue(7303)
                        }
                        else -> {
                            obj.options!![0]="Teleport-menu";obj.options!![1]="Last-destination";obj.options!![4]="Remove"
                            if(sourceId!=37520)obj.options!![3]="Upgrade"
                        }
                    }
                    val bytes=encode { with(ObjectEncoder()) { encode(obj) } }
                    val read=ObjectDefinition(id).also { ObjectDecoder().readLoop(it,BufferReader(bytes)) }
                    check(read.name==obj.name && read.options.contentEquals(obj.options) && read.modelIds.contentDeepEquals(obj.modelIds))
                    check(read.singleAnimation==obj.singleAnimation && read.sizeX==obj.sizeX && read.sizeY==obj.sizeY)
                    assets+=InfernoAssetWriter.Asset(Index.OBJECTS,id ushr 8,id and 255,bytes)
                    expectedObjects+=buildJsonObject {
                        put("source",sourceId);put("id",id);put("name",obj.name);put("sizeX",obj.sizeX);put("sizeY",obj.sizeY);put("solid",obj.solid)
                        put("animation",obj.singleAnimation);put("modelIds",JsonArray(obj.modelIds!!.flatMap { it.toList() }.map(::JsonPrimitive)))
                        put("options",JsonArray(obj.options!!.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                    }
                }
                // Native 727 has no signets for these OSRS altars. Reuse exact ordinary names if a later cache supplies them.
                val nativeItems=linkedMapOf<Int,ItemDefinition>()
                for(archive in native.archiveIds(Index.ITEMS)) for(file in native.files(Index.ITEMS,archive)) {
                    val id=(archive shl 8) or file
                    val item=readItem(id,requireNotNull(native.data(Index.ITEMS,archive,file)))
                    if(item.notedTemplateId==-1)nativeItems[id]=item
                }
                var nextMaterial=30330
                for((sourceId,name) in listOf(20611 to "Ancient signet",20613 to "Lunar signet")) {
                    val reused=nativeItems.values.singleOrNull { it.name.equals(name,true) }
                    if(reused!=null) {
                        itemIds[sourceId]=reused.id
                        nativeReuse+=buildJsonObject { put("source",sourceId);put("id",reused.id);put("name",reused.name) }
                    } else {
                        while(native.data(Index.ITEMS,nextMaterial ushr 8,nextMaterial and 255)!=null)nextMaterial++
                        val id=nextMaterial++
                        val item=TarnishStamina.parse(id,sourceItems[sourceId]).definition
                        require(item.name==name && item.noteId==-1 && item.notedTemplateId==-1)
                        item.modelId=addModel(item.modelId)
                        item.params=null
                        itemIds[sourceId]=id
                        sourceHashes["item/$sourceId"]=TarnishGarden.sha(sourceItems[sourceId])
                        stageItem(item,sourceId,null,assets,expectedItems)
                    }
                }
                val ancient=itemIds.getValue(20611);val lunar=itemIds.getValue(20613)
                val cards=listOf(
                    Card(20617,30322,"Ancient altar",80,listOf(3420 to 10,8788 to 1,ancient to 1,9044 to 1),"10 limestone bricks, magic stone, Ancient signet, Pharaoh's sceptre (any charge)"),
                    Card(20618,30323,"Lunar altar",80,listOf(3420 to 10,8788 to 1,lunar to 1,9075 to 10000),"10 limestone bricks, magic stone, Lunar signet, 10000 astral runes"),
                    Card(20620,30324,"Occult altar",90,listOf(lunar to 1,9075 to 10000),"Upgrade Ancient altar: Lunar signet, 10000 astral runes"),
                    Card(20621,30325,"Occult altar",90,listOf(ancient to 1,9044 to 1),"Upgrade Lunar altar: Ancient signet, Pharaoh's sceptre (any charge)"),
                    Card(20626,30326,"Basic jewellery box",81,listOf(8790 to 1,2353 to 1,3853 to 3,2552 to 3),"Bolt of cloth, steel bar, 3 games necklaces (8), 3 rings of duelling (8)"),
                    Card(20627,30327,"Fancy jewellery box",86,listOf(8784 to 1,11105 to 5,11118 to 5),"Basic box; gold leaf, 5 skills necklaces (4), 5 combat bracelets (4)"),
                    Card(20628,30328,"Ornate jewellery box",91,listOf(8784 to 2,1712 to 8,20659 to 8),"Fancy box; 2 gold leaves, 8 amulets of glory (4), 8 fully charged rings of wealth (4)"),
                    Card(20654,30329,"Achievement gallery",80,emptyList(),"200000 coins")
                )
                for(card in cards) {
                    require(native.data(Index.ITEMS,card.target ushr 8,card.target and 255)==null) { "Card occupied: ${card.target}" }
                    val item=TarnishStamina.parse(card.target,sourceItems[card.source]).definition
                    require(item.name==card.name && item.noteId==-1 && item.notedTemplateId==-1)
                    item.modelId=addModel(item.modelId)
                    item.params=cardParams(card)
                    sourceHashes["item/${card.source}"]=TarnishGarden.sha(sourceItems[card.source])
                    itemIds[card.source]=card.target
                    stageItem(item,card.source,card,assets,expectedItems)
                }
                for(id in listOf(3420,8788,9044,9046,9048,9075,8790,2353,3853,2552,8784,11105,11118,1712,20659)) {
                    val item=nativeItems[id] ?: error("Missing native gallery material $id")
                    nativeReuse+=buildJsonObject { put("id",id);put("name",item.name);put("modelId",item.modelId);put("options",JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull })) }
                }
                assets.forEach { require(native.data(it.index,it.archive,it.file)==null) { "Occupied gallery destination ${it.index}/${it.archive}/${it.file}" } }
            }
            listOf(29150,29154,29155,29156).forEach { sourceHashes["selector/$it"]=TarnishGarden.sha(objects[it]) }
        }
        assets.forEach { a-> val path=output.resolve("assets/${a.index}/${a.archive}/${a.file}.bin");Files.createDirectories(path.parent);Files.write(path,a.bytes) }
        val manifest=buildJsonObject {
            put("schemaVersion",1);put("status",STATUS);put("source",source.toRealPath().toString());put("target",target.toRealPath().toString())
            put("sourceObjectIds",buildJsonObject { sourceMap.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceItemIds",buildJsonObject { itemIds.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceModelIds",buildJsonObject { models.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceAnimationIds",buildJsonObject { animations.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceFrameGroupIds",buildJsonObject { groups.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceBaseIds",buildJsonObject { bases.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceHashes",buildJsonObject { sourceHashes.forEach { (a,b)->put(a,b) } })
            put("expectedObjects",JsonArray(expectedObjects));put("expectedItems",JsonArray(expectedItems));put("nativeReuse",JsonArray(nativeReuse))
            put("hotspots",buildJsonObject {
                put("sourceChunkX",241);put("sourceChunkY",720);put("sourceRegion",7770);put("sourceLandscapeArchive",527)
                put("altar",buildJsonObject { put("x",6);put("y",3);put("rotation",1) })
                put("jewellery",buildJsonObject { put("x",6);put("y",1);put("rotation",1) })
            })
            put("adaptations","Drynor owns selector menus and last destination; donor vars are not copied. Only Standard/Ancient/Lunar spellbooks are supported; no Dark altar or Arceuus requirements. Native fully charged wealth rings have four charges. Source hotspot coordinates are preserved in the native hall room adaptation.")
            put("omissions",JsonArray(emptyList()))
            put("assets",JsonArray(assets.map { a->buildJsonObject { put("index",a.index);put("archive",a.archive);put("file",a.file);put("sha256",TarnishGarden.sha(a.bytes)) } }))
        }
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),manifest))
        println("Prepared Achievement Gallery: ${assets.size} assets, 8 objects, ${expectedItems.size} items; no cache changed.")
    }

    private fun stageItem(item:ItemDefinition,source:Int,card:Card?,assets:MutableList<InfernoAssetWriter.Asset>,expected:MutableList<JsonObject>) {
        val bytes=encode { with(ItemEncoder()) { encode(item) } }
        val read=readItem(item.id,bytes)
        check(read.name==item.name && read.params==item.params && read.modelId==item.modelId)
        check(encode { with(ItemEncoder()) { encode(read) } }.contentEquals(bytes))
        assets+=InfernoAssetWriter.Asset(Index.ITEMS,item.id ushr 8,item.id and 255,bytes)
        expected+=buildJsonObject {
            put("source",source);put("id",item.id);put("name",item.name);put("modelId",item.modelId);put("noteId",item.noteId);put("notedTemplateId",item.notedTemplateId)
            put("constructionLevel",card?.level ?: 0);put("requirements",card?.text ?: "")
            put("graphicScale",item.graphicScale);put("graphicPitch",item.graphicPitch);put("graphicCameraRoll",item.graphicCameraRoll);put("graphicCameraYaw",item.graphicCameraYaw)
            put("graphicTranslateX",item.graphicTranslateX);put("graphicTranslateY",item.graphicTranslateY)
            put("materials",JsonArray(card?.materials.orEmpty().map { (id,count)->buildJsonObject { put("id",id);put("count",count) } }))
            put("options",JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull }))
        }
    }

    private fun importAnimations(donor:TarnishCache,native:Cache,assets:MutableList<InfernoAssetWriter.Asset>,hashes:MutableMap<String,String>,animations:MutableMap<Int,Int>,groups:MutableMap<Int,Int>,bases:MutableMap<Int,Int>) {
        val archive=donor.archive(2)
        val rawSequences=TarnishAnimations.records(archive.getValue(TarnishCache.hash("seq.dat")),true)
        val sourceSequences=listOf(7302,7303).associateWith { id ->
            val raw=rawSequences.getValue(id);hashes["sequence/$id"]=TarnishGarden.sha(raw);TarnishAnimations.sequence(raw)
        }
        require(sourceSequences.values.flatMap { it.frames.toList() }.map { it ushr 16 }.toSet()==setOf(1858))
        val sourceFrames=TarnishAnimations.frames(donor,1858)
        val required=sourceSequences.values.flatMap { it.frames.toList() }.map { it and 65535 }.toSet()
        require(required==sourceFrames.keys)
        val sourceBases=TarnishAnimations.records(archive.getValue(TarnishCache.hash("framebases.dat")))
        val usedBases=sourceFrames.values.map { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }.toSet()
        require(usedBases==setOf(1652))
        val group=native.lastArchiveId(Index.ANIMATION_FRAMES)+1;groups[1858]=group
        val baseId=native.lastArchiveId(Index.ANIMATION_SKELETONS)+1;bases[1652]=baseId
        require(group<=32767 && baseId<=65535)
        val rawBase=sourceBases.getValue(1652);hashes["base/1652"]=TarnishGarden.sha(rawBase)
        val base=TarnishAnimations.convertBase(baseId,rawBase)
        val baseBytes=encode { with(AnimationBaseEncoder()) { encode(base) } }
        val baseRead=AnimationBaseDefinition(baseId).also { AnimationBaseDecoder().readLoop(it,BufferReader(baseBytes)) }
        check(encode { with(AnimationBaseEncoder()) { encode(baseRead) } }.contentEquals(baseBytes))
        assets+=InfernoAssetWriter.Asset(Index.ANIMATION_SKELETONS,baseId,0,baseBytes)
        for((file,raw) in sourceFrames) {
            hashes["frame/1858/$file"]=TarnishGarden.sha(raw)
            val frame=TarnishAnimations.convertFrame((group shl 16) or file,raw,base)
            val bytes=encode { with(AnimationFrameEncoder()) { encode(frame) } }
            val read=AnimationFrameDefinition(frame.id).also { AnimationFrameDecoder().readLoop(it,BufferReader(bytes)) }
            check(encode { with(AnimationFrameEncoder()) { encode(read) } }.contentEquals(bytes))
            assets+=InfernoAssetWriter.Asset(Index.ANIMATION_FRAMES,group,file,bytes)
        }
        val last=native.lastArchiveId(Index.ANIMATIONS)
        var next=(last shl 7)+native.lastFileId(Index.ANIMATIONS,last)+1
        for((source,sequence) in sourceSequences) {
            val id=next++;require(id<=65534);animations[source]=id
            val converted=TarnishAnimations.convertSequence(id,sequence,groups)
            val bytes=encode { with(AnimationEncoder()) { encode(converted) } }
            val read=AnimationDefinition(id).also { AnimationDecoder().readLoop(it,BufferReader(bytes)) }
            check(read.frames.contentEquals(converted.frames) && read.durations.contentEquals(converted.durations))
            assets+=InfernoAssetWriter.Asset(Index.ANIMATIONS,id ushr 7,id and 127,bytes)
        }
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val report=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        require(report.getValue("assets").jsonArray.all { it.jsonObject.getValue("index").jsonPrimitive.int in setOf(0,1,7,16,19,20) })
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache ->
            report.getValue("expectedObjects").jsonArray.forEach { value ->
                val entry=value.jsonObject;val id=entry.getValue("id").jsonPrimitive.int
                val obj=ObjectDefinition(id).also { ObjectDecoder().readLoop(it,BufferReader(requireNotNull(cache.data(Index.OBJECTS,id ushr 8,id and 255)))) }
                check(obj.name==entry.getValue("name").jsonPrimitive.content)
            }
            report.getValue("expectedItems").jsonArray.forEach { value ->
                val entry=value.jsonObject;val id=entry.getValue("id").jsonPrimitive.int
                check(readItem(id,requireNotNull(cache.data(Index.ITEMS,id ushr 8,id and 255))).modelId==entry.getValue("modelId").jsonPrimitive.int)
            }
        }
    }
    private fun readItem(id:Int,bytes:ByteArray)=ItemDefinition(id).also { ItemDecoder().readLoop(it,BufferReader(bytes)) }
    private fun encode(block:BufferWriter.()->Unit)=BufferWriter(1 shl 20).apply(block).toArray()
}
