package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Aligns painted sprite pixels, rather than the transparent 32px sprite canvases. */
object TarnishJewelleryFrame {
    private const val STATUS="jewellery-frame-native-readback"
    fun prepare(target:Path,previous:Path,output:Path) {
        require(!Files.exists(output) && Files.isRegularFile(previous.resolve("applied.json")))
        val old=Json.parseToJsonElement(Files.readString(previous.resolve("manifest.json"))).jsonObject
        require(old.getValue("status").jsonPrimitive.content=="jewellery-polish-native-readback")
        val assets=mutableListOf<InfernoAssetWriter.Asset>();val before=linkedMapOf<Int,String>();val geometry=mutableListOf<JsonObject>()
        FileCache(target.toString(),writable=false).use { cache ->
            require(cache.lastFileId(3,3074)==74)
            fun c(group:Int,id:Int)=decode(group,id,requireNotNull(cache.data(3,group,id)))
            val title=c(3074,2)
            val components=listOf(title.copy(basePositionX=32,basePositionY=0,baseWidth=448,baseHeight=22,
                horizontalTextAlign=1,verticalTextAlign=1,fontId=c(402,11).fontId),
                c(3074,70).copy(basePositionX=-13),c(3074,71).copy(basePositionX=492),c(3074,73).copy(basePositionY=314))
                .map { it.copy(horizontalSizeMode=0,verticalSizeMode=0,horizontalPositionMode=0,verticalPositionMode=0) }
            val expected=listOf(821 to listOf(13,0,7,32),823 to listOf(13,0,7,32),822 to listOf(0,13,32,7),1124 to listOf(0,0,32,22))
            for((id,bounds) in expected) {
                val raw=requireNotNull(cache.data(8,id,0));val graphic=GraphicDefinition(id)
                GraphicDecoder().readLoop(graphic,BufferReader(raw))
                val sprite=graphic.graphics!!.single()
                require(graphic.width==32 && graphic.height==32 && listOf(sprite.offsetX,sprite.offsetY,sprite.width,sprite.height)==bounds)
                val path=output.resolve("evidence/$id.bin");Files.createDirectories(path.parent);Files.write(path,raw)
                geometry+=buildJsonObject { put("sprite",id);put("canvasWidth",32);put("canvasHeight",32);put("trim",JsonArray(bounds.map(::JsonPrimitive)));put("sha256",TarnishGarden.sha(raw)) }
            }
            components.forEach { component ->
                val id=component.id and 65535
                val oldBytes=requireNotNull(cache.data(3,3074,id));before[id]=TarnishGarden.sha(oldBytes)
                val raw=BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(component) } }.toArray()
                val read=decode(3074,id,raw)
                check(read.basePositionX==component.basePositionX && read.basePositionY==component.basePositionY)
                check(read.horizontalTextAlign==component.horizontalTextAlign && read.verticalTextAlign==component.verticalTextAlign)
                assets+=InfernoAssetWriter.Asset(3,3074,id,raw,replaceExisting=true)
            }
        }
        assets.forEach { a -> val p=output.resolve("assets/3/3074/${a.file}.bin");Files.createDirectories(p.parent);Files.write(p,a.bytes) }
        val report=JsonObject(old+mapOf("status" to JsonPrimitive(STATUS),"spriteGeometry" to JsonArray(geometry),
            "frameCorrection" to JsonPrimitive("Side canvas X=-13/492 paints outer borders X=0..7 and505..512; bottom canvasY314 paintsY327..334. Header is painted22px high; title centered within0..22."),
            "assets" to JsonArray(assets.map { a -> buildJsonObject { put("index",3);put("archive",3074);put("file",a.file);put("replaceExisting",true);put("sha256",TarnishGarden.sha(a.bytes));put("previousSha256",before.getValue(a.file)) } })))
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Prepared four-component jewellery frame correction; no cache changed.")
    }
    fun apply(output:Path,backup:Path,prior:List<Path>) {
        val report=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        val assets=report.getValue("assets").jsonArray.map { it.jsonObject }
        require(assets.map { it.getValue("file").jsonPrimitive.int }.toSet()==setOf(2,70,71,73) && assets.size==4)
        require(assets.all { it.getValue("index").jsonPrimitive.int==3 && it.getValue("archive").jsonPrimitive.int==3074 && it.getValue("replaceExisting").jsonPrimitive.boolean })
        TarnishReviewedPatch.apply(output,backup,prior,STATUS) { cache ->
            require(cache.lastFileId(3,3074)==74)
            assets.forEach { e -> val id=e.getValue("file").jsonPrimitive.int;val raw=requireNotNull(cache.data(3,3074,id));check(TarnishGarden.sha(raw)==e.getValue("sha256").jsonPrimitive.content);decode(3074,id,raw) }
        }
    }
    private fun decode(group:Int,id:Int,raw:ByteArray)=InterfaceComponentDefinition(id=(group shl 16) or id).also {
        val input=BufferReader(raw);with(InterfaceDecoder()) { it.read(input) };check(input.position()==raw.size)
    }
}
