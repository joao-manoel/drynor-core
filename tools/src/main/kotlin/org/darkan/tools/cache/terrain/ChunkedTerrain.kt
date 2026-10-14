package org.darkan.tools.cache.terrain

import org.darkan.tools.engine.render.ChunkMesh
import org.darkan.tools.engine.render.ShaderProgram

/**
 * Manages 64 terrain chunks (8x8 grid) for one region+plane, each independently rebuildable.
 * Replaces the monolithic [TerrainLoader.TerrainMeshes] approach for editing scenarios where
 * only a few tiles change at a time.
 *
 * Each chunk covers an 8x8 tile area and contains up to three mesh categories:
 * - Color mesh: untextured vertices (textureId == -1)
 * - Textured meshes: opaque textured vertices keyed by texture ID
 * - Alpha meshes: semi-transparent textured vertices keyed by texture ID
 *
 * Triangle-level alpha classification matches [TerrainLoader.createBatchedTerrainMeshes]:
 * a triangle goes to the alpha group if ANY of its three vertices has alpha < 1.0
 * and the triangle is textured (textureId >= 0). Otherwise it goes to the opaque group.
 */
class ChunkedTerrain(val regionX: Int, val regionY: Int, val plane: Int) {

    companion object {
        const val CHUNK_SIZE = 8   // tiles per chunk edge
        const val CHUNKS_PER_AXIS = 8  // 64 / 8
        /**
         * Maximum pre-allocated vertex capacity per chunk mesh.
         *
         * Each 8x8 chunk contains 64 tiles. In the worst case, a tile with
         * multi-pass alpha rendering (4 distinct corner underlay textures),
         * edge expansion, and blended overlay shapes can emit up to ~192
         * vertices. Regions like Falador have dense path/road tiles where
         * these worst-case conditions are common.
         *
         * Calculation: 64 tiles * 8 faces/tile * 2 (edge expansion) *
         *              4 (texture variation passes) * 3 verts/face = 12,288
         *
         * We use 16,384 (power of 2) for comfortable headroom.
         */
        const val MAX_VERTICES_PER_CHUNK = 16384
    }

    /** Per-chunk mesh storage indexed as [chunkX][chunkY]. */
    private val chunks = Array(CHUNKS_PER_AXIS) { Array(CHUNKS_PER_AXIS) { ChunkData() } }

    /**
     * Holds the color, textured, and alpha meshes for a single 8x8 terrain chunk.
     * Meshes are lazily created on first upload and cleaned up when the chunk is rebuilt
     * with no vertices of that category.
     */
    class ChunkData {
        var colorMesh: ChunkMesh? = null
        val texturedMeshes = mutableMapOf<Int, ChunkMesh>()  // textureId -> mesh
        val alphaMeshes = mutableMapOf<Int, ChunkMesh>()     // textureId -> mesh

        fun cleanup() {
            colorMesh?.cleanup()
            colorMesh = null
            texturedMeshes.values.forEach { it.cleanup() }
            texturedMeshes.clear()
            alphaMeshes.values.forEach { it.cleanup() }
            alphaMeshes.clear()
        }
    }

    /**
     * Build all 64 chunks from scratch (initial load).
     *
     * @param region The region data containing tile definitions
     * @param blendedColors Pre-computed blended underlay colors from
     *        [TerrainLoader.computeBlendedUnderlayColors]
     * @param normalsX Per-vertex normal X components from [TerrainLoader.computeVertexNormals]
     * @param normalsY Per-vertex normal Y components
     * @param normalsZ Per-vertex normal Z components
     */
    fun buildAll(
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                rebuildChunk(cx, cy, region, blendedColors, normalsX, normalsY, normalsZ, neighbors)
            }
        }
    }

    /**
     * Rebuild only the specified dirty chunks (incremental edit).
     * Chunks not in [dirtyChunks] are left untouched.
     *
     * @param dirtyChunks Set of chunk coordinates that need rebuilding
     */
    fun rebuildChunks(
        dirtyChunks: Set<ChunkCoord>,
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        for (coord in dirtyChunks) {
            rebuildChunk(coord.chunkX, coord.chunkY, region, blendedColors, normalsX, normalsY, normalsZ, neighbors)
        }
    }

    /**
     * Rebuild a single chunk by regenerating vertices for its 8x8 tile area,
     * classifying triangles into color/textured/alpha categories, and uploading
     * to the appropriate [ChunkMesh] instances.
     */
    private fun rebuildChunk(
        cx: Int, cy: Int,
        region: TerrainLoader.RegionData,
        blendedColors: Array<IntArray>,
        normalsX: Array<FloatArray>, normalsY: Array<FloatArray>, normalsZ: Array<FloatArray>,
        neighbors: TerrainLoader.NeighborData? = null
    ) {
        val chunk = chunks[cx][cy]

        // Collect all vertices for this chunk's 8x8 tiles, then classify by triangle
        val allVertices = mutableListOf<TerrainLoader.ShapedVertex>()

        val startX = cx * CHUNK_SIZE
        val startY = cy * CHUNK_SIZE
        for (dx in 0 until CHUNK_SIZE) {
            for (dy in 0 until CHUNK_SIZE) {
                val tileX = startX + dx
                val tileY = startY + dy
                val vertices = TerrainLoader.generateTileVertices(
                    region, plane, tileX, tileY, blendedColors, normalsX, normalsY, normalsZ,
                    neighbors
                )
                allVertices.addAll(vertices)
            }
        }

        // Separate into opaque and alpha groups by triangle, matching the logic in
        // TerrainLoader.createBatchedTerrainMeshes: a triangle goes to alpha if ANY
        // vertex has alpha < 1.0 AND the triangle is textured (textureId >= 0).
        val opaqueGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()
        val alphaGroups = mutableMapOf<Int, MutableList<TerrainLoader.ShapedVertex>>()

        var i = 0
        while (i + 2 < allVertices.size) {
            val v0 = allVertices[i]
            val v1 = allVertices[i + 1]
            val v2 = allVertices[i + 2]

            val needsAlpha = v0.alpha < 1f || v1.alpha < 1f || v2.alpha < 1f

            if (needsAlpha && v0.textureId >= 0) {
                val group = alphaGroups.getOrPut(v0.textureId) { mutableListOf() }
                group.add(v0); group.add(v1); group.add(v2)
            } else {
                val group = opaqueGroups.getOrPut(v0.textureId) { mutableListOf() }
                group.add(v0); group.add(v1); group.add(v2)
            }
            i += 3
        }
        // Handle remaining vertices (should be 0 if all triangles are complete)
        while (i < allVertices.size) {
            opaqueGroups.getOrPut(allVertices[i].textureId) { mutableListOf() }.add(allVertices[i])
            i++
        }

        // Upload color mesh (textureId == -1 from opaque groups)
        val colorVerts = opaqueGroups[-1]
        uploadColorMesh(chunk, colorVerts)

        // Upload textured meshes -- cleanup old texture IDs not present in new set
        val newTexturedIds = mutableSetOf<Int>()
        for ((texId, verts) in opaqueGroups) {
            if (texId == -1) continue
            newTexturedIds.add(texId)
            uploadTexturedMesh(chunk, texId, verts)
        }
        val staleTexturedIds = chunk.texturedMeshes.keys - newTexturedIds
        for (id in staleTexturedIds) {
            chunk.texturedMeshes.remove(id)?.cleanup()
        }

        // Upload alpha meshes -- cleanup old texture IDs not present in new set
        val newAlphaIds = mutableSetOf<Int>()
        for ((texId, verts) in alphaGroups) {
            newAlphaIds.add(texId)
            uploadAlphaMesh(chunk, texId, verts)
        }
        val staleAlphaIds = chunk.alphaMeshes.keys - newAlphaIds
        for (id in staleAlphaIds) {
            chunk.alphaMeshes.remove(id)?.cleanup()
        }
    }

    // ---- Upload helpers ----

    /**
     * Upload color-only (untextured) vertices into the chunk's color mesh.
     * Creates the [ChunkMesh] lazily on first use; uploads count=0 if the
     * vertex list is null or empty to mark it as skippable during rendering.
     */
    private fun uploadColorMesh(chunk: ChunkData, vertices: List<TerrainLoader.ShapedVertex>?) {
        if (vertices == null || vertices.isEmpty()) {
            // If no color vertices, upload 0 to existing mesh or leave null
            chunk.colorMesh?.upload(FloatArray(0), FloatArray(0), null, FloatArray(0), null, 0)
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val normals = FloatArray(count * 3)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
        }

        if (chunk.colorMesh == null) {
            chunk.colorMesh = ChunkMesh.createColor(MAX_VERTICES_PER_CHUNK)
        }
        chunk.colorMesh!!.upload(positions, colors, null, normals, null, count)
    }

    /**
     * Upload opaque textured vertices for a specific texture ID into the chunk's textured mesh map.
     * Creates the [ChunkMesh] lazily on first use.
     */
    private fun uploadTexturedMesh(chunk: ChunkData, textureId: Int, vertices: List<TerrainLoader.ShapedVertex>) {
        if (vertices.isEmpty()) {
            chunk.texturedMeshes[textureId]?.upload(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), null, 0
            )
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texcoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            texcoords[idx * 2]     = v.u
            texcoords[idx * 2 + 1] = v.v
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
        }

        if (chunk.texturedMeshes[textureId] == null) {
            chunk.texturedMeshes[textureId] = ChunkMesh.createTextured(MAX_VERTICES_PER_CHUNK)
        }
        chunk.texturedMeshes[textureId]!!.upload(positions, colors, texcoords, normals, null, count)
    }

    /**
     * Upload semi-transparent textured vertices for a specific texture ID into the chunk's alpha mesh map.
     * Creates the [ChunkMesh] lazily on first use.
     */
    private fun uploadAlphaMesh(chunk: ChunkData, textureId: Int, vertices: List<TerrainLoader.ShapedVertex>) {
        if (vertices.isEmpty()) {
            chunk.alphaMeshes[textureId]?.upload(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 0
            )
            return
        }

        val count = vertices.size
        val positions = FloatArray(count * 3)
        val colors = FloatArray(count * 3)
        val texcoords = FloatArray(count * 2)
        val normals = FloatArray(count * 3)
        val alphas = FloatArray(count)

        for (idx in vertices.indices) {
            val v = vertices[idx]
            positions[idx * 3]     = v.x
            positions[idx * 3 + 1] = v.y
            positions[idx * 3 + 2] = v.z
            colors[idx * 3]     = v.r
            colors[idx * 3 + 1] = v.g
            colors[idx * 3 + 2] = v.b
            texcoords[idx * 2]     = v.u
            texcoords[idx * 2 + 1] = v.v
            normals[idx * 3]     = v.nx
            normals[idx * 3 + 1] = v.ny
            normals[idx * 3 + 2] = v.nz
            alphas[idx] = v.alpha
        }

        if (chunk.alphaMeshes[textureId] == null) {
            chunk.alphaMeshes[textureId] = ChunkMesh.createAlpha(MAX_VERTICES_PER_CHUNK)
        }
        chunk.alphaMeshes[textureId]!!.upload(positions, colors, texcoords, normals, alphas, count)
    }

    // ---- Rendering ----

    /**
     * Render all non-empty color chunks (untextured terrain).
     *
     * @return what was actually submitted, so the caller can charge it to the frame's
     *         draw-call and triangle counters. An empty [ChunkMesh] issues no draw.
     */
    fun renderColor(shader: ShaderProgram): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                val mesh = chunks[cx][cy].colorMesh ?: continue
                if (mesh.isEmpty()) continue
                mesh.render()
                drawCalls++
                triangles += mesh.vertexCount / 3
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    /**
     * Render all non-empty textured chunks (opaque textured terrain).
     * The [bindTexture] callback is invoked before each texture group to bind the
     * appropriate GL texture.
     *
     * @return what was actually submitted. See [renderColor].
     */
    fun renderTextured(shader: ShaderProgram, bindTexture: (Int) -> Unit): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                for ((texId, mesh) in chunks[cx][cy].texturedMeshes) {
                    if (!mesh.isEmpty()) {
                        bindTexture(texId)
                        mesh.render()
                        drawCalls++
                        triangles += mesh.vertexCount / 3
                    }
                }
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    /**
     * Render all non-empty alpha chunks (semi-transparent textured terrain).
     * The [bindTexture] callback is invoked before each texture group to bind the
     * appropriate GL texture.
     *
     * @return what was actually submitted. See [renderColor].
     */
    fun renderAlpha(shader: ShaderProgram, bindTexture: (Int) -> Unit): TerrainRenderStats {
        var drawCalls = 0
        var triangles = 0
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                for ((texId, mesh) in chunks[cx][cy].alphaMeshes) {
                    if (!mesh.isEmpty()) {
                        bindTexture(texId)
                        mesh.render()
                        drawCalls++
                        triangles += mesh.vertexCount / 3
                    }
                }
            }
        }
        return TerrainRenderStats(drawCalls, triangles)
    }

    /** Release all GPU resources for every chunk. */
    fun cleanup() {
        for (cx in 0 until CHUNKS_PER_AXIS) {
            for (cy in 0 until CHUNKS_PER_AXIS) {
                chunks[cx][cy].cleanup()
            }
        }
    }
}
