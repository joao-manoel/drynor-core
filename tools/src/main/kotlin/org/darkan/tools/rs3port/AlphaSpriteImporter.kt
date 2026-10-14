package org.darkan.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class AlphaSprite(val material: Int, val graphic: Int, val gameval: String)

class AlphaSpriteSpec(root: JsonObject) {

    val ceiling: Int = root["ceilings"]!!.jsonObject["graphic"]!!.jsonPrimitive.int

    val sprites: List<AlphaSprite> = root["sprites"]!!.jsonArray.map { element ->
        val entry = element.jsonObject
        AlphaSprite(
            material = entry["material"]!!.jsonPrimitive.int,
            graphic = entry["graphic"]!!.jsonPrimitive.int,
            gameval = entry["gameval"]!!.jsonPrimitive.content
        )
    }

    companion object {

        fun read(path: Path): AlphaSpriteSpec = AlphaSpriteSpec(Json.parseToJsonElement(Files.readString(path)).jsonObject)
    }
}

class AlphaSpriteImporter(private val sourceTree: Path, private val spec: AlphaSpriteSpec) {

    private val writer = TreeWriter(sourceTree)

    private val findings = GrammarFindings()

    private val names = LinkedHashMap<Int, String>()

    private val allocated = spec.sprites.map { it.graphic }.toSet()

    private val context = ClientContext(
        exists = { kind, id ->
            when (kind) {
                "graphic" -> id in allocated || Files.exists(sourceTree.resolve("graphics").resolve("$id/0.png"))
                else -> throw IllegalArgumentException("An opacity import names no $kind.")
            }
        },
        frameExists = { _, _ -> false },
        baseTypes = { null }
    )

    fun run() {
        checkCeiling()
        val template = SourceJsonText.read(sourceTree.resolve("textures").resolve("${Rs3Materials.SPRITE_TEXTURE_TEMPLATE}.json"))
        for (entry in spec.sprites) {
            val texture = SourceJsonText.read(sourceTree.resolve("textures").resolve("${entry.material}.json"))
            val colour = colourSprite(texture, entry.material, entry.graphic)
            val material = SourceJsonText.read(sourceTree.resolve("materials").resolve("${entry.material}.json"))
            check(material["effectCombiner"]!!.jsonPrimitive.int == PER_PIXEL_ALPHA) {
                "Material ${entry.material} is combiner ${material["effectCombiner"]}; an opacity output is only read at $PER_PIXEL_ALPHA."
            }
            val sprite = SourceCodecFiles.graphic(sourceTree, entry.graphic, alpha(image(colour)))
            writer.add(sprite)
            findings.check("graphic ${entry.graphic}", ClientGrammar.sprite(entry.graphic, sprite.packed.single()))
            val definition = Rs3Materials.texture(template, entry.material, colour, entry.graphic, trailer(texture))
            val converted = SourceCodecFiles.texture(sourceTree, definition)
            writer.add(converted)
            findings.check("texture ${entry.material}", ClientGrammar.texture(definition, converted.packed.single(), context))
            names[entry.graphic] = entry.gameval
        }
        findings.refuse()
        val written = writer.flush()
        val lines = SourceEdits.archiveIndex(sourceTree, "graphics", names.mapValues { SourceEdits.named(it.value.hashCode()) })
        val added = SourceEdits.catalog(sourceTree, "graphic", names)
        SourceEdits.declarations(sourceTree, "Graphic", "graphic", names)
        println("index lines added: graphics $lines, catalog entries added: graphic $added")
        for ((path, state) in written) {
            println("  $state $path")
        }
        println("files ${written.size}: ${written.groupingBy { it.second }.eachCount()}")
    }

    private fun checkCeiling() {
        val ids = spec.sprites.map { it.graphic }
        val expected = (spec.ceiling + 1..spec.ceiling + ids.size).toList()
        check(ids == expected) { "The spec allocates graphics $ids; next-free from its ceiling ${spec.ceiling} is $expected." }
        val highest = Files.list(sourceTree.resolve("graphics")).use { stream ->
            stream.toList().mapNotNull { it.fileName.toString().substringBefore('.').toIntOrNull() }.max()
        }
        check(highest == spec.ceiling || highest == spec.ceiling + ids.size) {
            "graphic ids have moved: the tree's highest is $highest, the spec allocated ${expected.first()} to ${expected.last()}."
        }
    }

    private fun colourSprite(texture: JsonObject, material: Int, graphic: Int): Int {
        val operations = texture["operations"]!!.jsonArray
        val outputs = listOf("opaque", "opacity", "hdr").map { texture[it]!!.jsonPrimitive.int }
        val lone = operations.size == 1 && outputs.all { it == 0 }
        val rewired = operations.size == REWIRED_OPERATIONS && outputs == listOf(0, 1, 2) &&
            sprite(operations[1].jsonObject, material) == graphic
        check(lone || rewired) {
            "Texture $material is ${operations.size} operations with outputs $outputs; this import rewires a lone sprite op, " +
                "or rewrites one it has already given graphic $graphic."
        }
        return sprite(operations[0].jsonObject, material)
    }

    private fun sprite(operation: JsonObject, material: Int): Int {
        check(operation["type"]!!.jsonPrimitive.int == SPRITE) { "Texture $material is not a sprite operation." }
        return operation["opcodes"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.int
    }

    private fun trailer(texture: JsonObject): String? = texture["trailer"]?.jsonPrimitive?.content

    private fun image(graphic: Int): BufferedImage =
        ImageIO.read(sourceTree.resolve("graphics").resolve("$graphic/0.png").toFile())
            ?: throw IllegalArgumentException("Graphic $graphic has no readable frame 0.")

    private fun alpha(colour: BufferedImage): BufferedImage {
        val out = BufferedImage(colour.width, colour.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until colour.height) {
            for (x in 0 until colour.width) {
                val alpha = colour.getRGB(x, y) ushr 24
                out.setRGB(x, y, OPAQUE or (alpha shl 16) or (alpha shl 8) or alpha)
            }
        }
        return out
    }

    private companion object {

        private const val SPRITE = 39

        private const val REWIRED_OPERATIONS = 3

        private const val PER_PIXEL_ALPHA = 2

        private const val OPAQUE = 0xff000000.toInt()
    }
}
