package org.drynor.tools.rs3port

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SpriteBackground { NONE, ROUNDEL, KEYED }

sealed interface SpriteSource {
    data class Graphic(val id: Int) : SpriteSource
    data class ObjIcon(val obj: Int) : SpriteSource
}

class SpriteImport(
    val source: SpriteSource,
    val graphic: Int,
    val gameval: String,
    val size: Int = DEFAULT_SIZE,
    val background: SpriteBackground = SpriteBackground.NONE,
    val rename: Boolean = false
) {
    companion object {
        const val DEFAULT_SIZE = 24
    }
}

class SpriteImporter(private val rs3Tree: Path, private val sourceTree: Path, private val crosswalk: () -> Crosswalk) {

    fun run(import: SpriteImport) {
        val renamed = checkName(import)
        val source = when (val origin = import.source) {
            is SpriteSource.Graphic -> Rgba.read(rs3Tree.resolve("graphics").resolve("${origin.id}/0.png"))
            is SpriteSource.ObjIcon -> Rs3ObjIcon.render(rs3Tree, origin.obj, crosswalk())
        }
        when (import.background) {
            SpriteBackground.NONE -> Unit
            SpriteBackground.ROUNDEL -> SpriteBackdrop.roundel(source)
            SpriteBackground.KEYED -> SpriteBackdrop.keyed(source)
        }
        val small = SpriteScaler.downscale(source, import.size)
        val image = SpritePalette.reduce(small)
        val sprite = SourceCodecFiles.graphic(sourceTree, import.graphic, image)
        val findings = GrammarFindings()
        findings.check("graphic ${import.graphic}", ClientGrammar.sprite(import.graphic, sprite.packed.single()))
        findings.refuse()
        val writer = TreeWriter(sourceTree)
        writer.add(sprite)
        val written = writer.flush()
        val names = mapOf(import.graphic to import.gameval)
        if (renamed != null) {
            SourceEdits.renameArchive(sourceTree, "graphics", import.graphic, import.gameval.hashCode())
            SourceEdits.renameEntry(sourceTree, "graphic", "Graphic", import.graphic, renamed, import.gameval)
        }
        val lines = SourceEdits.archiveIndex(sourceTree, "graphics", names.mapValues { SourceEdits.named(it.value.hashCode()) })
        val entries = SourceEdits.catalog(sourceTree, "graphic", names)
        val declarations = SourceEdits.declarations(sourceTree, "Graphic", "graphic", names)
        val from = renamed?.let { ", renamed from $it" }.orEmpty()
        println("graphic ${import.graphic} ${import.gameval} from rs3 ${import.source} (${import.background.name.lowercase()}, ${import.size}px$from)")
        println("  index lines $lines, catalog entries $entries, declarations $declarations")
        for ((path, state) in written) {
            println("  $state $path")
        }
    }

    private fun checkName(import: SpriteImport): String? {
        val catalog = Files.readString(sourceTree.resolve("gamevals").resolve("graphic.json"))
        val existing = Regex("\"${import.graphic}\": \"([^\"]*)\"").find(catalog.substringAfter("\"entries\""))?.groupValues?.get(1)
        if (existing != null && existing != import.gameval) {
            check(import.rename) {
                "graphic ${import.graphic} is already named $existing; pass --rename to name it ${import.gameval}."
            }
            return existing
        }
        val index = Files.readAllLines(sourceTree.resolve("graphics").resolve("index.json"))
        val line = index.firstOrNull { it.trimStart().startsWith("\"${import.graphic}\":") } ?: return null
        check(line.contains("\"nameHash\": ${import.gameval.hashCode()}")) {
            "graphics/index.json archive ${import.graphic} does not carry the name hash of ${import.gameval}: $line"
        }
        return null
    }
}

class Rgba(val width: Int, val height: Int, val pixels: DoubleArray = DoubleArray(width * height * 4)) {

    operator fun get(x: Int, y: Int, channel: Int): Double = pixels[(x + y * width) * 4 + channel]

    operator fun set(x: Int, y: Int, channel: Int, value: Double) {
        pixels[(x + y * width) * 4 + channel] = value
    }

    companion object {

        fun read(path: Path): Rgba {
            val image = ImageIO.read(path.toFile()) ?: throw IllegalArgumentException("$path is not a readable image.")
            val out = Rgba(image.width, image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    out[x, y, RED] = ((argb shr 16) and 0xff).toDouble()
                    out[x, y, GREEN] = ((argb shr 8) and 0xff).toDouble()
                    out[x, y, BLUE] = (argb and 0xff).toDouble()
                    out[x, y, ALPHA] = (argb ushr 24).toDouble()
                }
            }
            return out
        }

        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val ALPHA = 3
    }
}

object SpriteBackdrop {

    private const val ROUNDEL_TOLERANCE = 6.0

    private val SLATE = doubleArrayOf(44.0, 50.0, 54.0)

    private const val KEY_THRESHOLD = 4.0

    private const val KEY_RAMP = 22.0

    fun roundel(image: Rgba) {
        val width = image.width
        val height = image.height
        val reference = DoubleArray(3) { image[0, 0, it] }
        val seen = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()
        listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1).forEach { (x, y) -> queue.add(x + y * width) }
        while (queue.isNotEmpty()) {
            val position = queue.removeFirst()
            if (seen[position]) {
                continue
            }
            val x = position % width
            val y = position / width
            if ((0 until 3).any { abs(image[x, y, it] - reference[it]) > ROUNDEL_TOLERANCE }) {
                continue
            }
            seen[position] = true
            image[x, y, Rgba.ALPHA] = 0.0
            if (x + 1 < width) queue.add(position + 1)
            if (x > 0) queue.add(position - 1)
            if (y + 1 < height) queue.add(position + width)
            if (y > 0) queue.add(position - width)
        }
    }

    fun keyed(image: Rgba) {
        val slateLuminance = SLATE.average()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val distance = sqrt((0 until 3).sumOf { (image[x, y, it] - SLATE[it]).let { d -> d * d } })
                val luminance = (0 until 3).sumOf { image[x, y, it] } / 3
                val alpha = ((distance - KEY_THRESHOLD) / KEY_RAMP).coerceIn(0.0, 1.0)
                if (luminance < slateLuminance && alpha < 1) {
                    for (channel in 0 until 3) {
                        image[x, y, channel] = 0.0
                    }
                    image[x, y, Rgba.ALPHA] = ((slateLuminance - luminance) / slateLuminance).coerceIn(0.0, 1.0) * 255
                } else {
                    image[x, y, Rgba.ALPHA] = alpha * 255
                }
            }
        }
    }
}

object SpriteScaler {

    private const val LOBES = 3.0

    private const val MIN_ALPHA = 8

    fun downscale(image: Rgba, size: Int): Rgba {
        val premultiplied = Rgba(image.width, image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image[x, y, Rgba.ALPHA]
                for (channel in 0 until 3) {
                    premultiplied[x, y, channel] = image[x, y, channel] * alpha / 255
                }
                premultiplied[x, y, Rgba.ALPHA] = alpha
            }
        }
        val scaled = vertical(horizontal(premultiplied, size), size)
        val out = Rgba(size, size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val alpha = scaled[x, y, Rgba.ALPHA].coerceIn(0.0, 255.0).roundToInt()
                if (alpha < MIN_ALPHA) {
                    continue
                }
                for (channel in 0 until 3) {
                    val premultipliedChannel = scaled[x, y, channel].coerceIn(0.0, 255.0)
                    out[x, y, channel] = (premultipliedChannel * 255 / alpha).coerceIn(0.0, 255.0)
                }
                out[x, y, Rgba.ALPHA] = alpha.toDouble()
            }
        }
        return out
    }

    private fun horizontal(image: Rgba, width: Int): Rgba {
        val out = Rgba(width, image.height)
        val taps = taps(image.width, width)
        for (y in 0 until image.height) {
            for ((x, tap) in taps.withIndex()) {
                for (channel in 0 until 4) {
                    var sum = 0.0
                    for (k in tap.weights.indices) {
                        sum += image[tap.first + k, y, channel] * tap.weights[k]
                    }
                    out[x, y, channel] = sum
                }
            }
        }
        return out
    }

    private fun vertical(image: Rgba, height: Int): Rgba {
        val out = Rgba(image.width, height)
        val taps = taps(image.height, height)
        for ((y, tap) in taps.withIndex()) {
            for (x in 0 until image.width) {
                for (channel in 0 until 4) {
                    var sum = 0.0
                    for (k in tap.weights.indices) {
                        sum += image[x, tap.first + k, channel] * tap.weights[k]
                    }
                    out[x, y, channel] = sum
                }
            }
        }
        return out
    }

    private class Tap(val first: Int, val weights: DoubleArray)

    private fun taps(from: Int, to: Int): List<Tap> {
        val scale = from.toDouble() / to
        val stretch = max(scale, 1.0)
        val support = LOBES * stretch
        return List(to) { position ->
            val centre = (position + 0.5) * scale
            val first = max(0, floor(centre - support + 0.5).toInt())
            val last = min(from, ceil(centre + support + 0.5).toInt())
            val weights = DoubleArray(last - first) { lanczos((first + it - centre + 0.5) / stretch) }
            val total = weights.sum()
            if (total != 0.0) {
                for (k in weights.indices) {
                    weights[k] /= total
                }
            }
            Tap(first, weights)
        }
    }

    private fun lanczos(x: Double): Double = if (abs(x) >= LOBES) 0.0 else sinc(x) * sinc(x / LOBES)

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
}

object SpritePalette {

    private const val LIMIT = 255

    fun reduce(image: Rgba): BufferedImage {
        val alphas = IntArray(image.width * image.height)
        val colours = IntArray(alphas.size)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val position = x + y * image.width
                alphas[position] = image[x, y, Rgba.ALPHA].roundToInt()
                colours[position] = (0 until 3).fold(0) { rgb, channel -> (rgb shl 8) or image[x, y, channel].roundToInt() }
            }
        }
        val counts = HashMap<Int, Int>()
        for (position in colours.indices) {
            if (alphas[position] != 0) {
                counts.merge(colours[position], 1, Int::plus)
            }
        }
        val mapping = if (counts.size <= LIMIT) counts.keys.associateWith { it } else medianCut(counts)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (position in colours.indices) {
            val argb = if (alphas[position] == 0) 0 else (alphas[position] shl 24) or mapping.getValue(colours[position])
            out.setRGB(position % image.width, position / image.width, argb)
        }
        return out
    }

    private class Box(val colours: List<Int>, val counts: Map<Int, Int>) {

        private fun channel(colour: Int, channel: Int): Int = (colour shr (16 - channel * 8)) and 0xff

        fun range(channel: Int): Int = colours.maxOf { channel(it, channel) } - colours.minOf { channel(it, channel) }

        fun widest(): Int = (0 until 3).maxBy { range(it) }

        fun split(): Pair<Box, Box> {
            val channel = widest()
            val sorted = colours.sortedBy { channel(it, channel) }
            val half = sorted.sumOf { counts.getValue(it) } / 2
            var seen = 0
            var cut = 1
            for ((index, colour) in sorted.withIndex()) {
                seen += counts.getValue(colour)
                if (seen >= half) {
                    cut = (index + 1).coerceIn(1, sorted.size - 1)
                    break
                }
            }
            return Box(sorted.subList(0, cut), counts) to Box(sorted.subList(cut, sorted.size), counts)
        }

        fun mean(): Int {
            val total = colours.sumOf { counts.getValue(it).toLong() }.toDouble()
            return (0 until 3).fold(0) { rgb, channel ->
                val value = colours.sumOf { channel(it, channel).toLong() * counts.getValue(it) } / total
                (rgb shl 8) or value.roundToInt()
            }
        }
    }

    private fun medianCut(counts: Map<Int, Int>): Map<Int, Int> {
        val boxes = mutableListOf(Box(counts.keys.toList(), counts))
        while (boxes.size < LIMIT) {
            val box = boxes.filter { it.colours.size > 1 }.maxByOrNull { it.range(it.widest()) } ?: break
            boxes.remove(box)
            val (low, high) = box.split()
            boxes.add(low)
            boxes.add(high)
        }
        val palette = boxes.map { it.mean() }.distinct()
        return counts.keys.associateWith { colour -> palette.minBy { distance(it, colour) } }
    }

    private fun distance(a: Int, b: Int): Int = (0 until 3).sumOf {
        val shift = 16 - it * 8
        val d = ((a shr shift) and 0xff) - ((b shr shift) and 0xff)
        d * d
    }
}
