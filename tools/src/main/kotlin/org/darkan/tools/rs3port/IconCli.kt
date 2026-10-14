package org.darkan.tools.rs3port

import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.engine.render.ObjIconRequest
import org.darkan.tools.engine.render.ObjIcons
import org.darkan.tools.engine.window.OffscreenContext
import org.darkan.tools.rsinterface.FontCache
import org.darkan.tools.rsinterface.GraphicCache
import org.darkan.tools.rsinterface.RsObjIconFont
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

private const val ZOOM = 4

private const val COLUMNS = 6

fun runIcon(cache: Cache, args: List<String>) {
    if (args.size < 2) {
        println("icon <objId>[,<objId>...] <out.png> [--sheet] [--amount n] [--outline n] [--wiki <dir>] [--glb <dir>]")
        return
    }
    val objs = args[0].split(',').flatMap { part ->
        if (part.contains('-')) {
            val bounds = part.split('-')
            (bounds[0].toInt()..bounds[1].toInt()).toList()
        } else {
            listOf(part.toInt())
        }
    }
    val out = File(args[1])
    val sheet = args.contains("--sheet")
    val wiki = args.indexOf("--wiki").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val glb = args.indexOf("--glb").takeIf { it >= 0 }?.let { Path.of(args[it + 1]) }
    val amount = args.indexOf("--amount").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val outline = args.indexOf("--outline").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 0

    OffscreenContext.use {
        val graphics = GraphicCache(cache)
        val fonts = FontCache(cache, graphics)
        val icons = ObjIcons(cache, RsObjIconFont(fonts))
        if (glb != null) {
            icons.modelSource = { modelId ->
                val file = glb.resolve("$modelId.glb")
                if (Files.exists(file)) {
                    ModelGltf.read(Files.readAllBytes(file), modelId)
                } else {
                    ModelLoader.loadDefinition(cache, modelId)
                }
            }
        }
        val cells = objs.mapNotNull { obj ->
            val definition = icons.definition(obj)
            val icon = icons.icon(ObjIconRequest(obj, amount, outline)) ?: return@mapNotNull null
            Cell("$obj ${definition?.name ?: ""}".trim(), image(icon.pixels), wiki?.let { fetch(it, definition?.name) })
        }
        out.parentFile?.mkdirs()
        if (!sheet && cells.size == 1) {
            ImageIO.write(cells[0].image, "png", out)
        } else {
            ImageIO.write(contactSheet(cells), "png", out)
        }
        println("rendered ${cells.size} of ${objs.size} objs to $out")
        icons.cleanup()
        fonts.cleanup()
        graphics.cleanup()
    }
}

private class Cell(val label: String, val image: BufferedImage, val reference: BufferedImage?)

private fun image(pixels: IntArray): BufferedImage {
    val image = BufferedImage(ObjIcons.WIDTH, ObjIcons.HEIGHT, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, ObjIcons.WIDTH, ObjIcons.HEIGHT, pixels, 0, ObjIcons.WIDTH)
    return image
}

private fun fetch(directory: File, name: String?): BufferedImage? {
    if (name == null || name == "null") {
        return null
    }
    directory.mkdirs()
    val file = File(directory, name.replace(' ', '_') + ".png")
    if (!file.exists()) {
        val url = "https://runescape.wiki/w/Special:FilePath/" + name.replace(' ', '_') + ".png"
        ProcessBuilder("curl", "-L", "-s", "-f", "-o", file.absolutePath, url)
            .redirectErrorStream(true).start().waitFor()
    }
    return if (file.exists() && file.length() > 0) runCatching { ImageIO.read(file) }.getOrNull() else null
}

private fun contactSheet(cells: List<Cell>): BufferedImage {
    val tile = ObjIcons.WIDTH * ZOOM
    val height = ObjIcons.HEIGHT * ZOOM
    val cellWidth = tile * 2 + 12
    val cellHeight = height + 20
    val rows = (cells.size + COLUMNS - 1) / COLUMNS
    val sheet = BufferedImage(cellWidth * COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_RGB)
    val graphics = sheet.createGraphics()
    graphics.color = Color(32, 32, 36)
    graphics.fillRect(0, 0, sheet.width, sheet.height)
    graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    for ((index, cell) in cells.withIndex()) {
        val x = (index % COLUMNS) * cellWidth
        val y = (index / COLUMNS) * cellHeight
        graphics.color = Color(200, 200, 205)
        graphics.drawString(cell.label.take(44), x + 4, y + 13)
        graphics.drawImage(cell.image, x + 4, y + 18, tile, height, null)
        cell.reference?.let { graphics.drawImage(it, x + tile + 8, y + 18, tile, height, null) }
    }
    graphics.dispose()
    return sheet
}
