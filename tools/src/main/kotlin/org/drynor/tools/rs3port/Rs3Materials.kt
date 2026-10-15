package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.data.TextureOp
import world.gregs.voidps.cache.definition.data.TextureOpcode
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class Sprites(val colour: BufferedImage, val opacity: BufferedImage?)

object Rs3Materials {

    const val SPRITE_TEXTURE_TEMPLATE = 6

    private const val SPRITE = 39

    private const val SPRITE_ID = 0

    private const val LARGE = 1

    private const val OPAQUE = 0xff000000.toInt()

    private const val REPEAT_S = 0x1

    private const val REPEAT_T = 0x8

    fun exists(tree: Path, id: Int): Boolean = Files.exists(path(tree, id))

    fun json(tree: Path, id: Int): JsonObject = SourceJsonText.read(path(tree, id))

    fun image(tree: Path, texture: Int): BufferedImage =
        ImageIO.read(tree.resolve("textures_png").resolve("$texture.png").toFile())
            ?: throw IllegalArgumentException("RS3 texture $texture is not a readable PNG.")

    fun convert(json: JsonObject, id: Int): MaterialDefinition {
        val size = json.int("textureSizeCode")
        require(size <= LARGE) { "RS3 material ${json["id"]} is texture size $size; 727 holds 64 or 128 pixels." }
        require(json.int("scrollU") == 0 && json.int("scrollV") == 0) {
            "RS3 material ${json["id"]} scrolls its texture; the RS3 to 727 scroll units are not calibrated."
        }
        val packed = json.int("packed")
        return MaterialDefinition(
            id = id,
            defined = true,
            detailsOnly = json.int("unknown30") == 1,
            isSmall = size != LARGE,
            brightness = json.int("unknown31").toByte(),
            alpha = json.int("unknown32").toByte(),
            effectId = json.int("unknown23").toByte(),
            effectParam1 = json.int("unknown24").toByte(),
            colourHsl = json.int("colour"),
            mipmapping = json.int("unknown28").toByte(),
            repeatS = packed and REPEAT_S != 0,
            repeatT = packed and REPEAT_T != 0,
            hdr = json.int("unknown13") == 1,
            effectCombiner = json.int("unknown15")
        )
    }

    fun sprites(png: BufferedImage): Sprites {
        val width = png.width
        val height = png.height
        val colour = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val opacity = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var partial = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = png.getRGB(width - 1 - x, y)
                val alpha = argb ushr 24
                colour.setRGB(x, y, if (alpha == 0) 0 else OPAQUE or (argb and 0xffffff))
                opacity.setRGB(x, y, OPAQUE or (alpha shl 16) or (alpha shl 8) or alpha)
                if (alpha != 0 && alpha != 0xff) {
                    partial = true
                }
            }
        }
        return Sprites(colour, if (partial) opacity else null)
    }

    fun texture(template: JsonObject, id: Int, colour: Int, opacity: Int?, trailer: String? = template["trailer"]?.jsonPrimitive?.content): TextureDefinition {
        val operations = template["operations"]!!.jsonArray.map { element ->
            val operation = element.jsonObject
            TextureOp(
                type = operation["type"]!!.jsonPrimitive.int,
                imageCacheCapacity = operation["imageCacheCapacity"]!!.jsonPrimitive.int,
                opcodes = operation["opcodes"]!!.jsonArray.map { opcode ->
                    TextureOpcode(opcode.jsonObject["opcode"]!!.jsonPrimitive.int, opcode.jsonObject["value"]?.jsonPrimitive?.intOrNull)
                },
                children = IntArray(0)
            )
        }.toMutableList()
        val opaque = template["opaque"]!!.jsonPrimitive.int
        val alpha = template["opacity"]!!.jsonPrimitive.int
        val sprite = operations[opaque]
        check(sprite.type == SPRITE && sprite.opcodes.single().opcode == SPRITE_ID) {
            "Texture $SPRITE_TEXTURE_TEMPLATE is no longer a plain sprite texture."
        }
        operations[opaque] = sprite.copy(opcodes = listOf(TextureOpcode(SPRITE_ID, colour)))
        if (opacity != null) {
            operations[alpha] = sprite.copy(opcodes = listOf(TextureOpcode(SPRITE_ID, opacity)))
        }
        return TextureDefinition(
            id = id,
            operations = operations,
            opaque = opaque,
            opacity = alpha,
            hdr = template["hdr"]!!.jsonPrimitive.int,
            trailer = trailer
        )
    }

    private fun path(tree: Path, id: Int): Path = tree.resolve("materials").resolve("$id.json")

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.int ?: 0
}
