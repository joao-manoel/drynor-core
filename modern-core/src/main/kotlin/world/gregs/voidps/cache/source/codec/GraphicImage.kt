package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * One graphic frame as one standard PNG, and everything a byte exact encoder needs written inside
 * the same file.
 *
 * The picture is **8 bit RGBA truecolour at the group's canvas size** - the canvas the client
 * composes every frame of the group onto - with the frame painted at its own offset and the rest
 * fully transparent. So the pixels alone say where the frame is (trim the transparent border), what
 * colours it uses and how opaque each one is, which is what makes a PNG dropped in by any image
 * editor packable: see [GraphicCodec] for the derivation. The colours are the palette entries the
 * *client* renders - the normalised ones, entry 0 apart - and the alpha is the client's own rule:
 * the frame's alpha plane where it has one, else opaque for every pixel whose palette index is not
 * 0 and transparent for index 0, which is the client's transparent colour.
 *
 * What the pixels cannot say rides along in three **private ancillary PNG chunks**, which every
 * decoder ignores and which make the unedited round trip byte exact:
 *
 * | chunk | scope | holds |
 * |---|---|---|
 * | `rsPL` | the group | the palette exactly as the file spells it, entry 0 first, and `paletteZeroEntry` |
 * | `rsFR` | the frame | the archive and frame ids, the canvas, the frame's box, the format byte and which shape its alpha plane is |
 * | `rsIX` | the frame | the palette index raster, only where the colours cannot recover it |
 *
 * All three are lowercase-first (ancillary) and lowercase-second (private) with an uppercase fourth
 * letter, which is PNG's "unsafe to copy": an editor that rewrites the pixels is told by the type
 * itself to drop them rather than carry a stale one forward. An editor that strips them entirely -
 * which is most of them - loses nothing but the byte exactness of frames the pixels under-determine;
 * see `docs/cache-source.md`, "graphics".
 *
 * The PNG is written through an in-memory stream rather than `ImageIO.write`, which otherwise spills
 * to a temporary file per image and is process-wide state to turn off, and the chunks are spliced in
 * straight after `IHDR` so the file stays a fixed, deterministic byte sequence for a given frame -
 * re-unpacking an unchanged cache must rewrite nothing.
 */
internal object GraphicImage {

    /** The group's palette, as `rsPL` carries it. */
    class Palette(
        /** The entries exactly as the graphic file spells them, entry 0 first. */
        val entries: IntArray,
        /** Whether the file writes an explicit `000000` for entry 0; [GraphicDefinition.paletteZeroEntry]. */
        val zeroEntry: Boolean
    )

    /** One frame's placement and format, as `rsFR` carries it. */
    class Frame(
        val archive: Int,
        val frame: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val minX: Int,
        val minY: Int,
        val width: Int,
        val height: Int,
        /** The frame's format byte; [IndexedGraphic.flags]. */
        val flags: Int,
        /** [NO_PLANE], [PLANE] or [OPAQUE_PLANE]. */
        val alpha: Int
    )

    /** `rsIX`'s payload: the frame's palette indices, row major over the frame's own box. */
    class Raster(val width: Int, val height: Int, val values: ByteArray)

    /** A frame PNG as it is on disk: the pixels, and whichever of the three chunks survived. */
    class Image(
        val width: Int,
        val height: Int,
        /** Non-premultiplied ARGB, row major, [width] x [height]. */
        val pixels: IntArray,
        val palette: Palette?,
        val frame: Frame?,
        /** `rsIX`'s raster, row major over the frame's box, or null. */
        val indices: Raster?
    )

    /** The frame's format byte has no alpha bit, so index 0 is its only transparency. */
    const val NO_PLANE = 0

    /** The frame carries an alpha plane and the PNG's alpha channel *is* that plane. */
    const val PLANE = 1

    /**
     * The frame carries an alpha plane that is entirely opaque, which the decoder (and the client)
     * drops - so only this says the plane was there, and the PNG's alpha channel is the index 0
     * rule rather than the plane.
     */
    const val OPAQUE_PLANE = 2

    /** [graphic] as the one PNG the source tree stores it in. */
    fun write(archive: Int, index: Int, definition: GraphicDefinition, graphic: IndexedGraphic): ByteArray {
        val canvasWidth = maxOf(definition.width, 1)
        val canvasHeight = maxOf(definition.height, 1)
        val palette = normalise(definition.rawPalette)
        val plane = graphic.alpha
        val kind = when {
            graphic.flags and ALPHA_FLAG == 0 -> NO_PLANE
            plane != null -> PLANE
            else -> OPAQUE_PLANE
        }
        val image = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
        val lookup = lookup(definition.rawPalette, palette)
        var raster: ByteArray? = null
        for (y in 0 until graphic.height) {
            for (x in 0 until graphic.width) {
                val offset = x + y * graphic.width
                val entry = graphic.raster[offset].toInt() and 0xff
                val alpha = if (plane != null) plane[offset].toInt() and 0xff else if (entry == 0) 0 else OPAQUE
                image.setRGB(x + graphic.offsetX, y + graphic.offsetY, (alpha shl 24) or palette[entry])
                // The index the colours alone would recover; where that is not the index the frame
                // actually holds, rsIX has to carry the raster.
                val recovered = if (alpha == 0) 0 else lookup.getOrDefault(palette[entry], -1)
                if (recovered != entry && raster == null) {
                    raster = graphic.raster
                }
            }
        }
        val chunks = ArrayList<ByteArray>(3)
        chunks.add(chunk(PALETTE_CHUNK, palette(definition)))
        chunks.add(chunk(FRAME_CHUNK, frame(archive, index, definition, graphic, kind)))
        if (raster != null) {
            chunks.add(chunk(INDEX_CHUNK, indices(graphic, raster)))
        }
        return splice(png(image), chunks)
    }

    /** [bytes] as its pixels and its chunks; [name] names the file in any error. */
    fun read(bytes: ByteArray, name: String): Image {
        val image = decode(bytes, name)
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        var palette: Palette? = null
        var frame: Frame? = null
        var indices: Raster? = null
        for (chunk in chunks(bytes, name)) {
            when (chunk.first) {
                PALETTE_CHUNK -> palette = palette(chunk.second, name)
                FRAME_CHUNK -> frame = frame(chunk.second, name)
                INDEX_CHUNK -> indices = indices(chunk.second, name)
            }
        }
        return Image(width, height, pixels, palette, frame, indices)
    }

    /** The palette the client renders: entry 0 apart, an entry that really is black becomes 1. */
    fun normalise(raw: IntArray): IntArray {
        val palette = raw.copyOf()
        for (index in 1 until palette.size) {
            if (palette[index] == 0) {
                palette[index] = 1
            }
        }
        return palette
    }

    /**
     * Colour to palette index, for entries 1 and up: the normalised colour first, then the raw one
     * where nothing claims it, and the lowest index wins a tie.
     *
     * Entry 0 is deliberately unreachable - it is the transparent index and a fully transparent
     * pixel is the only thing that recovers it - so an opaque pixel that really is black lands on
     * whichever entry the file spells `000000` and the client renders as 1.
     */
    fun lookup(raw: IntArray, palette: IntArray): MutableMap<Int, Int> {
        val out = HashMap<Int, Int>(palette.size * 2)
        for (index in 1 until palette.size) {
            out.putIfAbsent(palette[index], index)
        }
        for (index in 1 until raw.size) {
            out.putIfAbsent(raw[index], index)
        }
        return out
    }

    private fun palette(definition: GraphicDefinition): ByteArray {
        val entries = definition.rawPalette
        val out = ByteArray(4 + entries.size * COLOUR)
        out[0] = VERSION
        out[1] = if (definition.paletteZeroEntry) 1 else 0
        out[2] = (entries.size shr 8).toByte()
        out[3] = entries.size.toByte()
        for (index in entries.indices) {
            val offset = 4 + index * COLOUR
            out[offset] = (entries[index] shr 16).toByte()
            out[offset + 1] = (entries[index] shr 8).toByte()
            out[offset + 2] = entries[index].toByte()
        }
        return out
    }

    private fun palette(payload: ByteArray, name: String): Palette {
        if (payload.size < 4 || payload[0] != VERSION) {
            throw IOException("$name's $PALETTE_CHUNK chunk is not version $VERSION.")
        }
        val size = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        if (size < 1 || 4 + size * COLOUR != payload.size) {
            throw IOException("$name's $PALETTE_CHUNK chunk says $size entries but is ${payload.size} bytes.")
        }
        val entries = IntArray(size)
        for (index in 0 until size) {
            val offset = 4 + index * COLOUR
            entries[index] = ((payload[offset].toInt() and 0xff) shl 16) or
                ((payload[offset + 1].toInt() and 0xff) shl 8) or
                (payload[offset + 2].toInt() and 0xff)
        }
        return Palette(entries, payload[1].toInt() and 1 != 0)
    }

    private fun frame(
        archive: Int,
        index: Int,
        definition: GraphicDefinition,
        graphic: IndexedGraphic,
        kind: Int
    ): ByteArray {
        val out = ByteArray(FRAME_SIZE)
        out[0] = VERSION
        write32(out, 1, archive)
        write16(out, 5, index)
        write16(out, 7, definition.width)
        write16(out, 9, definition.height)
        write16(out, 11, graphic.offsetX)
        write16(out, 13, graphic.offsetY)
        write16(out, 15, graphic.width)
        write16(out, 17, graphic.height)
        out[19] = graphic.flags.toByte()
        out[20] = kind.toByte()
        return out
    }

    private fun frame(payload: ByteArray, name: String): Frame {
        if (payload.size != FRAME_SIZE || payload[0] != VERSION) {
            throw IOException("$name's $FRAME_CHUNK chunk is not ${FRAME_SIZE} bytes of version $VERSION.")
        }
        return Frame(
            archive = read32(payload, 1),
            frame = read16(payload, 5),
            canvasWidth = read16(payload, 7),
            canvasHeight = read16(payload, 9),
            minX = read16(payload, 11),
            minY = read16(payload, 13),
            width = read16(payload, 15),
            height = read16(payload, 17),
            flags = payload[19].toInt() and 0xff,
            alpha = payload[20].toInt() and 0xff
        )
    }

    private fun indices(graphic: IndexedGraphic, raster: ByteArray): ByteArray {
        val out = ByteArray(5 + raster.size)
        out[0] = VERSION
        write16(out, 1, graphic.width)
        write16(out, 3, graphic.height)
        raster.copyInto(out, 5)
        return out
    }

    private fun indices(payload: ByteArray, name: String): Raster {
        if (payload.size < 5 || payload[0] != VERSION) {
            throw IOException("$name's $INDEX_CHUNK chunk is not version $VERSION.")
        }
        val width = read16(payload, 1)
        val height = read16(payload, 3)
        if (5 + width * height != payload.size) {
            throw IOException("$name's $INDEX_CHUNK chunk says ${width}x$height but is ${payload.size} bytes.")
        }
        return Raster(width, height, payload.copyOfRange(5, payload.size))
    }

    /** [type] and [payload] as a PNG chunk, length and CRC and all. */
    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        write32(out, 0, payload.size)
        for (index in 0 until 4) {
            out[4 + index] = type[index].code.toByte()
        }
        payload.copyInto(out, 8)
        val crc = CRC32()
        crc.update(out, 4, 4 + payload.size)
        write32(out, 8 + payload.size, crc.value.toInt())
        return out
    }

    /** [png] with [chunks] inserted straight after its `IHDR`. */
    private fun splice(png: ByteArray, chunks: List<ByteArray>): ByteArray {
        val insert = SIGNATURE + 12 + IHDR_SIZE
        if (png.size < insert) {
            throw IOException("A PNG this build wrote is ${png.size} bytes, too short for an IHDR.")
        }
        val out = ByteArrayOutputStream(png.size + chunks.sumOf { it.size })
        out.write(png, 0, insert)
        for (chunk in chunks) {
            out.write(chunk)
        }
        out.write(png, insert, png.size - insert)
        return out.toByteArray()
    }

    /** Every chunk of [bytes] this cares about, in file order, as type to payload. */
    private fun chunks(bytes: ByteArray, name: String): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>(3)
        var position = SIGNATURE
        while (position + 8 <= bytes.size) {
            val length = read32(bytes, position)
            if (length < 0 || position + 12 + length > bytes.size) {
                throw IOException("$name has a chunk at $position that runs past the end of the file.")
            }
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            if (type in CHUNKS) {
                out.add(type to bytes.copyOfRange(position + 8, position + 8 + length))
            }
            position += 12 + length
        }
        return out
    }

    /**
     * The image, through a reader this owns.
     *
     * `ImageIO.read` disposes the stream it is handed, so the stream cannot also be closed by the
     * caller; driving the reader directly keeps both halves of this file symmetrical.
     */
    private fun decode(bytes: ByteArray, name: String): BufferedImage {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) {
            throw IOException("$name is not an image this build can read.")
        }
        val reader = readers.next()
        try {
            reader.input = stream
            return reader.read(0)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height + HEADER)
        val writer = ImageIO.getImageWritersByFormatName(FORMAT).next()
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

    private fun write16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value shr 8).toByte()
        out[offset + 1] = value.toByte()
    }

    private fun write32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value shr 24).toByte()
        out[offset + 1] = (value shr 16).toByte()
        out[offset + 2] = (value shr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun read32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    /** The group's palette, entry 0 first, exactly as the graphic file spells it. */
    private const val PALETTE_CHUNK = GraphicCodec.PALETTE_CHUNK

    /** The frame's placement in the group canvas, and its format byte. */
    private const val FRAME_CHUNK = GraphicCodec.FRAME_CHUNK

    /** The frame's palette index raster, where the colours alone cannot recover it. */
    private const val INDEX_CHUNK = GraphicCodec.INDEX_CHUNK

    private val CHUNKS = setOf(PALETTE_CHUNK, FRAME_CHUNK, INDEX_CHUNK)

    /** Every chunk this writes starts with its own version byte, so a reader can refuse a newer one. */
    private const val VERSION: Byte = 1

    /** `rsFR`: a version, an int, eight shorts and two bytes. */
    private const val FRAME_SIZE = 21

    /** The frame carries an alpha plane after its pixels. */
    private const val ALPHA_FLAG = 0x2

    private const val OPAQUE = 0xff

    private const val COLOUR = 3

    /** The PNG signature, which every chunk sits after. */
    private const val SIGNATURE = 8

    /** `IHDR`'s payload: width, height, depth, colour, compression, filter, interlace. */
    private const val IHDR_SIZE = 13

    private const val FORMAT = "png"

    /** Roughly what a PNG's chunks cost before any pixels. */
    private const val HEADER = 1024
}
