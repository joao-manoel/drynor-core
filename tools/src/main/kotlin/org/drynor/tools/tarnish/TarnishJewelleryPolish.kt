package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
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

object TarnishJewelleryPolish {
    private const val STATUS="jewellery-polish-native-readback"
    fun prepare(target:Path,previous:Path,output:Path) {
        require(Files.isRegularFile(previous.resolve("applied.json")))
        if(Files.exists(output)) {
            require(!Files.exists(output.resolve("applied.json")))
            val staged=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
            require(staged.getValue("status").jsonPrimitive.content==STATUS && Path.of(staged.getValue("target").jsonPrimitive.content).toRealPath()==target.toRealPath())
            staged.getValue("assets").jsonArray.forEach { value ->
                val a=value.jsonObject
                check(TarnishGarden.sha(Files.readAllBytes(output.resolve("assets/3/3074/${a.getValue("file").jsonPrimitive.int}.bin")))==a.getValue("sha256").jsonPrimitive.content)
            }
        }
        val old=Json.parseToJsonElement(Files.readString(previous.resolve("manifest.json"))).jsonObject
        require(old.getValue("interface").jsonPrimitive.int==3074)
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        val previousHashes=linkedMapOf<Int,String>()
        val panels=mutableListOf<JsonObject>()
        val frameSprites=mutableListOf<Int>()
        FileCache(target.toString(),writable=false).use { cache ->
            require(cache.lastFileId(Index.INTERFACES,3074)==66)
            require(cache.archiveId(Index.FONT_METRICS,"p11_full")==494)
            val font=requireNotNull(cache.data(Index.FONT_METRICS,494,0))
            Files.createDirectories(output.resolve("evidence"));Files.write(output.resolve("evidence/p11_full.bin"),font)
            val components=(0..66).map { id ->
                val raw=requireNotNull(cache.data(Index.INTERFACES,3074,id));previousHashes[id]=TarnishGarden.sha(raw)
                decode(3074,id,raw)
            }.toMutableList()
            fun set(id:Int,c:InterfaceComponentDefinition) { components[id]=c.copy(id=(3074 shl 16) or id) }
            set(1,InterfaceComponentDefinition(parent=0,baseWidth=512,baseHeight=334))
            set(2,components[2].copy(basePositionX=32,basePositionY=5,baseWidth=448,baseHeight=24,fontId=496))
            set(3,InterfaceComponentDefinition(type=5,parent=0,basePositionX=480,basePositionY=8,baseWidth=16,baseHeight=16,
                defaultImage=831,name="Close",options=arrayOf("Close")))
            set(4,InterfaceComponentDefinition(type=3,parent=1,baseWidth=512,baseHeight=334,colour=0x29251e,filled=true))
            old.getValue("panels").jsonArray.forEachIndexed { position,value ->
                val panel=value.jsonObject
                val base=panel.getValue("panel").jsonPrimitive.int
                val x=34+(position%2)*224;val y=37+(position/2)*88
                val label=listOf("Ring of<br>duelling","Games<br>necklace","Combat<br>bracelet","Skills<br>necklace","Ring of<br>wealth","Amulet of<br>glory")[position]
                set(base,components[base].copy(basePositionX=x,basePositionY=y,baseWidth=220,baseHeight=86,colour=0x39352f))
                set(listOf(5,6,7,8,9,57)[position],InterfaceComponentDefinition(type=3,parent=0,basePositionX=x-1,basePositionY=y-1,
                    baseWidth=222,baseHeight=88,colour=0x78694f,filled=false))
                set(base+1,InterfaceComponentDefinition(type=5,parent=0,basePositionX=x+23,basePositionY=y+8,baseWidth=36,baseHeight=32,defaultImage=-1))
                set(base+2,components[base+2].copy(basePositionX=x+2,basePositionY=y+46,baseWidth=78,baseHeight=30,
                    fontId=494,colour=0xffffff,lineHeight=11,text=label))
                val destinations=panel.getValue("destinations").jsonArray.map { d ->
                    val entry=d.jsonObject;val id=entry.getValue("component").jsonPrimitive.int
                    val name=entry.getValue("name").jsonPrimitive.content
                    val display=if(name=="Mobilizing Armies Command Centre")"Mobilizing Armies" else name
                    set(id,components[id].copy(basePositionX=x+83,basePositionY=y+8+entry.getValue("index").jsonPrimitive.int*18,
                        baseWidth=135,baseHeight=18,fontId=494,colour=0xff981f,text=display,lineHeight=11,horizontalTextAlign=0))
                    JsonObject(entry+mapOf("displayName" to JsonPrimitive(display)))
                }
                panels+=JsonObject(panel+mapOf("destinations" to JsonArray(destinations),"iconType" to JsonPrimitive(5),"labelText" to JsonPrimitive(label)))
            }
            // Actual native frame slices; no borrowed on-load/hover scripts or donor opcodes.
            val sources=listOf(10,5,8,3,4,7,6,9)
            val geometry=listOf(intArrayOf(0,0,32,32),intArrayOf(32,0,448,32),intArrayOf(480,0,32,32),
                intArrayOf(0,32,32,270),intArrayOf(480,32,32,270),intArrayOf(0,302,32,32),intArrayOf(32,302,448,32),intArrayOf(480,302,32,32))
            sources.forEachIndexed { position,source ->
                val c=decode(402,source,requireNotNull(cache.data(Index.INTERFACES,402,source)))
                require(c.type==5 && c.defaultImage>=0 && cache.data(Index.GRAPHICS,c.defaultImage)!=null && c.hooks().isEmpty())
                frameSprites+=c.defaultImage
                val g=geometry[position]
                components+=c.copy(id=(3074 shl 16) or (67+position),parent=1,basePositionX=g[0],basePositionY=g[1],baseWidth=g[2],baseHeight=g[3],
                    options=null,name="",hidden=false)
            }
            components.forEachIndexed { id,c ->
                val raw=BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(c) } }.toArray()
                val read=decode(3074,id,raw)
                check(read.type==c.type && read.text==c.text && read.defaultImage==c.defaultImage && read.fontId==c.fontId)
                if(id>=67 || TarnishGarden.sha(raw)!=previousHashes.getValue(id)) assets+=InfernoAssetWriter.Asset(Index.INTERFACES,3074,id,raw,replaceExisting=id<67)
            }
        }
        assets.forEach { a -> val path=output.resolve("assets/${a.index}/${a.archive}/${a.file}.bin");Files.createDirectories(path.parent);Files.write(path,a.bytes) }
        val report=JsonObject(old+mapOf(
            "status" to JsonPrimitive(STATUS),"target" to JsonPrimitive(target.toRealPath().toString()),"panels" to JsonArray(panels),
            "smallFont" to JsonPrimitive(494),"nativeFrameSprites" to JsonArray(frameSprites.map(::JsonPrimitive)),"lastComponent" to JsonPrimitive(74),
            "fontEvidenceSha256" to JsonPrimitive(TarnishGarden.sha(Files.readAllBytes(output.resolve("evidence/p11_full.bin")))),
            "assets" to JsonArray(assets.map { a -> buildJsonObject { put("index",a.index);put("archive",a.archive);put("file",a.file);put("replaceExisting",a.replaceExisting);put("sha256",TarnishGarden.sha(a.bytes));if(a.replaceExisting)put("previousSha256",previousHashes.getValue(a.file)) } })))
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Prepared native jewellery polish: ${assets.size} bounded components, p11 font494, type5 item icons, native402 frame; no cache changed.")
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val report=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        require(report.getValue("interface").jsonPrimitive.int==3074)
        val entries=report.getValue("assets").jsonArray.map { it.jsonObject }
        require(entries.all { it.getValue("index").jsonPrimitive.int==3 && it.getValue("archive").jsonPrimitive.int==3074 && it.getValue("file").jsonPrimitive.int in 0..74 })
        require(entries.filter { !it.getValue("replaceExisting").jsonPrimitive.boolean }.map { it.getValue("file").jsonPrimitive.int }.toSet()==(67..74).toSet())
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache ->
            check(cache.lastFileId(3,3074)==74)
            entries.forEach { e -> val file=e.getValue("file").jsonPrimitive.int;val raw=requireNotNull(cache.data(3,3074,file));check(TarnishGarden.sha(raw)==e.getValue("sha256").jsonPrimitive.content);decode(3074,file,raw) }
            check(cache.files(Index.CONFIGS,15).size==420)
        }
    }

    private fun decode(group:Int,id:Int,raw:ByteArray)=InterfaceComponentDefinition(id=(group shl 16) or id).also {
        val input=BufferReader(raw);with(InterfaceDecoder()) { it.read(input) };check(input.position()==raw.size)
    }
    fun audit(target:Path) {
        FileCache(target.toString(),writable=false).use { cache ->
            println("p11_full=${cache.archiveId(Index.FONT_METRICS,"p11_full")} p12_full=${cache.archiveId(Index.FONT_METRICS,"p12_full")}")
            for(group in listOf(402,1306,3074)) for(id in cache.files(Index.INTERFACES,group)) {
                val raw=requireNotNull(cache.data(Index.INTERFACES,group,id));val input=BufferReader(raw)
                val c=InterfaceComponentDefinition(id=(group shl 16) or id).also { with(InterfaceDecoder()) { it.read(input) } }
                if(c.type==5 || id<14) println("$group:$id type${c.type} parent${c.parent} xy${c.basePositionX},${c.basePositionY} size${c.baseWidth}x${c.baseHeight} sprite${c.defaultImage} font${c.fontId} repeat${c.imageRepeat} modes${c.horizontalSizeMode},${c.verticalSizeMode} align${c.horizontalPositionMode},${c.verticalPositionMode} text=${c.text} hooks=${c.hooks().keys}")
                if(group==402 && c.type==5 && c.defaultImage>=0) {
                    val graphic=world.gregs.voidps.cache.definition.data.GraphicDefinition(c.defaultImage)
                    world.gregs.voidps.cache.definition.decoder.GraphicDecoder().readLoop(graphic,BufferReader(requireNotNull(cache.data(Index.GRAPHICS,c.defaultImage))))
                    println("SPRITE ${c.defaultImage} canvas${graphic.width}x${graphic.height} frames=${graphic.graphics!!.map { g -> listOf(g.offsetX,g.offsetY,g.width,g.height,g.deltaWidth,g.deltaHeight) }}")
                }
            }
        }
    }
}
