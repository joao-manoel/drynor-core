package org.darkan.tools.cache.texture

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder

/**
 * Loads textures from the cache using the full RS procedural texture system:
 * 1. TextureTypes (TEXTURES index) define texture operation DAGs
 * 2. TextureOperations process and combine inputs to generate pixels
 * 3. TextureOpGraphic operations reference graphics from GRAPHICS index
 */
object TextureLoader {
    private val textureCache = mutableMapOf<Int, Int>() // textureId -> GL texture ID
    private val graphicDataCache = ConcurrentHashMap<Int, GraphicData>() // graphicId -> GraphicData
    private val textureDataCache = ConcurrentHashMap<Int, ByteArray>() // textureId -> raw TEXTURES data
    private var defaultTextureId: Int = 0
    private var cacheRef: Cache? = null

    /** Material definitions loaded from the cache, indexed by material ID */
    private var materialDefinitions: Array<MaterialDefinition> = emptyArray()

    /** Default texture size for procedural generation (128 for normal, 64 for small) */
    private const val TEXTURE_SIZE_NORMAL = 128
    private const val TEXTURE_SIZE_SMALL = 64

    /**
     * Absolute deadline for the top-level generation currently running on this thread.
     *
     * The 5 second budget covers a whole generation *tree*: the top level entry point sets
     * it and every nested material inherits it. Nested generation is reached through the
     * `textureLoader` lambda, which carries no context of its own, so the deadline is held
     * per thread here and passed down as a plain parameter from there. It is read once per
     * generation, never per row.
     */
    private val deadlineLocal: ThreadLocal<Long> = ThreadLocal.withInitial { 0L }

    /** Start a top-level generation on this thread and return its absolute deadline. */
    private fun beginGeneration(): Long {
        val deadline = System.nanoTime() + TextureRaster.MAX_GENERATION_NANOS
        deadlineLocal.set(deadline)
        return deadline
    }

    fun init(cache: Cache) {
        initHeadless(cache)
        defaultTextureId = createCheckerTexture()
    }

    /**
     * Initialise everything that does not require an OpenGL context: the raster
     * lookup tables and the material definitions.
     *
     * CPU-only tools (texture dumps, diagnostics, concurrency checks) call this
     * directly; [init] layers the GL placeholder texture on top for viewers.
     */
    fun initHeadless(cache: Cache) {
        cacheRef = cache
        TextureRaster.calculateTable()

        // Load all material definitions once at init time
        materialDefinitions = MaterialDecoder().load(cache)
    }

    /**
     * Get the MaterialDefinition for a given material ID, or null if not found.
     */
    private fun getMaterial(materialId: Int): MaterialDefinition? {
        if (materialId < 0 || materialId >= materialDefinitions.size) return null
        return materialDefinitions[materialId]
    }

    /**
     * Determine if a material should use the opaque pixel path.
     * Matches the RS client logic:
     *   effectCombiner != 2 && effectId != 1 && effectId != 7
     *
     * If no material definition exists, defaults to opaque (most materials are opaque).
     */
    private fun isMaterialOpaque(material: MaterialDefinition?): Boolean {
        if (material == null) return true
        val effectId = material.effectId.toInt()
        val effectCombiner = material.effectCombiner
        return effectCombiner != 2 && effectId != 1 && effectId != 7
    }

    /**
     * Public query: does the given material render as fully opaque?
     *
     * This answers the RENDERING question (opaque pass vs transparent pass),
     * not the texture GENERATION question (getPixelsRgb vs getPixelsArgb).
     *
     * After alpha post-processing:
     *   - Standard opaque materials (not effectId 1/7, not effectCombiner 2):
     *     all pixels get alpha=255 via isMaterialOpaque → opaque path → true
     *   - effectCombiner=0 with constAlpha=255: all pixels get alpha=255 → true
     *   - effectCombiner=0 with constAlpha<255: all pixels get partial alpha → false
     *   - effectCombiner=1 (binary alpha): some pixels alpha=0 → false
     *   - effectCombiner=2 (per-pixel alpha): variable alpha → false
     */
    fun isOpaque(textureId: Int): Boolean {
        if (textureId < 0) return true
        val material = getMaterial(textureId) ?: return true
        val effectId = material.effectId.toInt()
        val effectCombiner = material.effectCombiner

        // Standard opaque materials (not effectId 1/7, not effectCombiner 2)
        if (effectCombiner != 2 && effectId != 1 && effectId != 7) return true

        // For effectCombiner 0: check if constant alpha makes it opaque.
        // alpha is a signed byte: only negative values (unsigned >= 128) trigger
        // BlendMode.ALPHA in the RS client. Positive values are color blend weights,
        // not render alpha, so the material is still opaque.
        if (effectCombiner == 0) {
            return material.alpha.toInt() >= 0
        }

        // effectCombiner 1 (binary) or 2 (per-pixel): needs transparency handling
        return false
    }

    /**
     * Check if a material's faces should be skipped (not rendered).
     * Matches the RS client's ModelGL behavior where faces with
     * MaterialType.skipTriangles == true are excluded from rendering.
     *
     * @param materialId The material/texture ID from faceTextures
     * @return true if the material's faces should not be rendered
     */
    fun shouldSkipMaterial(materialId: Int): Boolean {
        if (materialId < 0) return false
        return getMaterial(materialId)?.skipTriangles ?: false
    }

    /**
     * Get the texture size for a material based on its isSmall flag.
     * true (isSmall) -> 64x64, false -> 128x128.
     * Defaults to 128 if no material definition exists.
     */
    private fun getTextureSize(material: MaterialDefinition?): Int {
        if (material == null) return TEXTURE_SIZE_NORMAL
        return if (material.isSmall) TEXTURE_SIZE_SMALL else TEXTURE_SIZE_NORMAL
    }

    /**
     * Get GL texture for a material/texture ID from model faces.
     * Uses the full procedural texture system to generate pixels.
     *
     * @param cache The game cache
     * @param textureId The texture/material ID from faceTextures
     * @return OpenGL texture ID
     */
    fun getTextureForMaterial(cache: Cache, textureId: Int): Int {
        if (textureId < 0) return defaultTextureId

        return textureCache.getOrPut(textureId) {
            // Try to load and generate the texture using the full operation pipeline
            val textureType = loadTextureType(cache, textureId)

            if (textureType != null) {
                val material = getMaterial(textureId)
                val textureSize = getTextureSize(material)
                val opaque = isMaterialOpaque(material)
                val deadline = beginGeneration()

                val pixels = if (opaque) {
                    // Opaque path: alpha=255 for non-black, alpha=0 for black.
                    // Does NOT evaluate the opacity operation.
                    textureType.getPixelsRgb(
                        textureSize,
                        textureSize,
                        graphicLoader = { graphicId ->
                            loadGraphicData(cache, graphicId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7,
                        deadlineNanos = deadline
                    )
                } else {
                    // Transparent path: alpha from the opacity operation.
                    textureType.getPixelsArgb(
                        textureSize,
                        textureSize,
                        graphicLoader = { graphicId ->
                            loadGraphicData(cache, graphicId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7,
                        deadlineNanos = deadline
                    )
                }

                // Post-process alpha based on effectCombiner.
                // In the RS client, effectCombiner controls how alpha is actually used:
                //   effectCombiner=2: per-pixel alpha from opacity operation (keep as-is)
                //   effectCombiner=0: constant alpha applied to ALL pixels (including black).
                //     The RS client's software rasterizer uses constant alpha for every texel,
                //     and its GL path uses BlendMode.NONE, so black pixels render as black,
                //     not as holes. Our shader discards alpha<0.01, so we must set alpha on
                //     all pixels to prevent black texels from being discarded.
                //   effectCombiner=1: binary alpha (0 for black/transparent, 255 for non-black).
                //     This creates hard-edged transparency masks (e.g., foliage, fences).
                if (material != null && material.effectCombiner != 2) {
                    val effectCombiner = material.effectCombiner
                    if (effectCombiner == 0) {
                        // Constant alpha for ALL pixels (including black).
                        // alpha is a signed byte: only negative values (unsigned >= 128)
                        // trigger BlendMode.ALPHA in the RS client GL path. Positive values
                        // are color blend weights (texture vs face color), NOT render alpha.
                        // Zero is treated as fully opaque (1.0).
                        val signedAlpha = material.alpha.toInt()
                        val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                        }
                    } else {
                        // effectCombiner=1 (binary) or other: alpha is 0 for black, 255 for non-black
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            val rgb = px and 0x00FFFFFF
                            pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                        }
                    }
                }

                return@getOrPut createTextureFromArgb(pixels, textureSize, textureSize)
            }

            // Fallback: try loading texture ID directly as graphic ID (some models use this)
            val directGraphic = loadGraphicAsTexture(cache, textureId)
            if (directGraphic != null) {
                return@getOrPut directGraphic
            }

            defaultTextureId
        }
    }

    /**
     * Get raw ARGB pixel data for a material WITHOUT creating a GL texture.
     *
     * This extracts the pixel generation and alpha post-processing logic from
     * [getTextureForMaterial] so it can be used to populate texture atlases
     * without creating individual GL textures.
     *
     * The returned pixels are in ARGB format with the same alpha post-processing
     * applied as [getTextureForMaterial] (effectCombiner-based alpha handling).
     *
     * Memoised in [materialPixelCache], and the reason is navigation rather than the initial
     * load. On a cold load the two callers ask for almost disjoint sets — 161 terrain and
     * 185 object materials at Lumbridge share exactly 10 ids — so the first attempt at this
     * cache measured a 0.5-2.9% reuse rate and was reverted. Navigating is a different
     * workload, and nothing in that measurement covered it: the terrain atlas is built once
     * in `MapViewer.init` and kept, while the whole IndirectRenderer, `buildTextureArrays`
     * included, is torn down and rebuilt on every jump and asks for the same materials
     * again. Over a 50,50 -> 54,54 -> 52,50 -> 50,50 tour at render distance 3 the per-jump
     * reuse rate is **70.5%, 80.8% and 100.0%**.
     *
     * All of that regeneration sits on the GL thread inside the blocking half of a jump,
     * which is the half that shows up as a freeze. Counterbalanced OFF/ON/ON/OFF, the
     * generation inside a jump falls from 592/127/240 ms to 76/12/0 ms and the blocking half
     * with it, 1231/498/1097 ms -> 780/373/759 ms. The *total* jump wall only moves where
     * the asynchronous context-region tail is not the binding constraint: at the third stop,
     * with 14 new regions, 2613 -> 2261 ms; at the first, with 39, the tail simply absorbs
     * the saving. Cold load is unchanged (10904/10952 ms vs 10622/10998 ms), as the 5.4%
     * cold reuse rate predicts. Cost is 17.0 MiB resident for the 369 distinct materials a
     * three-area tour touches, and the rendered image is unchanged: every cross-arm frame
     * diff came in at or below the same-config control for that location.
     *
     * The entries are handed out without copying. Every consumer is strictly read-only
     * ([TextureAtlas.addTexture] writes only the atlas page, [IndirectRenderer]'s array
     * upload and alpha audit, [SkyRenderer]'s material build, [InstanceManager]'s texture
     * packing), which was verified by re-hashing every returned array after use: zero
     * violations. A defensive copy would cost the very bytes the cache exists to stop moving.
     *
     * Bounded by [PIXEL_CACHE_BUDGET_BYTES] — past the budget generation still succeeds, it
     * just is not retained — and disabled entirely by `-Ddarkan.texPixelCache=false`.
     *
     * @param cache The game cache
     * @param textureId The texture/material ID
     * @return Triple of (ARGB pixels, width, height) or null if the texture doesn't exist
     */
    fun getTexturePixelsForMaterial(cache: Cache, textureId: Int): Triple<IntArray, Int, Int>? {
        if (textureId < 0) return null
        // Guard against circular references at the top level too
        if (textureId in generatingSet.get()) return null

        materialPixelCache[textureId]?.let {
            TexturePixelStats.recordHit()
            return it
        }

        val textureType = loadTextureType(cache, textureId) ?: return null

        val material = getMaterial(textureId)
        val textureSize = getTextureSize(material)
        val opaque = isMaterialOpaque(material)

        val statsStart = System.nanoTime()
        generatingSet.get().add(textureId)
        val deadline = beginGeneration()
        val pixels = try {
            if (opaque) {
                textureType.getPixelsRgb(
                    textureSize,
                    textureSize,
                    graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                    textureLoader = { materialId, width, height ->
                        generateTexturePixels(cache, materialId, width, height)
                    },
                    brightness = 0.7,
                    deadlineNanos = deadline
                )
            } else {
                textureType.getPixelsArgb(
                    textureSize,
                    textureSize,
                    graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                    textureLoader = { materialId, width, height ->
                        generateTexturePixels(cache, materialId, width, height)
                    },
                    brightness = 0.7,
                    deadlineNanos = deadline
                )
            }
        } finally {
            generatingSet.get().remove(textureId)
        }

        // Apply same alpha post-processing as getTextureForMaterial
        if (material != null && material.effectCombiner != 2) {
            val effectCombiner = material.effectCombiner
            if (effectCombiner == 0) {
                val signedAlpha = material.alpha.toInt()
                val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                for (i in pixels.indices) {
                    val px = pixels[i]
                    pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                }
            } else {
                for (i in pixels.indices) {
                    val px = pixels[i]
                    val rgb = px and 0x00FFFFFF
                    pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                }
            }
        }

        TexturePixelStats.record(textureId, System.nanoTime() - statsStart, pixels.size)
        val result = Triple(pixels, textureSize, textureSize)
        retainPixels(textureId, result)
        return result
    }

    /**
     * Memoised output of [getTexturePixelsForMaterial], keyed by material id.
     *
     * Generation is a pure function of the material id and the cache, both of which are
     * fixed for the life of the process, so an entry never goes stale on its own; the only
     * thing that drops one is [clearCpuCaches].
     *
     * Distinct from the [pixelCache] a few methods down, which memoises *nested* material
     * references at their own size and brightness 1.0. That one is an implementation detail
     * of a single generation tree and its entries are not what a caller asked for — they
     * carry neither the top-level brightness nor the effectCombiner alpha post-processing.
     */
    private val materialPixelCache = ConcurrentHashMap<Int, Triple<IntArray, Int, Int>>()

    private val materialPixelCacheBytes = AtomicLong(0)

    /** `-Ddarkan.texPixelCache=false` restores the regenerate-every-time behaviour. */
    private val materialPixelCacheEnabled: Boolean =
        System.getProperty("darkan.texPixelCache", "true").toBoolean()

    /**
     * Ceiling on retained pixel bytes. A tour of three areas retains ~16 MiB across 369
     * distinct materials, so this leaves roughly 4x headroom over anything a session
     * reaches; it exists so a pathological run degrades to the old regenerate-every-time
     * behaviour instead of growing without limit. `-Ddarkan.texPixelCacheMiB=<n>` overrides.
     */
    private val materialPixelCacheBudgetBytes: Long =
        (System.getProperty("darkan.texPixelCacheMiB")?.toLongOrNull() ?: 64L) * 1024L * 1024L

    /**
     * Retain [result] for [textureId] if the cache is on and still inside its budget.
     *
     * [ConcurrentHashMap.putIfAbsent] rather than `computeIfAbsent`, and this must stay that
     * way. Generating a material recurses back into this cache for every nested
     * `TextureOpTexture` reference, so a `computeIfAbsent` mapping function for the outer key
     * would insert the inner key into the same map. That is illegal reentrancy: when the two
     * keys share a bin the JDK throws `IllegalStateException: Recursive update`, and when they
     * do not, the reservation node stays live across an insert that may resize the table,
     * which the JDK documents as possible deadlock or memory-consistency corruption. It
     * reproduces on a single thread; it is not a data race.
     *
     * Here, two threads may have generated the same material concurrently and the loser simply
     * drops its copy. No generation, and no cache I/O, happens inside the map operation.
     */
    private fun retainPixels(textureId: Int, result: Triple<IntArray, Int, Int>) {
        if (!materialPixelCacheEnabled) return
        val bytes = result.first.size.toLong() * Int.SIZE_BYTES
        if (materialPixelCacheBytes.get() + bytes > materialPixelCacheBudgetBytes) return
        if (materialPixelCache.putIfAbsent(textureId, result) == null) {
            materialPixelCacheBytes.addAndGet(bytes)
        }
    }

    /**
     * Get the texture size for a material by its ID.
     * Returns the size in pixels (128 for normal, 64 for small textures).
     * Defaults to 128 if no material definition exists.
     *
     * @param textureId The texture/material ID
     * @return Texture size in pixels
     */
    fun getTextureSizeForMaterial(textureId: Int): Int {
        if (textureId < 0) return TEXTURE_SIZE_NORMAL
        return getTextureSize(getMaterial(textureId))
    }

    /**
     * Generate raw pixels for a texture (used for nested texture references).
     * The client uses materialProvider.getPixels(materialId, 1.0f, size, size, false)
     * for nested TextureOpTexture references, which is the RGB (opaque) path with
     * brightness=1.0. This is separate from getTextureForMaterial to avoid circular
     * GL texture creation.
     */
    private val pixelCache = ConcurrentHashMap<Int, IntArray>()
    private val generatingSet: ThreadLocal<MutableSet<Int>> = ThreadLocal.withInitial { mutableSetOf() }

    private fun generateTexturePixels(cache: Cache, textureId: Int, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int): IntArray? {
        // Fast path: already generated.
        pixelCache[textureId]?.let { return it }

        // Break circular texture references (A->B->A) by tracking in-progress generation.
        val inProgress = generatingSet.get()
        if (textureId in inProgress) return null

        val textureType = loadTextureType(cache, textureId) ?: return null

        // In the RS client, TextureOpTexture.createImageCache() loads nested textures
        // at the NESTED material's own size (based on isSmall), NOT the parent's.
        // We must do the same here to avoid array bounds mismatches.
        val nestedSize = getTextureSize(getMaterial(textureId))

        // Generation runs OUTSIDE any map mapping function. Generating a texture recurses
        // back into this method for every nested TextureOpTexture reference, and
        // ConcurrentHashMap forbids reentrant modification from inside computeIfAbsent:
        // a nested key landing in the same bin throws "Recursive update", and one landing
        // elsewhere can deadlock or corrupt the table during a resize. Resolving the whole
        // dependency chain first and only then publishing with putIfAbsent keeps every
        // insertion a single, non-reentrant operation.
        inProgress.add(textureId)
        val pixels = try {
            // Nested textures always use the RGB (opaque) path with brightness=1.0
            textureType.getPixelsRgb(
                nestedSize,
                nestedSize,
                graphicLoader = { graphicId ->
                    loadGraphicData(cache, graphicId)
                },
                textureLoader = { materialId, w, h ->
                    generateTexturePixels(cache, materialId, w, h)
                },
                brightness = 1.0,
                // Inherit the enclosing top-level generation's budget rather than
                // restarting it, so a deep chain of nested materials cannot extend the
                // total time indefinitely.
                deadlineNanos = deadlineLocal.get()
            )
        } finally {
            inProgress.remove(textureId)
        }

        // Two threads racing on the same material duplicate the work but agree on the
        // result; the first publication wins so every caller sees the same array.
        return pixelCache.putIfAbsent(textureId, pixels) ?: pixels
    }

    /**
     * Decode a TextureType from the TEXTURES index.
     *
     * Returns a FRESH instance on every call. Generation mutates the operation DAG in
     * place (nested texture pixels, per-operation image caches, brightness), so handing
     * out a shared instance would let two generations of the same material corrupt each
     * other's intermediate state. Only the raw bytes are cached, which is where the
     * decompression cost lives; re-parsing a few dozen opcodes is free next to
     * rasterising the DAG.
     */
    private fun loadTextureType(cache: Cache, textureId: Int): TextureType? {
        // putIfAbsent rather than computeIfAbsent: reading from the cache must not run
        // while a bin lock is held. A duplicate read is wasted work, not a correctness
        // problem, and the first publication wins.
        val data = textureDataCache[textureId] ?: run {
            val loaded = cache.data(Index.TEXTURES, textureId) ?: return null
            if (loaded.isEmpty()) return null
            textureDataCache.putIfAbsent(textureId, loaded) ?: loaded
        }
        return try {
            TextureType(data)
        } catch (e: Exception) {
            // Invalid texture data
            null
        }
    }

    /**
     * Load graphic data for use by TextureOpGraphic operations.
     */
    private fun loadGraphicData(cache: Cache, graphicId: Int): GraphicData? {
        graphicDataCache[graphicId]?.let { return it }
        val data = cache.data(Index.GRAPHICS, graphicId) ?: return null
        val graphic = decodeGraphicToData(data, 0) ?: return null
        return graphicDataCache.putIfAbsent(graphicId, graphic) ?: graphic
    }

    /**
     * Load a graphic directly as a GL texture (fallback for simple textures).
     */
    private fun loadGraphicAsTexture(cache: Cache, graphicId: Int): Int? {
        val graphicData = loadGraphicData(cache, graphicId) ?: return null
        return createTextureFromArgb(graphicData.pixels, graphicData.width, graphicData.height)
    }

    /**
     * Decode graphic data to GraphicData for use by texture operations.
     */
    private fun decodeGraphicToData(data: ByteArray, subIndex: Int): GraphicData? {
        if (data.size < 10) return null

        val buffer = BufferReader(data)

        // Read graphic count from end (unsigned short)
        buffer.position(data.size - 2)
        val graphicCount = buffer.readShort() and 0xFFFF
        if (graphicCount == 0 || subIndex >= graphicCount) return null

        // Read header
        buffer.position(data.size - 7 - graphicCount * 8)
        val biggestWidth = buffer.readShort()
        val biggestHeight = buffer.readShort()
        val paletteSize = buffer.readUnsignedByte() + 1

        // Read graphic dimensions
        val minX = IntArray(graphicCount)
        val minY = IntArray(graphicCount)
        val widths = IntArray(graphicCount)
        val heights = IntArray(graphicCount)

        for (i in 0 until graphicCount) minX[i] = buffer.readShort()
        for (i in 0 until graphicCount) minY[i] = buffer.readShort()
        for (i in 0 until graphicCount) widths[i] = buffer.readShort()
        for (i in 0 until graphicCount) heights[i] = buffer.readShort()

        val innerWidth = widths[subIndex]
        val innerHeight = heights[subIndex]
        val offsetX = minX[subIndex]
        val offsetY = minY[subIndex]
        if (innerWidth <= 0 || innerHeight <= 0) return null

        // Calculate full canvas size (like method1523 does)
        // biggestWidth and biggestHeight define the full canvas
        // Fall back to inner dimensions if biggest is invalid
        val fullWidth = if (biggestWidth > 0) biggestWidth else innerWidth
        val fullHeight = if (biggestHeight > 0) biggestHeight else innerHeight

        // Read palette
        buffer.position(data.size - 7 - graphicCount * 8 - (paletteSize - 1) * 3)
        val palette = IntArray(paletteSize)
        for (i in 1 until paletteSize) {
            palette[i] = buffer.readUnsignedMedium()
            if (palette[i] == 0) palette[i] = 1
        }

        // Read pixel data
        buffer.position(0)
        var raster: ByteArray? = null
        var alpha: ByteArray? = null

        for (i in 0 until graphicCount) {
            val area = widths[i] * heights[i]
            val flags = buffer.readUnsignedByte()
            val hasAlpha = (flags and 0x2) != 0
            val columnMajor = (flags and 0x1) != 0

            val graphicRaster = ByteArray(area)
            val graphicAlpha = if (hasAlpha) ByteArray(area) else null

            if (!columnMajor) {
                for (p in 0 until area) {
                    graphicRaster[p] = buffer.readByte().toByte()
                }
                if (hasAlpha) {
                    for (p in 0 until area) {
                        graphicAlpha!![p] = buffer.readByte().toByte()
                    }
                }
            } else {
                for (x in 0 until widths[i]) {
                    for (y in 0 until heights[i]) {
                        graphicRaster[x + y * widths[i]] = buffer.readByte().toByte()
                    }
                }
                if (hasAlpha) {
                    for (x in 0 until widths[i]) {
                        for (y in 0 until heights[i]) {
                            graphicAlpha!![x + y * widths[i]] = buffer.readByte().toByte()
                        }
                    }
                }
            }

            if (i == subIndex) {
                raster = graphicRaster
                alpha = graphicAlpha
            }
        }

        if (raster == null) return null

        // Convert indexed to ARGB on the full canvas (like method1523 does)
        // This expands the graphic to its full bounding box
        val pixels = IntArray(fullWidth * fullHeight)
        for (y in 0 until innerHeight) {
            val srcRowStart = y * innerWidth
            val destRowStart = (y + offsetY) * fullWidth + offsetX
            for (x in 0 until innerWidth) {
                val srcIdx = srcRowStart + x
                val destIdx = destRowStart + x
                if (destIdx < pixels.size) {
                    val paletteIdx = raster[srcIdx].toInt() and 0xFF
                    val rgb = palette[paletteIdx]
                    val a = if (alpha != null) {
                        alpha[srcIdx].toInt() and 0xFF
                    } else {
                        if (paletteIdx == 0) 0 else 255
                    }
                    // ARGB format
                    pixels[destIdx] = (a shl 24) or rgb
                }
            }
        }

        return GraphicData(pixels, fullWidth, fullHeight)
    }

    /**
     * Create OpenGL texture from ARGB pixels.
     */
    private fun createTextureFromArgb(pixels: IntArray, width: Int, height: Int): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        // Enable anisotropic filtering for better texture quality at angles
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            // Flip texture vertically for OpenGL (OpenGL V=0 at bottom, textures have row 0 at top)
            for (y in (height - 1) downTo 0) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                    buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
                    buffer.put((pixel and 0xFF).toByte())          // B
                    buffer.put(((pixel shr 24) and 0xFF).toByte()) // A
                }
            }
            buffer.flip()

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }

    /**
     * Create a checkerboard placeholder texture.
     */
    private fun createCheckerTexture(): Int {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val checker = ((x / 8) + (y / 8)) % 2 == 0
                val gray = if (checker) 200 else 100
                pixels[y * size + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return createTextureFromArgb(pixels, size, size)
    }

    fun bindTexture(textureId: Int, unit: Int = 0) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, textureId)
    }

    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * Public access to graphic decoding for diagnostic tools.
     */
    fun decodeGraphicToDataPublic(data: ByteArray, subIndex: Int): GraphicData? {
        return decodeGraphicToData(data, subIndex)
    }

    /**
     * Debug: Dump the generated texture pixels to PNG files for visual inspection.
     *
     * Writes `<base>.png` (RGB colour, alpha forced opaque so the colours are always
     * visible) and `<base>_alpha.png` (the alpha channel as greyscale), where `<base>`
     * is [outputPath] with any extension stripped. Images are saved WITHOUT vertical
     * flip (as generated, row 0 = top).
     */
    fun dumpTextureImage(cache: Cache, materialId: Int, outputPath: String) {
        val textureType = loadTextureType(cache, materialId)
        if (textureType == null) {
            println("dumpTextureImage: Could not load texture type for material $materialId")
            return
        }
        val material = getMaterial(materialId)
        val textureSize = getTextureSize(material)
        val opaque = isMaterialOpaque(material)

        val pixels = if (opaque) {
            textureType.getPixelsRgb(
                textureSize, textureSize,
                graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        } else {
            textureType.getPixelsArgb(
                textureSize, textureSize,
                graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        }

        // Apply same alpha post-processing as getTextureForMaterial
        if (material != null && material.effectCombiner != 2) {
            val effectCombiner = material.effectCombiner
            if (effectCombiner == 0) {
                val signedAlpha = material.alpha.toInt()
                val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                for (i in pixels.indices) {
                    val px = pixels[i]
                    pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                }
            } else {
                for (i in pixels.indices) {
                    val px = pixels[i]
                    val rgb = px and 0x00FFFFFF
                    pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                }
            }
        }

        // Write two real PNGs: the RGB colour image, and the alpha channel as greyscale.
        // The colour image forces alpha to opaque so the actual colours are visible even
        // where the material is fully transparent.
        val base = outputPath.substringBeforeLast('.', outputPath)
        val colorPath = "$base.png"
        val alphaPath = "${base}_alpha.png"

        val colorImage = BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_RGB)
        val alphaImage = BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_RGB)
        val colorRow = IntArray(textureSize)
        val alphaRow = IntArray(textureSize)
        for (y in 0 until textureSize) {
            val rowStart = y * textureSize
            for (x in 0 until textureSize) {
                val pixel = pixels[rowStart + x]
                colorRow[x] = pixel and 0x00FFFFFF
                val a = (pixel shr 24) and 0xFF
                alphaRow[x] = (a shl 16) or (a shl 8) or a
            }
            colorImage.setRGB(0, y, textureSize, 1, colorRow, 0, textureSize)
            alphaImage.setRGB(0, y, textureSize, 1, alphaRow, 0, textureSize)
        }
        ImageIO.write(colorImage, "png", File(colorPath))
        ImageIO.write(alphaImage, "png", File(alphaPath))

        // Print stats
        var nonBlack = 0
        var transparent = 0
        for (pixel in pixels) {
            val rgb = pixel and 0x00FFFFFF
            val a = (pixel shr 24) and 0xFF
            if (rgb != 0) nonBlack++
            if (a == 0) transparent++
        }
        println("dumpTextureImage($materialId): ${textureSize}x${textureSize}, opaque=$opaque, effectCombiner=${material?.effectCombiner}, nonBlack=$nonBlack/${pixels.size}, transparent=$transparent/${pixels.size}")
        println("  Saved colour to $colorPath")
        println("  Saved alpha to $alphaPath")
    }

    /**
     * Drop the CPU-side caches (decoded texture types, graphics, generated pixels)
     * without touching any GL resource. Safe to call without a GL context.
     */
    fun clearCpuCaches() {
        graphicDataCache.clear()
        textureDataCache.clear()
        pixelCache.clear()
        materialPixelCache.clear()
        materialPixelCacheBytes.set(0)
        generatingSet.get().clear()
    }

    fun clearCache() {
        textureCache.values.forEach { glDeleteTextures(it) }
        textureCache.clear()
        clearCpuCaches()
        if (defaultTextureId != 0) {
            glDeleteTextures(defaultTextureId)
            defaultTextureId = 0
        }
    }

}
