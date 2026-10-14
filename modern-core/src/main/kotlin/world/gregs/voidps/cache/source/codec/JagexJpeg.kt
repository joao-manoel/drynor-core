package world.gregs.voidps.cache.source.codec

import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

/**
 * The four component JPEGs index 32 holds its loading screens in, and how they become one file
 * anything can open.
 *
 * Jagex's loading screen art is a **baseline JPEG with four components and no Adobe APP14 marker**:
 * `Y`, `Cb`, `Cr` and an alpha plane. The stream opens straight on `ff d8 ff db` - there is no JFIF
 * APP0 either - and all 29 of them have the same frame:
 *
 * ```
 * ffdb(67) ffdb(67)     the standard IJG luminance and chrominance tables, scaled 0.5, i.e. q75
 * ffc0(20) id1 2x2 q0   Y,     full resolution
 *          id2 1x1 q1   Cb,    subsampled 2:1 both ways
 *          id3 1x1 q1   Cr,    subsampled 2:1 both ways
 *          id4 2x2 q0   alpha, full resolution, quantised and coded like luminance
 * ffc4(31) ffc4(181)    the standard DC and AC luminance tables, ids 0
 * ffc4(31) ffc4(181)    the standard DC and AC chrominance tables, ids 1
 * ffda(14)              one baseline scan interleaving all four, then EOI
 * ```
 *
 * Four components with no Adobe marker is what every standard viewer reads as CMYK or YCCK, which is
 * why these files show up cyan and magenta everywhere: the fourth component is *alpha*, and nothing
 * in the file says so. `ImageIO.read` and `Toolkit.createImage` do not merely guess wrong, they
 * refuse - `libjpeg` types the stream `JCS_CMYK`, AWT asks it for RGB and it throws
 * `Unsupported color conversion request`.
 *
 * **What the client does.** Nothing, is the honest answer.
 * `NativeSprite.fromPixelgrabber` hands the container bytes to `Toolkit.getDefaultToolkit().createImage`
 * and grabs `TYPE_INT_ARGB` ints back out with a `PixelGrabber`; there is no YCbCr matrix, no APP14
 * handling and no alpha branch anywhere in it, so every rule below belongs to the platform decoder.
 * The alpha reaches the GPU as `GL_BGRA` straight from those ints, un-premultiplied. The client even
 * knows the path is unreliable: `NativeSprite.supportsGzippedSprites` decodes one embedded 1x1 file
 * of exactly this shape at startup and, when AWT throws, sets `Resource.USING_JAGEX_IMAGE_FORMAT` and
 * reads index 34 - the same 34 pictures as palette graphics - instead. On any modern JVM that probe
 * fails, which makes index 34 a ready made cross check for [decode] and is what
 * `LoadingScreenCodecTest` measures against.
 *
 * So the rules, taken from the files themselves and confirmed against index 34:
 *
 * - components 1..3 are `Y`, `Cb`, `Cr` with the usual 128 offset on the two chroma planes, and the
 *   standard JFIF (ITU-R BT.601) matrix converts them;
 * - component 4 is **straight alpha**, 255 opaque, no inversion and no offset - 27 of the 29 files
 *   are a constant 255 and the two that are not (3764, 3765) hold the graphic's silhouette at 255 on
 *   a 0 ground, which would be inside out were it `255 - v`;
 * - no colour transform of any kind applies, because with no Adobe marker there is no transform flag
 *   to apply one.
 *
 * ## How the source tree stores them
 *
 * **One file per image**: `loading_graphics/<id>.jpg`, an ordinary three component baseline JPEG of
 * `Y`, `Cb` and `Cr` that opens in any viewer or editor, with the fourth component's own
 * entropy coded stream riding inside the same file as one or more `APP11` segments -
 * `JagexAlpha\0`, a sequence number and a count, then a slice of the greyscale codestream. Every
 * standard decoder skips an application segment it does not recognise, so the file *is* the picture
 * and the alpha is along for the ride. [editable] writes it and [archive] reads it back.
 *
 * Underneath, [split] takes the four component file apart at the DCT coefficient level - see
 * [BaselineJpeg] - into a three component colour half and a one component greyscale half, each
 * keeping the original's quantisation tables, Huffman tables, sampling factors and *the very
 * coefficients that were in the cache*, merely re-entropy-coded. Nothing is dequantised, inverse
 * transformed or re-compressed, so [join] puts the two back together into the cache's own bytes -
 * byte for byte, all 29 - and the tree needs no `.pristine` copy of anything.
 *
 * **An editor that strips the `APP11` segments** - most will, on a re-save - leaves a perfectly good
 * three component JPEG behind, and [join] then *synthesises* an entirely opaque fourth component
 * rather than failing: a flat DC that dequantises to 255 in every block, coded with the luminance
 * tables the file already carries. The picture survives an edit anywhere; only its transparency has
 * to be painted again. [archive] with a `BufferedImage` is the other way to paint it - drop a
 * `<id>.png` with an alpha channel in place of the `.jpg` and the packer encodes the four component
 * file from its pixels.
 *
 * The one thing a standalone one component JPEG cannot hold is the block grid's **MCU padding**. In
 * the interleaved four component scan a 2x2 component is stored on the MCU grid, which for six of
 * the 29 files (3764, 3765, 3773, 3774, 3775, 3776) runs a block past the image; alone in its own
 * file the alpha is coded on its own smaller grid and those blocks have nowhere to go. They are not
 * information: they are what `libjpeg`'s `compress_data` fabricates at an edge - all AC zero, and the
 * DC copied from the block before - so [join] rebuilds them, which is [padding], and byte for byte
 * parity on those six files is the proof the rule is the right one.
 */
object JagexJpeg {

    /** A four component Jagex JPEG taken apart into the two codestreams [editable] stores in one file. */
    class Parts(
        /** The `Y`, `Cb` and `Cr` components as a three component baseline JPEG. */
        val colour: ByteArray,
        /** The fourth component as a one component greyscale baseline JPEG. */
        val alpha: ByteArray
    )

    /** Whether [data] is a JPEG container rather than something else - the `ff d8` start of image. */
    fun isJpeg(data: ByteArray): Boolean =
        data.size > 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1

    /**
     * [archive] as the one JPEG the source tree stores it as: the three component colour image, with
     * the fourth component's codestream in `APP11` segments straight after `SOI`.
     *
     * @throws IOException when [archive] is not a four component baseline JPEG.
     */
    fun editable(archive: ByteArray): ByteArray {
        val parts = split(archive)
        return embed(parts.colour, parts.alpha)
    }

    /**
     * The tree's one JPEG back as the four component archive the client reads.
     *
     * Byte for byte the archive [editable] came from while nothing has edited the file. An editor
     * that stripped the `APP11` segments gets an entirely opaque fourth component instead of an
     * error - see the class documentation.
     */
    fun archive(file: ByteArray): ByteArray = join(file, alphaOf(file))

    /**
     * An RGBA image as a four component archive, for a `<id>.png` dropped in place of the `.jpg`.
     *
     * The colour and the alpha are encoded as two ordinary JPEGs by the platform writer and then
     * joined, so this is the one path here that re-compresses: the cache's own files are never
     * touched by it.
     */
    fun archive(image: BufferedImage): ByteArray {
        val width = image.width
        val height = image.height
        val colour = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val plane = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val row = IntArray(width)
        for (y in 0 until height) {
            image.getRGB(0, y, width, 1, row, 0, width)
            for (x in 0 until width) {
                colour.setRGB(x, y, row[x] and 0xffffff)
                plane.raster.setSample(x, y, 0, row[x] ushr 24)
            }
        }
        return join(jpeg(colour), jpeg(plane))
    }

    /** [colour] with [alpha] inside it as `APP11` segments, split at the segment length limit. */
    fun embed(colour: ByteArray, alpha: ByteArray): ByteArray {
        if (!isJpeg(colour)) {
            throw IOException("An embedded alpha needs a JPEG to ride in.")
        }
        val segments = maxOf(BaselineJpeg.ceil(alpha.size, PAYLOAD), 1)
        if (segments > 0xffff) {
            throw IOException("An alpha codestream of ${alpha.size} bytes needs $segments APP11 segments.")
        }
        val out = ByteArrayOutputStream(colour.size + alpha.size + segments * OVERHEAD)
        out.write(colour, 0, 2)
        var offset = 0
        for (sequence in 1..segments) {
            val length = minOf(PAYLOAD, alpha.size - offset)
            val size = 2 + IDENTIFIER.size + 4 + length
            out.write(0xff)
            out.write(APP11)
            out.write(size shr 8)
            out.write(size and 0xff)
            out.write(IDENTIFIER)
            out.write(sequence shr 8)
            out.write(sequence and 0xff)
            out.write(segments shr 8)
            out.write(segments and 0xff)
            out.write(alpha, offset, length)
            offset += length
        }
        out.write(colour, 2, colour.size - 2)
        return out.toByteArray()
    }

    /** [file]'s embedded alpha codestream, or null when nothing in it carries one. */
    fun alphaOf(file: ByteArray): ByteArray? {
        if (!isJpeg(file)) {
            return null
        }
        val parts = HashMap<Int, ByteArray>()
        var segments = 0
        var position = 2
        while (position + 3 < file.size) {
            if ((file[position].toInt() and 0xff) != 0xff) {
                break
            }
            val marker = file[position + 1].toInt() and 0xff
            if (marker == SOS || marker == EOI) {
                break
            }
            val length = ((file[position + 2].toInt() and 0xff) shl 8) or (file[position + 3].toInt() and 0xff)
            val end = position + 2 + length
            if (end > file.size) {
                break
            }
            if (marker == APP11 && length >= 2 + IDENTIFIER.size + 4 && identifier(file, position + 4)) {
                val start = position + 4 + IDENTIFIER.size
                val sequence = ((file[start].toInt() and 0xff) shl 8) or (file[start + 1].toInt() and 0xff)
                segments = ((file[start + 2].toInt() and 0xff) shl 8) or (file[start + 3].toInt() and 0xff)
                parts[sequence] = file.copyOfRange(start + 4, end)
            }
            position = end
        }
        if (parts.isEmpty()) {
            return null
        }
        if (segments != parts.size) {
            throw IOException("An embedded alpha says it is $segments segments but ${parts.size} are in the file.")
        }
        val out = ByteArrayOutputStream(parts.values.sumOf { it.size })
        for (sequence in 1..segments) {
            out.write(parts[sequence] ?: throw IOException("An embedded alpha is missing segment $sequence of $segments."))
        }
        return out.toByteArray()
    }

    /**
     * [data] as the colour and alpha halves the source tree stores, losing nothing.
     *
     * @throws IOException when [data] is not a four component baseline JPEG, which is every JPEG in
     *   index 32 and the only shape [join] can put back together.
     */
    fun split(data: ByteArray): Parts {
        val image = BaselineJpeg.read(data)
        if (image.components.size != COMPONENTS) {
            throw IOException("A Jagex JPEG has four components; this one has ${image.components.size}.")
        }
        val colour = BaselineJpeg(image.width, image.height, restartInterval = image.restartInterval)
        for (index in 0 until COMPONENTS - 1) {
            val component = image.components[index]
            colour.quantisation[component.quantisation] = image.quantisation[component.quantisation]
            colour.dcTables[component.dc] = image.dcTables[component.dc]
            colour.acTables[component.ac] = image.acTables[component.ac]
            colour.components.add(component)
        }
        val fourth = image.components[COMPONENTS - 1]
        val alpha = BaselineJpeg(image.width, image.height, restartInterval = image.restartInterval)
        alpha.quantisation[0] = image.quantisation[fourth.quantisation]
        alpha.dcTables[0] = image.dcTables[fourth.dc]
        alpha.acTables[0] = image.acTables[fourth.ac]
        // Alone in its own frame the plane is a full resolution single component, so 1x1, and its
        // grid shrinks to the image's own blocks; the MCU padding it drops is what padding() rebuilds.
        val across = BaselineJpeg.ceil(image.width, BaselineJpeg.BLOCK)
        val down = BaselineJpeg.ceil(image.height, BaselineJpeg.BLOCK)
        val plane = JpegComponent(1, 1, 1, 0)
        plane.across = across
        plane.down = down
        plane.coefficients = ShortArray(across * down * BaselineJpeg.BLOCK_SIZE)
        for (row in 0 until down) {
            fourth.coefficients.copyInto(
                plane.coefficients,
                row * across * BaselineJpeg.BLOCK_SIZE,
                row * fourth.across * BaselineJpeg.BLOCK_SIZE,
                (row * fourth.across + across) * BaselineJpeg.BLOCK_SIZE
            )
        }
        alpha.components.add(plane)
        return Parts(colour.write(), alpha.write())
    }

    /**
     * The two halves back into the one four component file the client reads.
     *
     * Byte for byte the archive [split] came from while neither half has been edited. Once one has,
     * whatever tables the editor wrote are what comes out: the alpha's quantisation and Huffman
     * tables are matched against the colour file's by content and share an id where they are the
     * same table - which is what reproduces Jagex's own two `ffdb` and four `ffc4` segments - and get
     * an id of their own where they are not.
     *
     * A null [alpha] - an editor stripped the `APP11` segments - is an entirely opaque fourth
     * component built from the luminance component's own tables: [FLAT] is the DC a block of 255s
     * transforms to, so the smallest DC coefficient that dequantises to at least it decodes back to
     * 255 in every block, and the plane costs one Huffman code per block.
     */
    fun join(colour: ByteArray, alpha: ByteArray?): ByteArray {
        if (alpha == null) {
            return opaque(BaselineJpeg.read(colour))
        }
        val image = BaselineJpeg.read(colour)
        val plane = BaselineJpeg.read(alpha)
        if (plane.components.size != 1) {
            throw IOException("An alpha JPEG is one component; this one has ${plane.components.size}.")
        }
        if (plane.width != image.width || plane.height != image.height) {
            throw IOException(
                "An alpha JPEG is ${plane.width}x${plane.height} but its colour JPEG is ${image.width}x${image.height}."
            )
        }
        val source = plane.components[0]
        val quantisation = assign(image.quantisation, plane.quantisation[source.quantisation], "quantisation")
        val dc = assign(image.dcTables, plane.dcTables[source.dc], "DC Huffman")
        val ac = assign(image.acTables, plane.acTables[source.ac], "AC Huffman")
        // Alpha is never subsampled, so it takes the frame's largest factors - 2x2 in the cache's
        // own files, and whatever an edited colour file uses otherwise.
        val horizontal = image.maxHorizontal
        val vertical = image.maxVertical
        val fourth = JpegComponent(COMPONENTS, horizontal, vertical, quantisation)
        fourth.dc = dc
        fourth.ac = ac
        fourth.across = image.mcusAcross * horizontal
        fourth.down = image.mcusDown * vertical
        fourth.coefficients = ShortArray(fourth.across * fourth.down * BaselineJpeg.BLOCK_SIZE)
        val across = BaselineJpeg.ceil(image.width, BaselineJpeg.BLOCK)
        val down = BaselineJpeg.ceil(image.height, BaselineJpeg.BLOCK)
        if (source.across < across || source.down < down) {
            throw IOException(
                "An alpha JPEG holds ${source.across}x${source.down} blocks where ${across}x$down are needed."
            )
        }
        for (row in 0 until down) {
            source.coefficients.copyInto(
                fourth.coefficients,
                row * fourth.across * BaselineJpeg.BLOCK_SIZE,
                row * source.across * BaselineJpeg.BLOCK_SIZE,
                (row * source.across + across) * BaselineJpeg.BLOCK_SIZE
            )
        }
        padding(fourth, across, down)
        image.components.add(fourth)
        return image.write()
    }

    /**
     * [data] as a straight (un-premultiplied) `TYPE_INT_ARGB` image, by the client's rules.
     *
     * The only way to read one of these: [javax.imageio.ImageReader.readRaster] hands back the
     * components exactly as they were coded, with no colour conversion attempted and so nothing to
     * refuse. Three component files - none are in 727, but the sniff is on the magic and not on a
     * list of archives - decode the same way with every pixel opaque.
     */
    fun decode(data: ByteArray): BufferedImage {
        val raster = raster(data)
        val width = raster.width
        val height = raster.height
        val bands = raster.numBands
        if (bands != COMPONENTS && bands != COMPONENTS - 1) {
            throw IOException("A Jagex JPEG is 3 or 4 components; this one is $bands.")
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val samples = IntArray(width * bands)
        val row = IntArray(width)
        for (y in 0 until height) {
            raster.getPixels(0, y, width, 1, samples)
            for (x in 0 until width) {
                val offset = x * bands
                val luma = samples[offset]
                val blue = samples[offset + 1] - HALF
                val red = samples[offset + 2] - HALF
                val alpha = if (bands == COMPONENTS) samples[offset + 3] else OPAQUE
                row[x] = (alpha shl 24) or
                    (clamp(luma + R_CR * red) shl 16) or
                    (clamp(luma - G_CB * blue - G_CR * red) shl 8) or
                    clamp(luma + B_CB * blue)
            }
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        return image
    }

    /** [image] with an entirely opaque fourth component added, coded like its luminance. */
    private fun opaque(image: BaselineJpeg): ByteArray {
        val luma = image.components[0]
        val table = image.quantisation[luma.quantisation]
            ?: throw IOException("A JPEG's luminance quantisation table is not defined.")
        val fourth = JpegComponent(COMPONENTS, image.maxHorizontal, image.maxVertical, luma.quantisation)
        fourth.dc = luma.dc
        fourth.ac = luma.ac
        fourth.across = image.mcusAcross * fourth.horizontal
        fourth.down = image.mcusDown * fourth.vertical
        fourth.coefficients = ShortArray(fourth.across * fourth.down * BaselineJpeg.BLOCK_SIZE)
        val level = BaselineJpeg.ceil(FLAT, table[0]).toShort()
        for (block in 0 until fourth.across * fourth.down) {
            fourth.coefficients[block * BaselineJpeg.BLOCK_SIZE] = level
        }
        image.components.add(fourth)
        return image.write()
    }

    /** [image] through the platform writer, which is the only re-compression anywhere here. */
    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(FORMAT).next()
        try {
            val out = ByteArrayOutputStream(image.width * image.height)
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = QUALITY
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    /** Whether [IDENTIFIER] is at [offset] of [data]. */
    private fun identifier(data: ByteArray, offset: Int): Boolean {
        for (index in IDENTIFIER.indices) {
            if (data[offset + index] != IDENTIFIER[index]) {
                return false
            }
        }
        return true
    }

    /**
     * Fill every block of [component] outside [across] by [down] the way `libjpeg` does at an edge:
     * all AC zero, and the DC copied from the block before it.
     *
     * "Before it" is along the row for a block past the right edge, and for a dummy row it is the
     * last block of the row above *within the same MCU* - which is why every block of such a row
     * ends up with the one value. That is `jccoefct.c`'s `compress_data` exactly, and it is what
     * makes the six files with an odd block count rebuild byte for byte.
     */
    private fun padding(component: JpegComponent, across: Int, down: Int) {
        val mcusAcross = component.across / component.horizontal
        val mcusDown = component.down / component.vertical
        for (mcuY in 0 until mcusDown) {
            for (offsetY in 0 until component.vertical) {
                val blockY = mcuY * component.vertical + offsetY
                val dummy = blockY >= down
                if (dummy && offsetY == 0) {
                    throw IOException("An alpha JPEG's MCU row $mcuY is padding, which cannot happen.")
                }
                for (mcuX in 0 until mcusAcross) {
                    for (offsetX in 0 until component.horizontal) {
                        val blockX = mcuX * component.horizontal + offsetX
                        if (!dummy && blockX < across) {
                            continue
                        }
                        val offset = (blockY * component.across + blockX) * BaselineJpeg.BLOCK_SIZE
                        component.coefficients.fill(0, offset, offset + BaselineJpeg.BLOCK_SIZE)
                        val previous = if (dummy && offsetX == 0) {
                            (blockY - 1) * component.across + mcuX * component.horizontal + component.horizontal - 1
                        } else {
                            blockY * component.across + blockX - 1
                        }
                        component.coefficients[offset] =
                            component.coefficients[previous * BaselineJpeg.BLOCK_SIZE]
                    }
                }
            }
        }
    }

    /** [want]'s id in [tables]: the id that already holds it, else the lowest free one. */
    private fun assign(tables: Array<IntArray?>, want: IntArray?, kind: String): Int {
        val values = want ?: throw IOException("An alpha JPEG's $kind table is not defined.")
        for (table in tables.indices) {
            if (values.contentEquals(tables[table])) {
                return table
            }
        }
        for (table in tables.indices) {
            if (tables[table] == null) {
                tables[table] = values
                return table
            }
        }
        throw IOException("A joined JPEG needs a fifth $kind table; baseline JPEG allows four.")
    }

    private fun assign(tables: Array<JpegHuffmanTable?>, want: JpegHuffmanTable?, kind: String): Int {
        val values = want ?: throw IOException("An alpha JPEG's $kind table is not defined.")
        for (table in tables.indices) {
            if (values.sameAs(tables[table])) {
                return table
            }
        }
        for (table in tables.indices) {
            if (tables[table] == null) {
                tables[table] = values
                return table
            }
        }
        throw IOException("A joined JPEG needs a fifth $kind table; baseline JPEG allows four.")
    }

    /** The components exactly as they were coded, which is the only read that does not colour convert. */
    private fun raster(data: ByteArray): Raster {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(data))
        val readers = ImageIO.getImageReadersByFormatName(FORMAT)
        if (!readers.hasNext()) {
            throw IOException("This build has no JPEG reader.")
        }
        val reader = readers.next()
        try {
            reader.setInput(stream, true, true)
            return reader.readRaster(0, null)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun clamp(value: Double): Int {
        val rounded = value.roundToInt()
        return if (rounded < 0) 0 else if (rounded > OPAQUE) OPAQUE else rounded
    }

    private const val FORMAT = "JPEG"

    /** Y, Cb, Cr and alpha, and so also the fourth component's id. */
    private const val COMPONENTS = 4

    private const val OPAQUE = 255

    private const val HALF = 128

    /** ITU-R BT.601, the matrix JFIF specifies and every JPEG in this cache was written with. */
    private const val R_CR = 1.402
    private const val G_CB = 0.344136
    private const val G_CR = 0.714136
    private const val B_CB = 1.772

    private const val MAGIC_0 = 0xff.toByte()

    private const val MAGIC_1 = 0xd8.toByte()

    /** The application segment the alpha codestream rides in, and the two markers that end a header. */
    private const val APP11 = 0xeb

    private const val SOS = 0xda

    private const val EOI = 0xd9

    /** What an `APP11` segment of this build's says it is, null terminated as JPEG conventions have it. */
    private val IDENTIFIER = "JagexAlpha\u0000".toByteArray(Charsets.US_ASCII)

    /** A segment's length field counts itself, so 65,533 bytes follow it at most. */
    private const val SEGMENT = 65533

    /** What is left of a segment for the codestream after the identifier, sequence and count. */
    private const val PAYLOAD = SEGMENT - 11 - 4

    /** The marker, the length, the identifier, the sequence and the count. */
    private const val OVERHEAD = 4 + 11 + 4

    /** The DC coefficient a block of 255s transforms to: `8 * (255 - 128)`. */
    private const val FLAT = 1016

    /** What a re-compressed half is written at, which is above the cache's own q75. */
    private const val QUALITY = 0.95f
}
