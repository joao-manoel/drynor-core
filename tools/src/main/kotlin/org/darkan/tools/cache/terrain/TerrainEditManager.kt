package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Matrix4f
import world.gregs.voidps.cache.Cache

/**
 * Orchestrates dirty tracking, recomputation, and chunk rebuilding for real-time terrain editing.
 *
 * Uses a dual-path rendering strategy:
 * - **Merged meshes** (default): Region-level static meshes with ~16 draw calls total.
 *   Built after init and after edits are committed. Used for the 99% viewing use case.
 * - **Chunked terrain**: Per-chunk dynamic meshes with ~1,024 draw calls.
 *   Used during active editing for responsive per-chunk rebuilds.
 *
 * Ties together [MutableRegionData] (dirty tracking / tile mutation) and [ChunkedTerrain]
 * (chunk mesh management). Editor tools modify [mutableData] directly via its setHeight,
 * setUnderlayId, setOverlayId, and setFlags methods. Each frame, [processEdits] checks
 * for pending changes, recomputes region-wide blending and normals as needed, then
 * rebuilds only the dirty chunks.
 *
 * Typical usage:
 * ```
 * val editManager = TerrainEditManager(regionX, regionY, plane, cache)
 * editManager.init()
 *
 * // In edit tool:
 * editManager.mutableData.setHeight(plane, x, y, newHeight)
 *
 * // In frame loop (before render):
 * val result = editManager.processEdits()
 *
 * // In render pass:
 * editManager.renderColor(shader)
 * editManager.renderTextured(shader, ::bindTexture)
 * editManager.renderAlpha(shader, ::bindTexture)
 * ```
 */
class TerrainEditManager(
    val regionX: Int,
    val regionY: Int,
    var plane: Int,
    private val cache: Cache
) {
    /** Mutable region data with dirty tracking. Editor tools modify this directly. */
    lateinit var mutableData: MutableRegionData
        private set

    /**
     * Immutable view of [mutableData], refreshed whenever an edit is committed.
     *
     * Tile picking marches a ray through a region's tile grid several times per frame and
     * cannot afford [MutableRegionData.toTileArray], which allocates 16384 tiles per call.
     * It also cannot read the cache copy, or a region whose heights have been sculpted would
     * be picked against the surface it had before the first stroke - the cursor would drift
     * off the terrain the longer the brush was used. The commit path already builds this
     * array for its own recomputation, so publishing it costs nothing.
     */
    var tileSnapshot: Array<Array<Array<TerrainLoader.TileData>>> = emptyArray()
        private set

    private lateinit var chunkedTerrain: ChunkedTerrain

    /** Neighbor data for seamless edge blending. Built once at init from cached regions. */
    private var neighbors: TerrainLoader.NeighborData? = null

    // Pre-computed region-wide data (recomputed when dirty)
    private lateinit var blendedColors: Array<IntArray>
    private lateinit var normalsX: Array<FloatArray>
    private lateinit var normalsY: Array<FloatArray>
    private lateinit var normalsZ: Array<FloatArray>

    // Merged meshes for efficient viewing (region-level, ~16 draw calls)
    private var mergedColorMesh: Mesh? = null
    private var mergedTexturedBatches: List<TerrainLoader.TerrainBatch> = emptyList()
    private var mergedAlphaBatches: List<TerrainLoader.AlphaTerrainBatch> = emptyList()

    /** When true, render from merged meshes. When false (active editing), render from chunks. */
    private var useMergedMeshes: Boolean = false

    /**
     * Initialize from cache data. Loads the region, ensures underlay/overlay definitions
     * are available, creates a mutable copy, computes blending and normals, and builds
     * all 64 chunks. Then builds merged meshes for efficient viewing.
     */
    fun init() {
        // Ensure underlay/overlay definitions are loaded for blending computation
        TerrainLoader.loadDefinitions(cache)

        val regionData = TerrainLoader.loadRegion(cache, regionX, regionY)
            ?: error("Failed to load region ($regionX, $regionY)")

        // Build neighbor data for seamless edge blending (cheap - uses cached RegionData)
        neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)

        mutableData = MutableRegionData(regionData)
        tileSnapshot = mutableData.toTileArray()
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)

        // Compute initial blending and normals
        recomputeBlendingAndNormals()

        // Build all 64 chunks (needed for editing)
        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ, neighbors)

        // Build merged meshes for efficient viewing
        buildMergedMeshes()
    }

    /**
     * Change the active plane. Cleans up existing chunk meshes, recomputes
     * blending and normals for the new plane, and rebuilds all chunks.
     */
    fun changePlane(newPlane: Int) {
        plane = newPlane
        chunkedTerrain.cleanup()
        chunkedTerrain = ChunkedTerrain(regionX, regionY, plane)
        recomputeBlendingAndNormals()
        chunkedTerrain.buildAll(mutableData.toRegionData(), blendedColors, normalsX, normalsY, normalsZ, neighbors)
        mutableData.clearDirty()

        // Rebuild merged meshes for the new plane
        buildMergedMeshes()
    }

    /**
     * Call once per frame before rendering. Processes any pending edits:
     * 1. Recomputes blended underlay colors if any underlay was changed
     * 2. Recomputes vertex normals if any height was changed
     * 3. Rebuilds only the dirty chunks
     * 4. When edits are cleared, rebuilds merged meshes
     *
     * @return [EditResult] describing which chunks were rebuilt, for downstream
     *         reactivity (e.g., repositioning objects on changed terrain).
     */
    fun processEdits(): EditResult {
        if (!mutableData.hasDirty()) return EditResult.NONE

        // Switch to chunked rendering during active editing
        useMergedMeshes = false

        val regionSnapshot = mutableData.toRegionData()
        // Published before anything else reads it: the picker resolves the cursor against
        // this every frame, and an edited region must be picked against its edited heights.
        tileSnapshot = regionSnapshot.tiles

        // 1. Recompute blended underlay colors if underlay changed
        if (mutableData.blendingDirty) {
            blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane, neighbors)
        }

        // 2. Recompute vertex normals if heights changed
        if (mutableData.heightsDirty) {
            val (nx, ny, nz) = TerrainLoader.computeVertexNormals(regionSnapshot.tiles, plane, neighbors)
            normalsX = nx
            normalsY = ny
            normalsZ = nz
        }

        // 3. Rebuild only dirty chunks
        val dirtyChunks = mutableData.getDirtyChunks()
        chunkedTerrain.rebuildChunks(dirtyChunks, regionSnapshot, blendedColors, normalsX, normalsY, normalsZ, neighbors)

        // 4. Collect result and reset dirty state
        val result = EditResult(dirtyChunks, heightsChanged = mutableData.heightsDirty)
        mutableData.clearDirty()

        // 5. Rebuild merged meshes now that editing is done
        buildMergedMeshes()

        return result
    }

    /**
     * Build merged region-level meshes from the current tile state.
     * Produces one color Mesh, one textured Mesh per texture ID, and one alpha
     * Mesh per texture ID. These use GL_STATIC_DRAW for optimal GPU throughput.
     *
     * This replaces ~1,024 chunk draw calls with ~16 merged draw calls.
     */
    private fun buildMergedMeshes() {
        // Clean up previous merged meshes
        cleanupMergedMeshes()

        val regionSnapshot = mutableData.toRegionData()

        // Collect all vertices for the entire region, grouped by texture ID
        val textureGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val vertices = TerrainLoader.generateTileVertices(
                    regionSnapshot, plane, x, y,
                    blendedColors, normalsX, normalsY, normalsZ,
                    neighbors
                )
                for (vertex in vertices) {
                    textureGroups.getOrPut(vertex.textureId) { mutableListOf() }.add(vertex)
                }
            }
        }

        // Separate into opaque and alpha groups by triangle
        val opaqueGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        for ((texId, verts) in textureGroups) {
            var i = 0
            while (i + 2 < verts.size) {
                val v0 = verts[i]
                val v1 = verts[i + 1]
                val v2 = verts[i + 2]

                val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

                if (needsAlpha && v0.textureId >= 0) {
                    val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                } else {
                    val group = opaqueGroups.getOrPut(texId) { mutableListOf() }
                    group.add(v0); group.add(v1); group.add(v2)
                }
                i += 3
            }
            while (i < verts.size) {
                opaqueGroups.getOrPut(texId) { mutableListOf() }.add(verts[i])
                i++
            }
        }

        // Build color mesh (textureId == -1)
        mergedColorMesh = opaqueGroups[-1]?.let { vertices ->
            if (vertices.isEmpty()) return@let null
            buildColorMesh(vertices)
        }

        // Build opaque textured batches
        val texturedList = mutableListOf<TerrainLoader.TerrainBatch>()
        for ((texId, vertices) in opaqueGroups) {
            if (texId == -1 || vertices.isEmpty()) continue
            texturedList.add(TerrainLoader.TerrainBatch(buildTexturedMesh(vertices), texId))
        }
        mergedTexturedBatches = texturedList

        // Build alpha batches
        val alphaList = mutableListOf<TerrainLoader.AlphaTerrainBatch>()
        for ((texId, vertices) in alphaGroups) {
            if (vertices.isEmpty()) continue
            alphaList.add(TerrainLoader.AlphaTerrainBatch(buildAlphaMesh(vertices), texId))
        }
        mergedAlphaBatches = alphaList

        useMergedMeshes = true
    }

    private fun buildColorMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            indices[i] = i
        }

        return Mesh(positions, colors, null, normals, indices, colorComponents = 3)
    }

    private fun buildTexturedMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texCoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            texCoords[i * 2]     = v.u
            texCoords[i * 2 + 1] = v.v
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            indices[i] = i
        }

        return Mesh(positions, colors, texCoords, normals, indices, colorComponents = 3)
    }

    private fun buildAlphaMesh(vertices: List<TerrainLoader.ShapedVertex>): Mesh {
        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texCoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val alphas = FloatArray(count)
        val indices = IntArray(count)

        for (i in vertices.indices) {
            val v = vertices[i]
            positions[i * 3]     = v.x
            positions[i * 3 + 1] = v.y
            positions[i * 3 + 2] = v.z
            colors[i * 3]     = v.r
            colors[i * 3 + 1] = v.g
            colors[i * 3 + 2] = v.b
            texCoords[i * 2]     = v.u
            texCoords[i * 2 + 1] = v.v
            normals[i * 3]     = v.nx
            normals[i * 3 + 1] = v.ny
            normals[i * 3 + 2] = v.nz
            alphas[i] = v.alpha
            indices[i] = i
        }

        return Mesh.createWithAlpha(positions, colors, texCoords, normals, alphas, indices)
    }

    /**
     * Recompute both blended underlay colors and vertex normals from current tile state.
     * Used during init and plane changes when everything must be recalculated.
     */
    private fun recomputeBlendingAndNormals() {
        val regionSnapshot = mutableData.toRegionData()
        blendedColors = TerrainLoader.computeBlendedUnderlayColors(regionSnapshot, plane, neighbors)
        val (nx, ny, nz) = TerrainLoader.computeVertexNormals(mutableData.toTileArray(), plane, neighbors)
        normalsX = nx
        normalsY = ny
        normalsZ = nz
    }

    /**
     * Render all non-empty color terrain (untextured).
     *
     * @return what this pass submitted, so the caller can charge it to the frame's
     *         terrain draw-call and triangle counters. The centre region is drawn from
     *         here rather than from the merged atlas meshes, so without this the frame
     *         statistics under-report the whole region -- which is what made
     *         `-Ddarkan.terrainAtlas=false` look like it was skipping passes.
     */
    fun renderColor(shader: ShaderProgram, modelMatrix: Matrix4f): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderColor(shader)
        val mesh = mergedColorMesh ?: return TerrainRenderStats.NONE
        mesh.render()
        return TerrainRenderStats(1, mesh.vertexCount / 3)
    }

    /**
     * Render all non-empty textured terrain (opaque).
     *
     * @return what this pass submitted. See [renderColor].
     */
    fun renderTextured(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderTextured(shader, bindTexture)
        var triangles = 0
        for (batch in mergedTexturedBatches) {
            bindTexture(batch.textureId)
            batch.mesh.render()
            triangles += batch.mesh.vertexCount / 3
        }
        return TerrainRenderStats(mergedTexturedBatches.size, triangles)
    }

    /**
     * Render all non-empty alpha terrain (semi-transparent).
     *
     * @return what this pass submitted. See [renderColor].
     */
    fun renderAlpha(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit): TerrainRenderStats {
        shader.setUniform("model", modelMatrix)
        if (!useMergedMeshes) return chunkedTerrain.renderAlpha(shader, bindTexture)
        var triangles = 0
        for (batch in mergedAlphaBatches) {
            bindTexture(batch.textureId)
            batch.mesh.render()
            triangles += batch.mesh.vertexCount / 3
        }
        return TerrainRenderStats(mergedAlphaBatches.size, triangles)
    }

    /** Release all GPU resources held by chunk meshes and merged meshes. */
    fun cleanup() {
        chunkedTerrain.cleanup()
        cleanupMergedMeshes()
    }

    private fun cleanupMergedMeshes() {
        mergedColorMesh?.cleanup()
        mergedColorMesh = null
        for (batch in mergedTexturedBatches) batch.mesh.cleanup()
        mergedTexturedBatches = emptyList()
        for (batch in mergedAlphaBatches) batch.mesh.cleanup()
        mergedAlphaBatches = emptyList()
        useMergedMeshes = false
    }
}

/**
 * What one [TerrainEditManager] or [ChunkedTerrain] render pass actually submitted.
 *
 * These passes decide internally whether to draw from the merged region meshes or from
 * the 64 per-chunk meshes, and how many texture batches each of those comes to, so the
 * caller cannot count the draws from the outside. Returning the counts keeps
 * [org.darkan.tools.engine.render.RenderMetrics] honest about the frame it is describing.
 */
data class TerrainRenderStats(
    val drawCalls: Int,
    val triangles: Int
) {
    companion object {
        /** Nothing was drawn. */
        val NONE = TerrainRenderStats(0, 0)
    }
}

/**
 * Result of processing terrain edits in a single frame.
 * Contains the set of chunk coordinates that were rebuilt, which downstream
 * systems can use to update dependent data (e.g., object Y-positions on
 * modified terrain).
 */
data class EditResult(
    val rebuiltChunks: Set<ChunkCoord>,
    val heightsChanged: Boolean = false
) {
    /** True if any chunks were rebuilt this frame. */
    val hasChanges: Boolean get() = rebuiltChunks.isNotEmpty()

    companion object {
        /** Sentinel value representing no edits processed. */
        val NONE = EditResult(emptySet())
    }
}
