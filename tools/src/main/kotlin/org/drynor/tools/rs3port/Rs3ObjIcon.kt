package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.drynor.tools.engine.render.ObjIconRequest
import org.drynor.tools.engine.render.ObjIcons
import org.drynor.tools.engine.window.OffscreenContext
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import java.nio.file.Path
import kotlin.math.max

object Rs3ObjIcon {

    private const val OUTLINE = 1

    private const val SHADOW = 0xff302020.toInt()

    private val RETEXTURES = listOf("originalTextureColours", "modifiedTextureColours")

    fun render(rs3Tree: Path, obj: Int, crosswalk: Crosswalk): Rgba {
        val json = SourceJsonText.read(rs3Tree.resolve("config_obj").resolve("$obj.json"))
        require(RETEXTURES.none { json.containsKey(it) }) { "RS3 obj $obj retextures its model; RS3 material ids are not 727's." }
        val definition = definition(obj, json)
        val model = ModelConverter.convert(Rs3Model.read(rs3Tree, definition.modelId), definition.modelId, false, crosswalk)
        val pixels = OffscreenContext.use {
            val icons = ObjIcons(Cache.get())
            icons.modelSource = { if (it == definition.modelId) model else null }
            try {
                icons.pixels(definition, ObjIconRequest(obj, 1, OUTLINE, SHADOW))
            } finally {
                icons.cleanup()
            }
        } ?: throw IllegalStateException("RS3 obj $obj rendered no icon.")
        return square(pixels, ObjIcons.WIDTH, ObjIcons.HEIGHT)
    }

    private fun definition(obj: Int, json: JsonObject): ItemDefinition = ItemDefinition(
        id = obj,
        modelId = json["modelIds"]!!.jsonArray.first().jsonPrimitive.int,
        name = json["name"]!!.jsonPrimitive.content,
        graphicScale = json.int("zoom2d"),
        graphicPitch = json.int("xan2d"),
        graphicCameraRoll = json.int("yan2d"),
        graphicCameraYaw = json.int("zan2d"),
        graphicTranslateX = json.int("xof2d"),
        graphicTranslateY = json.int("yof2d"),
        stackable = json.int("stackable"),
        members = json["members"]!!.jsonPrimitive.boolean,
        floorScaleX = json.int("floorScaleX"),
        floorScaleY = json.int("floorScaleY"),
        floorScaleZ = json.int("floorScaleZ"),
        ambience = json.int("ambience"),
        originalColours = json.shorts("originalColours"),
        modifiedColours = json.shorts("modifiedColours")
    )

    private fun square(pixels: IntArray, width: Int, height: Int): Rgba {
        val filled = pixels.indices.filter { pixels[it] ushr 24 != 0 }
        check(filled.isNotEmpty()) { "the icon is empty." }
        val left = filled.minOf { it % width }
        val right = filled.maxOf { it % width }
        val top = filled.minOf { it / width }
        val bottom = filled.maxOf { it / width }
        val side = max(right - left, bottom - top) + 1
        val offsetX = (side - (right - left + 1)) / 2
        val offsetY = (side - (bottom - top + 1)) / 2
        val out = Rgba(side, side)
        for (y in top..bottom) {
            for (x in left..right) {
                val argb = pixels[x + y * width]
                val column = x - left + offsetX
                val row = y - top + offsetY
                out[column, row, Rgba.RED] = ((argb shr 16) and 0xff).toDouble()
                out[column, row, Rgba.GREEN] = ((argb shr 8) and 0xff).toDouble()
                out[column, row, Rgba.BLUE] = (argb and 0xff).toDouble()
                out[column, row, Rgba.ALPHA] = (argb ushr 24).toDouble()
            }
        }
        return out
    }

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.shorts(key: String): ShortArray? =
        this[key]?.jsonArray?.map { it.jsonPrimitive.int.toShort() }?.toShortArray()
}
