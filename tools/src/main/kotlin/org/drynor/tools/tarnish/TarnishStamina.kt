package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsByteReader
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Bounded import of ordinary stamina doses and their bank notes, excluding LMS variants. */
object TarnishStamina {
    private val doses = linkedMapOf(12625 to 4, 12627 to 3, 12629 to 2, 12631 to 1)
    private val mapping = linkedMapOf(12625 to 30300,12627 to 30301,12629 to 30302,12631 to 30303,
        12626 to 30304,12628 to 30305,12630 to 30306,12632 to 30307)
    private val json = Json { prettyPrint = true }
    internal data class Parsed(val definition: ItemDefinition, val presentation: Map<String,Int>)

    fun prepare(source: Path,target: Path,output: Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        val expected=mutableListOf<JsonObject>()
        val sourceHashes=linkedMapOf<Int,String>()
        val modelMap=linkedMapOf<Int,Int>()
        TarnishCache(source).use { donor ->
            val records=TarnishMaterials.itemRecords(donor)
            FileCache(target.toString(),writable=false).use { native ->
                val noteTemplate=readItem(native,799)
                require(noteTemplate.name=="null" || noteTemplate.name.contains("note",true)) { "Unexpected native note template 799 name ${noteTemplate.name}" }
                require(noteTemplate.modelId>0 && native.data(Index.MODELS,noteTemplate.modelId)!=null) { "Native note template is incomplete" }
                var nextModel=native.lastArchiveId(Index.MODELS)+1
                for((sourceId,targetId) in mapping) require(native.data(Index.ITEMS,targetId ushr 8,targetId and 255)==null) { "Item destination occupied: $targetId" }
                for((sourceId,targetId) in mapping) {
                    val parsed=parse(targetId,records[sourceId])
                    val item=parsed.definition
                    val dose=doses[sourceId] ?: doses.getValue(sourceId-1)
                    val noted=sourceId !in doses
                    sourceHashes[sourceId]=TarnishGarden.sha(records[sourceId])
                    if(!noted) {
                        require(item.name=="Stamina potion($dose)") { "Wrong source item: $sourceId/${item.name}" }
                        require(item.notedTemplateId==-1 && item.noteId==sourceId+1)
                        require(item.options[0]=="Drink" && item.options[3]=="Empty")
                        val sourceModel=item.modelId
                        item.modelId=modelMap.getOrPut(sourceModel) {
                            val id=nextModel++
                            val bytes=TarnishModels.nativeBytes(donor.model(sourceModel))
                            val model=ModelDecoder.read(id,bytes)
                            require((model.faceTextures ?: shortArrayOf()).none { it.toInt()>=0 }) { "Stamina model requires material mapping" }
                            check(ModelEncoder.encode(model).contentEquals(bytes))
                            assets += InfernoAssetWriter.Asset(Index.MODELS,id,0,bytes)
                            id
                        }
                    } else {
                        require(item.noteId==sourceId-1 && item.notedTemplateId==799) { "Unexpected note template/link for $sourceId" }
                    }
                    item.noteId=mapping.getValue(item.noteId)
                    val bytes=encode(item)
                    val readback=decode(targetId,bytes)
                    check(encode(readback).contentEquals(bytes)) { "Item round trip changed $sourceId" }
                    check(readback.name==item.name && readback.modelId==item.modelId && readback.noteId==item.noteId && readback.notedTemplateId==item.notedTemplateId)
                    assets += InfernoAssetWriter.Asset(Index.ITEMS,targetId ushr 8,targetId and 255,bytes)
                    expected += buildJsonObject {
                        put("source",sourceId);put("id",targetId);put("doses",dose);put("noted",noted)
                        put("name",item.name);put("displayName","Stamina potion($dose)");put("modelId",item.modelId)
                        put("noteId",item.noteId);put("notedTemplateId",item.notedTemplateId)
                        put("graphicScale",item.graphicScale);put("graphicPitch",item.graphicPitch);put("graphicCameraRoll",item.graphicCameraRoll)
                        put("graphicCameraYaw",item.graphicCameraYaw);put("graphicTranslateX",item.graphicTranslateX);put("graphicTranslateY",item.graphicTranslateY)
                        put("options",JsonArray(item.options.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                        put("sourcePresentation",buildJsonObject { parsed.presentation.forEach { (key,value)->put(key,value) } })
                    }
                }
            }
        }
        assets.forEach { asset ->
            val path=output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(path.parent);Files.write(path,asset.bytes)
        }
        val report=buildJsonObject {
            put("schemaVersion",1);put("status","stamina-doses-native-readback");put("source",source.toRealPath().toString());put("target",target.toRealPath().toString())
            put("sourceItemIds",buildJsonObject { mapping.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceModelIds",buildJsonObject { modelMap.forEach { (a,b)->put(a.toString(),b) } })
            put("sourceHashes",buildJsonObject { sourceHashes.forEach { (a,b)->put(a.toString(),b) } })
            put("expectedItems",JsonArray(expected))
            put("nativeNoteTemplate",799)
            put("presentationPolicy","Source weight, category, shift-click and bank-placeholder links are retained as evidence only. Native 727 has different fields and no OSRS bank-placeholder behavior. Drink/Empty, dose names, inventory framing, recolours and native bank notes are preserved; gameplay is supplied by the world.")
            put("assets",JsonArray(assets.map { a->buildJsonObject { put("index",a.index);put("archive",a.archive);put("file",a.file);put("sha256",TarnishGarden.sha(a.bytes)) } }))
        }
        Files.writeString(output.resolve("manifest.json"),json.encodeToString(JsonObject.serializer(),report))
        println("Prepared stamina: 4 doses, 4 native bank notes, ${modelMap.size} models; no cache changed. $output")
    }

    internal fun parse(id:Int,bytes:ByteArray):Parsed {
        val input=OsrsByteReader(bytes)
        val item=ItemDefinition(id)
        val presentation=linkedMapOf<String,Int>()
        fun signed()=input.u16().toShort().toInt()
        while(true) when(val opcode=input.u8()) {
            0->{input.requireEnd();return Parsed(item,presentation)}
            1->item.modelId=input.u16()
            2->item.name=input.string()
            4->item.graphicScale=input.u16()
            5->item.graphicPitch=input.u16()
            6->item.graphicCameraRoll=input.u16()
            7->item.graphicTranslateX=signed()
            8->item.graphicTranslateY=signed()
            11->item.stackable=1
            12->item.cost=(input.u16() shl 16) or input.u16()
            16->item.members=true
            in 30..34->item.floorOptions[opcode-30]=input.string().takeUnless { it.equals("hidden",true) }
            in 35..39->item.options[opcode-35]=input.string()
            40,41->{
                val count=input.u8();val from=ShortArray(count);val to=ShortArray(count)
                repeat(count) { from[it]=input.u16().toShort();to[it]=input.u16().toShort() }
                if(opcode==40){item.originalColours=from;item.modifiedColours=to}else{item.originalTextureColours=from;item.modifiedTextureColours=to}
            }
            42->presentation["shiftClick"]=input.u8().toByte().toInt()
            65->item.exchangeable=true
            75->presentation["weight"]=signed()
            94->presentation["category"]=input.u16()
            95->item.graphicCameraYaw=input.u16()
            97->item.noteId=input.u16()
            98->item.notedTemplateId=input.u16()
            110->item.floorScaleX=input.u16()
            111->item.floorScaleZ=input.u16()
            112->item.floorScaleY=input.u16()
            113->item.ambience=input.u8().toByte().toInt()
            114->item.diffusion=input.u8().toByte().toInt()*5
            148->presentation["bankPlaceholderId"]=input.u16()
            149->presentation["bankPlaceholderTemplateId"]=input.u16()
            249->{
                val params=linkedMapOf<Int,Any>()
                repeat(input.u8()) {
                    val type=input.u8()
                    require(type in 0..1) { "Unsupported item parameter type $type" }
                    val key=(input.u8() shl 16) or input.u16()
                    require(key !in params) { "Duplicate item parameter $key" }
                    params[key]=if(type==1)input.string() else (input.u16() shl 16) or input.u16()
                }
                item.params=params
            }
            else->error("Unsupported stamina item opcode $opcode at ${input.position-1}")
        }
    }

    private fun encode(item:ItemDefinition)=BufferWriter(65536).apply { with(ItemEncoder()){encode(item)} }.toArray()
    private fun decode(id:Int,bytes:ByteArray)=ItemDefinition(id).also { ItemDecoder().readLoop(it,BufferReader(bytes)) }
    private fun readItem(cache:Cache,id:Int)=decode(id,requireNotNull(cache.data(Index.ITEMS,id ushr 8,id and 255)))

    /** Offline canonical-cache mutation with the existing pre-garden backup and audited prior addition. */
    fun apply(output:Path,backup:Path,priorGarden:Path) {
        val manifest=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        require(manifest.getValue("status").jsonPrimitive.content=="stamina-doses-native-readback")
        val target=Path.of(manifest.getValue("target").jsonPrimitive.content).toRealPath()
        val prior=Json.parseToJsonElement(Files.readString(priorGarden.resolve("manifest.json"))).jsonObject
        require(prior.getValue("status").jsonPrimitive.content=="static-diagnostic-only")
        require(Path.of(prior.getValue("target").jsonPrimitive.content).toRealPath()==target)
        require(Files.exists(priorGarden.resolve("applied.json"))) { "Prior garden package has no completed application evidence" }
        val assets=loadAssets(output,manifest)
        val previous=loadAssets(priorGarden,prior)
        require(assets.count { it.index==Index.ITEMS }==8 && assets.all { it.index==Index.ITEMS || it.index==Index.MODELS })
        require(previous.size==14 && previous.all { it.index==Index.OBJECTS || it.index==Index.MODELS })
        val baseline=Json.parseToJsonElement(Files.readString(backup.resolve("cache-baseline.json"))).jsonArray
        baseline.forEach { record ->
            val item=record.jsonObject
            val file=backup.resolve("cache").resolve(item.getValue("name").jsonPrimitive.content)
            check(TarnishGarden.sha(Files.readAllBytes(file))==item.getValue("sha256").jsonPrimitive.content) { "Original rollback backup changed: $file" }
        }
        val previousGroups=previous.groupBy { it.index to it.archive }
        val changed=assets.map { it.index to it.archive }.toSet()
        val unchanged=linkedMapOf<Pair<Int,Int>,String>()
        val siblings=linkedMapOf<Triple<Int,Int,Int>,String>()
        FileCache(backup.resolve("cache").toString(),writable=false).use { original ->
            FileCache(target.toString(),writable=false).use { current ->
                // Only the already-verified 14 garden assets may differ from the original baseline.
                for(index in (original.indices().toSet()+current.indices().toSet())) {
                    val oldIds=original.archiveIds(index).toSet();val nowIds=current.archiveIds(index).toSet()
                    check(nowIds==oldIds+previousGroups.keys.filter { it.first==index }.map { it.second }) { "Unplanned archives in index $index" }
                    for(archive in oldIds) {
                        val additions=previousGroups[index to archive]
                        if(additions==null) {
                            check(TarnishGarden.sha(requireNotNull(original.container(index,archive)))==TarnishGarden.sha(requireNotNull(current.container(index,archive)))) { "Baseline archive changed outside garden: $index/$archive" }
                        } else {
                            val oldFiles=original.files(index,archive).toSet()
                            check(current.files(index,archive).toSet()==oldFiles+additions.map { it.file }) { "Unexpected garden siblings: $index/$archive" }
                            for(file in oldFiles) check(requireNotNull(original.data(index,archive,file)).contentEquals(requireNotNull(current.data(index,archive,file)))) { "Original garden sibling changed: $index/$archive/$file" }
                        }
                    }
                    for(archive in nowIds) {
                        if(index to archive in changed) for(file in current.files(index,archive)) siblings[Triple(index,archive,file)]=TarnishGarden.sha(requireNotNull(current.data(index,archive,file)))
                        else unchanged[index to archive]=TarnishGarden.sha(requireNotNull(current.container(index,archive)))
                    }
                }
                previous.forEach { check(requireNotNull(current.data(it.index,it.archive,it.file)).contentEquals(it.bytes)) { "Garden addition changed" } }
                assets.forEach { check(current.data(it.index,it.archive,it.file)==null) { "Stamina destination is occupied" } }
            }
        }
        fun verifyCurrentSnapshot()=FileCache(target.toString(),writable=false).use { current ->
            unchanged.forEach { (a,h)->check(TarnishGarden.sha(requireNotNull(current.container(a.first,a.second)))==h) { "Unrelated archive changed: $a" } }
            siblings.forEach { (a,h)->check(TarnishGarden.sha(requireNotNull(current.data(a.first,a.second,a.third)))==h) { "Existing sibling changed: $a" } }
        }
        try {
            InfernoAssetWriter.write(target,assets)
            FileCache(target.toString(),writable=false).use { current ->
                assets.forEach { check(requireNotNull(current.data(it.index,it.archive,it.file)).contentEquals(it.bytes)) { "Stamina readback failed" } }
            }
            verifyCurrentSnapshot()
        } catch(failure:Exception) {
            baseline.forEach { record ->
                val name=record.jsonObject.getValue("name").jsonPrimitive.content
                Files.copy(backup.resolve("cache").resolve(name),target.resolve(name),REPLACE_EXISTING)
            }
            InfernoAssetWriter.write(target,previous)
            verifyCurrentSnapshot()
            throw IllegalStateException("Stamina import failed; original cache plus verified garden additions restored",failure)
        }
        Files.writeString(output.resolve("applied.json"),buildJsonObject {
            put("status","stamina-doses-applied-native-readback");put("newAssets",assets.size)
            put("unchangedArchives",unchanged.size);put("preservedSiblingFiles",siblings.size)
            put("backup",backup.toRealPath().toString());put("priorGarden",priorGarden.toRealPath().toString())
        }.toString())
        println("Stamina applied: ${assets.size} new assets; ${unchanged.size} unrelated archives and ${siblings.size} sibling files preserved.")
    }

    private fun loadAssets(root:Path,manifest:JsonObject):List<InfernoAssetWriter.Asset> = manifest.getValue("assets").jsonArray.map { value ->
        val entry=value.jsonObject
        val index=entry.getValue("index").jsonPrimitive.int;val archive=entry.getValue("archive").jsonPrimitive.int;val file=entry.getValue("file").jsonPrimitive.int
        require(index in 0..254 && archive>=0 && file>=0)
        val bytes=Files.readAllBytes(root.resolve("assets/$index/$archive/$file.bin"))
        check(TarnishGarden.sha(bytes)==entry.getValue("sha256").jsonPrimitive.content) { "Staged asset changed: $index/$archive/$file" }
        InfernoAssetWriter.Asset(index,archive,file,bytes)
    }.also { require(it.map { a->Triple(a.index,a.archive,a.file) }.distinct().size==it.size) }
}
