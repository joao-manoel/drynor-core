package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.cs2.Cs2Codec
import org.drynor.cs2.Cs2Op
import org.drynor.cs2.Cs2Script
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Adds the level-80 native room row without moving or renumbering any existing room. */
object TarnishGalleryRoomUi {
    private const val STATUS="gallery-room-ui-native-readback"
    private const val INTERFACE=402
    private const val OLD_HEIGHT=1468
    private const val NEW_HEIGHT=1532
    private const val ROW_Y=1467

    internal fun patchScroll(script:Cs2Script):Cs2Script {
        require(script.instructions.size==14)
        require(script.instructions[0].op==Cs2Op.PUSH_CONSTANT_INT && script.instructions[0].intOperand==332)
        require(script.instructions[1].op==Cs2Op.PUSH_CONSTANT_INT && script.instructions[1].intOperand==OLD_HEIGHT)
        require(script.instructions[2].op==Cs2Op.PUSH_CONSTANT_INT && script.instructions[2].intOperand==((INTERFACE shl 16) or 12))
        require(script.instructions[3].op==Cs2Op.IF_SETSCROLLSIZE)
        val instructions=script.instructions.toMutableList().also { it[1]=it[1].copy(intOperand=NEW_HEIGHT) }
        return Cs2Script(script.name,instructions,script.intLocalsCount,script.stringLocalsCount,script.longLocalsCount,
            script.intArgsCount,script.stringArgsCount,script.longArgsCount,script.switchTables)
    }

    fun prepare(target:Path,galleryPackage:Path,output:Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        var oldScriptHash=""
        val gallery=Json.parseToJsonElement(Files.readString(galleryPackage.resolve("manifest.json"))).jsonObject
        require(gallery.getValue("status").jsonPrimitive.content=="gallery-native-readback")
        require(Path.of(gallery.getValue("target").jsonPrimitive.content).toRealPath()==target.toRealPath())
        val roomCard=gallery.getValue("expectedItems").jsonArray.map { it.jsonObject }.single { it.getValue("source").jsonPrimitive.int==20654 }
        require(roomCard.getValue("name").jsonPrimitive.content.equals("Achievement gallery",true))
        val iconModel=roomCard.getValue("modelId").jsonPrimitive.int
        require(iconModel>0)
        val modelAsset=gallery.getValue("assets").jsonArray.map { it.jsonObject }.singleOrNull {
            it.getValue("index").jsonPrimitive.int==Index.MODELS && it.getValue("archive").jsonPrimitive.int==iconModel && it.getValue("file").jsonPrimitive.int==0
        }
        if(modelAsset!=null) {
            val model=Files.readAllBytes(galleryPackage.resolve("assets/${Index.MODELS}/$iconModel/0.bin"))
            check(TarnishGarden.sha(model)==modelAsset.getValue("sha256").jsonPrimitive.content)
        }
        val expected=mutableListOf<JsonObject>()
        FileCache(target.toRealPath().toString(),writable=false).use { cache ->
            require(cache.lastFileId(Index.INTERFACES,INTERFACE)==121) { "Expected the audited 122-component native room menu" }
            val old=(0..121).associateWith { id->decode(id,requireNotNull(cache.data(Index.INTERFACES,INTERFACE,id))) }
            val rows=old.filterValues { it.parent==12 && it.options?.contains("Build")==true }
            val last=rows.maxBy { it.value.basePositionY }
            require(last.value.name.equals("Treasure room",true) && last.value.basePositionY==1403)
            require(old.getValue(118).name=="Superior garden" && old.getValue(119).text=="Superior garden: Lvl 65")
            require(old.getValue(117).onLoadScript?.script==2686)
            if(modelAsset==null) require(cache.data(Index.MODELS,iconModel)!=null)
            val sourceRow=old.getValue(118)
            require(old.getValue(121).baseWidth>0)
            fun shifted(id:Int,newId:Int)=old.getValue(id).copy(id=(INTERFACE shl 16) or newId,
                basePositionY=ROW_Y+old.getValue(id).basePositionY-sourceRow.basePositionY)
            val added=listOf(
                shifted(118,122).copy(name="Achievement gallery"),
                shifted(119,123).copy(text="Achievement gallery: Lvl 80"),
                shifted(120,124).copy(text="200000 coins"),
                shifted(121,125).copy(defaultMediaId=iconModel,centreType=false,viewportZ=0,
                    // The packed definition codec stores the raw unsigned word; the client reads it signed.
                    viewportX=roomCard.getValue("graphicTranslateX").jsonPrimitive.int and 65535,
                    viewportY=roomCard.getValue("graphicTranslateY").jsonPrimitive.int and 65535,
                    graphicPitch=roomCard.getValue("graphicPitch").jsonPrimitive.int,
                    graphicRoll=roomCard.getValue("graphicCameraRoll").jsonPrimitive.int,
                    graphicYaw=roomCard.getValue("graphicCameraYaw").jsonPrimitive.int,
                    graphicScale=roomCard.getValue("graphicScale").jsonPrimitive.int*32/old.getValue(121).baseWidth),
            )
            added.forEach { component ->
                require(component.parent==12 && component.basePositionY>=ROW_Y && component.basePositionY+component.baseHeight<=NEW_HEIGHT)
                val bytes=encode(component)
                val read=decode(component.id and 65535,bytes)
                check(read==component) { "Component ${component.id and 65535} roundtrip differs: expected=$component actual=$read" }
                assets+=InfernoAssetWriter.Asset(Index.INTERFACES,INTERFACE,component.id and 65535,bytes)
                expected+=buildJsonObject {
                    put("file",component.id and 65535);put("type",component.type);put("y",component.basePositionY)
                    put("name",component.name);put("text",component.text);put("modelId",component.defaultMediaId)
                    put("pitch",component.graphicPitch);put("roll",component.graphicRoll);put("yaw",component.graphicYaw)
                    put("scale",component.graphicScale);put("offsetX",component.viewportX);put("offsetY",component.viewportY)
                }
            }
            val raw=requireNotNull(cache.data(Index.CLIENT_SCRIPTS,2686,0))
            oldScriptHash=TarnishGarden.sha(raw)
            val script=Cs2Codec.decode(raw)
            check(Cs2Codec.encode(script).contentEquals(raw))
            val patched=patchScroll(script)
            val bytes=Cs2Codec.encode(patched)
            check(Cs2Codec.decode(bytes).instructions==patched.instructions)
            assets+=InfernoAssetWriter.Asset(Index.CLIENT_SCRIPTS,2686,0,bytes,replaceExisting=true)
        }
        assets.forEach { asset ->
            val file=output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(file.parent);Files.write(file,asset.bytes)
        }
        val report=buildJsonObject {
            put("schemaVersion",1);put("status",STATUS);put("target",target.toRealPath().toString())
            put("interface",INTERFACE);put("selectComponent",122);put("titleComponent",123);put("priceComponent",124)
            put("roomLevel",80);put("roomPrice",200000);put("scrollHeight",NEW_HEIGHT)
            put("expectedComponents",JsonArray(expected))
            put("iconPolicy","Actual Tarnish Achievement gallery room card 20654, with imported model and source item framing scaled to the native room thumbnail.")
            put("galleryPackage",galleryPackage.toRealPath().toString());put("iconSourceItem",20654)
            put("iconItemId",roomCard.getValue("id").jsonPrimitive.int);put("iconModelId",iconModel)
            put("assets",JsonArray(assets.map { asset->buildJsonObject {
                put("index",asset.index);put("archive",asset.archive);put("file",asset.file)
                put("replaceExisting",asset.replaceExisting);put("sha256",TarnishGarden.sha(asset.bytes))
                if(asset.replaceExisting) put("previousSha256",oldScriptHash)
            } }))
        }
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Prepared native Achievement Gallery row 402:122..125 and scroll 2686; existing rows untouched. No cache changed.")
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val report=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        val entries=report.getValue("assets").jsonArray.map { it.jsonObject }
        require(entries.size==5)
        require(entries.map { Triple(it.getValue("index").jsonPrimitive.int,it.getValue("archive").jsonPrimitive.int,it.getValue("file").jsonPrimitive.int) }.toSet()==
            (122..125).map { Triple(Index.INTERFACES,INTERFACE,it) }.toSet()+Triple(Index.CLIENT_SCRIPTS,2686,0))
        require(entries.filter { it.getValue("replaceExisting").jsonPrimitive.boolean }.single().getValue("index").jsonPrimitive.int==Index.CLIENT_SCRIPTS)
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache ->
            require(cache.lastFileId(Index.INTERFACES,INTERFACE)==125)
            report.getValue("expectedComponents").jsonArray.forEach { value ->
                val expected=value.jsonObject
                val actual=decode(expected.getValue("file").jsonPrimitive.int,requireNotNull(cache.data(Index.INTERFACES,INTERFACE,expected.getValue("file").jsonPrimitive.int)))
                check(actual.parent==12 && actual.basePositionY==expected.getValue("y").jsonPrimitive.int)
                check(actual.name==expected.getValue("name").jsonPrimitive.content && actual.text==expected.getValue("text").jsonPrimitive.content)
                check(actual.type==expected.getValue("type").jsonPrimitive.int)
                check(actual.defaultMediaId==expected.getValue("modelId").jsonPrimitive.int)
                check(actual.graphicPitch==expected.getValue("pitch").jsonPrimitive.int)
                check(actual.graphicRoll==expected.getValue("roll").jsonPrimitive.int)
                check(actual.graphicYaw==expected.getValue("yaw").jsonPrimitive.int)
                check(actual.graphicScale==expected.getValue("scale").jsonPrimitive.int)
                check(actual.viewportX==expected.getValue("offsetX").jsonPrimitive.int)
                check(actual.viewportY==expected.getValue("offsetY").jsonPrimitive.int)
            }
            val scroll=Cs2Codec.decode(requireNotNull(cache.data(Index.CLIENT_SCRIPTS,2686,0)))
            check(scroll.instructions[1].intOperand==NEW_HEIGHT)
        }
    }

    private fun decode(id:Int,bytes:ByteArray):InterfaceComponentDefinition {
        val input=BufferReader(bytes)
        return InterfaceComponentDefinition(id=(INTERFACE shl 16) or id).also {
            with(InterfaceDecoder()) { it.read(input) }
            check(input.position()==bytes.size)
        }
    }
    private fun encode(component:InterfaceComponentDefinition)=BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(component) } }.toArray()
}
