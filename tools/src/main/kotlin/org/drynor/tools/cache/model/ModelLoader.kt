package org.drynor.tools.cache.model

import org.drynor.tools.cache.texture.TextureLoader
import org.drynor.tools.engine.render.Mesh
import org.drynor.tools.engine.render.MeshData
import org.drynor.tools.util.ColorUtil
import org.drynor.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.BillboardTypeLoader
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A batch of faces sharing the same texture.
 */
data class TextureBatch(
    val mesh: Mesh,
    val textureId: Int  // -1 for untextured, material ID for textured
)

/**
 * Textured model data containing batched meshes for efficient rendering.
 * Faces are grouped by texture to minimize texture bind calls.
 */
data class TexturedModel(
    val colorBatch: Mesh?,              // Faces without textures (rendered with vertex colors)
    val textureBatches: List<TextureBatch>, // Faces grouped by texture ID
    val hasTextures: Boolean
) {
    val mesh: Mesh? get() = colorBatch  // Legacy access for non-textured rendering

    fun cleanup() {
        colorBatch?.cleanup()
        textureBatches.forEach { it.mesh.cleanup() }
    }
}

/**
 * Intermediate face data for batching.
 * Uses per-vertex normals for smooth shading.
 */
private data class FaceData(
    val v0: Vector3f, val v1: Vector3f, val v2: Vector3f,
    val color: Vector3f,
    val n0: Vector3f, val n1: Vector3f, val n2: Vector3f,  // Per-vertex normals
    val u0: Float, val v0Uv: Float,
    val u1: Float, val v1Uv: Float,
    val u2: Float, val v2Uv: Float,
    // Source model vertex behind each emitted slot's normal, or -1 when the slot does not carry a
    // smoothed vertex normal (flat-shaded face, billboard quad). Lets a cross-object normal merge
    // rewrite the normals of an already-decoded mesh instead of decoding it again.
    val sv0: Int = -1, val sv1: Int = -1, val sv2: Int = -1
)

/**
 * Model modifications from ObjectDefinition for retexturing/recoloring.
 * These are applied when creating mesh data to substitute textures and colors.
 *
 * @param retextureSrc Source texture IDs to replace
 * @param retextureDst Destination texture IDs (same length as retextureSrc)
 * @param recolorSrc Source HSL colors to replace
 * @param recolorDst Destination HSL colors (same length as recolorSrc)
 * @param normalMerge Cross-object vertex normal contributions from adjacent scenery, or null
 *   when this object merges with nothing. Unlike the other fields this one is per *instance*
 *   rather than per definition; it rides along here so that it lands in the mesh cache key
 *   ([cacheKeyComponent]) automatically and instances with identical neighbourhoods keep
 *   sharing one mesh. See [NormalMerge].
 */
data class ModelModifications(
    val retextureSrc: ShortArray? = null,
    val retextureDst: ShortArray? = null,
    val recolorSrc: ShortArray? = null,
    val recolorDst: ShortArray? = null,
    val ambient: Int = DEFAULT_AMBIENT,
    val normalMerge: NormalMerge? = null
) {
    /**
     * Check if any modifications are defined (beyond the default ambient).
     */
    fun hasModifications(): Boolean {
        return (retextureSrc != null && retextureSrc.isNotEmpty()) ||
               (recolorSrc != null && recolorSrc.isNotEmpty()) ||
               ambient != DEFAULT_AMBIENT ||
               normalMerge != null
    }

    /**
     * This modification set with [merge] attached, or unchanged when [merge] is null.
     */
    fun withNormalMerge(merge: NormalMerge?): ModelModifications {
        if (merge == null) return this
        return copy(normalMerge = merge)
    }

    /**
     * Apply texture substitution to a texture ID.
     */
    fun applyRetexture(textureId: Int): Int {
        if (retextureSrc == null || retextureDst == null) return textureId
        for (i in retextureSrc.indices) {
            val srcTexId = retextureSrc[i].toInt() and 0xffff
            if (srcTexId == textureId && i < retextureDst.size) {
                val dstTexId = retextureDst[i].toInt() and 0xffff
                return dstTexId
            }
        }
        return textureId
    }

    /**
     * Apply color substitution to an HSL color.
     */
    fun applyRecolor(hsl: Int): Int {
        if (recolorSrc == null || recolorDst == null) return hsl
        for (i in recolorSrc.indices) {
            val srcHsl = recolorSrc[i].toInt() and 0xffff
            if (srcHsl == hsl && i < recolorDst.size) {
                val dstHsl = recolorDst[i].toInt() and 0xffff
                return dstHsl
            }
        }
        return hsl
    }

    /**
     * Generate a unique key component for caching based on modifications.
     */
    fun cacheKeyComponent(): String {
        if (!hasModifications()) return ""
        val sb = StringBuilder()
        if (retextureSrc != null && retextureDst != null) {
            sb.append("_rt")
            for (i in retextureSrc.indices) {
                if (i < retextureDst.size) {
                    sb.append("_${retextureSrc[i].toInt() and 0xffff}>${retextureDst[i].toInt() and 0xffff}")
                }
            }
        }
        if (recolorSrc != null && recolorDst != null) {
            sb.append("_rc")
            for (i in recolorSrc.indices) {
                if (i < recolorDst.size) {
                    sb.append("_${recolorSrc[i].toInt() and 0xffff}>${recolorDst[i].toInt() and 0xffff}")
                }
            }
        }
        if (ambient != DEFAULT_AMBIENT) {
            sb.append("_amb$ambient")
        }
        if (normalMerge != null) {
            sb.append("_nm").append(normalMerge.signature)
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelModifications
        if (ambient != other.ambient) return false
        if (normalMerge != other.normalMerge) return false
        if (retextureSrc != null) {
            if (other.retextureSrc == null) return false
            if (!retextureSrc.contentEquals(other.retextureSrc)) return false
        } else if (other.retextureSrc != null) return false
        if (retextureDst != null) {
            if (other.retextureDst == null) return false
            if (!retextureDst.contentEquals(other.retextureDst)) return false
        } else if (other.retextureDst != null) return false
        if (recolorSrc != null) {
            if (other.recolorSrc == null) return false
            if (!recolorSrc.contentEquals(other.recolorSrc)) return false
        } else if (other.recolorSrc != null) return false
        if (recolorDst != null) {
            if (other.recolorDst == null) return false
            if (!recolorDst.contentEquals(other.recolorDst)) return false
        } else if (other.recolorDst != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = retextureSrc?.contentHashCode() ?: 0
        result = 31 * result + (retextureDst?.contentHashCode() ?: 0)
        result = 31 * result + (recolorSrc?.contentHashCode() ?: 0)
        result = 31 * result + (recolorDst?.contentHashCode() ?: 0)
        result = 31 * result + ambient
        result = 31 * result + (normalMerge?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Default ambient factor matching the RS client base value.
         * The RS client computes adjustedAmbient = 64 + objectDef.ambient,
         * where objectDef.ambient defaults to 0. So the base is always 64.
         * This factor scales the 7-bit lightness: lightness * 64 >> 7 = lightness / 2.
         */
        const val DEFAULT_AMBIENT = 64

        val NONE = ModelModifications()
    }
}

/**
 * Converts ModelDefinitions to renderable Meshes with texture support.
 */
object ModelLoader {
    /** Thread-safe wrapper for ConcurrentHashMap (which disallows null values). */
    private sealed class CacheResult<out T> {
        data class Found<T>(val value: T) : CacheResult<T>()
        data object NotFound : CacheResult<Nothing>()
    }

    private val definitionCache = ConcurrentHashMap<Int, CacheResult<ModelDefinition>>()

    /**
     * When true, untextured faces on models that also have textured faces are hidden.
     * This filters out "marker faces" - solid-color backing panels for text labels,
     * ground marker squares, and other untextured placeholder geometry that has no
     * visual value without a text/overlay rendering system.
     *
     * Models that are entirely untextured (no textured faces at all) render normally.
     * Only "mixed" models (both textured and untextured faces) are affected.
     *
     * Examples: signpost label backing panels (model 68750), ground markers (model 1105).
     */
    var hideUntexturedMarkerFaces: Boolean = true
    /**
     * Load a textured model. Creates new mesh objects each time since meshes
     * contain OpenGL resources that may be cleaned up by the caller.
     */
    fun loadTexturedModel(cache: Cache, modelId: Int): TexturedModel? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createTexturedModel(def, cache = cache)
    }

    fun loadMesh(cache: Cache, modelId: Int): Mesh? {
        return loadTexturedModel(cache, modelId)?.mesh
    }

    var glbDirectory: Path? = null

    fun loadDefinition(cache: Cache, modelId: Int): ModelDefinition? {
        val result = definitionCache.computeIfAbsent(modelId) {
            val glb = glbDirectory?.resolve("$modelId.glb")?.takeIf { Files.exists(it) }
            val def = if (glb != null) ModelGltf.read(Files.readAllBytes(glb), modelId) else ModelDecoder.decode(cache, modelId)
            if (def != null) CacheResult.Found(def) else CacheResult.NotFound
        }
        return when (result) {
            is CacheResult.Found -> result.value
            is CacheResult.NotFound -> null
        }
    }

    /**
     * Check whether a model has any textured faces.
     *
     * In the RS client, ground decorations (shape 22) that are entirely untextured
     * sit at terrain level and are hidden by the terrain mesh due to GL_LEQUAL depth
     * testing and render order (non-alpha objects draw before terrain in drawTiles()).
     * These are engine markers/placeholders (e.g., model 1105 for object 16438).
     *
     * Ground decorations with textured faces (farming patches, floor markings, etc.)
     * are visually distinct and render correctly.
     *
     * @param cache The game cache
     * @param modelId The model ID to check
     * @return true if the model has at least one textured face
     */
    fun modelHasTextures(cache: Cache, modelId: Int): Boolean {
        val def = loadDefinition(cache, modelId) ?: return false
        val textures = def.faceTextures ?: return false
        for (i in textures.indices) {
            if (textures[i].toInt() != -1) return true
        }
        return false
    }

    /**
     * Accumulated vertex normal data for smooth shading.
     */
    private class VertexNormal {
        var x: Float = 0f
        var y: Float = 0f
        var z: Float = 0f
        var magnitude: Int = 0
    }

    /**
     * Sum the unit normal of every render-type-0 face into each of its three vertices,
     * counting contributions in [VertexNormal.magnitude].
     *
     * This is the RS client's smoothing pass (`ModelGL.createModel`): the running sum and
     * the contribution count are kept separately rather than being averaged eagerly, because
     * the count is what makes two vertex normals *combinable*. Adding another model's sum and
     * count at a coincident vertex and only then dividing gives the average over both models'
     * faces, which is exactly what `ModelGL.applyShading` does across adjacent objects.
     *
     * Render type 1 (flat) faces deliberately contribute nothing: they are shaded from their
     * own face normal. Render type 2 faces are not drawn at all.
     *
     * Values are in RS space (Y and Z inverted relative to OpenGL); callers flip when they
     * emit the final normal.
     */
    private fun accumulateVertexNormals(def: ModelDefinition): Array<VertexNormal> {
        val vertexNormals = Array(def.vertexCount) { VertexNormal() }

        for (i in 0 until def.faceCount) {
            val faceRenderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (faceRenderType != 0) continue  // Only type 0 accumulates vertex normals

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx < 0 || aIdx >= def.vertexCount) continue
            if (bIdx < 0 || bIdx >= def.vertexCount) continue
            if (cIdx < 0 || cIdx >= def.vertexCount) continue

            val ax = def.vertexX[aIdx]
            val ay = def.vertexY[aIdx]
            val az = def.vertexZ[aIdx]

            val dx1 = def.vertexX[bIdx] - ax
            val dy1 = def.vertexY[bIdx] - ay
            val dz1 = def.vertexZ[bIdx] - az
            val dx2 = def.vertexX[cIdx] - ax
            val dy2 = def.vertexY[cIdx] - ay
            val dz2 = def.vertexZ[cIdx] - az

            var nx = (dy1 * dz2 - dz1 * dy2).toFloat()
            var ny = (dz1 * dx2 - dx1 * dz2).toFloat()
            var nz = (dx1 * dy2 - dy1 * dx2).toFloat()

            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                len = 1f / len
                nx *= len
                ny *= len
                nz *= len
            }

            vertexNormals[aIdx].x += nx
            vertexNormals[aIdx].y += ny
            vertexNormals[aIdx].z += nz
            vertexNormals[aIdx].magnitude++

            vertexNormals[bIdx].x += nx
            vertexNormals[bIdx].y += ny
            vertexNormals[bIdx].z += nz
            vertexNormals[bIdx].magnitude++

            vertexNormals[cIdx].x += nx
            vertexNormals[cIdx].y += ny
            vertexNormals[cIdx].z += nz
            vertexNormals[cIdx].magnitude++
        }

        return vertexNormals
    }

    /**
     * Per-vertex position and accumulated normal for a model, as the cross-object normal
     * merge needs to see it.
     *
     * Positions stay in raw RS model units so the merge can locate coincident vertices with
     * the client's exact integer arithmetic instead of comparing transformed floats.
     */
    class VertexNormalField(
        val vertexX: IntArray,
        val vertexY: IntArray,
        val vertexZ: IntArray,
        val normalX: FloatArray,
        val normalY: FloatArray,
        val normalZ: FloatArray,
        val magnitude: IntArray
    ) {
        val vertexCount: Int get() = vertexX.size
    }

    private val vertexNormalFieldCache = ConcurrentHashMap<Int, CacheResult<VertexNormalField>>()

    /**
     * Vertex positions and accumulated (unaveraged) vertex normals for a model or model
     * combination, keyed and cached by combined model id.
     *
     * Retexture/recolor modifications never move a vertex or change a face's winding, so this
     * is a property of the model id set alone and is shared across every object that uses it.
     */
    fun vertexNormalField(cache: Cache, modelIds: List<Int>): VertexNormalField? {
        if (modelIds.isEmpty()) return null
        val combinedId = getCombinedModelId(modelIds)
        vertexNormalFieldCache[combinedId]?.let { cached ->
            return (cached as? CacheResult.Found)?.value
        }
        // See createMeshDataListFromDef: computed outside the map so a decode never holds a bin
        // lock against another region's loader thread.
        val result = run {
            val def = loadCombinedDefinition(cache, modelIds)
            if (def == null || def.vertexCount == 0) {
                CacheResult.NotFound
            } else {
                val accumulated = accumulateVertexNormals(def)
                val count = def.vertexCount
                val normalX = FloatArray(count)
                val normalY = FloatArray(count)
                val normalZ = FloatArray(count)
                val magnitude = IntArray(count)
                for (i in 0 until count) {
                    val vn = accumulated[i]
                    normalX[i] = vn.x
                    normalY[i] = vn.y
                    normalZ[i] = vn.z
                    magnitude[i] = vn.magnitude
                }
                CacheResult.Found(
                    VertexNormalField(
                        vertexX = def.vertexX.copyOf(count),
                        vertexY = def.vertexY.copyOf(count),
                        vertexZ = def.vertexZ.copyOf(count),
                        normalX = normalX,
                        normalY = normalY,
                        normalZ = normalZ,
                        magnitude = magnitude
                    )
                )
            }
        }
        val stored = vertexNormalFieldCache.putIfAbsent(combinedId, result) ?: result
        return when (stored) {
            is CacheResult.Found -> stored.value
            is CacheResult.NotFound -> null
        }
    }

    /**
     * Unit face normal for [face], in OpenGL space.
     *
     * This is the normal that flat-shaded faces (`faceRenderTypes[face] == 1`) must use on
     * all three of their vertices. The RS client keeps flat faces off the smoothing pass
     * entirely: `ModelGL.createModel` only accumulates a face's normal into its vertices
     * when the render type is 0, and then re-reads the *face* normal for type 1
     * (`connectNormal(..., faceNormal.x, faceNormal.y, faceNormal.z, magnitude = 0, ...)`).
     * The reference client's modern GL builder states it outright:
     * "faceRenderType 1: flat-shaded; uses the face normal for all three vertices"
     * (`ModernModelBuilder.buildFromMesh`).
     *
     * Feeding a flat face the smoothed vertex normals of its neighbours bleeds shading across
     * the crease the face was authored to break at: staircase treads and risers wash into one
     * ramp, panelled and stepped geometry loses its edges. 4.48M of the 31.5M faces in the
     * cache (14.2%, across 22,122 of 73,409 models) carry render type 1, so this is not a
     * corner case.
     *
     * Cross product is taken in RS space as `(B - A) x (C - A)`, matching the smoothing pass,
     * then mapped to OpenGL space with the same `(x, -y, -z)` axis flip. Flipping two axes is
     * a 180-degree rotation about X, so it preserves handedness and the two normal sources
     * stay consistent with each other.
     */
    private fun faceNormalGL(def: ModelDefinition, aIdx: Int, bIdx: Int, cIdx: Int): Vector3f {
        val ax = def.vertexX[aIdx]
        val ay = def.vertexY[aIdx]
        val az = def.vertexZ[aIdx]
        val dx1 = def.vertexX[bIdx] - ax
        val dy1 = def.vertexY[bIdx] - ay
        val dz1 = def.vertexZ[bIdx] - az
        val dx2 = def.vertexX[cIdx] - ax
        val dy2 = def.vertexY[cIdx] - ay
        val dz2 = def.vertexZ[cIdx] - az

        var nx = (dy1 * dz2 - dz1 * dy2).toFloat()
        var ny = (dz1 * dx2 - dx1 * dz2).toFloat()
        var nz = (dx1 * dy2 - dy1 * dx2).toFloat()

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0f) {
            val invLen = 1f / len
            nx *= invLen
            ny *= invLen
            nz *= invLen
        } else {
            // Degenerate triangle: match the smoothing pass's +Y up fallback.
            return Vector3f(0f, 1f, 0f)
        }

        return Vector3f(nx, -ny, -nz)
    }

    /** Cache of texture transform data per model */
    private val textureTransformCache = ConcurrentHashMap<Int, CacheResult<TextureTransformData>>()

    /**
     * Pre-computed texture transformation data for complex mappings (types 1-3).
     * This matches the RS client's generateTextureMatrix approach.
     */
    private data class TextureTransformData(
        val centerX: IntArray,      // Center X for each texture triangle
        val centerY: IntArray,      // Center Y for each texture triangle
        val centerZ: IntArray,      // Center Z for each texture triangle
        val matrix: Array<FloatArray>,  // 3x3 transformation matrix for each texture triangle
        val complexIndex: IntArray,  // Pre-computed index into complex texture arrays for each texture pointer
        val cubeIndex: IntArray      // Pre-computed index into cube texture arrays for each texture pointer
    )

    /**
     * Generate texture transformation data for all texture triangles in a model.
     * This computes bounding boxes and transformation matrices for complex mappings.
     * Also pre-computes indices into complex/cube texture arrays for O(1) lookup.
     */
    private fun generateTextureTransformData(def: ModelDefinition): TextureTransformData? {
        if (def.textureTriangleCount == 0 || def.texturesMapping == null) return null

        val numTextures = def.textureTriangleCount

        // Pre-compute indices into complex and cube texture arrays (O(n) once instead of O(n) per face)
        val complexIndex = IntArray(numTextures)
        val cubeIndex = IntArray(numTextures)
        var complexCount = 0
        var cubeCount = 0
        for (i in 0 until numTextures) {
            complexIndex[i] = complexCount
            cubeIndex[i] = cubeCount
            val mapping = def.texturesMapping!![i].toInt() and 0xff
            if (mapping in 1..3) complexCount++
            if (mapping == 2) cubeCount++
        }

        // Initialize min/max arrays for computing centers
        val minX = IntArray(numTextures) { Int.MAX_VALUE }
        val maxX = IntArray(numTextures) { Int.MIN_VALUE }
        val minY = IntArray(numTextures) { Int.MAX_VALUE }
        val maxY = IntArray(numTextures) { Int.MIN_VALUE }
        val minZ = IntArray(numTextures) { Int.MAX_VALUE }
        val maxZ = IntArray(numTextures) { Int.MIN_VALUE }

        // Accumulate bounds for each texture from faces that use it
        for (faceIdx in 0 until def.faceCount) {
            val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255
            if (texturePointer == 255 || texturePointer >= numTextures) continue

            for (axis in 0..2) {
                val vertIdx = when (axis) {
                    0 -> def.faceA[faceIdx]
                    1 -> def.faceB[faceIdx]
                    else -> def.faceC[faceIdx]
                }
                if (vertIdx < 0 || vertIdx >= def.vertexCount) continue

                val x = def.vertexX[vertIdx]
                val y = def.vertexY[vertIdx]
                val z = def.vertexZ[vertIdx]

                if (x < minX[texturePointer]) minX[texturePointer] = x
                if (x > maxX[texturePointer]) maxX[texturePointer] = x
                if (y < minY[texturePointer]) minY[texturePointer] = y
                if (y > maxY[texturePointer]) maxY[texturePointer] = y
                if (z < minZ[texturePointer]) minZ[texturePointer] = z
                if (z > maxZ[texturePointer]) maxZ[texturePointer] = z
            }
        }

        // Compute centers and transformation matrices
        val centerX = IntArray(numTextures)
        val centerY = IntArray(numTextures)
        val centerZ = IntArray(numTextures)
        val matrices = Array(numTextures) { floatArrayOf() }

        for (texIdx in 0 until numTextures) {
            val mapping = def.texturesMapping!![texIdx].toInt() and 0xff
            if (mapping > 0) {
                // Compute center of bounding box
                centerX[texIdx] = (minX[texIdx] + maxX[texIdx]) / 2
                centerY[texIdx] = (minY[texIdx] + maxY[texIdx]) / 2
                centerZ[texIdx] = (minZ[texIdx] + maxZ[texIdx]) / 2

                // Use pre-computed complex index for array access
                val complexIdx = complexIndex[texIdx]

                // Get scale factors based on mapping type
                val scaleX: Float
                val scaleY: Float
                val scaleZ: Float

                if (mapping == 1) {
                    // Cylindrical mapping
                    val x = def.texturesScaleX?.get(complexIdx) ?: 0
                    if (x == 0) {
                        scaleX = 1.0f
                        scaleZ = 1.0f
                    } else if (x > 0) {
                        scaleX = 1.0f
                        scaleZ = x / 1024.0f
                    } else {
                        scaleZ = 1.0f
                        scaleX = (-x) / 1024.0f
                    }
                    scaleY = 64.0f / (def.texturesScaleY?.get(complexIdx) ?: 1)
                } else if (mapping == 2) {
                    // Cube mapping - protect against division by zero
                    val rawX = def.texturesScaleX?.get(complexIdx) ?: 1
                    val rawY = def.texturesScaleY?.get(complexIdx) ?: 1
                    val rawZ = def.texturesScaleZ?.get(complexIdx) ?: 1
                    scaleX = 64.0f / (if (rawX == 0) 1 else rawX)
                    scaleY = 64.0f / (if (rawY == 0) 1 else rawY)
                    scaleZ = 64.0f / (if (rawZ == 0) 1 else rawZ)
                } else {
                    // Spherical mapping (type 3)
                    scaleX = (def.texturesScaleX?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleY = (def.texturesScaleY?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleZ = (def.texturesScaleZ?.get(complexIdx) ?: 1024) / 1024.0f
                }

                // Get rotation and texture triangle vertices
                val rotation = def.texturesRotation?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
                val n = def.textureFaceX?.get(texIdx)?.toInt() ?: 0
                val m = def.textureFaceY?.get(texIdx)?.toInt() ?: 0
                val p = def.textureFaceZ?.get(texIdx)?.toInt() ?: 0

                matrices[texIdx] = createTextureMatrix(n, m, p, rotation, scaleX, scaleY, scaleZ)
            }
        }

        return TextureTransformData(centerX, centerY, centerZ, matrices, complexIndex, cubeIndex)
    }

    /**
     * Create a 3x3 texture transformation matrix.
     * Combines Y-axis rotation with axis-angle rotation derived from texture triangle vertices.
     */
    private fun createTextureMatrix(n: Int, m: Int, p: Int, rotation: Int, scaleX: Float, scaleY: Float, scaleZ: Float): FloatArray {
        val rotationMap = FloatArray(9)
        val result = FloatArray(9)

        // Y-axis rotation from rotation parameter (0-255 maps to 0-2pi)
        var cosR = cos(rotation * 0.024543693f)  // rotation * (2*PI / 256)
        var sinR = sin(rotation * 0.024543693f)

        rotationMap[0] = cosR
        rotationMap[1] = 0.0f
        rotationMap[2] = sinR
        rotationMap[3] = 0.0f
        rotationMap[4] = 1.0f
        rotationMap[5] = 0.0f
        rotationMap[6] = -sinR
        rotationMap[7] = 0.0f
        rotationMap[8] = cosR

        val matrix3x3 = FloatArray(9)
        var x = 1.0f
        var y = 0.0f

        // Axis-angle rotation from texture triangle vertices
        val cosA = m / 32767.0f
        val sinA = -sqrt(1.0f - cosA * cosA)
        val omc = 1.0f - cosA

        val size = sqrt((p * p + n * n).toFloat())
        if (size == 0.0f && cosA == 0.0f) {
            return rotationMap
        }

        if (size != 0.0f) {
            x = (-p) / size
            y = n.toFloat() / size
        }

        matrix3x3[0] = cosA + x * x * omc
        matrix3x3[1] = y * sinA
        matrix3x3[2] = y * x * omc
        matrix3x3[3] = -y * sinA
        matrix3x3[4] = cosA
        matrix3x3[5] = x * sinA
        matrix3x3[6] = x * y * omc
        matrix3x3[7] = -x * sinA
        matrix3x3[8] = cosA + y * y * omc

        // Multiply rotation matrices
        result[0] = rotationMap[0] * matrix3x3[0] + rotationMap[1] * matrix3x3[3] + rotationMap[2] * matrix3x3[6]
        result[1] = rotationMap[0] * matrix3x3[1] + rotationMap[1] * matrix3x3[4] + rotationMap[2] * matrix3x3[7]
        result[2] = rotationMap[0] * matrix3x3[2] + rotationMap[1] * matrix3x3[5] + rotationMap[2] * matrix3x3[8]
        result[3] = rotationMap[3] * matrix3x3[0] + rotationMap[4] * matrix3x3[3] + rotationMap[5] * matrix3x3[6]
        result[4] = rotationMap[3] * matrix3x3[1] + rotationMap[4] * matrix3x3[4] + rotationMap[5] * matrix3x3[7]
        result[5] = rotationMap[3] * matrix3x3[2] + rotationMap[4] * matrix3x3[5] + rotationMap[5] * matrix3x3[8]
        result[6] = rotationMap[6] * matrix3x3[0] + rotationMap[7] * matrix3x3[3] + rotationMap[8] * matrix3x3[6]
        result[7] = rotationMap[6] * matrix3x3[1] + rotationMap[7] * matrix3x3[4] + rotationMap[8] * matrix3x3[7]
        result[8] = rotationMap[6] * matrix3x3[2] + rotationMap[7] * matrix3x3[5] + rotationMap[8] * matrix3x3[8]

        // Apply scale
        result[0] *= scaleX
        result[1] *= scaleX
        result[2] *= scaleX
        result[3] *= scaleY
        result[4] *= scaleY
        result[5] *= scaleY
        result[6] *= scaleZ
        result[7] *= scaleZ
        result[8] *= scaleZ

        return result
    }

    /**
     * Map cylindrical UV coordinates (type 1).
     */
    private fun mapCylindricalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, offset: Float, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Transform by matrix
        val y = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val height = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val x = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        // Cylindrical projection
        var u = atan2(y, x) / (2 * Math.PI.toFloat()) + 0.5f
        if (offset != 1.0f) {
            u *= offset
        }
        var v = height + 0.5f + speedOffset

        // Apply direction rotation
        applyDirection(direction, u, v, uv)
    }

    /**
     * Map spherical UV coordinates (type 3).
     */
    private fun mapSphericalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Transform by matrix
        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        val dist = sqrt(tx * tx + ty * ty + tz * tz)

        // Spherical projection
        var u = atan2(tx, tz) / (2 * Math.PI.toFloat()) + 0.5f
        var v = asin(ty / dist) / Math.PI.toFloat() + 0.5f + speedOffset

        // Apply direction rotation
        applyDirection(direction, u, v, uv)
    }

    /**
     * Map cube/rectangular UV coordinates (type 2).
     *
     * This matches the RS client's mapRectangularUv function exactly.
     * The cube face determines which pair of transformed coordinates to use:
     * - Face 0 (+Y): tx for U, -tz for V
     * - Face 1 (-Y): tx for U, tz for V
     * - Face 2 (+Z): -tx for U, -ty for V
     * - Face 3 (-Z): tx for U, -ty for V
     * - Face 4 (+X): tz for U, -ty for V
     * - Face 5 (-X): -tz for U, -ty for V
     *
     * Parameters speedOffset, uTrans, vTrans come from the texture definition
     * and control animation/translation of the texture.
     */
    private fun mapCubeUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        cubeFace: Int, matrix: FloatArray, direction: Int,
        speedOffset: Float, uTrans: Float, vTrans: Float,
        uv: FloatArray
    ) {
        // Transform vertex position relative to center through texture matrix
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Matrix rows [0-2], [3-5], [6-8] transform to X, Y, Z axes respectively
        // This includes the scale factor that was baked into the matrix
        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        var u: Float
        var v: Float

        // Project onto appropriate cube face
        // This matches the RS client's mapRectangularUv switch statement exactly:
        // - f_14 = tx, f_15 = ty, f_16 = tz
        // - f_10 = speedOffset, f_11 = uTrans, f_12 = vTrans
        when (cubeFace) {
            0 -> { // +Y face: u = tx + speedOffset, v = -tz + vTrans
                u = tx + speedOffset + 0.5f
                v = -tz + vTrans + 0.5f
            }
            1 -> { // -Y face: u = tx + speedOffset, v = tz + vTrans
                u = tx + speedOffset + 0.5f
                v = tz + vTrans + 0.5f
            }
            2 -> { // +Z face: u = -tx + speedOffset, v = -ty + uTrans
                u = -tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            3 -> { // -Z face: u = tx + speedOffset, v = -ty + uTrans
                u = tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            4 -> { // +X face: u = tz + vTrans, v = -ty + uTrans
                u = tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
            else -> { // -X face (5): u = -tz + vTrans, v = -ty + uTrans
                u = -tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
        }

        // Apply direction rotation (0-3 for 0/90/180/270 degrees)
        applyDirection(direction, u, v, uv)
    }

    /**
     * Determine which face of a cube a transformed normal points to.
     *
     * This matches the RS client's getCubeFace function exactly.
     * Returns:
     * - 0: +Y face (normal points up)
     * - 1: -Y face (normal points down)
     * - 2: +Z face (normal points forward)
     * - 3: -Z face (normal points backward)
     * - 4: +X face (normal points right)
     * - 5: -X face (normal points left)
     *
     * The face with the largest absolute component wins.
     * Ties are broken in order: Y > Z > X (due to the if-else chain structure).
     */
    private fun getCubeFace(nx: Float, ny: Float, nz: Float): Int {
        // Get absolute values for comparison
        val ax = if (nx < 0.0f) -nx else nx
        val ay = if (ny < 0.0f) -ny else ny
        val az = if (nz < 0.0f) -nz else nz

        // Determine dominant axis and sign
        // This matches the client's ternary operator chain exactly
        return if (ay > ax && ay > az) {
            if (ny > 0.0f) 0 else 1  // Y is dominant: +Y or -Y
        } else if (az > ax && az > ay) {
            if (nz > 0.0f) 2 else 3  // Z is dominant: +Z or -Z
        } else {
            if (nx > 0.0f) 4 else 5  // X is dominant (or tied): +X or -X
        }
    }

    /**
     * Apply direction rotation to UV coordinates and flip V for OpenGL.
     * Direction 0 = no rotation, 1-3 = 90/180/270 degree rotations.
     *
     * The V flip (1 - V) corrects for the difference between RS and OpenGL texture coordinates:
     * - RS textures have V=0 at top, V=1 at bottom
     * - OpenGL textures have V=0 at bottom, V=1 at top
     * - Since we flip texture data when loading (TextureLoader.createTextureFromArgb),
     *   we must also flip V coordinates for correct sampling.
     * - This works correctly for repeating textures because:
     *   frac(1 - V) maps RS sample positions to correct OpenGL positions.
     */
    private fun applyDirection(direction: Int, u: Float, v: Float, uv: FloatArray) {
        when (direction) {
            1 -> {
                uv[0] = -v
                uv[1] = u
            }
            2 -> {
                uv[0] = -u
                uv[1] = -v
            }
            3 -> {
                uv[0] = v
                uv[1] = -u
            }
            else -> {
                uv[0] = u
                uv[1] = v
            }
        }
        // Flip V for OpenGL (RS has V=0 at top, OpenGL has V=0 at bottom with flipped texture)
        uv[1] = 1.0f - uv[1]
    }

    /**
     * Compute UV coordinates for a textured face using RS texture triangle projection.
     *
     * Supports all 4 mapping types:
     * - Type 0: Planar projection onto texture triangle plane
     * - Type 1: Cylindrical projection
     * - Type 2: Cube/rectangular projection
     * - Type 3: Spherical projection
     *
     * If faceTexturePointer == -1 (no texture pointer): use fixed UVs matching the RS client.
     * The client uses: (0, 1), (1, 1), (0, 0) which after V-flip for OpenGL become: (0, 0), (1, 0), (0, 1).
     */
    private fun computeUVs(def: ModelDefinition, faceIdx: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int = -1): FloatArray {
        val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255

        // If no texture pointer or invalid, use fixed UVs matching the RS client behavior.
        // The RS client uses: u=(0,1,0) v=(1,1,0). After V-flip for OpenGL: v=(0,0,1).
        if (texturePointer == 255 || texturePointer >= def.textureTriangleCount) {
            return FIXED_FALLBACK_UVS
        }

        val mappingType = def.texturesMapping?.get(texturePointer)?.toInt()?.and(0xff) ?: 0

        return when (mappingType) {
            0 -> computePlanarUVs(def, texturePointer, aIdx, bIdx, cIdx, textureId)
            1 -> computeCylindricalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            2 -> computeCubeUVs(def, faceIdx, texturePointer, aIdx, bIdx, cIdx)
            3 -> computeSphericalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            else -> FIXED_FALLBACK_UVS
        }
    }

    /**
     * Fixed fallback UV coordinates matching the RS client behavior.
     *
     * When a face has a material/texture but no texture pointer (textureOnFace == -1),
     * the RS client uses these fixed UVs:
     *   u[0]=0.0, v[0]=1.0  (vertex A)
     *   u[1]=1.0, v[1]=1.0  (vertex B)
     *   u[2]=0.0, v[2]=0.0  (vertex C)
     *
     * After V-flip for OpenGL (V_gl = 1 - V_rs):
     *   u[0]=0.0, v[0]=0.0  (vertex A)
     *   u[1]=1.0, v[1]=0.0  (vertex B)
     *   u[2]=0.0, v[2]=1.0  (vertex C)
     */
    private val FIXED_FALLBACK_UVS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

    /**
     * Type 0: Planar projection onto texture triangle plane.
     *
     * Implements the RS client's planar UV mapping exactly:
     * 1. Get texture triangle vertices (textureFaceX/Y/Z[texturePointer])
     * 2. Compute projection basis using cross products
     * 3. Project face vertices onto the texture triangle plane
     * 4. V is flipped (1-V) to compensate for OpenGL texture coordinate convention
     */
    private fun computePlanarUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int): FloatArray {
        val texFaceX = def.textureFaceX ?: return FIXED_FALLBACK_UVS
        val texFaceY = def.textureFaceY ?: return FIXED_FALLBACK_UVS
        val texFaceZ = def.textureFaceZ ?: return FIXED_FALLBACK_UVS

        val tAIdx = texFaceX[texturePointer].toInt() and 0xffff
        val tBIdx = texFaceY[texturePointer].toInt() and 0xffff
        val tCIdx = texFaceZ[texturePointer].toInt() and 0xffff

        if (tAIdx >= def.vertexCount || tBIdx >= def.vertexCount || tCIdx >= def.vertexCount) {
            return FIXED_FALLBACK_UVS
        }

        val tAx = def.vertexX[tAIdx].toFloat()
        val tAy = def.vertexY[tAIdx].toFloat()
        val tAz = def.vertexZ[tAIdx].toFloat()

        val e1x = def.vertexX[tBIdx].toFloat() - tAx
        val e1y = def.vertexY[tBIdx].toFloat() - tAy
        val e1z = def.vertexZ[tBIdx].toFloat() - tAz

        val e2x = def.vertexX[tCIdx].toFloat() - tAx
        val e2y = def.vertexY[tCIdx].toFloat() - tAy
        val e2z = def.vertexZ[tCIdx].toFloat() - tAz

        val v0x = def.vertexX[aIdx].toFloat() - tAx
        val v0y = def.vertexY[aIdx].toFloat() - tAy
        val v0z = def.vertexZ[aIdx].toFloat() - tAz

        val v1x = def.vertexX[bIdx].toFloat() - tAx
        val v1y = def.vertexY[bIdx].toFloat() - tAy
        val v1z = def.vertexZ[bIdx].toFloat() - tAz

        val v2x = def.vertexX[cIdx].toFloat() - tAx
        val v2y = def.vertexY[cIdx].toFloat() - tAy
        val v2z = def.vertexZ[cIdx].toFloat() - tAz

        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x

        val uBasisX = e2y * nz - e2z * ny
        val uBasisY = e2z * nx - e2x * nz
        val uBasisZ = e2x * ny - e2y * nx
        var uDenom = uBasisX * e1x + uBasisY * e1y + uBasisZ * e1z
        if (uDenom == 0f) uDenom = 1f
        val uScale = 1f / uDenom

        val vBasisX = e1y * nz - e1z * ny
        val vBasisY = e1z * nx - e1x * nz
        val vBasisZ = e1x * ny - e1y * nx
        var vDenom = vBasisX * e2x + vBasisY * e2y + vBasisZ * e2z
        if (vDenom == 0f) vDenom = 1f
        val vScale = 1f / vDenom

        val aU = (uBasisX * v0x + uBasisY * v0y + uBasisZ * v0z) * uScale
        val aV = 1.0f - (vBasisX * v0x + vBasisY * v0y + vBasisZ * v0z) * vScale  // Flip V for OpenGL
        val bU = (uBasisX * v1x + uBasisY * v1y + uBasisZ * v1z) * uScale
        val bV = 1.0f - (vBasisX * v1x + vBasisY * v1y + vBasisZ * v1z) * vScale  // Flip V for OpenGL
        val cU = (uBasisX * v2x + uBasisY * v2y + uBasisZ * v2z) * uScale
        val cV = 1.0f - (vBasisX * v2x + vBasisY * v2y + vBasisZ * v2z) * vScale  // Flip V for OpenGL

        return floatArrayOf(aU, aV, bU, bV, cU, cV)
    }

    /**
     * Type 1: Cylindrical projection.
     */
    private fun computeCylindricalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        // Get or compute texture transform data
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed complex texture index (O(1) instead of O(n))
        val complexIdx = transformData.complexIndex[texturePointer]
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f
        val offset = (def.texturesScaleZ?.get(complexIdx) ?: 1024) / 1024.0f

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapCylindricalUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvA)
        mapCylindricalUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvB)
        mapCylindricalUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvC)

        // Apply UV seam correction for cylindrical mapping
        val halfOffset = offset / 2.0f
        if ((direction and 1) == 0) {
            // Correct U seam
            if (uvB[0] - uvA[0] > halfOffset) uvB[0] -= offset
            else if (uvA[0] - uvB[0] > halfOffset) uvB[0] += offset
            if (uvC[0] - uvA[0] > halfOffset) uvC[0] -= offset
            else if (uvA[0] - uvC[0] > halfOffset) uvC[0] += offset
        } else {
            // Correct V seam
            if (uvB[1] - uvA[1] > halfOffset) uvB[1] -= offset
            else if (uvA[1] - uvB[1] > halfOffset) uvB[1] += offset
            if (uvC[1] - uvA[1] > halfOffset) uvC[1] -= offset
            else if (uvA[1] - uvC[1] > halfOffset) uvC[1] += offset
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    /**
     * Type 2: Cube/rectangular projection.
     *
     * This matches the RS client's cube mapping logic exactly (ModelGL.kt lines 715-740):
     * 1. Compute face normal via cross product of edges
     * 2. Transform normal by texture matrix (which has scale baked in)
     * 3. UN-scale the transformed normal to determine which cube face
     * 4. Project all three vertices using mapCubeUV (which uses the scaled matrix)
     *
     * The key insight is that scale is baked into the matrix for UV projection,
     * but we need to remove it for cube face determination so the face selection
     * is based on the un-scaled orientation.
     */
    private fun computeCubeUVs(def: ModelDefinition, faceIdx: Int, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed sparse indices for O(1) array access
        val complexIdx = transformData.complexIndex[texturePointer]
        val cubeIdx = transformData.cubeIndex[texturePointer]

        // Get cube mapping parameters from sparse arrays
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f
        val uTrans = (def.texturesUtrans?.get(cubeIdx) ?: 0) / 256.0f
        val vTrans = (def.texturesVtrans?.get(cubeIdx) ?: 0) / 256.0f

        // Compute face normal via cross product (B-A) x (C-A)
        // This matches the client's: i_100-i_108 calculations
        val dx1 = def.vertexX[bIdx] - def.vertexX[aIdx]
        val dy1 = def.vertexY[bIdx] - def.vertexY[aIdx]
        val dz1 = def.vertexZ[bIdx] - def.vertexZ[aIdx]
        val dx2 = def.vertexX[cIdx] - def.vertexX[aIdx]
        val dy2 = def.vertexY[cIdx] - def.vertexY[aIdx]
        val dz2 = def.vertexZ[cIdx] - def.vertexZ[aIdx]
        val fnx = dy1 * dz2 - dz1 * dy2  // i_106
        val fny = dz1 * dx2 - dx1 * dz2  // i_107
        val fnz = dx1 * dy2 - dy1 * dx2  // i_108

        // Get scales for un-scaling the transformed normal
        // The matrix already has scale baked in, so we need to divide by scale
        // to get the true face orientation. This matches: f_52, f_53, f_54
        val rawScaleX = def.texturesScaleX?.get(complexIdx) ?: 1
        val rawScaleY = def.texturesScaleY?.get(complexIdx) ?: 1
        val rawScaleZ = def.texturesScaleZ?.get(complexIdx) ?: 1
        // Protect against division by zero
        val scaleX = 64.0f / (if (rawScaleX == 0) 1 else rawScaleX)
        val scaleY = 64.0f / (if (rawScaleY == 0) 1 else rawScaleY)
        val scaleZ = 64.0f / (if (rawScaleZ == 0) 1 else rawScaleZ)

        // Transform normal by matrix, then divide by scale to un-do the scaling
        // This gives us the true face orientation for cube face selection
        // Matches client: f_55 = (i_106 * matrix[0] + i_107 * matrix[1] + i_108 * matrix[2]) / f_52
        val tnx = (fnx * matrix[0] + fny * matrix[1] + fnz * matrix[2]) / scaleX
        val tny = (fnx * matrix[3] + fny * matrix[4] + fnz * matrix[5]) / scaleY
        val tnz = (fnx * matrix[6] + fny * matrix[7] + fnz * matrix[8]) / scaleZ
        val cubeFace = getCubeFace(tnx, tny, tnz)

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapCubeUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvA)
        mapCubeUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvB)
        mapCubeUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvC)

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    /**
     * Type 3: Spherical projection.
     */
    private fun computeSphericalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed complex texture index (O(1) instead of O(n))
        val complexIdx = transformData.complexIndex[texturePointer]
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapSphericalUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvA)
        mapSphericalUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvB)
        mapSphericalUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvC)

        // Apply UV seam correction for spherical mapping
        if ((direction and 1) == 0) {
            // Correct U seam
            if (uvB[0] - uvA[0] > 0.5f) uvB[0] -= 1.0f
            else if (uvA[0] - uvB[0] > 0.5f) uvB[0] += 1.0f
            if (uvC[0] - uvA[0] > 0.5f) uvC[0] -= 1.0f
            else if (uvA[0] - uvC[0] > 0.5f) uvC[0] += 1.0f
        } else {
            // Correct V seam
            if (uvB[1] - uvA[1] > 0.5f) uvB[1] -= 1.0f
            else if (uvA[1] - uvB[1] > 0.5f) uvB[1] += 1.0f
            if (uvC[1] - uvA[1] > 0.5f) uvC[1] -= 1.0f
            else if (uvA[1] - uvC[1] > 0.5f) uvC[1] += 1.0f
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    /**
     * Create a textured model with faces batched by texture ID for efficient rendering.
     * Uses smooth shading with per-vertex normals calculated by accumulating face normals.
     *
     * @param def The model definition
     * @param ambient Ambient lightness factor (default 64, matching RS client base).
     *   The RS client computes this as 64 + objectDef.ambient.
     */
    fun createTexturedModel(
        def: ModelDefinition,
        ambient: Int = ModelModifications.DEFAULT_AMBIENT,
        cache: Cache? = null,
        hideMarkerFaces: Boolean = hideUntexturedMarkerFaces
    ): TexturedModel? {
        if (def.faceCount == 0) return null


        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        // Calculate per-vertex normals by accumulating face normals (smooth shading)
        val vertexNormals = accumulateVertexNormals(def)

        // Function to get normalized vertex normal in OpenGL space
        fun getVertexNormal(idx: Int): Vector3f {
            val vn = vertexNormals[idx]
            if (vn.magnitude == 0) return Vector3f(0f, 1f, 0f)

            // Average the accumulated normals
            var nx = vn.x / vn.magnitude
            var ny = vn.y / vn.magnitude
            var nz = vn.z / vn.magnitude

            // Normalize
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                val invLen = 1f / len
                nx *= invLen
                ny *= invLen
                nz *= invLen
            }

            // Convert to OpenGL space (Y and Z are flipped in RS)
            return Vector3f(nx, -ny, -nz)
        }

        // Build set of face indices hidden by billboard hasUid.
        // In the RS client (ModelGL.kt lines 354-378), if ANY billboard attached to a face
        // has hasUid=true, that face is removed from rendering and replaced by the billboard quad.
        val billboardHiddenFaces = mutableSetOf<Int>()
        if (cache != null && def.billboards != null) {
            for (bb in def.billboards) {
                if (bb.appliesOnFace < 0 || bb.appliesOnFace >= def.faceCount) continue
                val type = BillboardTypeLoader.get(cache, bb.billboardId)
                if (type.hasUid) {
                    billboardHiddenFaces.add(bb.appliesOnFace)
                }
            }
        }

        // Pre-scan: check if the model has any textured faces (for marker face filtering).
        // A "mixed" model has both textured and untextured faces. When hideUntexturedMarkerFaces
        // is enabled, untextured faces on mixed models are skipped (they are likely backing panels
        // or marker geometry that serves no visual purpose without text overlays).
        val modelHasTexturedFaces = if (hideMarkerFaces) {
            def.faceTextures?.any { it.toInt() != -1 } == true
        } else {
            false
        }

        // Group faces by texture ID (-1 for untextured)
        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()
        var hasAnyTextures = false

        for (i in 0 until def.faceCount) {
            // Skip faces hidden by billboard system (hasUid billboards replace the anchor face)
            if (i in billboardHiddenFaces) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Skip faces with render type 2 (hidden faces).
            // The RS client (ModelGL.kt) excludes these from rendering entirely.
            // They are used for internal purposes (billboard anchors, etc.) and
            // may contain arbitrary color values that should not be displayed.
            val renderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (renderType == 2) continue

            // Check alpha - RS uses inverted alpha where:
            // - alpha = 0 means OPAQUE (fully visible)
            // - alpha = 255 means FULLY TRANSPARENT (invisible)
            // If faceAlphas is null, faces are opaque (visible)
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue  // Skip fully transparent faces

            // Get texture ID for this face (-1 if none)
            // faceTextures values are stored as signed Short but represent unsigned texture IDs
            // Use signed comparison for -1 (no texture), but apply 0xffff mask for actual IDs
            val textureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
            if (textureId >= 0) hasAnyTextures = true

            // Hide untextured marker faces on mixed models (models with both textured and
            // untextured faces). These are typically label backing panels or ground markers.
            if (modelHasTexturedFaces && textureId == -1) continue

            // Skip faces whose material has skipTriangles set (matches RS client ModelGL behavior)
            if (textureId >= 0 && TextureLoader.shouldSkipMaterial(textureId)) continue

            // Get positions
            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            // Get color - use HSL color for all faces
            // For textured faces, this will be modulated by the texture
            // For untextured faces, this is the final color
            val rawHsl = def.faceColors[i].toInt() and 0xffff
            // Apply ambient lightness modulation matching the RS client's blendColorLightness().
            // The RS client always applies this: hslToRgb(blendColorLightness(hsl, ambient)).
            // This scales the 7-bit lightness component by ambient/128.
            val hsl = if (rawHsl == 65535) rawHsl else ColorUtil.blendColorLightness(rawHsl, ambient)
            // HSL value 0 is valid (black), only 65535 (0xFFFF) is a sentinel for "no color"
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f) // White for textured faces without explicit color
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // UV coordinates - compute from texture triangle projection
            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            // Normals: render type 0 is Gouraud (smoothed across the faces meeting at each
            // vertex), anything else is flat and takes this face's own normal on all three
            // vertices. See faceNormalGL for why the distinction matters at hard creases.
            val flatNormal = if (renderType == 0) null else faceNormalGL(def, aIdx, bIdx, cIdx)
            val norm0 = flatNormal ?: getVertexNormal(aIdx)
            val norm1 = flatNormal ?: getVertexNormal(bIdx)
            val norm2 = flatNormal ?: getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5])
            )
        }

        // Generate billboard quads for billboards that have a material to render.
        // Each billboard generates a quad (2 triangles) at the anchor face centroid,
        // textured with the billboard's materialId and colored with the anchor face HSL.
        // Matches the RS client's ModelGL.kt billboard effect creation (lines 431-472).
        if (cache != null && def.billboards != null) {
            generateBillboardQuads(def, cache, ambient, facesByTexture, ::getVertexNormal, ::isValidIndex)
            hasAnyTextures = hasAnyTextures || facesByTexture.keys.any { it >= 0 }
        }

        if (facesByTexture.isEmpty()) return null

        // Build mesh for untextured faces
        val colorBatch = facesByTexture[-1]?.let { faces ->
            buildMesh(faces)
        }

        // Build meshes for each texture batch
        val textureBatches = facesByTexture
            .filter { it.key != -1 }
            .map { (textureId, faces) ->
                TextureBatch(buildMesh(faces), textureId)
            }

        return TexturedModel(colorBatch, textureBatches, hasAnyTextures)
    }

    /**
     * Generate textured billboard quads and add them to the facesByTexture map.
     *
     * For each billboard attachment on the model:
     * 1. Look up the BillboardType config from cache
     * 2. Skip billboards with no material (materialId == -1) or stationary effects
     * 3. Compute the anchor face centroid in RS coordinates
     * 4. Generate a quad oriented along the anchor face normal, sized by size2d x size3d
     * 5. Add 2 FaceData triangles textured with the billboard's materialId
     *
     * This matches the RS client's ModelGL.kt billboard effect creation (lines 431-472),
     * adapted for static mesh generation. The client creates BillboardEffectGL objects
     * that are rendered as camera-facing quads at runtime; here we approximate with
     * face-normal-oriented quads since we generate static geometry.
     *
     * @param def The model definition
     * @param cache The game cache for loading billboard type configs
     * @param ambient Ambient lightness factor
     * @param facesByTexture The face batch map to add billboard faces to
     * @param getVertexNormal Function to get a vertex normal in OpenGL space
     * @param isValidIndex Function to validate vertex indices
     */
    private fun generateBillboardQuads(
        def: ModelDefinition,
        cache: Cache,
        ambient: Int,
        facesByTexture: MutableMap<Int, MutableList<FaceData>>,
        getVertexNormal: (Int) -> Vector3f,
        isValidIndex: (Int) -> Boolean
    ) {
        val billboards = def.billboards ?: return
        val scale = CoordinateUtil.DEFAULT_SCALE

        for (bb in billboards) {
            val type = BillboardTypeLoader.get(cache, bb.billboardId)

            // Skip billboards with no material to render
            if (type.materialId == -1) continue

            // Skip stationary billboards (bloom-only effect, no visible quad)
            if (type.stationary) continue

            // Validate anchor face index
            val faceIdx = bb.appliesOnFace
            if (faceIdx < 0 || faceIdx >= def.faceCount) continue

            val aIdx = def.faceA[faceIdx]
            val bIdx = def.faceB[faceIdx]
            val cIdx = def.faceC[faceIdx]
            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Compute face centroid in RS coordinates (matching client BillboardEffectGL
            // which stores faceA/faceB/faceC vertex indices for centroid calculation)
            val cx = (def.vertexX[aIdx] + def.vertexX[bIdx] + def.vertexX[cIdx]) / 3.0f
            val cy = (def.vertexY[aIdx] + def.vertexY[bIdx] + def.vertexY[cIdx]) / 3.0f
            val cz = (def.vertexZ[aIdx] + def.vertexZ[bIdx] + def.vertexZ[cIdx]) / 3.0f

            // Convert centroid to OpenGL space
            val center = CoordinateUtil.rsToOpenGL(cx, cy, cz)

            // Compute face normal in OpenGL space for quad orientation
            val edge1x = (def.vertexX[bIdx] - def.vertexX[aIdx]).toFloat()
            val edge1y = (def.vertexY[bIdx] - def.vertexY[aIdx]).toFloat()
            val edge1z = (def.vertexZ[bIdx] - def.vertexZ[aIdx]).toFloat()
            val edge2x = (def.vertexX[cIdx] - def.vertexX[aIdx]).toFloat()
            val edge2y = (def.vertexY[cIdx] - def.vertexY[aIdx]).toFloat()
            val edge2z = (def.vertexZ[cIdx] - def.vertexZ[aIdx]).toFloat()

            // Cross product in RS space
            var nx = edge1y * edge2z - edge1z * edge2y
            var ny = edge1z * edge2x - edge1x * edge2z
            var nz = edge1x * edge2y - edge1y * edge2x

            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 0.001f) {
                // Degenerate face, use default up normal in RS space
                nx = 0f; ny = -1f; nz = 0f; len = 1f
            }
            val invLen = 1f / len
            nx *= invLen; ny *= invLen; nz *= invLen

            // Convert normal to OpenGL space: flip Y and Z
            val normalGL = Vector3f(nx, -ny, -nz)

            // Build tangent frame from the face normal.
            // Choose an arbitrary "up" vector that isn't parallel to the normal.
            val up = if (abs(normalGL.y) < 0.99f) {
                Vector3f(0f, 1f, 0f)
            } else {
                Vector3f(1f, 0f, 0f)
            }
            // tangent = normalize(up x normal)
            val tangent = Vector3f()
            up.cross(normalGL, tangent)
            tangent.normalize()
            // bitangent = normal x tangent
            val bitangent = Vector3f()
            normalGL.cross(tangent, bitangent)
            bitangent.normalize()

            // Billboard size in OpenGL units (RS model units * scale)
            // size2d = horizontal width, size3d = vertical height
            val halfW = (type.size2d / 2.0f) * scale
            val halfH = (type.size3d / 2.0f) * scale

            // Generate quad corners: center +/- tangent*halfW +/- bitangent*halfH
            // Apply billboard distance offset along the face normal
            val distOffset = bb.distance.toFloat() * scale
            val offsetCenter = Vector3f(
                center.x + normalGL.x * distOffset,
                center.y + normalGL.y * distOffset,
                center.z + normalGL.z * distOffset
            )

            val v0 = Vector3f(
                offsetCenter.x - tangent.x * halfW - bitangent.x * halfH,
                offsetCenter.y - tangent.y * halfW - bitangent.y * halfH,
                offsetCenter.z - tangent.z * halfW - bitangent.z * halfH
            )
            val v1 = Vector3f(
                offsetCenter.x + tangent.x * halfW - bitangent.x * halfH,
                offsetCenter.y + tangent.y * halfW - bitangent.y * halfH,
                offsetCenter.z + tangent.z * halfW - bitangent.z * halfH
            )
            val v2 = Vector3f(
                offsetCenter.x + tangent.x * halfW + bitangent.x * halfH,
                offsetCenter.y + tangent.y * halfW + bitangent.y * halfH,
                offsetCenter.z + tangent.z * halfW + bitangent.z * halfH
            )
            val v3 = Vector3f(
                offsetCenter.x - tangent.x * halfW + bitangent.x * halfH,
                offsetCenter.y - tangent.y * halfW + bitangent.y * halfH,
                offsetCenter.z - tangent.z * halfW + bitangent.z * halfH
            )

            // Get color from anchor face HSL (matching client ModelGL.kt line 455:
            // ColorUtil.hslToRgb[mesh.facesColor[billboard.appliesOnFace] & 0xffff])
            val rawHsl = def.faceColors[faceIdx].toInt() and 0xffff
            val hsl = if (rawHsl == 65535) rawHsl else ColorUtil.blendColorLightness(rawHsl, ambient)
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f)
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // UV coordinates for the quad (full texture coverage)
            // V is flipped for OpenGL (1-V)
            val materialId = type.materialId

            // Add two triangles forming the quad (v0-v1-v2 and v0-v2-v3)
            // Front face: CCW winding when viewed from the normal direction
            val faces = facesByTexture.getOrPut(materialId) { mutableListOf() }
            faces.add(
                FaceData(
                    v0, v1, v2, color,
                    normalGL, normalGL, normalGL,
                    0f, 1f,  1f, 1f,  1f, 0f
                )
            )
            faces.add(
                FaceData(
                    v0, v2, v3, color,
                    normalGL, normalGL, normalGL,
                    0f, 1f,  1f, 0f,  0f, 0f
                )
            )

            // Back face: reversed winding for two-sided visibility
            val backNormal = Vector3f(-normalGL.x, -normalGL.y, -normalGL.z)
            faces.add(
                FaceData(
                    v2, v1, v0, color,
                    backNormal, backNormal, backNormal,
                    1f, 0f,  1f, 1f,  0f, 1f
                )
            )
            faces.add(
                FaceData(
                    v3, v2, v0, color,
                    backNormal, backNormal, backNormal,
                    0f, 0f,  1f, 0f,  0f, 1f
                )
            )
        }
    }

    private fun buildMesh(faces: List<FaceData>): Mesh {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (face in faces) {
            positions.addAll(listOf(face.v0.x, face.v0.y, face.v0.z))
            positions.addAll(listOf(face.v1.x, face.v1.y, face.v1.z))
            positions.addAll(listOf(face.v2.x, face.v2.y, face.v2.z))

            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))

            texCoords.addAll(listOf(face.u0, face.v0Uv))
            texCoords.addAll(listOf(face.u1, face.v1Uv))
            texCoords.addAll(listOf(face.u2, face.v2Uv))

            // Use per-vertex normals for smooth shading
            normals.addAll(listOf(face.n0.x, face.n0.y, face.n0.z))
            normals.addAll(listOf(face.n1.x, face.n1.y, face.n1.z))
            normals.addAll(listOf(face.n2.x, face.n2.y, face.n2.z))

            indices.add(vertexIndex)
            indices.add(vertexIndex + 1)
            indices.add(vertexIndex + 2)
            vertexIndex += 3
        }

        return Mesh(
            positions.toFloatArray(),
            colors.toFloatArray(),
            texCoords.toFloatArray(),
            normals.toFloatArray(),
            indices.toIntArray()
        )
    }

    /**
     * Create mesh without texture support (legacy method).
     */
    fun createMesh(def: ModelDefinition): Mesh? {
        return createTexturedModel(def)?.mesh
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     * Similar to createTexturedModel but returns raw arrays instead of GPU meshes.
     */
    fun createMeshDataList(cache: Cache, modelId: Int, debug: Boolean = false): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, ModelModifications.NONE, if (debug) modelId else -1, cache)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering with modifications.
     * Similar to createTexturedModel but returns raw arrays instead of GPU meshes.
     *
     * @param cache The game cache
     * @param modelId The model ID to load
     * @param modifications Retexture/recolor modifications to apply
     * @param debug Enable debug output
     */
    fun createMeshDataListWithModifications(
        cache: Cache,
        modelId: Int,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, modifications, if (debug) modelId else -1, cache)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     * Overload without modifications for backwards compatibility.
     */
    fun createMeshDataListFromDef(def: ModelDefinition, debugModelId: Int = -1): List<MeshData>? {
        return createMeshDataListFromDef(def, ModelModifications.NONE, debugModelId, null)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     *
     * @param def The model definition
     * @param modifications Retexture/recolor modifications to apply
     * @param debugModelId Unused, kept for API compatibility
     */
    @Suppress("UNUSED_PARAMETER")
    fun createMeshDataListFromDef(def: ModelDefinition, modifications: ModelModifications, debugModelId: Int = -1, cache: Cache? = null): List<MeshData>? {
        val merge = modifications.normalMerge
            ?: return buildBaseMesh(def, modifications, cache)?.meshes

        // A cross-object normal merge changes nothing but the normals, so decoding the model again
        // per neighbourhood would be pure waste — and there are ~2000 distinct neighbourhoods in a
        // town. Decode once without the merge, then hand out copies whose normal arrays are
        // rewritten and whose positions, colours, UVs and indices are shared with the base.
        // This is the same shape as the client's own merge, which copies only the normal arrays.
        val baseKey = "${def.id}|${modifications.copy(normalMerge = null).cacheKeyComponent()}|$hideUntexturedMarkerFaces"
        // putIfAbsent rather than computeIfAbsent: every region loads on its own thread, and
        // computeIfAbsent would hold a bin lock for the whole decode. A duplicate decode wastes a
        // little work; a held lock stalls another region.
        val existing = baseMeshCache[baseKey]
        val result = existing ?: run {
            val built = buildBaseMesh(def, modifications, cache)
            val computed = if (built != null) CacheResult.Found(built) else CacheResult.NotFound
            baseMeshCache.putIfAbsent(baseKey, computed) ?: computed
        }
        val base = (result as? CacheResult.Found)?.value ?: return null
        return applyMergeToBase(def, base, merge)
    }

    /**
     * A decoded mesh together with the map from each emitted vertex slot back to the model vertex
     * whose smoothed normal it carries. Cached so that normal-merge variants of the same model can
     * be produced by rewriting normals rather than by decoding again.
     */
    private class BaseMesh(
        val meshes: List<MeshData>,
        val slotVertex: Array<IntArray>
    )

    private val baseMeshCache = ConcurrentHashMap<String, CacheResult<BaseMesh>>()

    /**
     * Rebuild [base]'s normal arrays with [merge] folded in, sharing every other buffer.
     *
     * Slots that carry a flat face normal or belong to a billboard quad are left untouched: the
     * client does not smooth those either, and their normal has no source vertex to merge at.
     */
    private fun applyMergeToBase(def: ModelDefinition, base: BaseMesh, merge: NormalMerge): List<MeshData> {
        // Vertex -> slot in the merge's arrays. A dense array rather than a map: model vertex
        // counts are in the tens to low hundreds, and this is walked once per emitted vertex slot
        // of every mesh variant in the scene.
        val slotOfVertex = IntArray(def.vertexCount) { -1 }
        var touched = 0
        for (i in merge.vertexIndex.indices) {
            val idx = merge.vertexIndex[i]
            if (idx < 0 || idx >= slotOfVertex.size) continue
            slotOfVertex[idx] = i
            touched++
        }
        if (touched == 0) return base.meshes

        val out = ArrayList<MeshData>(base.meshes.size)
        for (batchIdx in base.meshes.indices) {
            val mesh = base.meshes[batchIdx]
            val normals = mesh.normals
            val slots = base.slotVertex.getOrNull(batchIdx)
            if (normals == null || slots == null) {
                out.add(mesh)
                continue
            }

            var patched: FloatArray? = null
            for (slot in slots.indices) {
                val sourceVertex = slots[slot]
                if (sourceVertex < 0 || sourceVertex >= slotOfVertex.size) continue
                val mergeSlot = slotOfVertex[sourceVertex]
                if (mergeSlot < 0) continue
                val target = patched ?: normals.copyOf().also { patched = it }
                val offset = slot * 3
                if (offset + 2 >= target.size) break
                target[offset] = merge.normalX[mergeSlot]
                target[offset + 1] = merge.normalY[mergeSlot]
                target[offset + 2] = merge.normalZ[mergeSlot]
            }

            out.add(
                if (patched == null) {
                    mesh
                } else {
                    MeshData(
                        positions = mesh.positions,
                        colors = mesh.colors,
                        textureCoords = mesh.textureCoords,
                        normals = patched,
                        indices = mesh.indices,
                        textureId = mesh.textureId
                    )
                }
            )
        }
        return out
    }

    /**
     * Averaged, normalised vertex normal in OpenGL space, written into [out].
     *
     * This is the form a mesh's normal array stores, including the Y/Z sign flip out of RS space.
     * It is exposed so [org.drynor.tools.cache.map.NormalMerger] can hand back *finished* normals
     * that agree bit for bit with the ones [buildBaseMesh] derives for unmerged vertices.
     */
    fun smoothedNormal(sumX: Float, sumY: Float, sumZ: Float, magnitude: Int, out: Vector3f): Vector3f {
        if (magnitude == 0) return out.set(0f, 1f, 0f)

        var nx = sumX / magnitude
        var ny = sumY / magnitude
        var nz = sumZ / magnitude

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0) {
            val invLen = 1f / len
            nx *= invLen
            ny *= invLen
            nz *= invLen
        }

        return out.set(nx, -ny, -nz)
    }

    private fun buildBaseMesh(def: ModelDefinition, modifications: ModelModifications, cache: Cache?): BaseMesh? {
        if (def.faceCount == 0) return null

        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        // Calculate per-vertex normals by accumulating face normals (smooth shading)
        val vertexNormals = accumulateVertexNormals(def)

        fun getVertexNormal(idx: Int): Vector3f {
            val vn = vertexNormals[idx]
            return smoothedNormal(vn.x, vn.y, vn.z, vn.magnitude, Vector3f())
        }

        // Build set of face indices hidden by billboard hasUid.
        // In the RS client (ModelGL.kt lines 354-378), if ANY billboard attached to a face
        // has hasUid=true, that face is removed from rendering and replaced by the billboard quad.
        val billboardHiddenFaces = mutableSetOf<Int>()
        if (cache != null && def.billboards != null) {
            for (bb in def.billboards) {
                if (bb.appliesOnFace < 0 || bb.appliesOnFace >= def.faceCount) continue
                val type = BillboardTypeLoader.get(cache, bb.billboardId)
                if (type.hasUid) {
                    billboardHiddenFaces.add(bb.appliesOnFace)
                }
            }
        }

        // Pre-scan: check if the model has any textured faces (for marker face filtering).
        // Same logic as createTexturedModel - hide untextured faces on mixed models.
        val modelHasTexturedFaces = if (hideUntexturedMarkerFaces) {
            def.faceTextures?.any { it.toInt() != -1 } == true
        } else {
            false
        }

        // Group faces by texture ID (-1 for untextured)
        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()

        for (i in 0 until def.faceCount) {
            // Skip faces hidden by billboard system (hasUid billboards replace the anchor face)
            if (i in billboardHiddenFaces) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Skip faces with render type 2 (hidden faces).
            // The RS client (ModelGL.kt) excludes these from rendering entirely.
            // They are used for internal purposes (billboard anchors, etc.) and
            // may contain arbitrary color values that should not be displayed.
            val renderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (renderType == 2) continue

            // Check alpha - RS uses inverted alpha where:
            // - alpha = 0 means OPAQUE (fully visible)
            // - alpha = 255 means FULLY TRANSPARENT (invisible)
            // If faceAlphas is null, faces are opaque (visible)
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue  // Skip fully transparent faces

            // Get raw texture ID and apply retexturing if modifications provided
            // Note: faceTextures values are stored as signed Short but represent unsigned texture IDs
            // Use signed comparison for -1 (no texture), but apply 0xffff mask for actual IDs
            val rawTextureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val rawTextureId = if (rawTextureIdSigned == -1) -1 else (rawTextureIdSigned and 0xffff)
            val textureId = if (rawTextureId >= 0) {
                modifications.applyRetexture(rawTextureId)
            } else {
                rawTextureId
            }

            // Hide untextured marker faces on mixed models (models with both textured and
            // untextured faces). These are typically label backing panels or ground markers.
            if (modelHasTexturedFaces && textureId == -1) continue

            // Skip faces whose material has skipTriangles set (matches RS client ModelGL behavior)
            if (textureId >= 0 && TextureLoader.shouldSkipMaterial(textureId)) continue

            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            // Get raw HSL color and apply recoloring if modifications provided
            val rawHsl = def.faceColors[i].toInt() and 0xffff
            val recoloredHsl = modifications.applyRecolor(rawHsl)
            // Apply ambient lightness modulation matching the RS client's blendColorLightness().
            // The RS client always applies this: hslToRgb(blendColorLightness(hsl, ambient)).
            // This scales the 7-bit lightness component by ambient/128.
            val hsl = if (recoloredHsl == 65535) recoloredHsl else ColorUtil.blendColorLightness(recoloredHsl, modifications.ambient)
            // HSL value 0 is valid (black), only 65535 (0xFFFF) is a sentinel for "no color"
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f) // White for textured faces without explicit color
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            // Render type 0 is Gouraud, anything else is flat-shaded and takes this face's
            // own normal on all three vertices (see faceNormalGL).
            val flatNormal = if (renderType == 0) null else faceNormalGL(def, aIdx, bIdx, cIdx)
            val norm0 = flatNormal ?: getVertexNormal(aIdx)
            val norm1 = flatNormal ?: getVertexNormal(bIdx)
            val norm2 = flatNormal ?: getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5],
                         // Flat faces take a face normal, so their slots have no vertex to track.
                         if (flatNormal == null) aIdx else -1,
                         if (flatNormal == null) bIdx else -1,
                         if (flatNormal == null) cIdx else -1)
            )
        }

        // Generate billboard quads for billboards that have a material to render.
        if (cache != null && def.billboards != null) {
            generateBillboardQuads(def, cache, modifications.ambient, facesByTexture, ::getVertexNormal, ::isValidIndex)
        }

        if (facesByTexture.isEmpty()) return null

        val meshes = ArrayList<MeshData>(facesByTexture.size)
        val slotVertex = ArrayList<IntArray>(facesByTexture.size)

        // Build MeshData for each texture batch (including -1 for untextured)
        for ((textureId, faces) in facesByTexture) {
            meshes.add(buildMeshData(faces, textureId))
            slotVertex.add(buildSlotVertexMap(faces))
        }

        return BaseMesh(meshes, slotVertex.toTypedArray())
    }

    /** Source model vertex for every emitted vertex slot of a batch; see [FaceData.sv0]. */
    private fun buildSlotVertexMap(faces: List<FaceData>): IntArray {
        val map = IntArray(faces.size * 3)
        var slot = 0
        for (face in faces) {
            map[slot] = face.sv0
            map[slot + 1] = face.sv1
            map[slot + 2] = face.sv2
            slot += 3
        }
        return map
    }

    private fun buildMeshData(faces: List<FaceData>, textureId: Int): MeshData {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (face in faces) {
            positions.addAll(listOf(face.v0.x, face.v0.y, face.v0.z))
            positions.addAll(listOf(face.v1.x, face.v1.y, face.v1.z))
            positions.addAll(listOf(face.v2.x, face.v2.y, face.v2.z))

            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))

            texCoords.addAll(listOf(face.u0, face.v0Uv))
            texCoords.addAll(listOf(face.u1, face.v1Uv))
            texCoords.addAll(listOf(face.u2, face.v2Uv))

            normals.addAll(listOf(face.n0.x, face.n0.y, face.n0.z))
            normals.addAll(listOf(face.n1.x, face.n1.y, face.n1.z))
            normals.addAll(listOf(face.n2.x, face.n2.y, face.n2.z))

            indices.add(vertexIndex)
            indices.add(vertexIndex + 1)
            indices.add(vertexIndex + 2)
            vertexIndex += 3
        }

        return MeshData(
            positions = positions.toFloatArray(),
            colors = if (colors.isNotEmpty()) colors.toFloatArray() else null,
            textureCoords = if (texCoords.isNotEmpty()) texCoords.toFloatArray() else null,
            normals = if (normals.isNotEmpty()) normals.toFloatArray() else null,
            indices = indices.toIntArray(),
            textureId = textureId
        )
    }

    fun clearCache() {
        definitionCache.clear()
        textureTransformCache.clear()
        combinedDefinitionCache.clear()
        vertexNormalFieldCache.clear()
        baseMeshCache.clear()
        syntheticIdMapping.clear()
        nextSyntheticId.set(-1)
        BillboardTypeLoader.clearCache()
    }

    fun getCachedDefinition(modelId: Int): ModelDefinition? {
        return (definitionCache[modelId] as? CacheResult.Found)?.value
    }

    // Cache for combined model definitions (key is sorted list of model IDs)
    private val combinedDefinitionCache = ConcurrentHashMap<List<Int>, CacheResult<ModelDefinition>>()

    /**
     * Load multiple model definitions and combine them into a single definition.
     * This matches how the RS client combines multiple models for multi-model objects
     * (e.g., fountains with water + stone base, lanterns with frame + window).
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @return Combined model definition, or null if no models could be loaded
     */
    fun loadCombinedDefinition(cache: Cache, modelIds: List<Int>): ModelDefinition? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return loadDefinition(cache, modelIds[0])

        // Use sorted list as cache key for consistent lookup
        val cacheKey = modelIds.sorted()
        val result = combinedDefinitionCache.computeIfAbsent(cacheKey) {
            // Load all individual models
            val models = modelIds.mapNotNull { loadDefinition(cache, it) }
            if (models.isEmpty()) return@computeIfAbsent CacheResult.NotFound

            // Combine using the first model's ID as the combined ID
            // (with negative sign to distinguish from regular model IDs)
            val combinedId = -(modelIds.hashCode() and 0x7FFFFFFF)
            val combined = ModelDefinition.combine(models, combinedId)
            if (combined != null) CacheResult.Found(combined) else CacheResult.NotFound
        }
        return when (result) {
            is CacheResult.Found -> result.value
            is CacheResult.NotFound -> null
        }
    }

    /**
     * Create MeshData list from multiple model IDs (combined).
     * This is the primary method for loading multi-model objects.
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @param debug Enable debug output (currently unused, kept for API compatibility)
     * @return List of MeshData for instanced rendering, or null if loading fails
     */
    fun createCombinedMeshDataList(cache: Cache, modelIds: List<Int>, debug: Boolean = false): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataList(cache, modelIds[0], debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, ModelModifications.NONE, -1, cache)
    }

    /**
     * Create MeshData list from multiple model IDs (combined) with modifications.
     * This is the primary method for loading multi-model objects with retexturing/recoloring.
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @param modifications Retexture/recolor modifications to apply
     * @param debug Enable debug output (currently unused, kept for API compatibility)
     * @return List of MeshData for instanced rendering, or null if loading fails
     */
    fun createCombinedMeshDataListWithModifications(
        cache: Cache,
        modelIds: List<Int>,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataListWithModifications(cache, modelIds[0], modifications, debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, modifications, -1, cache)
    }

    /**
     * Mapping from composite string keys to unique negative Int IDs.
     * Used by [getCombinedModelId] and [getCombinedModelIdWithModifications]
     * to guarantee collision-free IDs for multi-model and modified-model combinations.
     *
     * Single models without modifications use their raw model ID (positive) directly.
     * All other combinations get a unique negative ID from [nextSyntheticId].
     */
    private val syntheticIdMapping = ConcurrentHashMap<String, Int>()
    private val nextSyntheticId = AtomicInteger(-1)

    /**
     * Generate a unique combined model ID from a list of model IDs.
     * Used as a key for registering combined models with InstanceManager.
     *
     * Single-model lists return the model ID directly (positive).
     * Multi-model lists get a unique negative ID (collision-free).
     */
    fun getCombinedModelId(modelIds: List<Int>): Int {
        if (modelIds.size == 1) return modelIds[0]
        val key = "m_${modelIds.sorted()}"
        return syntheticIdMapping.computeIfAbsent(key) { nextSyntheticId.getAndDecrement() }
    }

    /**
     * Generate a unique combined model ID that includes modifications.
     * Used as a key for registering combined models with retexturing/recoloring.
     *
     * Single models without modifications return the model ID directly.
     * All other combinations get a unique negative ID (collision-free).
     *
     * @param modelIds List of model IDs
     * @param modifications Retexture/recolor modifications
     * @return A unique ID for the model+modifications combination
     */
    fun getCombinedModelIdWithModifications(modelIds: List<Int>, modifications: ModelModifications): Int {
        if (!modifications.hasModifications()) return getCombinedModelId(modelIds)
        val key = "x_${modelIds.sorted()}_${modifications.cacheKeyComponent()}"
        return syntheticIdMapping.computeIfAbsent(key) { nextSyntheticId.getAndDecrement() }
    }

}
