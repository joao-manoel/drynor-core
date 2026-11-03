package org.drynor.tools.cache

import org.drynor.tools.engine.render.ObjIconRequest
import org.drynor.tools.engine.render.ObjIcons
import org.drynor.tools.engine.window.OffscreenContext
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Targeted import only. The caller stops cache consumers and supplies the rollback backup. */
object InfernoTicketAssets {
    const val ITEM_ID = 30261
    const val MODEL_ID = 239026
    private const val ITEM_ARCHIVE = ITEM_ID ushr 8
    private const val ITEM_FILE = ITEM_ID and 255

    fun definition() = ItemDefinition(
        id = ITEM_ID, name = "Inferno unlock ticket", modelId = MODEL_ID,
        graphicScale = 1000, graphicPitch = 480, graphicCameraRoll = 192,
        options = arrayOf("Unlock", null, null, null, "Destroy"),
        cost = 0, exchangeable = false
    )

    fun itemBytes(): ByteArray {
        val writer = BufferWriter(4096)
        with(ItemEncoder()) { writer.encode(definition()) }
        return writer.toArray()
    }

    fun decode(bytes: ByteArray): ItemDefinition = ItemDefinition(id = ITEM_ID).also {
        ItemDecoder().readLoop(it, BufferReader(bytes))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3 && args[0] in listOf("audit", "apply", "verify", "preview")) {
            "InfernoTicketAssets <audit|apply|verify|preview> <cache> <rs.glb> [backup|preview.png]"
        }
        val directory = Path.of(args[1]).toRealPath()
        val source = ModelGltf.read(Files.readAllBytes(Path.of(args[2])), MODEL_ID)
        val modelBytes = ModelEncoder.encode(source)
        val roundTrip = ModelDecoder.read(MODEL_ID, modelBytes)
        check(source.vertexX.contentEquals(roundTrip.vertexX) && source.vertexY.contentEquals(roundTrip.vertexY) &&
            source.vertexZ.contentEquals(roundTrip.vertexZ) && source.faceA.contentEquals(roundTrip.faceA) &&
            source.faceB.contentEquals(roundTrip.faceB) && source.faceC.contentEquals(roundTrip.faceC) &&
            source.faceColors.contentEquals(roundTrip.faceColors)) { "Model round trip failed" }
        val itemBytes = itemBytes()
        checkDefinition(decode(itemBytes))
        if (args[0] == "preview") {
            val output = Path.of(args[3])
            FileCache.load(directory.toString()).use { cache ->
                OffscreenContext.use {
                    val icons = ObjIcons(cache)
                    icons.modelSource = { if (it == MODEL_ID) source else null }
                    try {
                        val pixels = checkNotNull(icons.pixels(definition(), ObjIconRequest(ITEM_ID, outline = 1)))
                        check(pixels.any { it ushr 24 != 0 }) { "Empty icon" }
                        val image = BufferedImage(ObjIcons.WIDTH, ObjIcons.HEIGHT, BufferedImage.TYPE_INT_ARGB)
                        image.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                        ImageIO.write(image, "png", output.toFile())
                        val sheet = BufferedImage(36 * 8 * 5, 32 * 8 + 24, BufferedImage.TYPE_INT_RGB)
                        val graphics = sheet.createGraphics()
                        graphics.color = java.awt.Color(35, 35, 35)
                        graphics.fillRect(0, 0, sheet.width, sheet.height)
                        for ((column, scale) in listOf(400, 600, 800, 1000, 1600).withIndex()) {
                            val candidate = definition().also { it.graphicScale = scale }
                            val rgba = checkNotNull(icons.pixels(candidate, ObjIconRequest(ITEM_ID, outline = 1)))
                            val small = BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB)
                            small.setRGB(0, 0, 36, 32, rgba, 0, 36)
                            graphics.drawImage(small, column * 288, 24, 288, 256, null)
                            graphics.color = java.awt.Color.WHITE
                            graphics.drawString("zoom=$scale", column * 288 + 8, 16)
                            val filled = rgba.indices.filter { rgba[it] ushr 24 != 0 }
                            println("zoom=$scale bounds=${filled.minOfOrNull { it % 36 }}..${filled.maxOfOrNull { it % 36 }},${filled.minOfOrNull { it / 36 }}..${filled.maxOfOrNull { it / 36 }}")
                        }
                        graphics.dispose()
                        ImageIO.write(sheet, "png", output.resolveSibling("inventory-zoom-sheet.png").toFile())
                        println("Icon: ${image.width}x${image.height} at $output")
                    } finally { icons.cleanup() }
                }
            }
            return
        }
        if (args[0] == "apply") {
            val backup = Path.of(args[3]).toRealPath()
            require(directory != backup) { "Backup must be separate from the canonical cache" }
            // Refuse a partial or outdated backup before the first write.
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach {
                    check(Files.mismatch(it, backup.resolve(it.fileName)) == -1L) { "Backup differs: $it" }
                }
            }
            FileCache.load(directory.toString()).use { checkVacant(it) }
            FileCache.load(directory.toString(), writable = true).use { cache ->
                cache.write(7, MODEL_ID, 0, modelBytes)
                cache.write(19, ITEM_ARCHIVE, ITEM_FILE, itemBytes)
            }
        }
        FileCache.load(directory.toString()).use { cache ->
            if (args[0] == "audit") checkVacant(cache)
            else {
                check(cache.data(7, MODEL_ID, 0)!!.contentEquals(modelBytes))
                checkDefinition(decode(cache.data(19, ITEM_ARCHIVE, ITEM_FILE)!!))
                val verified = ModelDecoder.read(MODEL_ID, cache.data(7, MODEL_ID, 0)!!)
                check(verified.vertexCount == source.vertexCount && verified.faceCount == source.faceCount)
                if (args[0] == "apply" || args.size > 3) {
                    FileCache.load(args[3]).use { backup -> verifyUnchanged(backup, cache) }
                }
            }
        }
        println("PASS ${args[0]} item=$ITEM_ID model=$MODEL_ID vertices=${source.vertexCount} faces=${source.faceCount}")
    }

    private fun checkVacant(cache: Cache) {
        check(MODEL_ID !in cache.archives(7)) { "Model ID already allocated" }
        check(ITEM_FILE !in cache.files(19, ITEM_ARCHIVE)) { "Item ID already allocated" }
    }

    private fun checkDefinition(item: ItemDefinition) {
        check(item.name == definition().name && item.modelId == MODEL_ID)
        check(item.options.contentEquals(definition().options) && item.isDestroyItem())
        check(!item.exchangeable && item.stackable == 0 && item.noteId == -1 && item.primaryMaleModel == -1)
        check(item.graphicScale == definition().graphicScale && item.graphicPitch == definition().graphicPitch &&
            item.graphicCameraYaw == definition().graphicCameraYaw && item.graphicCameraRoll == definition().graphicCameraRoll)
    }

    private fun verifyUnchanged(before: Cache, after: Cache) {
        for (index in 0 until maxOf(before.indexCount(), after.indexCount())) {
            val old = before.archives(index).toSet()
            val added = after.archives(index).toSet() - old
            check(added == if (index == 7) setOf(MODEL_ID) else emptySet<Int>()) { "Unexpected added archives in $index" }
            check((old - after.archives(index).toSet()).isEmpty()) { "Removed archives in $index" }
            for (archive in old) {
                if (index == 19 && archive == ITEM_ARCHIVE) {
                    val files = before.files(index, archive)
                    check(after.files(index, archive).toSet() == files.toSet() + ITEM_FILE)
                    for (file in files) check(before.data(index, archive, file)!!.contentEquals(after.data(index, archive, file)!!)) {
                        "Unrelated item changed: $archive/$file"
                    }
                } else check(before.container(index, archive)!!.contentEquals(after.container(index, archive)!!)) {
                    "Unrelated archive changed: $index/$archive"
                }
            }
        }
        println("PASS all unrelated archives and sibling item definitions unchanged")
    }
}
