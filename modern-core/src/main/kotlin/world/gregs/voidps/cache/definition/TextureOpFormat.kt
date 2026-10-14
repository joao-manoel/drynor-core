package world.gregs.voidps.cache.definition

/**
 * The wire shape of index 9's procedural texture operations: how many inputs each operation type
 * takes, and how many bytes each of its opcodes carries.
 *
 * A texture is a small DAG of operations and the file spells each one out as
 * `[index][type][image cache capacity][opcode count][opcode, payload]...[input index]...`. The
 * payload's length is a property of the *type*, not of the opcode byte, so reading a texture at
 * all needs this table - it is the one thing standing between the file and a desync, and it is
 * transcribed from the client's `TextureOpX.decodeOpcode` implementations.
 *
 * **An opcode a type does not handle carries nothing.** The client's `when` simply falls through,
 * so the byte is a no-op and the next byte is the next opcode. That is not a theoretical case:
 * twelve of the 727 cache's textures give the waveform operation opcodes 2, 4, 5 and 6, which it
 * has never handled, and reading them any other way runs off the end of the file. [payload]
 * therefore answers [NONE] for anything it does not know rather than refusing.
 *
 * Four payloads are variable length and each is its own shape - see [CURVE], [GRADIENT], [RASTER]
 * and [PERLIN].
 */
object TextureOpFormat {

    /** The opcode carries no payload: the type ignores it, exactly as the client does. */
    const val NONE = 0

    /** One unsigned byte. */
    const val BYTE = 1

    /** One unsigned short. */
    const val SHORT = 2

    /** One unsigned 3 byte colour. */
    const val MEDIUM = 3

    /** Curve (type 8): an interpolation mode byte, a point count byte, then that many `x, y` shorts. */
    const val CURVE = 4

    /**
     * Colour gradient (type 10): a preset byte; when it is 0, a stop count byte and that many
     * `position` short plus `r`, `g`, `b` byte stops. A nonzero preset carries nothing else.
     */
    const val GRADIENT = 5

    /**
     * Rasterizer (type 29): a shape count byte, then that many shapes, each a type byte followed
     * by [shape] bytes of coordinates and colours.
     */
    const val RASTER = 6

    /**
     * Perlin noise (type 34) opcode 2: a *signed* short amplitude; when it is negative, one short
     * per noise step follows, and the step count is whatever opcode 1 last set - defaulting to
     * [PERLIN_STEPS], because the file is free to give opcode 2 before opcode 1 and 2,000 of them do.
     */
    const val PERLIN = 7

    /** The client's `TextureOpPerlinNoise.numNoiseSteps` before any opcode has set it. */
    const val PERLIN_STEPS = 4

    /** A line: four coordinate shorts, a stroke colour and a stroke width. */
    const val LINE = 0

    /** A cubic bezier: eight coordinate shorts, a stroke colour and a stroke width. */
    const val BEZIER = 1

    /** A rectangle: four coordinate shorts, a fill colour, a stroke colour and a stroke width. */
    const val RECTANGLE = 2

    /** An ellipse, the same shape as a [RECTANGLE]. */
    const val ELLIPSE = 3

    /** How many operations feed each operation type, by type. */
    private val CHILDREN = intArrayOf(
        0, 0, 0, 0, 0, 1, 1, 2, 1, 1,
        1, 1, 0, 0, 0, 0, 0, 1, 0, 3,
        1, 3, 1, 1, 1, 1, 1, 0, 0, 0,
        1, 0, 1, 1, 0, 1, 0, 0, 0, 0
    )

    /** Per operation type, the payload of each opcode it handles, indexed by opcode. */
    private val PAYLOADS: Array<IntArray> = arrayOf(
        intArrayOf(BYTE), // 0 monochrome fill
        intArrayOf(MEDIUM), // 1 colour fill
        IntArray(0), // 2 horizontal gradient
        IntArray(0), // 3 vertical gradient
        intArrayOf(BYTE, BYTE, SHORT, SHORT, SHORT, SHORT, SHORT, SHORT), // 4 bricks
        intArrayOf(BYTE, BYTE, BYTE), // 5 box blur
        intArrayOf(SHORT, SHORT, BYTE), // 6 clamp
        intArrayOf(BYTE, BYTE), // 7 combine
        intArrayOf(CURVE), // 8 curve
        intArrayOf(BYTE, BYTE, BYTE), // 9 flip
        intArrayOf(GRADIENT), // 10 colour gradient
        intArrayOf(SHORT, SHORT, SHORT), // 11 colourize
        intArrayOf(BYTE, BYTE, NONE, BYTE), // 12 waveform - opcode 2 is one of the four it ignores
        IntArray(0), // 13 noise
        intArrayOf(SHORT), // 14 weave
        intArrayOf(BYTE, BYTE, SHORT, BYTE, BYTE, BYTE, BYTE), // 15 voronoi noise
        intArrayOf(BYTE, BYTE, SHORT), // 16 herringbone
        intArrayOf(SHORT, BYTE, BYTE), // 17 hsl adjust
        intArrayOf(SHORT), // 18 tiled graphic, which is a graphic
        intArrayOf(SHORT, BYTE), // 19 polar distortion
        intArrayOf(BYTE, BYTE), // 20 tile
        intArrayOf(BYTE), // 21 interpolate
        intArrayOf(BYTE), // 22 invert
        intArrayOf(BYTE), // 23 kaleidoscope
        IntArray(0), // 24 monochrome
        intArrayOf(SHORT, SHORT, SHORT, SHORT, MEDIUM), // 25 brightness
        intArrayOf(SHORT, SHORT), // 26 binary
        intArrayOf(BYTE, SHORT, BYTE), // 27 square waveform
        intArrayOf(BYTE, SHORT, SHORT, SHORT, SHORT, SHORT, BYTE, SHORT, SHORT), // 28 irregular bricks
        intArrayOf(RASTER, BYTE), // 29 rasterizer
        intArrayOf(SHORT, SHORT, BYTE), // 30 range
        intArrayOf(SHORT, SHORT, SHORT, SHORT), // 31 mandelbrot
        intArrayOf(SHORT, SHORT, SHORT), // 32 emboss
        intArrayOf(SHORT, BYTE), // 33 colour edge detector
        intArrayOf(BYTE, BYTE, PERLIN, BYTE, BYTE, BYTE, BYTE), // 34 perlin noise
        intArrayOf(SHORT), // 35 monochrome edge detector
        intArrayOf(SHORT), // 36 texture, i.e. a nested material
        intArrayOf(SHORT, SHORT, SHORT, SHORT, SHORT, SHORT, SHORT), // 37 diagonal waves
        intArrayOf(BYTE, SHORT, BYTE, SHORT, SHORT), // 38 line noise
        intArrayOf(SHORT) // 39 graphic
    )

    /** How many operation types the client implements. */
    val types: Int
        get() = PAYLOADS.size

    /** How many inputs operation [type] takes, which is how many input indices follow its opcodes. */
    fun children(type: Int): Int {
        require(type in CHILDREN.indices) { "Texture operation type $type does not exist." }
        return CHILDREN[type]
    }

    /** The payload [opcode] of operation [type] carries, [NONE] when the type ignores it. */
    fun payload(type: Int, opcode: Int): Int {
        require(type in PAYLOADS.indices) { "Texture operation type $type does not exist." }
        val payloads = PAYLOADS[type]
        return if (opcode in payloads.indices) payloads[opcode] else NONE
    }

    /** How many coordinate shorts a rasterizer [shape] carries. */
    fun coordinates(shape: Int): Int = when (shape) {
        LINE, RECTANGLE, ELLIPSE -> 4
        BEZIER -> 8
        else -> error("Texture rasterizer shape $shape does not exist; the client reads nothing for it.")
    }

    /** Whether a rasterizer [shape] carries a fill colour before its stroke colour. */
    fun filled(shape: Int): Boolean = shape == RECTANGLE || shape == ELLIPSE
}
