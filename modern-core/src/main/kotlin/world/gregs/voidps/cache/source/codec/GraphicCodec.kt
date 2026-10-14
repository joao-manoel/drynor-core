package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.encoder.GraphicEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.name

/**
 * A graphic index as standard images: **exactly one PNG per frame and nothing else**.
 *
 * ```
 * graphics/1234/0.png     frame 0, RGBA at the group's canvas size
 * graphics/1234/1.png     frame 1
 * ```
 *
 * Every frame is an 8 bit RGBA truecolour PNG the size of the group's canvas - the canvas the client
 * composes the group's frames onto - with the frame drawn at its own offset and the rest of the
 * picture fully transparent. So the file displays correctly in any viewer, and the pixels alone say
 * everything the cache needs: where the frame is (trim the transparent border), which colours it
 * uses (the distinct ones), how opaque each pixel is, and which pixels are the transparent index.
 * **A PNG made or re-saved by any image editor packs**, which is the whole point of the layout; see
 * [compile] for the derivation, rule by rule.
 *
 * What the pixels *under*-determine - the palette's order, a frame box wider than the drawing in it,
 * the column major storage bit, an alpha plane that is entirely opaque - rides in three private
 * ancillary PNG chunks in the same file, `rsPL`, `rsFR` and `rsIX`. See [GraphicImage] for their
 * layout. They are what makes an untouched frame pack back to the cache's own bytes; an editor that
 * strips them (most do) costs only that exactness, never the picture.
 *
 * **JPEG archives are not graphics.** Index 32 holds 29 of its 34 loading screen images as four
 * component JPEG containers rather than palette graphics - the client hands them to
 * `Toolkit.createImage` - so they are stored as one ordinary three component JPEG each, with the
 * fourth component's codestream inside the same file as `APP11` segments:
 *
 * ```
 * loading_graphics/3770.jpg      Y, Cb and Cr as a baseline JPEG; alpha in APP11 `JagexAlpha`
 * ```
 *
 * [JagexJpeg] splits and joins them at the DCT coefficient level, so the file opens in any viewer and
 * comes back as the archive's own bytes. A `<id>.png` with an alpha channel is accepted in its place
 * and encoded into the four component file, for painting transparency in an ordinary editor. See
 * `docs/cache-source.md`, "loading graphics".
 */
object GraphicCodec : SourceCodec {

    /** One frame of a graphic group, or a whole JPEG archive painted as RGBA. */
    const val EXTENSION = ".png"

    /** A JPEG archive: the colour image, with its alpha component in `APP11` segments. */
    const val JPEG_EXTENSION = ".jpg"

    /** The private PNG chunk carrying the group's palette; [GraphicImage] has the layout. */
    const val PALETTE_CHUNK = "rsPL"

    /** The private PNG chunk carrying the frame's placement and format byte. */
    const val FRAME_CHUNK = "rsFR"

    /** The private PNG chunk carrying the frame's palette index raster. */
    const val INDEX_CHUNK = "rsIX"

    /**
     * The name a group's pristine sidecar takes.
     *
     * A group is a directory rather than one file, so the sidecar cannot be named after the file it
     * guards the way every other codec's is. Nothing in the 727 cache needs one - all 12,110 groups
     * pack back byte for byte, which `ImageCodecTest` asserts - and the tree holds none.
     */
    const val PRISTINE = "group"

    private val decoder = GraphicDecoder()

    private val encoder = GraphicEncoder()

    override val id: String
        get() = "graphic-png"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    /**
     * False, so the unpacker packs every archive back and compares it with the bytes it came from.
     *
     * There is no sidecar in this tree to fall back on, so this *is* the proof: a group or a JPEG
     * that could not be rebuilt from its own images would be an inexact archive in the unpack report
     * rather than a silent loss.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; a graphic group is one."
        }
        val data = archive.files[0]
        if (jpeg(data)) {
            return UnpackedArchive(
                listOf(SourceFile("${archive.archive}$JPEG_EXTENSION", JagexJpeg.editable(data)))
            )
        }
        val definition = decode(archive.archive, data)
        val files = write(archive.archive, definition)
        if (compile(archive.archive, files).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        // The images stay the editable form and the shipped bytes ride along beside them, exactly
        // as a definition's JSON does when its encoder cannot reproduce the file.
        val guard = guard(files)
        return UnpackedArchive(
            files + SourceFile(SourceFiles.pristine("${archive.archive}/$PRISTINE"), data),
            mapOf(archive.fileIds[0] to guard)
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one graphic group but its metadata lists ${ids.size} files." }
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return PackedArchive(JagexJpeg.archive(Files.readAllBytes(jpeg)), ids)
        }
        // A JPEG archive repainted as one RGBA PNG, which is how transparency is edited without a
        // JPEG aware tool. Only ever at the top of the index directory; a group's frames are a
        // directory down.
        val painted = directory.resolve("$archive$EXTENSION")
        if (Files.isRegularFile(painted)) {
            return PackedArchive(JagexJpeg.archive(image(painted)), ids)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            throw IOException(
                "Archive $archive has neither a $archive$JPEG_EXTENSION nor a $archive/ in ${directory.toAbsolutePath()}."
            )
        }
        val files = read(folder, archive)
        val sidecar = SourceFiles.pristine(folder.resolve(PRISTINE))
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (guard(files) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(archive, files), ids)
    }

    override fun archiveOf(path: String): Int? {
        val name = SourceFiles.guarded(path) ?: path
        val separator = name.indexOf('/')
        if (separator < 0) {
            // A JPEG archive is one file at the top of the index directory, as a JPEG or as the
            // RGBA PNG that may stand in for it.
            for (extension in listOf(JPEG_EXTENSION, EXTENSION)) {
                if (name.endsWith(extension)) {
                    return name.dropLast(extension.length).toIntOrNull()
                }
            }
            return null
        }
        if (name.indexOf('/', separator + 1) >= 0) {
            return null
        }
        return name.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return listOf(jpeg)
        }
        val painted = directory.resolve("$archive$EXTENSION")
        if (Files.isRegularFile(painted)) {
            return listOf(painted)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    /** Whether [data] is a JPEG container rather than a palette graphic group. */
    fun jpeg(data: ByteArray): Boolean = JagexJpeg.isJpeg(data)

    private fun decode(archive: Int, data: ByteArray): GraphicDefinition {
        val definition = GraphicDefinition(archive)
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    /** [definition] as the files it is stored in: one PNG per frame, in frame order. */
    private fun write(archive: Int, definition: GraphicDefinition): List<SourceFile> {
        val graphics = definition.graphics ?: error("Graphic $archive decoded to no frames.")
        val files = ArrayList<SourceFile>(graphics.size)
        for (frame in graphics.indices) {
            files.add(
                SourceFile(
                    "$archive/$frame$EXTENSION",
                    GraphicImage.write(archive, frame, definition, graphics[frame])
                )
            )
        }
        return files
    }

    /** The archive's frames as they are on disk, in frame order, which is the order [write] wrote. */
    private fun read(folder: Path, archive: Int): List<SourceFile> {
        val frames = HashMap<Int, Path>()
        Files.list(folder).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val name = path.name
                if (SourceFiles.isPristine(name)) {
                    continue
                }
                if (!name.endsWith(EXTENSION)) {
                    throw IOException("Archive $archive has a $name; a graphic group is $EXTENSION frames only.")
                }
                val frame = name.dropLast(EXTENSION.length).toIntOrNull()
                    ?: throw IOException("Archive $archive has a $name, whose name is not a frame number.")
                frames[frame] = path
            }
        }
        if (frames.isEmpty()) {
            throw IOException("Archive $archive has no frames in ${folder.toAbsolutePath()}.")
        }
        val files = ArrayList<SourceFile>(frames.size)
        for (frame in 0 until frames.size) {
            val path = frames[frame]
                ?: throw IOException("Archive $archive has ${frames.size} frames but no $frame$EXTENSION.")
            files.add(SourceFile("$archive/$frame$EXTENSION", Files.readAllBytes(path)))
        }
        return files
    }

    /**
     * The frames back into a group, which is what a pack emits and what [unpack] checks itself with.
     *
     * Every field of the group is taken from the pixels where the pixels determine it and from the
     * private chunks where they do not:
     *
     * | field | from the pixels | from a chunk |
     * |---|---|---|
     * | the canvas | the PNGs' own size, which every frame shares | `rsFR`, for a group whose canvas is 0 wide or high |
     * | the frame's box | the transparent border trimmed off | `rsFR`, where the shipped box is wider than the drawing in it |
     * | the palette | the distinct colours, in first appearance order | `rsPL`, which is the order the file spells |
     * | the index raster | palette index by colour, 0 where the pixel is fully transparent | `rsIX`, where a colour maps to no index or to more than one |
     * | the alpha plane | the alpha channel, whenever any pixel is partly transparent | `rsFR`, for a plane the client drops because it is entirely opaque |
     * | the format byte | row major storage, and the alpha bit from the plane above | `rsFR`, which also holds the column major bit |
     *
     * A chunk is used only where it still agrees with the pixels: a `rsFR` box that no longer holds
     * every drawn pixel and a `rsIX` raster that would not render what the PNG shows are both
     * discarded in favour of the derivation, so editing a frame in a tool that happens to keep
     * unknown chunks cannot silently clip or recolour it.
     */
    private fun compile(archive: Int, files: List<SourceFile>): ByteArray {
        if (files.isEmpty()) {
            throw IOException("Archive $archive has no frames.")
        }
        val images = files.map { GraphicImage.read(it.bytes, "Archive $archive's ${it.path.substringAfterLast('/')}") }
        val width = images[0].width
        val height = images[0].height
        for (frame in images.indices) {
            if (images[frame].width != width || images[frame].height != height) {
                throw IOException(
                    "Archive $archive frame $frame is ${images[frame].width}x${images[frame].height}" +
                        " but frame 0 is ${width}x$height; every frame of a group is its canvas."
                )
            }
        }
        val declared = images.firstNotNullOfOrNull { it.frame }
        val canvasWidth = declared?.canvasWidth ?: width
        val canvasHeight = declared?.canvasHeight ?: height
        val boxes = Array(images.size) { box(images[it]) }
        val colours = Colours(archive, images.firstNotNullOfOrNull { it.palette }?.entries)
        val graphics = Array(images.size) { frame -> graphic(images[frame], boxes[frame], colours) }
        val definition = GraphicDefinition(archive)
        definition.width = canvasWidth
        definition.height = canvasHeight
        definition.paletteZeroEntry = images.firstNotNullOfOrNull { it.palette }?.zeroEntry ?: false
        definition.rawPalette = colours.entries()
        val palette = GraphicImage.normalise(definition.rawPalette)
        for (graphic in graphics) {
            graphic.palette = palette
            graphic.deltaWidth = canvasWidth - graphic.width - graphic.offsetX
            graphic.deltaHeight = canvasHeight - graphic.height - graphic.offsetY
        }
        definition.graphics = graphics
        val writer = BufferWriter(capacity(definition, graphics))
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    /** One frame: its pixels inside [box], as palette indices, an alpha plane and a format byte. */
    private fun graphic(image: GraphicImage.Image, box: Box, colours: Colours): IndexedGraphic {
        val graphic = IndexedGraphic(box.minX, box.minY, box.width, box.height)
        val area = box.width * box.height
        val alpha = ByteArray(area)
        var partial = false
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val opacity = image.pixels[(box.minX + x) + (box.minY + y) * image.width] ushr 24
                alpha[x + y * box.width] = opacity.toByte()
                if (opacity != 0 && opacity != OPAQUE) {
                    partial = true
                }
            }
        }
        // A partly transparent pixel can only be an alpha plane, whatever a chunk says; without one
        // the format byte is what says the plane was there at all.
        val kind = if (partial) GraphicImage.PLANE else image.frame?.alpha ?: GraphicImage.NO_PLANE
        val plane = if (kind == GraphicImage.PLANE) alpha else null
        graphic.raster = ByteArray(area)
        val raster = image.indices
        if (raster != null && raster.width == box.width && raster.height == box.height &&
            agrees(raster.values, image, box, colours, plane)
        ) {
            raster.values.copyInto(graphic.raster)
        } else {
            derive(graphic.raster, image, box, colours)
        }
        graphic.flags = ((image.frame?.flags ?: 0) and ALPHA_FLAG.inv()) or
            if (kind == GraphicImage.NO_PLANE) 0 else ALPHA_FLAG
        graphic.alpha = plane
        return graphic
    }

    /** Palette index by colour: 0 where the pixel is fully transparent, the palette's entry otherwise. */
    private fun derive(raster: ByteArray, image: GraphicImage.Image, box: Box, colours: Colours) {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                raster[x + y * box.width] =
                    if (pixel ushr 24 == 0) 0 else colours.index(pixel and RGB).toByte()
            }
        }
    }

    /**
     * Whether [raster] would render the pixels the PNG actually shows.
     *
     * What is compared is what is *visible*: the opacity of every pixel, and the colour of every
     * pixel that is not fully transparent. A stale `rsIX` fails this and is discarded; one that
     * survived an edit that only moved fully transparent pixels around passes, because the picture
     * is the same either way.
     */
    private fun agrees(
        raster: ByteArray,
        image: GraphicImage.Image,
        box: Box,
        colours: Colours,
        plane: ByteArray?
    ): Boolean {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val offset = x + y * box.width
                val entry = raster[offset].toInt() and 0xff
                if (entry >= colours.size) {
                    return false
                }
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                val opacity = pixel ushr 24
                val expected = if (plane != null) {
                    plane[offset].toInt() and 0xff
                } else if (entry == 0) {
                    0
                } else {
                    OPAQUE
                }
                if (expected != opacity) {
                    return false
                }
                if (opacity != 0 && colours.colour(entry) != (pixel and RGB)) {
                    return false
                }
            }
        }
        return true
    }

    /** The frame's box: `rsFR`'s where it still holds every drawn pixel, else the drawing's own. */
    private fun box(image: GraphicImage.Image): Box {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.pixels[x + y * image.width] ushr 24 == 0) {
                    continue
                }
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        val tight = if (maxX < 0) Box(0, 0, 0, 0) else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
        val frame = image.frame ?: return tight
        val box = Box(frame.minX, frame.minY, frame.width, frame.height)
        if (box.minX + box.width > image.width || box.minY + box.height > image.height) {
            return tight
        }
        if (tight.width == 0 || tight.height == 0) {
            return box
        }
        val inside = tight.minX >= box.minX && tight.minY >= box.minY &&
            tight.minX + tight.width <= box.minX + box.width &&
            tight.minY + tight.height <= box.minY + box.height
        return if (inside) box else tight
    }

    /** A frame's trimmed placement in the group's canvas. */
    private class Box(val minX: Int, val minY: Int, val width: Int, val height: Int)

    /**
     * The group's palette while a pack builds it.
     *
     * Starts as `rsPL`'s entries where a frame still carries them and as nothing but the transparent
     * entry 0 where none does, and grows by first appearance as the frames are read - which is the
     * order Jagex's own packer writes, and the order [index] therefore reproduces for a group of
     * PNGs that came from an image editor.
     *
     * Entry 0 is deliberately unreachable by colour: it is the transparent index, and a fully
     * transparent pixel is the only thing that recovers it. So an opaque pixel that really is black
     * lands on whichever entry the file spells `000000` - which the client renders as 1 - or on a new
     * entry of its own, and a frame that genuinely draws index 0 opaque needs `rsIX`.
     */
    private class Colours(private val archive: Int, entries: IntArray?) {

        private val raw = ArrayList<Int>(LIMIT)

        private val colours = ArrayList<Int>(LIMIT)

        private val lookup = HashMap<Int, Int>(LIMIT * 2)

        init {
            val source = if (entries == null || entries.isEmpty()) intArrayOf(0) else entries
            if (source.size > LIMIT) {
                throw IOException("Archive $archive's palette has ${source.size} entries; the limit is $LIMIT.")
            }
            for (index in source.indices) {
                raw.add(source[index])
                colours.add(if (index > 0 && source[index] == 0) 1 else source[index])
            }
            for (index in 1 until colours.size) {
                lookup.putIfAbsent(colours[index], index)
            }
            for (index in 1 until raw.size) {
                lookup.putIfAbsent(raw[index], index)
            }
        }

        val size: Int
            get() = raw.size

        /** The palette entry [colour] renders as. */
        fun colour(index: Int): Int = colours[index]

        /**
         * [colour]'s index, appending an entry for a colour the palette does not hold yet.
         *
         * A graphic group is 255 colours and a transparent index, which is the format and not a
         * limit this could lift, so a colour that arrives once the palette is full is quantised to
         * the nearest entry already in it rather than failing the build. Two things reach that
         * branch: a picture an editor made with more colours than a graphic can hold, and - one
         * level of blue away - a frame drawing the transparent index *opaque*, which the client
         * renders as black and which a group with a full palette therefore has no entry for. The
         * `rsIX` chunk is what keeps the second exact while it survives; see [compile].
         */
        fun index(colour: Int): Int {
            val found = lookup[colour]
            if (found != null) {
                return found
            }
            if (raw.size >= LIMIT) {
                val nearest = nearest(colour)
                lookup[colour] = nearest
                return nearest
            }
            raw.add(colour)
            colours.add(if (colour == 0) 1 else colour)
            lookup[colour] = raw.size - 1
            return raw.size - 1
        }

        /** The entry [colour] is closest to, by squared distance in RGB. */
        private fun nearest(colour: Int): Int {
            var best = 1
            var distance = Int.MAX_VALUE
            for (index in 1 until colours.size) {
                val red = ((colours[index] shr 16) and 0xff) - ((colour shr 16) and 0xff)
                val green = ((colours[index] shr 8) and 0xff) - ((colour shr 8) and 0xff)
                val blue = (colours[index] and 0xff) - (colour and 0xff)
                val error = red * red + green * green + blue * blue
                if (error < distance) {
                    distance = error
                    best = index
                }
            }
            return best
        }

        /** The palette as the file spells it, entry 0 first. */
        fun entries(): IntArray = raw.toIntArray()
    }

    /** A PNG on disk as its pixels, for a JPEG archive that was repainted as one. */
    private fun image(path: Path): BufferedImage =
        ImageIO.read(ByteArrayInputStream(Files.readAllBytes(path)))
            ?: throw IOException("${path.toAbsolutePath()} is not an image this build can read.")

    /** Exactly what the group will be: pixels, planes, palette and the fixed trailer. */
    private fun capacity(definition: GraphicDefinition, graphics: Array<IndexedGraphic>): Int {
        var size = TRAILER + graphics.size * FRAME_TRAILER
        size += definition.rawPalette.size * COLOUR
        for (graphic in graphics) {
            val area = graphic.width * graphic.height
            size += 1 + area + if (graphic.flags and ALPHA_FLAG != 0) area else 0
        }
        return size
    }

    /**
     * The guard `index.json` records for a pristine sidecar: every editable file of the group,
     * named and hashed together, because the group is a directory rather than one file.
     */
    private fun guard(files: List<SourceFile>): String {
        val out = StringBuilder()
        for (file in files.sortedBy { it.path }) {
            out.append(file.path).append(':').append(SourceFiles.sha256(file.bytes)).append('\n')
        }
        return SourceFiles.sha256(out.toString().toByteArray(Charsets.UTF_8))
    }

    /** The frame carries an alpha plane after its pixels. */
    private const val ALPHA_FLAG = 0x2

    private const val OPAQUE = 0xff

    private const val RGB = 0xffffff

    /** The palette size is stored as `size - 1` in one byte. */
    private const val LIMIT = 256

    /** The canvas size, the palette size byte and the frame count. */
    private const val TRAILER = 7

    /** Four shorts per frame: `minX`, `minY`, `width`, `height`. */
    private const val FRAME_TRAILER = 8

    private const val COLOUR = 3
}
