package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.engine.render.ObjIconRequest
import org.drynor.tools.engine.render.ObjIcons
import org.drynor.tools.engine.window.OffscreenContext
import world.gregs.voidps.cache.FileCache
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Native 36x32 inventory renders from an applied item package and its canonical cache. */
object StaminaIcons {
    fun render(pack:Path,output:Path) {
        require(!Files.exists(output)) { "Icon output must be new" }
        val manifest=Json.parseToJsonElement(Files.readString(pack.resolve("manifest.json"))).jsonObject
        require(Files.exists(pack.resolve("applied.json"))) { "Apply and verify the package before rendering its icons" }
        val expected=manifest.getValue("expectedItems").jsonArray.map { it.jsonObject }
        require(expected.isNotEmpty())
        val prefix=if(manifest.getValue("status").jsonPrimitive.content=="stamina-doses-native-readback") "stamina" else "house-materials"
        val report=mutableListOf<JsonObject>()
        val sheet=BufferedImage(4*36*6,((expected.size+3)/4)*(32*6+26),BufferedImage.TYPE_INT_RGB)
        val graphics=sheet.createGraphics()
        graphics.color=Color(35,35,35);graphics.fillRect(0,0,sheet.width,sheet.height)
        Files.createDirectories(output)
        FileCache(manifest.getValue("target").jsonPrimitive.content,writable=false).use { cache ->
            OffscreenContext.use {
                val icons=ObjIcons(cache)
                try {
                    expected.forEachIndexed { position,record ->
                        val id=record.getValue("id").jsonPrimitive.int
                        val sprite=requireNotNull(icons.icon(ObjIconRequest(id,outline=1))) { "No rendered icon for $id" }
                        val pixels=sprite.pixels
                        val filled=pixels.indices.filter { pixels[it] ushr 24!=0 }
                        check(filled.isNotEmpty()) { "Empty item icon: $id" }
                        val image=BufferedImage(36,32,BufferedImage.TYPE_INT_ARGB)
                        image.setRGB(0,0,36,32,pixels,0,36)
                        ImageIO.write(image,"png",output.resolve("$id.png").toFile())
                        val x=(position%4)*216;val y=(position/4)*218
                        graphics.drawImage(image,x,y+26,216,192,null)
                        graphics.color=Color.WHITE
                        graphics.drawString("$id ${record["doses"]?.jsonPrimitive?.int?.let { "($it)" } ?: record.getValue("displayName").jsonPrimitive.content}${if(record.getValue("noted").jsonPrimitive.boolean) " note" else ""}",x+8,y+18)
                        report += buildJsonObject {
                            put("id",id);put("width",36);put("height",32);put("filledPixels",filled.size)
                            put("minX",filled.minOf { it%36 });put("maxX",filled.maxOf { it%36 })
                            put("minY",filled.minOf { it/36 });put("maxY",filled.maxOf { it/36 })
                        }
                    }
                } finally { icons.cleanup() }
            }
        }
        graphics.dispose()
        ImageIO.write(sheet,"png",output.resolve("$prefix-sheet.png").toFile())
        Files.writeString(output.resolve("icons.json"),Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(report)))
        println("Rendered ${report.size} item icons from canonical cache: $output")
    }
}
