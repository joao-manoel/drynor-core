package org.darkan.tools.cache.texture

import org.darkan.tools.cache.texture.ops.*
import world.gregs.voidps.buffer.read.BufferReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Represents a texture definition with its operation DAG.
 *
 * Textures in RS are procedurally generated using a directed acyclic graph
 * of operations. Each operation can reference other operations as inputs,
 * creating complex textures from simple building blocks.
 *
 * @param data The raw texture data from the TEXTURES cache index
 */
class TextureType(data: ByteArray) {
    /** All operations in this texture */
    val textureOperations: Array<TextureOperation>

    /** Graphic IDs required by TextureOpGraphic operations */
    val textureFileIds: IntArray

    /** Material IDs required by TextureOpTexture operations */
    val materialFileIds: IntArray

    /** The operation that produces the opaque RGB output */
    val opaqueOperation: TextureOperation

    /** The operation that produces the opacity/alpha output */
    val opacityOperation: TextureOperation

    /** The operation that produces HDR intensity output */
    val hdrOperation: TextureOperation

    init {
        val buffer = BufferReader(data)
        val totalOperations = buffer.readUnsignedByte()

        var textureFileCount = 0
        var materialFileCount = 0
        val operationIndices = Array(totalOperations) { IntArray(0) }

        @Suppress("UNCHECKED_CAST")
        textureOperations = arrayOfNulls<TextureOperation>(totalOperations) as Array<TextureOperation>

        // Decode all operations
        for (index in 0 until totalOperations) {
            val textureOperation = decodeOperation(buffer)

            if (textureOperation.getTextureId() >= 0) {
                textureFileCount++
            }
            if (textureOperation.getMaterialId() >= 0) {
                materialFileCount++
            }

            // Read child operation indices
            val operationCount = textureOperation.operations.size
            operationIndices[index] = IntArray(operationCount)
            for (sub in 0 until operationCount) {
                operationIndices[index][sub] = buffer.readUnsignedByte()
            }

            textureOperations[index] = textureOperation
        }

        // Collect file IDs and wire up operation references
        textureFileIds = IntArray(textureFileCount)
        textureFileCount = 0
        materialFileIds = IntArray(materialFileCount)
        materialFileCount = 0

        for (operationIndex in 0 until totalOperations) {
            val textureOperation = textureOperations[operationIndex]
            val operationCount = textureOperation.operations.size

            // Wire up child operation references
            for (sub in 0 until operationCount) {
                textureOperation.operations[sub] = textureOperations[operationIndices[operationIndex][sub]]
            }

            // Collect file IDs
            val textureFileId = textureOperation.getTextureId()
            val materialFileId = textureOperation.getMaterialId()
            if (textureFileId > 0) {
                textureFileIds[textureFileCount++] = textureFileId
            }
            if (materialFileId > 0) {
                materialFileIds[materialFileCount++] = materialFileId
            }
        }

        // Read output operation indices
        opaqueOperation = textureOperations[buffer.readUnsignedByte()]
        opacityOperation = textureOperations[buffer.readUnsignedByte()]
        hdrOperation = textureOperations[buffer.readUnsignedByte()]
    }

    /**
     * Decode a single operation from the buffer.
     */
    private fun decodeOperation(buffer: BufferReader): TextureOperation {
        buffer.readUnsignedByte() // padding
        val typeId = buffer.readUnsignedByte()
        val textureOperation = createOperation(typeId)
        textureOperation.imageCacheCapacity = buffer.readUnsignedByte()

        val opcodeCount = buffer.readUnsignedByte()
        for (i in 0 until opcodeCount) {
            val opcode = buffer.readUnsignedByte()
            textureOperation.decodeOpcode(opcode, buffer)
        }
        textureOperation.postDecode()
        return textureOperation
    }

    /**
     * Prepare texture operations by loading graphics and nested textures,
     * binding the raster dimensions, and creating image caches.
     *
     * The raster dimensions are bound onto the operations themselves rather than into a
     * singleton, so a concurrent generation at another size cannot disturb this one.
     */
    private fun prepareOperations(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        deadlineNanos: Long = 0L
    ) {
        // Load required graphics
        for (operation in textureOperations) {
            if (operation is TextureOpGraphic && operation.graphicId >= 0) {
                val graphic = graphicLoader(operation.graphicId)
                if (graphic != null) {
                    operation.loadGraphic(graphic.pixels, graphic.width, graphic.height)
                }
            }
        }

        // Load required nested textures
        if (textureLoader != null) {
            for (operation in textureOperations) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout during nested texture loading")
                }
                if (operation is TextureOpTexture && operation.getMaterialId() >= 0) {
                    val pixels = textureLoader(operation.getMaterialId(), width, height)
                    if (pixels != null) {
                        operation.texturePixels = pixels
                        // The nested texture may have been generated at a different size
                        // than the parent (e.g. 64x64 vs 128x128 based on isSmall).
                        // Derive actual dimensions from the pixel array (RS textures are square).
                        val actualSize = sqrt(pixels.size.toDouble()).toInt()
                        operation.textureWidth = actualSize
                        operation.textureHeight = actualSize
                    }
                }
            }
        }

        // Bind the raster dimensions, then create caches for all operations.
        // bindRaster must run first: createImageCache propagates the deadline into the
        // row caches.
        val raster = TextureRaster.context(width, height)
        for (operation in textureOperations) {
            operation.bindRaster(raster, deadlineNanos)
            operation.createImageCache(width, height)
        }
    }

    /**
     * Cleanup image caches for all operations after pixel generation.
     */
    private fun cleanupOperations() {
        for (operation in textureOperations) {
            operation.clearImageCache()
        }
    }

    /**
     * Generate ARGB pixel data for this texture using the transparent path.
     * Reads alpha from the opacity operation. Used for materials with
     * transparent effects (effectId 1 or 7, or effectCombiner == 2).
     *
     * @param width The output width
     * @param height The output height
     * @param brightness The brightness/gamma value (typically 0.7)
     * @param graphicLoader Function to load graphic pixels by ID
     * @param textureLoader Optional function to load nested texture pixels by material ID
     * @param deadlineNanos Absolute [System.nanoTime] deadline for this generation, or 0 for none
     * @return ARGB pixel array (width * height)
     */
    fun getPixelsArgb(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        brightness: Double = 0.7,
        deadlineNanos: Long = 0L
    ): IntArray {
        prepareOperations(width, height, graphicLoader, textureLoader, deadlineNanos)

        try {
            val pixels = IntArray(width * height)
            var pixelIndex = 0
            val brightnessTable = brightnessTable(brightness)

            // Generate row by row
            for (row in 0 until height) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout at row $row")
                }

                val redBuffer: IntArray
                val greenBuffer: IntArray
                val blueBuffer: IntArray

                if (opaqueOperation.monochrome) {
                    val buffer = opaqueOperation.getMonochromeOutput(row)
                    redBuffer = buffer
                    greenBuffer = buffer
                    blueBuffer = buffer
                } else {
                    val palette = opaqueOperation.getColorOutput(row)
                    redBuffer = palette[0]
                    greenBuffer = palette[1]
                    blueBuffer = palette[2]
                }

                // Evaluate the opacity operation for alpha values.
                // When opacityOperation === opaqueOperation, the image cache returns
                // the already-computed data (dirty=false), so this is efficient.
                val opacityBuffer: IntArray = if (opacityOperation.monochrome) {
                    opacityOperation.getMonochromeOutput(row)
                } else {
                    opacityOperation.getColorOutput(row)[0]
                }

                // Process pixels from right to left (matching RS client behavior)
                // This horizontally flips the texture as the client expects
                for (x in width - 1 downTo 0) {
                    var red = (redBuffer[x] shr 4).coerceIn(0, 255)
                    var green = (greenBuffer[x] shr 4).coerceIn(0, 255)
                    var blue = (blueBuffer[x] shr 4).coerceIn(0, 255)

                    // Apply brightness curve
                    red = brightnessTable[red]
                    green = brightnessTable[green]
                    blue = brightnessTable[blue]

                    // Black pixels are always fully transparent.
                    // Non-black pixels use the opacity channel value.
                    val alpha: Int = if (red == 0 && green == 0 && blue == 0) {
                        0
                    } else {
                        (opacityBuffer[x] shr 4).coerceIn(0, 255)
                    }

                    pixels[pixelIndex++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }

            return pixels
        } finally {
            cleanupOperations()
        }
    }

    /**
     * Generate RGB pixel data for this texture using the opaque path.
     * Does NOT evaluate the opacity operation. Forces alpha=255 for all
     * non-black pixels and alpha=0 for black pixels. This matches the RS
     * client's getPixelsRgb() method used for opaque materials.
     *
     * @param width The output width
     * @param height The output height
     * @param brightness The brightness/gamma value (typically 0.7)
     * @param graphicLoader Function to load graphic pixels by ID
     * @param textureLoader Optional function to load nested texture pixels by material ID
     * @param deadlineNanos Absolute [System.nanoTime] deadline for this generation, or 0 for none
     * @return ARGB pixel array (width * height) with alpha=255 for non-black, alpha=0 for black
     */
    fun getPixelsRgb(
        width: Int,
        height: Int,
        graphicLoader: (Int) -> GraphicData?,
        textureLoader: ((Int, Int, Int) -> IntArray?)? = null,
        brightness: Double = 0.7,
        deadlineNanos: Long = 0L
    ): IntArray {
        prepareOperations(width, height, graphicLoader, textureLoader, deadlineNanos)

        try {
            val pixels = IntArray(width * height)
            var pixelIndex = 0
            val brightnessTable = brightnessTable(brightness)

            // Generate row by row
            for (row in 0 until height) {
                if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                    throw TextureGenerationException("Texture generation timeout at row $row")
                }

                val redBuffer: IntArray
                val greenBuffer: IntArray
                val blueBuffer: IntArray

                if (opaqueOperation.monochrome) {
                    val buffer = opaqueOperation.getMonochromeOutput(row)
                    redBuffer = buffer
                    greenBuffer = buffer
                    blueBuffer = buffer
                } else {
                    val palette = opaqueOperation.getColorOutput(row)
                    redBuffer = palette[0]
                    greenBuffer = palette[1]
                    blueBuffer = palette[2]
                }

                // NOTE: Opacity operation is NOT evaluated in the opaque path

                // Process pixels from right to left (matching RS client behavior)
                // This horizontally flips the texture as the client expects
                for (x in width - 1 downTo 0) {
                    var red = (redBuffer[x] shr 4).coerceIn(0, 255)
                    var green = (greenBuffer[x] shr 4).coerceIn(0, 255)
                    var blue = (blueBuffer[x] shr 4).coerceIn(0, 255)

                    // Apply brightness curve
                    red = brightnessTable[red]
                    green = brightnessTable[green]
                    blue = brightnessTable[blue]

                    // Opaque path: alpha=255 for non-black, alpha=0 for black
                    val pixel = blue + (green shl 8) + (red shl 16)
                    if (pixel != 0) {
                        pixels[pixelIndex++] = pixel or 0xFF000000.toInt()
                    } else {
                        pixels[pixelIndex++] = 0
                    }
                }
            }

            return pixels
        } finally {
            cleanupOperations()
        }
    }

    companion object {
        /**
         * Gamma correction tables, interned per brightness value.
         *
         * This used to be a single shared table rebuilt whenever the requested brightness
         * changed. Nested materials generate at brightness 1.0 and top-level ones at 0.7,
         * so two concurrent generations would repeatedly rebuild the table under each
         * other and silently mis-grade each other's pixels. The tables are pure functions
         * of the brightness, so interning them makes them safe to share.
         */
        private val brightnessTables = ConcurrentHashMap<Double, IntArray>()

        /**
         * Get the gamma correction table for a brightness value, building it on first use.
         * Callers hoist the result into a local so the per-pixel lookup stays an array read.
         *
         * @param brightness The brightness value (typically 0.7, or 1.0 for nested textures)
         */
        fun brightnessTable(brightness: Double): IntArray {
            brightnessTables[brightness]?.let { return it }
            val table = IntArray(256) { grade ->
                min(255, ((grade / 255.0).pow(brightness) * 255.0).toInt())
            }
            // putIfAbsent, not computeIfAbsent: a duplicate build is cheap and the first
            // publication wins, so every caller at a given brightness sees one table.
            return brightnessTables.putIfAbsent(brightness, table) ?: table
        }

        /**
         * Create a texture operation by type ID.
         *
         * @param typeId The operation type (0-39)
         * @return The created operation
         */
        fun createOperation(typeId: Int): TextureOperation {
            return when (typeId) {
                0 -> TextureOpMonochromeFill()
                1 -> TextureOpColorFill()
                2 -> TextureOpHorizontalGradient()
                3 -> TextureOpVerticalGradient()
                4 -> TextureOpBricks()
                5 -> TextureOpBoxBlur()
                6 -> TextureOpClamp()
                7 -> TextureOpCombine()
                8 -> TextureOpCurve()
                9 -> TextureOpFlip()
                10 -> TextureOpColorGradient()
                11 -> TextureOpColorize()
                12 -> TextureOpWaveform()
                13 -> TextureOpNoise()
                14 -> TextureOpWeave()
                15 -> TextureOpVoronoiNoise()
                16 -> TextureOpHerringbone()
                17 -> TextureOpHslAdjust()
                18 -> TextureOpTiledGraphic()
                19 -> TextureOpPolarDistortion()
                20 -> TextureOpTile()
                21 -> TextureOpInterpolate()
                22 -> TextureOpInvert()
                23 -> TextureOpKaleidoscope()
                24 -> TextureOpMonochrome()
                25 -> TextureOpBrightness()
                26 -> TextureOpBinary()
                27 -> TextureOpSquareWaveform()
                28 -> TextureOpIrregularBricks()
                29 -> TextureOpRasterizer()
                30 -> TextureOpRange()
                31 -> TextureOpMandelbrot()
                32 -> TextureOpEmboss()
                33 -> TextureOpColorEdgeDetector()
                34 -> TextureOpPerlinNoise()
                35 -> TextureOpMonochromeEdgeDetector()
                36 -> TextureOpTexture()
                37 -> TextureOp37()
                38 -> TextureOpLineNoise()
                39 -> TextureOpGraphic()
                else -> throw IllegalArgumentException("Unknown texture operation type: $typeId")
            }
        }
    }
}

/**
 * Container for graphic data.
 */
data class GraphicData(
    val pixels: IntArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphicData) return false
        return pixels.contentEquals(other.pixels) && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
