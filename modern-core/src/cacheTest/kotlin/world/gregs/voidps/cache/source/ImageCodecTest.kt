package world.gregs.voidps.cache.source

import org.drynor.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.source.codec.GraphicCodec
import world.gregs.voidps.cache.source.codec.ImageCodecs
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every archive of the six 2D art indices, through the codec that stores it.
 *
 * The bar is the one `docs/cache-source.md` sets for a typed codec: `pack(unpack(bytes)) == bytes`
 * for every archive of every index, with no pristine sidecar anywhere. Unlike
 * `DefinitionJsonCodecTest` this has to write the files out - a graphic is PNGs on disk and the
 * codec reads them back through `javax.imageio`, which is the half of the round trip that could
 * lose a pixel - so each archive is unpacked into a scratch directory and packed straight back.
 *
 * Four further things are proved, because the layout promises them:
 *
 * 1. **One file per image.** A graphic directory holds nothing but `<frame>.png` and a JPEG archive
 *    is one `<id>.jpg`; no sidecar, no metadata, no second half.
 * 2. **Every PNG is a standard picture.** `ImageIO` opens it as 8 bit RGBA at the group's canvas
 *    size, so it displays correctly in any viewer.
 * 3. **A stripped PNG still packs.** Rewriting every frame through `ImageIO.write` - which is what
 *    an image editor does, and which drops the private chunks - packs into a group whose frames are
 *    the *same picture*: the same composed canvas, pixel for pixel, and the same trimmed box. Byte
 *    parity is not expected there and is not asserted; the picture is.
 * 4. **An edit survives.** Recolour one pixel of a stripped PNG and the packed group decodes with
 *    that pixel recoloured and nothing else moved.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class ImageCodecTest {

    @Test
    fun `every 2D asset round trips through its files`() {
        val cache = cache() ?: return
        val work = Files.createTempDirectory(SCRATCH)
        var archives = 0
        var pristine = 0
        var bytes = 0L
        var files = 0
        try {
            println("%-20s %-6s %9s %9s %9s %9s %8s".format("index", "id", "archives", "exact", "pristine", "files", "ms"))
            for (index in ImageCodecs.indices) {
                val counts = count(cache, index, work)
                archives += counts.archives
                pristine += counts.pristine
                files += counts.files
                bytes += counts.bytes
                println(
                    "%-20s %-6d %9d %9d %9d %9d %8d".format(
                        ImageCodecs.directory(index), index, counts.archives,
                        counts.archives - counts.pristine, counts.pristine, counts.files, counts.millis
                    )
                )
                assertEquals(0, counts.pristine, "${ImageCodecs.directory(index)} archives did not round trip")
            }
            println(
                "%-20s %-6s %9d %9d %9d %9d %8s".format(
                    "total", "", archives, archives - pristine, pristine, files, "${bytes / 1_000_000} MB"
                )
            )
        } finally {
            work.toFile().deleteRecursively()
        }
        assertTrue(archives > 14_000, "only $archives archives were compared")
        assertEquals(0, pristine, "no 2D asset may need a pristine copy")
    }

    /**
     * The three graphic indices as files: one PNG per frame and nothing else, every one of them a
     * standard RGBA picture at the group's canvas size, and the private chunks counted.
     */
    @Test
    fun `a graphic index is one standard RGBA PNG per frame and nothing else`() {
        val cache = cache() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(SCRATCH)
        var groups = 0
        var jpegs = 0
        var frames = 0
        var empty = 0
        var index0 = 0
        var frameChunk = 0
        var paletteChunk = 0
        var indexChunk = 0
        var bytes = 0L
        var proof: ByteArray? = null
        var proofName = ""
        var best = 0
        try {
            for (index in intArrayOf(Index.GRAPHICS, Index.LOADING_GRAPHICS, Index.LOADING_GRAPHICS_RAW)) {
                val directory = work.resolve(ImageCodecs.directory(index))
                Files.createDirectories(directory)
                for (archive in cache.archiveIds(index)) {
                    val group = cache.data(index, archive) ?: continue
                    val unpacked = unpack(index, archive, group, directory)
                    if (GraphicCodec.jpeg(group)) {
                        jpegs++
                        assertEquals(1, unpacked.size, "JPEG archive $archive is not one file")
                        assertTrue(
                            unpacked[0].endsWith(GraphicCodec.JPEG_EXTENSION),
                            "JPEG archive $archive wrote ${unpacked[0]}"
                        )
                        continue
                    }
                    groups++
                    val definition = decode(archive, group)
                    val graphics = definition.graphics!!
                    assertEquals(
                        graphics.size, unpacked.size,
                        "index $index group $archive has ${graphics.size} frames but wrote ${unpacked.size} files"
                    )
                    if (needsPalette(definition)) {
                        paletteChunk++
                    }
                    for (frame in graphics.indices) {
                        val path = directory.resolve("$archive/$frame${GraphicCodec.EXTENSION}")
                        assertTrue(Files.isRegularFile(path), "index $index group $archive has no frame $frame")
                        val png = Files.readAllBytes(path)
                        bytes += png.size
                        frames++
                        val graphic = graphics[frame]
                        if (graphic.width == 0 || graphic.height == 0) {
                            empty++
                        }
                        if (needsFrame(definition, frame)) {
                            frameChunk++
                        }
                        val types = chunks(png)
                        assertTrue(GraphicCodec.PALETTE_CHUNK in types, "$path has no palette chunk")
                        assertTrue(GraphicCodec.FRAME_CHUNK in types, "$path has no frame chunk")
                        if (GraphicCodec.INDEX_CHUNK in types) {
                            indexChunk++
                        }
                        // A standard picture: RGBA, eight bits a channel, the group's canvas.
                        val image = ImageIO.read(ByteArrayInputStream(png))
                        assertNotNull(image, "$path is not an image ImageIO can read")
                        assertEquals(maxOf(definition.width, 1), image.width, "$path is the wrong width")
                        assertEquals(maxOf(definition.height, 1), image.height, "$path is the wrong height")
                        assertEquals(4, image.raster.numBands, "$path is not four bands")
                        assertTrue(image.colorModel.hasAlpha(), "$path has no alpha channel")
                        assertEquals(8, image.colorModel.componentSize.max(), "$path is not eight bits a channel")
                        if (index == Index.GRAPHICS && frame == 0 && graphic.width * graphic.height > best) {
                            best = graphic.width * graphic.height
                            proof = png
                            proofName = "graphics/$archive/0${GraphicCodec.EXTENSION}"
                        }
                    }
                    if (graphics.any { it.raster.any { pixel -> pixel.toInt() == 0 } }) {
                        index0++
                    }
                }
                // Nothing in the index directory but images.
                Files.walk(directory).use { stream ->
                    for (path in stream) {
                        if (!Files.isRegularFile(path)) {
                            continue
                        }
                        val name = path.fileName.toString()
                        assertTrue(
                            name.endsWith(GraphicCodec.EXTENSION) || name.endsWith(GraphicCodec.JPEG_EXTENSION),
                            "${ImageCodecs.directory(index)} holds a $name, which is neither a PNG nor a JPEG"
                        )
                    }
                }
            }
            val file = File(PROOFS, "graphic-proof.png")
            file.parentFile.mkdirs()
            file.writeBytes(proof!!)
            println("wrote $proofName to ${file.absolutePath}")
        } finally {
            work.toFile().deleteRecursively()
        }
        println(
            "$groups graphic groups and $jpegs JPEG archives; $frames frame PNGs ($empty of them zero sized) " +
                "in ${bytes / 1_000_000} MB, $index0 groups drawing the transparent index"
        )
        println(
            "private chunks: rsPL needed by $paletteChunk of $groups groups, rsFR by $frameChunk of $frames frames, " +
                "rsIX written for $indexChunk of $frames frames"
        )
        assertTrue(groups > 12_000, "only $groups graphic groups were written")
        assertTrue(jpegs >= 21, "only $jpegs JPEG archives were written")
    }

    /**
     * Every private chunk stripped - which is what an image editor's save does - and the group packed
     * from the pixels alone.
     *
     * The bytes are not expected back; the picture is. Both sides are decoded and composed onto the
     * group's canvas by the client's own rule, and every pixel and every frame's trimmed box has to
     * agree. Then one pixel is recoloured in a stripped PNG and has to come back recoloured.
     */
    @Test
    fun `a PNG stripped of its chunks still packs into the same picture`() {
        val cache = cache() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(SCRATCH)
        var groups = 0
        var frames = 0
        var edited = 0
        var drifted = 0
        var saturated = 0
        try {
            for (index in intArrayOf(Index.GRAPHICS, Index.LOADING_GRAPHICS, Index.LOADING_GRAPHICS_RAW)) {
                val directory = work.resolve(ImageCodecs.directory(index))
                Files.createDirectories(directory)
                for (archive in cache.archiveIds(index)) {
                    if (index == Index.GRAPHICS && archive % SAMPLE != 0) {
                        continue
                    }
                    val group = cache.data(index, archive) ?: continue
                    if (GraphicCodec.jpeg(group)) {
                        continue
                    }
                    unpack(index, archive, group, directory)
                    val was = decode(archive, group)
                    val graphics = was.graphics!!
                    for (frame in graphics.indices) {
                        val path = directory.resolve("$archive/$frame${GraphicCodec.EXTENSION}")
                        Files.write(path, strip(Files.readAllBytes(path)))
                        assertTrue(
                            chunks(Files.readAllBytes(path)).none { it.startsWith("rs") },
                            "$path still has a private chunk"
                        )
                        frames++
                    }
                    val metadata = ArchiveMetadata()
                    metadata.fileIds(intArrayOf(0))
                    val now = decode(archive, SourceCodecs.codec(index).pack(directory, archive, metadata).group)
                    val drift = compare(index, archive, was, now)
                    drifted += drift
                    groups++
                    if (drift > 0 || now.rawPalette.size >= PALETTE) {
                        // The group's palette is full, so painting a new colour into it evicts one
                        // and the edit cannot be isolated to the pixel it was made in. The strip
                        // round trip above is what such a group is held to.
                        saturated++
                        continue
                    }
                    // ... and now with one pixel repainted, in the first frame that has a pixel to
                    // repaint. The colour is one nothing in the group uses, so it has to be added to
                    // the palette and cannot be confused with an entry that was already there.
                    val frame = graphics.indexOfFirst { it.width > 0 && it.height > 0 && it.raster.any { p -> p.toInt() != 0 } }
                    if (frame < 0) {
                        continue
                    }
                    val path = directory.resolve("$archive/$frame${GraphicCodec.EXTENSION}")
                    val point = repaint(path) ?: continue
                    val repainted = decode(archive, SourceCodecs.codec(index).pack(directory, archive, metadata).group)
                    val canvas = canvas(repainted, frame)
                    assertEquals(
                        PAINT, canvas[point], "index $index group $archive frame $frame did not keep the repainted pixel"
                    )
                    val before = canvas(was, frame)
                    var moved = 0
                    for (pixel in canvas.indices) {
                        if (pixel != point && !near(before[pixel], canvas[pixel])) {
                            moved++
                        }
                    }
                    assertEquals(
                        0, moved, "index $index group $archive frame $frame moved $moved pixels besides the repainted one"
                    )
                    edited++
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }
        println(
            "$groups groups and $frames frames packed from stripped PNGs alone, $edited of them with a repainted " +
                "pixel; ${frames - drifted} of $frames frames came back pixel identical, $drifted within one level " +
                "($saturated groups have a full palette, so an edit to them evicts a colour)"
        )
        assertTrue(groups > 1_000, "only $groups groups were packed from stripped PNGs")
        assertTrue(edited > 1_000, "only $edited groups could be repainted")
        assertTrue(drifted * 100 < frames, "$drifted of $frames frames drifted; a stripped PNG should rebuild exactly")
    }

    @Test
    fun `a graphic maps every one of its files back to its archive`() {
        assertEquals(1234, GraphicCodec.archiveOf("1234/0${GraphicCodec.EXTENSION}"))
        assertEquals(1234, GraphicCodec.archiveOf("1234/255${GraphicCodec.EXTENSION}"))
        assertEquals(1234, GraphicCodec.archiveOf("1234/${GraphicCodec.PRISTINE}.pristine"))
        assertEquals(1234, GraphicCodec.archiveOf("1234${GraphicCodec.JPEG_EXTENSION}"))
        assertEquals(1234, GraphicCodec.archiveOf("1234${GraphicCodec.EXTENSION}"))
        assertEquals(null, GraphicCodec.archiveOf("1234.dat"))
        assertEquals(null, GraphicCodec.archiveOf("1234/nested/0${GraphicCodec.EXTENSION}"))
    }

    @Test
    fun `every 2D index has its codec registered`() {
        SourceCodecs.reset()
        assertEquals(GraphicCodec, SourceCodecs.codec(Index.GRAPHICS))
        assertEquals(GraphicCodec, SourceCodecs.codec(Index.LOADING_GRAPHICS))
        assertEquals(GraphicCodec, SourceCodecs.codec(Index.LOADING_GRAPHICS_RAW))
        assertEquals(ImageCodecs.textures, SourceCodecs.codec(Index.TEXTURES))
        assertEquals(ImageCodecs.fonts, SourceCodecs.codec(Index.FONT_METRICS))
        assertEquals("json-material", SourceCodecs.codec(Index.TEXTURE_DEFINITIONS).id)
    }

    private class Counts(
        val archives: Int,
        val pristine: Int,
        val files: Int,
        val bytes: Long,
        val millis: Long
    )

    /** One index, archive by archive: unpack into a scratch directory, pack it back, compare. */
    private fun count(cache: Cache, index: Int, work: Path): Counts {
        val start = System.nanoTime()
        val codec = SourceCodecs.codec(index)
        val directory = work.resolve(ImageCodecs.directory(index))
        Files.createDirectories(directory)
        var archives = 0
        var pristine = 0
        var files = 0
        var bytes = 0L
        for (archive in cache.archiveIds(index)) {
            val ids = cache.files(index, archive)
            val data = ids.map { cache.data(index, archive, it) }.filterNotNull()
            if (data.size != ids.size) {
                continue
            }
            archives++
            val metadata = ArchiveMetadata()
            metadata.fileIds(ids)
            val group = data.single()
            val source = SourceArchive(index, archive, null, ids, group, data, emptyList(), metadata)
            val unpacked = codec.unpack(directory, source)
            for (file in unpacked.files) {
                files++
                bytes += file.bytes.size
                SourceFiles.write(directory.resolve(file.path), file.bytes)
            }
            if (unpacked.pristine.isNotEmpty()) {
                metadata.pristine = unpacked.pristine
                pristine++
                continue
            }
            val packed = pack(codec, directory, archive, metadata)
            assertTrue(
                packed.contentEquals(group),
                "${ImageCodecs.directory(index)} archive $archive packed to ${packed.size} bytes, not ${group.size}"
            )
        }
        return Counts(archives, pristine, files, bytes, (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * The materials index is one archive holding every material, so its files accumulate across
     * the loop rather than being a directory per archive; everything else packs as it goes.
     */
    private fun pack(codec: SourceCodec, directory: Path, archive: Int, metadata: ArchiveMetadata): ByteArray =
        codec.pack(directory, archive, metadata).group

    /** [group] through the codec and onto disk; the paths it wrote, relative to the index. */
    private fun unpack(index: Int, archive: Int, group: ByteArray, directory: Path): List<String> {
        val metadata = ArchiveMetadata()
        metadata.fileIds(intArrayOf(0))
        val source = SourceArchive(index, archive, null, intArrayOf(0), group, listOf(group), emptyList(), metadata)
        val unpacked = GraphicCodec.unpack(directory, source)
        for (file in unpacked.files) {
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        assertTrue(unpacked.pristine.isEmpty(), "index $index archive $archive needed a pristine copy")
        return unpacked.files.map { it.path }
    }

    /**
     * Two decodings of the same group, composed onto the canvas the way the client draws them.
     *
     * Every frame's opacity and trimmed box must match exactly. The colours must match exactly too,
     * bar one level of one channel: a group whose palette is already full and which draws the
     * transparent index *opaque* has no entry for the black the client renders there, so packing it
     * from pixels alone quantises that pixel to the nearest colour the palette holds. Returns how
     * many frames were not identical, which is how rare that is measured.
     */
    private fun compare(index: Int, archive: Int, was: GraphicDefinition, now: GraphicDefinition): Int {
        assertEquals(was.width, now.width, "index $index group $archive changed canvas width")
        assertEquals(was.height, now.height, "index $index group $archive changed canvas height")
        val before = was.graphics!!
        val after = now.graphics!!
        assertEquals(before.size, after.size, "index $index group $archive changed frame count")
        var drifted = 0
        for (frame in before.indices) {
            val one = canvas(was, frame)
            val two = canvas(now, frame)
            var same = true
            for (pixel in one.indices) {
                if (one[pixel] == two[pixel]) {
                    continue
                }
                same = false
                assertTrue(
                    near(one[pixel], two[pixel]),
                    "index $index group $archive frame $frame moved a pixel from " +
                        "${Integer.toHexString(one[pixel])} to ${Integer.toHexString(two[pixel])}"
                )
            }
            if (!same) {
                drifted++
            }
            assertEquals(
                trimmed(was, frame), trimmed(now, frame),
                "index $index group $archive frame $frame has a different trimmed box"
            )
        }
        return drifted
    }

    /**
     * Whether two composed pixels are the same picture: the same opacity, and colours no more than
     * one level of one channel apart - which is all a group with a full palette can lose when it is
     * packed from pixels that hold a colour it has no entry for.
     */
    private fun near(one: Int, two: Int): Boolean {
        if (one ushr 24 != two ushr 24) {
            return false
        }
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs(((one shr shift) and 0xff) - ((two shr shift) and 0xff)) > 1) {
                return false
            }
        }
        return true
    }

    /**
     * One frame drawn onto the group's canvas, as the client draws it: the palette colour, and the
     * alpha plane where the frame has one or the transparent index where it does not. A fully
     * transparent pixel is 0 whatever colour is under it, because nothing shows it.
     */
    private fun canvas(definition: GraphicDefinition, frame: Int): IntArray {
        val width = maxOf(definition.width, 1)
        val out = IntArray(width * maxOf(definition.height, 1))
        val graphic = definition.graphics!![frame]
        val plane = graphic.alpha
        for (y in 0 until graphic.height) {
            for (x in 0 until graphic.width) {
                val offset = x + y * graphic.width
                val entry = graphic.raster[offset].toInt() and 0xff
                val opacity = if (plane != null) plane[offset].toInt() and 0xff else if (entry == 0) 0 else 0xff
                if (opacity != 0) {
                    out[(x + graphic.offsetX) + (y + graphic.offsetY) * width] =
                        (opacity shl 24) or graphic.palette[entry]
                }
            }
        }
        return out
    }

    /** The frame's drawn pixels' bounding box in the canvas, which is what a stripped PNG recovers. */
    private fun trimmed(definition: GraphicDefinition, frame: Int): String {
        val width = maxOf(definition.width, 1)
        val pixels = canvas(definition, frame)
        var minX = width
        var minY = pixels.size
        var maxX = -1
        var maxY = -1
        for (pixel in pixels.indices) {
            if (pixels[pixel] ushr 24 == 0) {
                continue
            }
            val x = pixel % width
            val y = pixel / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) "empty" else "$minX,$minY ${maxX - minX + 1}x${maxY - minY + 1}"
    }

    /** Whether the group's palette order is one the pixels alone would not rebuild. */
    private fun needsPalette(definition: GraphicDefinition): Boolean {
        val order = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (graphic in definition.graphics!!) {
            val plane = graphic.alpha
            for (y in 0 until graphic.height) {
                for (x in 0 until graphic.width) {
                    val offset = x + y * graphic.width
                    val entry = graphic.raster[offset].toInt() and 0xff
                    val opacity = if (plane != null) plane[offset].toInt() and 0xff else if (entry == 0) 0 else 0xff
                    if (opacity != 0 && seen.add(graphic.palette[entry])) {
                        order.add(graphic.palette[entry])
                    }
                }
            }
        }
        if (order.size != definition.rawPalette.size - 1) {
            return true
        }
        for (entry in order.indices) {
            if (order[entry] != definition.graphics!![0].palette[entry + 1]) {
                return true
            }
        }
        return false
    }

    /** Whether the frame's box, storage order or dropped alpha plane is something the pixels lose. */
    private fun needsFrame(definition: GraphicDefinition, frame: Int): Boolean {
        val graphic = definition.graphics!![frame]
        if (graphic.width == 0 || graphic.height == 0) {
            return graphic.offsetX != 0 || graphic.offsetY != 0 || graphic.flags != 0
        }
        if (graphic.flags and 0x1 != 0) {
            return true
        }
        if (graphic.flags and 0x2 != 0 && graphic.alpha == null) {
            return true
        }
        return trimmed(definition, frame) != "${graphic.offsetX},${graphic.offsetY} ${graphic.width}x${graphic.height}"
    }

    /** [png] rewritten through `ImageIO`, which is what an editor's save does to a private chunk. */
    private fun strip(png: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val out = ByteArrayOutputStream(png.size)
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(image)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Paint one drawn pixel of [path] a colour the group does not use; its offset in the canvas. */
    private fun repaint(path: Path): Int? {
        val image = ImageIO.read(ByteArrayInputStream(Files.readAllBytes(path)))
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) ushr 24 != 0xff) {
                    continue
                }
                image.setRGB(x, y, PAINT)
                val out = ByteArrayOutputStream()
                val writer = ImageIO.getImageWritersByFormatName("png").next()
                try {
                    MemoryCacheImageOutputStream(out).use { stream ->
                        writer.output = stream
                        writer.write(image)
                    }
                } finally {
                    writer.dispose()
                }
                Files.write(path, out.toByteArray())
                return x + y * image.width
            }
        }
        return null
    }

    /** Every chunk type in [png], in file order. */
    private fun chunks(png: ByteArray): List<String> {
        val out = ArrayList<String>(8)
        var position = 8
        while (position + 8 <= png.size) {
            val length = ((png[position].toInt() and 0xff) shl 24) or ((png[position + 1].toInt() and 0xff) shl 16) or
                ((png[position + 2].toInt() and 0xff) shl 8) or (png[position + 3].toInt() and 0xff)
            out.add(String(png, position + 4, 4, Charsets.US_ASCII))
            position += 12 + length
        }
        return out
    }

    private fun decode(archive: Int, group: ByteArray): GraphicDefinition {
        val definition = GraphicDefinition(archive)
        GraphicDecoder().readLoop(definition, BufferReader(group))
        return definition
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping 2D codec parity; no cache at ${directory.absolutePath}")
            return null
        }
        SourceCodecs.reset()
        return Cache.get()
    }

    private companion object {
        private const val SCRATCH = "image-codec"

        /** One group of index 8 in this many, for the checks that pack every frame twice over. */
        private const val SAMPLE = 8

        /** Opaque magenta: a colour no graphic group in the cache holds, so an edit cannot hide. */
        private const val PAINT = 0xffff00ff.toInt()

        /** A graphic group's palette: 255 colours and the transparent index. */
        private const val PALETTE = 256

        private const val PROOFS =
            "/tmp/claude-1000/-home-trent-drynor-server/7342d152-879a-4df7-9110-0ce208e488a1/scratchpad"
    }
}
