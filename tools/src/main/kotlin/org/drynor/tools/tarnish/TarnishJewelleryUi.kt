package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.nio.file.Files
import java.nio.file.Path

/** A six-panel native 727 equivalent: donor scripts/317 widget bytes are never installed. */
object TarnishJewelleryUi {
    private const val STATUS="jewellery-ui-native-readback"
    private val titles=listOf("Ring of duelling","Games necklace","Combat bracelet","Skills necklace","Ring of wealth","Amulet of glory")
    private val items=listOf(2552,3853,11118,11105,2572,1712)
    private val families=listOf(0,1,5,4,7,3)
    private val names=listOf(
        listOf("Al Kharid Duel Arena","Castle Wars Arena","Mobilizing Armies Command Centre","Fist Of Guthix"),
        listOf("Burthorpe Games Room","Barbarian Outpost","Gamers' Grotto","Corporeal Beast"),
        listOf("Warrior's Guild","Champions Guild","Monastery","Ranging Guild"),
        listOf("Fishing Guild","Mining Guild","Crafting Guild","Cooking Guild"),
        listOf("Miscellania","Grand Exchange"),
        listOf("Edgeville","Karamja","Draynor Village","Al Kharid"))

    fun prepare(source:Path,target:Path,output:Path) {
        require(!Files.exists(output))
        val donor=TarnishCache(source).use { cache ->
            val raw=requireNotNull(cache.archive(3)[TarnishCache.hash("data")])
            buildJsonObject {
                put("archive",3);put("file","data");put("sha256",TarnishGarden.sha(raw));put("bytes",raw.size)
                put("widgetFormat","317 unsigned-short widget IDs; RuneLite group100132 exceeds the source loader address space")
                put("jewelleryTextPresent",String(raw,Charsets.ISO_8859_1).contains("jewellery",true))
            }
        }
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        val expected=mutableListOf<JsonObject>()
        var interfaceId=-1
        var varcStringFirst=-1
        var oldVarcStringCount=0
        FileCache(target.toRealPath().toString(),writable=false).use { cache ->
            interfaceId=cache.lastArchiveId(Index.INTERFACES)+1
            val oldStrings=cache.files(Index.CONFIGS,15).toSet()
            oldVarcStringCount=oldStrings.size
            varcStringFirst=(oldStrings.maxOrNull() ?: -1)+1
            require(oldStrings==(0 until varcStringFirst).toSet()) { "Varc-string group must be contiguous before appending" }
            repeat(3) { offset -> assets+=InfernoAssetWriter.Asset(Index.CONFIGS,15,varcStringFirst+offset,byteArrayOf(0)) }
            require(interfaceId in 1..65534 && cache.data(Index.INTERFACES,interfaceId,0)==null)
            val nativeText=decode(402,61,requireNotNull(cache.data(Index.INTERFACES,402,61)))
            require(nativeText.fontId>=0)
            val components=(0..66).map { id -> InterfaceComponentDefinition(id=(interfaceId shl 16) or id,
                parent=0,baseWidth=1,baseHeight=1) }.toMutableList()
            fun set(id:Int,value:InterfaceComponentDefinition) { components[id]=value.copy(id=(interfaceId shl 16) or id) }
            fun text(x:Int,y:Int,width:Int,height:Int,value:String,colour:Int=0xff981f)=InterfaceComponentDefinition(
                type=4,parent=0,basePositionX=x,basePositionY=y,baseWidth=width,baseHeight=height,
                fontId=nativeText.fontId,text=value,colour=colour,shaded=true,horizontalTextAlign=1,verticalTextAlign=1)
            set(0,InterfaceComponentDefinition(parent=-1,baseWidth=512,baseHeight=334))
            set(1,InterfaceComponentDefinition(type=3,parent=0,baseWidth=512,baseHeight=334,colour=0x2d2923,filled=true))
            set(2,text(12,4,472,28,"Jewellery Box"))
            set(3,text(482,5,24,24,"X",0xffffff).copy(name="Close",options=arrayOf("Close")))
            set(4,InterfaceComponentDefinition(type=3,parent=0,basePositionX=1,basePositionY=1,baseWidth=510,baseHeight=332,colour=0x81765b))
            for(panel in 0..5) {
                val base=(panel+1)*10
                val x=8+(panel%2)*252
                val y=38+(panel/2)*96
                set(base,InterfaceComponentDefinition(type=3,parent=0,basePositionX=x,basePositionY=y,baseWidth=244,baseHeight=92,colour=0x171612,filled=true))
                val itemId=items[panel]
                val raw=requireNotNull(cache.data(Index.ITEMS,itemId ushr 8,itemId and 255))
                val input=BufferReader(raw)
                val item=ItemDefinition(id=itemId).also { ItemDecoder().readLoop(it,input) }
                check(input.position()==raw.size && item.modelId>0 && cache.data(Index.MODELS,item.modelId)!=null)
                require(item.name.contains(titles[panel].substringBefore(" of"),true) || panel==1 || panel==2 || panel==3)
                set(base+1,InterfaceComponentDefinition(type=6,parent=0,basePositionX=x+23,basePositionY=y+43,baseWidth=38,baseHeight=36,
                    defaultMediaId=item.modelId,graphicPitch=item.graphicPitch,graphicRoll=item.graphicCameraRoll,graphicYaw=item.graphicCameraYaw,
                    graphicScale=item.graphicScale*32/38,viewportX=item.graphicTranslateX and 65535,viewportY=item.graphicTranslateY and 65535))
                set(base+2,text(x+3,y+3,80,38,titles[panel]))
                names[panel].forEachIndexed { destination,name ->
                    set(base+3+destination,text(x+86,y+2+destination*22,156,22,name,0xffffff).copy(name=name,options=arrayOf("Teleport")))
                }
                expected+=buildJsonObject {
                    put("panel",base);put("family",families[panel]);put("tier",if(panel<2)1 else if(panel<4)2 else 3)
                    put("title",titles[panel]);put("iconItem",itemId);put("iconModel",item.modelId)
                    put("destinations",JsonArray(names[panel].mapIndexed { destination,name -> buildJsonObject { put("component",base+3+destination);put("index",destination);put("name",name) } }))
                }
            }
            components.forEachIndexed { id,component ->
                val raw=BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(component) } }.toArray()
                val decoded=decode(interfaceId,id,raw)
                check(decoded.parent==component.parent && decoded.text==component.text && decoded.name==component.name)
                check(decoded.options.contentEquals(component.options) && decoded.defaultMediaId==component.defaultMediaId)
                assets+=InfernoAssetWriter.Asset(Index.INTERFACES,interfaceId,id,raw)
            }
        }
        assets.forEach { asset -> val file=output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin");Files.createDirectories(file.parent);Files.write(file,asset.bytes) }
        val manifest=buildJsonObject {
            put("status",STATUS);put("target",target.toRealPath().toString());put("interface",interfaceId)
            put("closeComponent",3);put("width",512);put("height",334);put("panels",JsonArray(expected));put("donorEvidence",donor)
            put("previousVarcStringCount",oldVarcStringCount);put("varcStringIds",JsonArray((0..2).map { JsonPrimitive(varcStringFirst+it) }));put("varcStringCount",varcStringFirst+3)
            put("adaptation","Native six-panel layout with verified native jewellery models and the 22 existing Drynor destinations. No OSRS/317 scripts are installed.")
            put("assets",JsonArray(assets.map { asset -> buildJsonObject { put("index",asset.index);put("archive",asset.archive);put("file",asset.file);put("replaceExisting",false);put("sha256",TarnishGarden.sha(asset.bytes)) } }))
        }
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),manifest))
        println("Prepared native jewellery interface $interfaceId (67 components, six panels, 22 destinations); no cache changed.")
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val manifest=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        val id=manifest.getValue("interface").jsonPrimitive.int
        val entries=manifest.getValue("assets").jsonArray.map { it.jsonObject }
        val ui=entries.filter { it.getValue("index").jsonPrimitive.int==Index.INTERFACES }
        val strings=entries.filter { it.getValue("index").jsonPrimitive.int==Index.CONFIGS }
        require(entries.size==70 && ui.size==67 && ui.map { it.getValue("file").jsonPrimitive.int }.toSet()==(0..66).toSet())
        require(ui.all { it.getValue("archive").jsonPrimitive.int==id })
        require(strings.size==3 && strings.all { it.getValue("archive").jsonPrimitive.int==15 })
        require(strings.map { it.getValue("file").jsonPrimitive.int }.toSet()==manifest.getValue("varcStringIds").jsonArray.map { it.jsonPrimitive.int }.toSet())
        require(entries.none { it.getValue("replaceExisting").jsonPrimitive.boolean })
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache ->
            check(cache.lastFileId(Index.INTERFACES,id)==66)
            for(entry in entries) {
                val file=entry.getValue("file").jsonPrimitive.int
                val index=entry.getValue("index").jsonPrimitive.int
                val archive=entry.getValue("archive").jsonPrimitive.int
                val raw=requireNotNull(cache.data(index,archive,file))
                check(TarnishGarden.sha(raw)==entry.getValue("sha256").jsonPrimitive.content)
                if(index==Index.INTERFACES) decode(id,file,raw) else check(raw.contentEquals(byteArrayOf(0)))
            }
            check(cache.files(Index.CONFIGS,15).size==manifest.getValue("varcStringCount").jsonPrimitive.int)
        }
    }

    private fun decode(group:Int,id:Int,raw:ByteArray)=InterfaceComponentDefinition(id=(group shl 16) or id).also {
        val input=BufferReader(raw);with(InterfaceDecoder()) { it.read(input) };check(input.position()==raw.size)
    }
}
