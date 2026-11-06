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

/** Reorders existing native rows without renumbering components or changing saved room enums. */
object TarnishGardenRoomOrder {
    private const val STATUS="garden-room-order-native-readback"
    internal data class Row(val component:Int,val name:String,val y:Int)

    internal fun positions(rows:List<Row>):Map<Int,Int> {
        require(rows.size>=3 && rows.map { it.component }.distinct().size==rows.size)
        val old=rows.sortedBy { it.y }
        require(old.map { it.y }.distinct().size==old.size)
        val garden=old.single { it.component==118 && it.name.equals("Superior garden",true) }
        val anchor=old.single { it.name.equals("Oubliette",true) || it.name.equals("Outbliette",true) }
        val ordered=old.filter { it!=garden }.toMutableList()
        ordered.add(ordered.indexOf(anchor)+1,garden)
        return ordered.mapIndexed { index,row->row.component to old[index].y }.toMap()
    }

    fun prepare(target:Path,output:Path) {
        require(!Files.exists(output)) { "Output already exists: $output" }
        val assets=mutableListOf<InfernoAssetWriter.Asset>()
        val originals=linkedMapOf<Int,String>()
        val expected=mutableListOf<JsonObject>()
        lateinit var originalRows:List<Row>
        lateinit var planned:Map<Int,Int>
        FileCache(target.toString(),writable=false).use { cache->
            require(cache.lastFileId(Index.INTERFACES,402)==121) { "Expected the audited 122-component room menu" }
            val raw=(0..121).associateWith { requireNotNull(cache.data(Index.INTERFACES,402,it)) }
            val components=raw.mapValues { (id,bytes)->decode(id,bytes) }
            require(components.getValue(119).text=="Superior garden: Lvl 65")
            require(components.getValue(118).parent==12)
            originalRows=components.filterValues { it.parent==12 && it.options?.contains("Build")==true }
                .map { (id,c)->Row(id,c.name,c.basePositionY) }.sortedBy { it.y }
            require(originalRows.last().component==118) { "Room menu is not the appended-row baseline" }
            planned=positions(originalRows)
            for((id,component) in components) {
                if(component.parent!=12) continue
                val owner=originalRows.lastOrNull { component.basePositionY>=it.y } ?: continue
                val delta=planned.getValue(owner.component)-owner.y
                if(delta==0) continue
                val shifted=component.copy(basePositionY=component.basePositionY+delta)
                require(shifted.basePositionY>=0 && shifted.basePositionY+shifted.baseHeight<=1468)
                val bytes=encode(shifted)
                val read=decode(id,bytes)
                check(read.basePositionY==shifted.basePositionY && read.parent==component.parent)
                check(read.name==component.name && read.text==component.text && read.options.contentEquals(component.options))
                // A coordinate-only copy must not alter any other serialized field.
                check(encode(read.copy(basePositionY=component.basePositionY)).contentEquals(raw.getValue(id)))
                originals[id]=TarnishGarden.sha(raw.getValue(id))
                assets+=InfernoAssetWriter.Asset(Index.INTERFACES,402,id,bytes,replaceExisting=true)
                expected+=buildJsonObject {
                    put("file",id);put("y",read.basePositionY);put("name",read.name);put("text",read.text)
                }
            }
            check((118..121).all { it in originals }) { "All four Superior Garden components must move together" }
            check(assets.isNotEmpty())
        }
        assets.forEach { asset->
            val file=output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(file.parent);Files.write(file,asset.bytes)
        }
        val report=buildJsonObject {
            put("schemaVersion",1);put("status",STATUS);put("target",target.toRealPath().toString())
            put("orderingPolicy","Superior garden after Oubliette at level 65; existing component IDs, room ordinals and relative row order preserved")
            put("expectedComponents",JsonArray(expected))
            put("rows",JsonArray(originalRows.map { row->buildJsonObject {
                put("component",row.component);put("name",row.name);put("originalY",row.y);put("y",planned.getValue(row.component))
            } }))
            put("assets",JsonArray(assets.map { asset->buildJsonObject {
                put("index",asset.index);put("archive",asset.archive);put("file",asset.file)
                put("replaceExisting",true);put("previousSha256",originals.getValue(asset.file));put("sha256",TarnishGarden.sha(asset.bytes))
            } }))
        }
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Prepared coordinate-only native room reorder: ${assets.size} components; Superior Garden follows Oubliette. No cache changed.")
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val manifest=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache->
            manifest.getValue("expectedComponents").jsonArray.forEach { value->
                val entry=value.jsonObject
                val id=entry.getValue("file").jsonPrimitive.int
                val read=decode(id,requireNotNull(cache.data(Index.INTERFACES,402,id)))
                check(read.basePositionY==entry.getValue("y").jsonPrimitive.int)
                check(read.name==entry.getValue("name").jsonPrimitive.content && read.text==entry.getValue("text").jsonPrimitive.content)
            }
            val rows=manifest.getValue("rows").jsonArray.map { it.jsonObject }.sortedBy { it.getValue("y").jsonPrimitive.int }
            val index=rows.indexOfFirst { it.getValue("component").jsonPrimitive.int==118 }
            check(index>0 && rows[index-1].getValue("name").jsonPrimitive.content.let { it.equals("Oubliette",true) || it.equals("Outbliette",true) })
        }
    }

    private fun decode(id:Int,bytes:ByteArray):InterfaceComponentDefinition {
        val input=BufferReader(bytes)
        return InterfaceComponentDefinition(id=(402 shl 16) or id).also {
            with(InterfaceDecoder()) { it.read(input) }
            check(input.position()==bytes.size)
        }
    }

    private fun encode(component:InterfaceComponentDefinition)=BufferWriter(8192).apply { with(InterfaceEncoder()) { encode(component) } }.toArray()
}
