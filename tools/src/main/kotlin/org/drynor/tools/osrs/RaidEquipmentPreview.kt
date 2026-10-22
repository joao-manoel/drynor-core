package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.*
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.buffer.read.BufferReader
import java.nio.file.*
import world.gregs.voidps.buffer.write.*
import world.gregs.voidps.cache.definition.encoder.*

object RaidEquipmentPreview {
    val roots = setOf(51285,51282,52325,52327,52323,52481,52324,52477,52322,52326,52327,52328,50997,51043,51006,51003,51018,51021,51024,51012,51015,51000,52446,52486)
    private fun encode(block:(Writer)->Unit)=BufferWriter(1 shl 20).also(block).toArray()
    private fun modelIds(i:ItemDefinition)=listOf(i.modelId,i.primaryMaleModel,i.secondaryMaleModel,i.tertiaryMaleModel,i.primaryFemaleModel,i.secondaryFemaleModel,i.tertiaryFemaleModel,i.primaryMaleDialogueHead,i.secondaryMaleDialogueHead,i.primaryFemaleDialogueHead,i.secondaryFemaleDialogueHead).filter {it>0}
    internal fun applyOverrides(id:Int,item:ItemDefinition,nativeBas:Map<Int,Int>) {
        val source=when(id) {52481->52323;52486->52325;51285->51295;else->id}
        val p=item.params.orEmpty().mapKeys {(key,_)->if(key in 451..454)key+77 else key}.toMutableMap()
        fun stats(vararg pairs:Pair<Int,Int>) {p.putAll(pairs.toMap())}
        fun req(vararg levels:Pair<Int,Int>) {for((n,pair)in levels.withIndex()){p[749+n*2]=pair.first;p[750+n*2]=pair.second}}
        when(source) {
            50997->{stats(4 to 70,643 to 200,11 to 0,14 to 6,686 to 17);req(4 to 85)}
            51000->{stats(0 to -7,1 to -8,3 to -18,4 to 18,6 to 24,7 to 22,8 to 26,9 to 58);req(4 to 75,1 to 75)}
            51003->{stats(2 to 135,3 to -4,641 to 1470,14 to 6,686 to 10);req(0 to 75,2 to 75)}
            51006->{stats(3 to 28,6 to 3,7 to 3,8 to 20,685 to 15,14 to 4,686 to 1);req(6 to 75)}
            51012->{stats(4 to 95,14 to 6,686 to 17);req(4 to 95)}
            51015->{stats(2 to 110,5 to 141,6 to 145,7 to 145,8 to 18,9 to 148,641 to 380,14 to 6,686 to 10,687 to 1);req(0 to 75,1 to 75)}
            51018->{stats(3 to 8,4 to -2,5 to 12,6 to 13,7 to 13,8 to 5,685 to 2);req(6 to 65,1 to 75)}
            51021->{stats(3 to 35,4 to 8,5 to 42,6 to 31,7 to 51,8 to 28,685 to 2);req(6 to 65,1 to 75)}
            51024->{stats(3 to 26,4 to -7,5 to 27,6 to 24,7 to 30,8 to 20,685 to 2);req(6 to 65,1 to 75)}
            51295->{stats(0 to 4,1 to 4,2 to 4,3 to 1,4 to 1,5 to 12,6 to 12,7 to 12,8 to 12,9 to 12,641 to 80,11 to 2)}
            52322->{stats(0 to 30,1 to 29,2 to 28,3 to -5,4 to -4,5 to 30,6 to 29,7 to 28,8 to -5,9 to -4,641 to 80);req(0 to 70,1 to 70)}
            52323->{stats(3 to 25,5 to 2,6 to 3,7 to 1,8 to 15,14 to 4,686 to 28);req(6 to 75)}
            52324->{stats(0 to 94,1 to 55,641 to 890,14 to 4,686 to 5);req(0 to 75)}
                        // Current OSRS rebalance; empty and charged forms intentionally have different bonuses.
            52325->{
                stats(0 to 70,1 to 125,2 to 30,3 to -6,5 to -2,6 to 8,7 to 10,641 to 750,14 to 5,686 to 22)
                if(id==52486)stats(0 to 50,1 to 75,2 to 10,6 to 6,7 to 0,641 to 500)
                req(0 to 80,2 to 90)
            }
            52326->{stats(3 to -6,4 to -2,5 to 60,6 to 63,7 to 59,8 to -6,9 to 67,11 to 2);req(1 to 75)}
            52327->{stats(3 to -40,4 to -20,5 to 132,6 to 130,7 to 117,8 to -16,9 to 142,11 to 4);req(1 to 75)}
            52328->{stats(3 to -31,4 to -17,5 to 95,6 to 92,7 to 93,8 to -14,9 to 102,11 to 4);req(1 to 75)}
        }
        val weapon=source in setOf(50997,51003,51006,51012,51015,52323,52324,52325)
        item.wearPos=when {weapon->3;id==51285->1;id in setOf(51018,51282,52326)->0;id in setOf(51021,52327)->4;id in setOf(51024,52328)->7;id in setOf(51000,52322)->5;else->-1}
        item.wearPos2=when {source in setOf(50997,51003,51015,52325)->5;id in setOf(51018,51282,52326)->8;id in setOf(51021,52327)->6;else->-1}
        if(id==52326)item.wearPos3=11
        if(weapon)p[644]=nativeBas.getValue(source)
        if(id in setOf(52481,52486))item.options=arrayOf(null,"Wield","Check","Charge","Drop")
        if(id==52323)item.options=arrayOf(null,"Wield","Check","Charge","Uncharge")
        if(id==52325)item.options=arrayOf(null,"Wield","Check","Uncharge","Drop")
        item.exchangeable=id !in setOf(51282,51285,52322,52323,52325)
        item.params=p.takeIf {it.isNotEmpty()}
    }

    fun build(sourcePath:Path,baselinePath:Path,output:Path) {
        require(!Files.exists(output) && !output.toAbsolutePath().normalize().startsWith(baselinePath.toRealPath()))
        FileCache(sourcePath.toString(),writable=false).use {s->FileCache(baselinePath.toString(),writable=false).use {b->
            val sourceSnapshot=InfernoDependencyPlan.snapshot(s);val baselineSnapshot=InfernoDependencyPlan.snapshot(b)
            val items=sortedMapOf<Int,ItemDefinition>();val pending=java.util.TreeSet(roots)
            val omitted=sortedMapOf<Int,Map<Int,Int>>()
            while(pending.isNotEmpty()) {
                val id=pending.pollFirst();if(id in items)continue;require(items.size<128)
                val placeholders=linkedMapOf<Int,Int>()
                val item=OsrsItemConverter.decode(id,requireNotNull(s.data(Index.ITEMS,id ushr 8,id and 255)),placeholders,mutableMapOf())
                items[id]=item;if(placeholders.isNotEmpty())omitted[id]=placeholders
                pending.addAll((listOf(item.noteId,item.notedTemplateId,item.lendId,item.lendTemplateId)+item.stackIds?.toList().orEmpty()).filter {it>0 && it !in items})
            }
            for((id,name)in mapOf(30000 to "Infernal cape",20767 to "Max cape",20768 to "Max hood",20072 to "Dragon defender",6914 to "Master wand")) {
                val existing=ItemDefinition(id)
                ItemDecoder().readLoop(existing,BufferReader(requireNotNull(b.data(Index.ITEMS,id ushr 8,id and 255))))
                require(existing.name==name) {"Reused ingredient $id is ${existing.name}, expected $name"}
            }
            val taken=b.archiveIds(Index.ITEMS).flatMap {a->b.files(Index.ITEMS,a).map {(a shl 8)or it}}.toMutableSet()
            val itemMap=items.keys.associateWith {(30000..32766).first {it !in taken}.also(taken::add)}+mapOf(51295 to 30000,20767 to 20767,20768 to 20768,20072 to 20072,6914 to 6914)
            println("Equipment item mapping: "+items.map {(id,item)->"$id=${itemMap.getValue(id)}:${item.name}"}.joinToString())
            val basSources=mapOf(50997 to 2588,51003 to 1747,51006 to 2587,51012 to 2556,51015 to 1747,52323 to 2553,52324 to 2622,52325 to 2553)
            val basDefinitions=basSources.values.toSet().associateWith {id->BASDefinition(id).also {BASDecoder().readLoop(it,BufferReader(requireNotNull(s.data(Index.CONFIGS,Config.RENDER_ANIMATIONS,id))))}}
            val basFields=BASDefinition::class.java.declaredFields.filter {it.type==Int::class.javaPrimitiveType && (it.name.contains("Animation") || it.name.matches(Regex("(standTurn|walkDir|walkTurn|runDir|runTurn|teleDir|teleTurn)\\d")))}.onEach {it.isAccessible=true}
            val nativeSequenceIds=(basDefinitions.values.flatMap {bas->basFields.map {it.getInt(bas)}}+listOf(811,1203,428,2067,13049,13048,13055,419,4230,401,424)).filter {it>=0}.toSet()
            require(nativeSequenceIds.all {it<20000 && b.data(Index.ANIMATIONS,it ushr 7,it and 127)!=null})
            val spots=setOf(6539,6540,6541,6542).associateWith {OsrsSpotConverter.parse(it,requireNotNull(s.data(Index.SPOTANIMS,it ushr 8,it and 255)))}
            val animation=ZulrahAnimationAssets.plan(s,b,spots.values.mapNotNull {it.animationId.takeIf {v->v>=0}}.toSet()+setOf(20426,27516))
            val spotMap=DeterministicIds.plan(spots.keys,b.archiveIds(Index.SPOTANIMS).flatMap {a->b.files(Index.SPOTANIMS,a).map {(a shl 8)or it}}.toSet())
            val modelMap=DeterministicIds.plan((items.values.flatMap(::modelIds)+spots.values.map {it.modelId}).toSet(),b.archiveIds(Index.MODELS).toSet())
            val assets=animation.assets.toMutableList()
            val occupiedBas=b.files(Index.CONFIGS,Config.RENDER_ANIMATIONS).toMutableSet()
            val basMap=basDefinitions.keys.sorted().associateWith {(0..65534).first {it !in occupiedBas}.also(occupiedBas::add)}
            for((from,to)in basMap) {
                val bas=basDefinitions.getValue(from).copy(id=to)
                val bytes=encode {with(BASEncoder()){it.encode(bas)}};val check=BASDefinition(to);BASDecoder().readLoop(check,BufferReader(bytes));check(check==bas)
                assets.add(InfernoAssetWriter.Asset(Index.CONFIGS,Config.RENDER_ANIMATIONS,to,bytes))
            }
            val nativeBas=basSources.mapValues {basMap.getValue(it.value)}
            val infernal=ItemDefinition(30000).also {ItemDecoder().readLoop(it,BufferReader(requireNotNull(b.data(Index.ITEMS,30000 ushr 8,30000 and 255))))}
            val infernalModel=ModelDecoder.read(infernal.modelId,requireNotNull(b.data(Index.MODELS,infernal.modelId)))
            val infernalMaterial=infernalModel.faceTextures?.filter {it.toInt()!=-1}?.toSet()?.singleOrNull()
            val occupiedModels=(b.archiveIds(Index.MODELS).toSet()+modelMap.values).toMutableSet()
            for((from,to)in modelMap) {
                val model=ModelDecoder.read(to,requireNotNull(s.data(Index.MODELS,from)))
                require(model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty() && model.billboards.isNullOrEmpty()) {"Model $from footer dependencies"}
                val textures=model.faceTextures?.filter {it.toInt()!=-1}?.toSet().orEmpty()
                require(textures.all {it.toInt()==59}) {"Model $from unresolved materials $textures"}
                val converted=if(textures.isEmpty())model else model.copy(faceTextures=model.faceTextures!!.map {if(it.toInt()==59)requireNotNull(infernalMaterial) else it}.toShortArray())
                val bytes=ModelEncoder.encode(converted);check(ModelEncoder.encode(ModelDecoder.read(to,bytes)).contentEquals(bytes))
                assets.add(InfernoAssetWriter.Asset(Index.MODELS,to,0,bytes))
            }
            fun remapItem(id:Int)=if(id<0)id else itemMap.getValue(id)
            fun remapModel(id:Int)=if(id<=0)id else modelMap.getValue(id)
            val variants=mutableListOf<JsonObject>()
            val expected=buildJsonArray {for((from,item)in items) {
                item.id=itemMap.getValue(from)
                val fields=ItemDefinition::class.java.declaredFields.filter {it.type==Int::class.javaPrimitiveType && it.name in setOf("modelId","primaryMaleModel","secondaryMaleModel","tertiaryMaleModel","primaryFemaleModel","secondaryFemaleModel","tertiaryFemaleModel","primaryMaleDialogueHead","secondaryMaleDialogueHead","primaryFemaleDialogueHead","secondaryFemaleDialogueHead")}.onEach {it.isAccessible=true}
                fields.forEach {it.setInt(item,remapModel(it.getInt(item)))}
                item.noteId=remapItem(item.noteId);item.notedTemplateId=remapItem(item.notedTemplateId);item.lendId=remapItem(item.lendId);item.lendTemplateId=remapItem(item.lendTemplateId)
                item.stackIds=item.stackIds?.map {if(it==0)0 else itemMap.getValue(it)}?.toIntArray()
                if(item.notedTemplateId<0)applyOverrides(from,item,nativeBas)
                if(item.wearPos>=0) {
                    fun worn(id:Int,female:Boolean):Int {
                        if(id<0)return id
                        val target=(200000..Int.MAX_VALUE).first {it !in occupiedModels};occupiedModels.add(target)
                        val original=ModelDecoder.read(target,assets.single {it.index==Index.MODELS && it.archive==id}.bytes)
                        val xyz=when(item.wearPos) {3->intArrayOf(-10,if(female)30 else 35,-20);5->intArrayOf(if(female)15 else -5,if(female)10 else 20,-20);1->intArrayOf(0,0,20);0->intArrayOf(0,if(from==52326)-10 else -20,15);7->intArrayOf(0,5,0);else->intArrayOf(0,0,0)}
                        var converted=ZulrahItemsPreview.translateWorn(original,target,xyz[0],xyz[1],xyz[2])
                        if(item.wearPos==7)converted=converted.copy(vertexY=converted.vertexY.map {it*135/128}.toIntArray())
                        val bytes=ModelEncoder.encode(converted);check(ModelEncoder.encode(ModelDecoder.read(target,bytes)).contentEquals(bytes))
                        assets.add(InfernoAssetWriter.Asset(Index.MODELS,target,0,bytes));variants.add(buildJsonObject {put("sourceItem",from);put("female",female);put("sourceNativeModel",id);put("target",target)})
                        return target
                    }
                    item.primaryMaleModel=worn(item.primaryMaleModel,false);item.primaryFemaleModel=worn(item.primaryFemaleModel,true)
                }
                val bytes=encode {with(ItemEncoder()){it.encode(item)}};val decoded=ItemDefinition(item.id);ItemDecoder().readLoop(decoded,BufferReader(bytes));check(decoded==item)
                assets.add(InfernoAssetWriter.Asset(Index.ITEMS,item.id ushr 8,item.id and 255,bytes))
                add(buildJsonObject {put("source",from);put("target",item.id);put("name",item.name);put("wearPos",item.wearPos);put("wearPos2",item.wearPos2);put("options",JsonArray(item.options.map {it?.let(::JsonPrimitive)?:JsonNull}));put("params",buildJsonObject {item.params.orEmpty().forEach {(k,v)->put(k.toString(),JsonPrimitive(v.toString()))}})})
            }}
            for((from,to)in spotMap) {
                val spot=OsrsSpotConverter.convert(to,spots.getValue(from),modelMap,animation.sequenceMap)
                assets.add(InfernoAssetWriter.Asset(Index.SPOTANIMS,to ushr 8,to and 255,encode {with(SpotAnimEncoder()){it.encode(spot)}}))
            }
            ZulrahActorPreview.validateFreshAssets(b,assets)
            Files.createDirectories(output)
            val packed=output.resolve("packed-cache")
            Files.walk(baselinePath).use {paths->paths.forEach {path->val dest=packed.resolve(baselinePath.relativize(path));if(Files.isDirectory(path))Files.createDirectories(dest) else Files.copy(path,dest)}}
            InfernoAssetWriter.write(packed,assets)
            writeBinary(output.resolve("gear"),assets)
            exportNativeAnimations(output.resolve("gear"),b,nativeSequenceIds)
            ZulrahActorPreview.stage(output.resolve("source-package"),assets,b)
            var untouched=0
            FileCache(packed.toString(),writable=false).use {result->
                assets.forEach {check(result.data(it.index,it.archive,it.file).contentEquals(it.bytes))}
                val touched=assets.map {it.index to it.archive}.toSet()
                for(index in b.indices())for(archive in b.archiveIds(index))if(index to archive !in touched){check(b.container(index,archive).contentEquals(result.container(index,archive)));untouched++}else for(file in b.files(index,archive))check(b.data(index,archive,file).contentEquals(result.data(index,archive,file)))
            }
            check(sourceSnapshot==InfernoDependencyPlan.snapshot(s) && baselineSnapshot==InfernoDependencyPlan.snapshot(b))
            fun ids(map:Map<Int,Int>)=buildJsonObject {map.toSortedMap().forEach {(a,z)->put(a.toString(),z)}}
            Files.writeString(output.resolve("equipment-preview.json"),Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
                put("status","experimental-osrs-raid-equipment");put("packedCache","packed-cache");put("rawFolder","gear")
                put("ids",buildJsonObject {put("items",ids(itemMap));put("animations",ids(animation.sequenceMap+nativeSequenceIds.associateWith {it}));put("spots",ids(spotMap));put("models",ids(modelMap));put("bas",ids(basMap))})
                put("expectedItems",expected);put("animation",animation.report);put("wornVariants",JsonArray(variants));put("unchangedArchivesVerified",untouched);put("editableRoundtripVerified",true);put("renderingValidated",false)
                put("omittedPlaceholderLinks",buildJsonObject {omitted.forEach {(id,links)->put(id.toString(),ids(links))}})
                put("adaptations","Dinh donor BAS19999 absent: uses donor native two-handed maul BAS1747. Infernal material reused from validated cape30000. Native humanoid sequences exported unchanged for client probe. Economic/gameplay behavior is separate.")
            }))
            println("Native raid equipment verified: $output")
        }}
    }
    private fun writeBinary(root:Path,assets:List<InfernoAssetWriter.Asset>) {
        for(a in assets) {
            val name=when(a.index) {Index.ITEMS->"items/${(a.archive shl 8)or a.file}.bin";Index.MODELS->"models/${a.archive}.bin";Index.ANIMATIONS->"sequences/${(a.archive shl 7)or a.file}.bin";Index.ANIMATION_FRAMES->"frames/${a.archive}/${a.file}.bin";Index.ANIMATION_SKELETONS->"bases/${a.archive}.bin";Index.SPOTANIMS->"spots/${(a.archive shl 8)or a.file}.bin";Index.CONFIGS->"bas/${a.file}.bin";else->error("Unexpected asset ${a.index}")}
            val path=root.resolve(name);Files.createDirectories(path.parent);Files.write(path,a.bytes,StandardOpenOption.CREATE_NEW)
        }
    }
    private fun exportNativeAnimations(root:Path,cache:Cache,ids:Set<Int>) {
        val assets=mutableListOf<InfernoAssetWriter.Asset>();val frames=mutableSetOf<Pair<Int,Int>>();val bases=mutableSetOf<Int>()
        for(id in ids) {
            val bytes=requireNotNull(cache.data(Index.ANIMATIONS,id ushr 7,id and 127));val seq=AnimationDefinition(id);AnimationDecoder().readLoop(seq,BufferReader(bytes));assets.add(InfernoAssetWriter.Asset(Index.ANIMATIONS,id ushr 7,id and 127,bytes))
            frames.addAll((seq.frames?.toList().orEmpty()+seq.expressionFrames?.toList().orEmpty()).filter {it>=0}.map {(it ushr 16)to(it and 65535)})
        }
        for((archive,file)in frames) {
            val bytes=requireNotNull(cache.data(Index.ANIMATION_FRAMES,archive,file));val f=AnimationFrameDefinition((archive shl 16)or file);AnimationFrameDecoder().readLoop(f,BufferReader(bytes));bases.add(f.baseId);assets.add(InfernoAssetWriter.Asset(Index.ANIMATION_FRAMES,archive,file,bytes))
        }
        for(base in bases)assets.add(InfernoAssetWriter.Asset(Index.ANIMATION_SKELETONS,base,0,requireNotNull(cache.data(Index.ANIMATION_SKELETONS,base))))
        writeBinary(root,assets)
    }
    fun audit(sourcePath:Path,baselinePath:Path) {
        FileCache(sourcePath.toString(),writable=false).use { s -> FileCache(baselinePath.toString(),writable=false).use { b ->
            for(id in roots.sorted()) {
                val raw=s.data(Index.ITEMS,id ushr 8,id and 255) ?: continue
                val item=OsrsItemConverter.decode(id,raw,mutableMapOf(),mutableMapOf())
                println("ITEM $id ${item.name} model=${item.modelId} male=${item.primaryMaleModel} female=${item.primaryFemaleModel} note=${item.noteId}/${item.notedTemplateId} options=${item.options.toList()} params=${item.params}")
            }
            for(id in listOf(2553,2622,1747,2587,2588,2556,19999,1426)) {
                val bas=BASDefinition(id); val raw=s.data(Index.CONFIGS,Config.RENDER_ANIMATIONS,id) ?: continue
                BASDecoder().readLoop(bas,BufferReader(raw)); println("BAS $id $bas")
            }
            for(id in listOf(20767,20768,20072,6914,30000,15486,18349,18353,18357,4153,6914,11235,18359)) {
                val item=ItemDefinition(id); val raw=b.data(Index.ITEMS,id ushr 8,id and 255) ?: continue
                ItemDecoder().readLoop(item,BufferReader(raw)); println("NATIVE $id ${item.name} wear=${item.wearPos}/${item.wearPos2}/${item.wearPos3} params=${item.params}")
            }
            println("TAKEN "+b.archiveIds(Index.ITEMS).flatMap { a -> b.files(Index.ITEMS,a).map { (a shl 8) or it } }.filter { it in 30000..30100 })
        } }
    }
}

