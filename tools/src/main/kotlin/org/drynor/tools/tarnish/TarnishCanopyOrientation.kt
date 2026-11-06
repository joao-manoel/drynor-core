package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.encoder.GraphicEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Corrects only the imported foliage sprite's orientation; geometry, UVs and all other materials stay intact. */
object TarnishCanopyOrientation {
    private const val STATUS="garden-canopy-orientation-native-readback"
    fun prepare(target:Path,treePackage:Path,output:Path) {
        require(!Files.exists(output))
        val original=Json.parseToJsonElement(Files.readString(treePackage.resolve("manifest.json"))).jsonObject
        require(original.getValue("status").jsonPrimitive.content=="garden-spirit-trees-native-readback")
        require(Files.isRegularFile(treePackage.resolve("applied.json")))
        require(Path.of(original.getValue("target").jsonPrimitive.content).toRealPath()==target.toRealPath())
        val spriteId=original.getValue("sourceSpriteIds").jsonObject.getValue("455").jsonPrimitive.int
        require(spriteId==20319)
        val record=original.getValue("assets").jsonArray.map { it.jsonObject }.single { it.getValue("index").jsonPrimitive.int==Index.GRAPHICS }
        lateinit var before:ByteArray
        FileCache(target.toRealPath().toString(),writable=false).use { cache ->
            before=requireNotNull(cache.data(Index.GRAPHICS,spriteId,0))
            check(TarnishGarden.sha(before)==record.getValue("sha256").jsonPrimitive.content) { "Foliage preimage differs from the reviewed import" }
        }
        val graphic=decode(spriteId,before)
        require(graphic.width==128 && graphic.height==128)
        val corrected=TarnishSpiritTrees.forNativeTexture(graphic)
        val encoded=BufferWriter(1 shl 20).apply { with(GraphicEncoder()) { encode(corrected) } }.toArray()
        check(decode(spriteId,encoded)==corrected)
        check(!encoded.contentEquals(before))
        val path=output.resolve("assets/${Index.GRAPHICS}/$spriteId/0.bin")
        Files.createDirectories(path.parent);Files.write(path,encoded)
        Files.write(output.resolve("source-normalized-sprite.bin"),before)
        val report=buildJsonObject {
            put("schemaVersion",1);put("status",STATUS);put("target",target.toRealPath().toString())
            put("treePackage",treePackage.toRealPath().toString());put("spriteId",spriteId)
            put("sourceNormalizedSha256",TarnishGarden.sha(before))
            put("policy","Counter-mirror the normalized imported sprite so the 727 ARGB right-to-left raster matches source texture pixel positions. No geometry, UV, palette, alpha, texture graph or material changes.")
            put("assets",JsonArray(listOf(buildJsonObject {
                put("index",Index.GRAPHICS);put("archive",spriteId);put("file",0)
                put("replaceExisting",true);put("previousSha256",TarnishGarden.sha(before));put("sha256",TarnishGarden.sha(encoded))
            })))
        }
        Files.writeString(output.resolve("manifest.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report))
        println("Prepared canopy texture orientation: sprite $spriteId only; no cache changed.")
    }
    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val report=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        val sprite=report.getValue("spriteId").jsonPrimitive.int
        require(sprite==20319)
        val assets=report.getValue("assets").jsonArray
        require(assets.size==1)
        val asset=assets.single().jsonObject
        require(asset.getValue("index").jsonPrimitive.int==Index.GRAPHICS && asset.getValue("archive").jsonPrimitive.int==sprite &&
            asset.getValue("file").jsonPrimitive.int==0 && asset.getValue("replaceExisting").jsonPrimitive.boolean)
        TarnishReviewedPatch.apply(output,backup,priorPackages,STATUS) { cache ->
            val source=decode(sprite,Files.readAllBytes(output.resolve("source-normalized-sprite.bin")))
            check(TarnishGarden.sha(Files.readAllBytes(output.resolve("source-normalized-sprite.bin")))==report.getValue("sourceNormalizedSha256").jsonPrimitive.content)
            check(decode(sprite,requireNotNull(cache.data(Index.GRAPHICS,sprite,0)))==TarnishSpiritTrees.forNativeTexture(source))
        }
    }
    private fun decode(id:Int,bytes:ByteArray)=GraphicDefinition(id).also { GraphicDecoder().readLoop(it,BufferReader(bytes)) }
}
