package org.drynor.tools.engine.terrain

import org.drynor.tools.engine.render.BatchKey
import org.drynor.tools.engine.render.Mesh
import org.drynor.tools.engine.render.ShaderProgram
import org.drynor.tools.engine.render.StaticBatcher
import org.drynor.tools.engine.render.TransformedGeometry
import org.drynor.tools.engine.spatial.AABB
import org.drynor.tools.engine.spatial.Frustum
import org.drynor.tools.engine.spatial.Rect
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * A terrain chunk containing batched geometry.
 */
class TerrainChunk(
    val chunkX: Int,
    val chunkZ: Int,
    val bounds: AABB,
    val rect: Rect
) {
    // Meshes by texture/material
    private val meshes = mutableMapOf<BatchKey, Mesh>()

    // LOD meshes (0 = highest detail)
    private val lodMeshes = mutableMapOf<Int, Map<BatchKey, Mesh>>()

    var visible = true
    var currentLod = 0

    /**
     * Set meshes for this chunk at a specific LOD level.
     */
    fun setMeshes(lod: Int, meshMap: Map<BatchKey, Mesh>) {
        lodMeshes[lod] = meshMap
        if (lod == 0) {
            meshes.clear()
            meshes.putAll(meshMap)
        }
    }

    /**
     * Get meshes for rendering at the current LOD.
     */
    fun getMeshes(): Map<BatchKey, Mesh> {
        return lodMeshes[currentLod] ?: meshes
    }

    /**
     * Render the chunk's untextured geometry.
     */
    fun renderUntextured(shader: ShaderProgram, modelMatrix: Matrix4f) {
        if (!visible) return

        val chunkMeshes = getMeshes()
        for ((key, mesh) in chunkMeshes) {
            if (key.textureId != -1) continue
            shader.setUniform("model", modelMatrix)
            mesh.render()
        }
    }

    /**
     * Render the chunk's textured geometry.
     */
    fun renderTextured(shader: ShaderProgram, modelMatrix: Matrix4f, bindTexture: (Int) -> Unit) {
        if (!visible) return

        val chunkMeshes = getMeshes()
        for ((key, mesh) in chunkMeshes) {
            if (key.textureId == -1) continue
            shader.setUniform("model", modelMatrix)
            bindTexture(key.textureId)
            mesh.render()
        }
    }

    fun cleanup() {
        for (meshMap in lodMeshes.values) {
            for (mesh in meshMap.values) {
                mesh.cleanup()
            }
        }
        lodMeshes.clear()
        meshes.clear()
    }
}

/**
 * Manages terrain split into renderable chunks for efficient culling.
 *
 * Each chunk is a square region of tiles that are batched together.
 * Chunks are culled against the camera frustum before rendering.
 *
 * @param chunkSize Number of tiles per chunk side (e.g., 16 = 16x16 tiles)
 * @param tileSize World units per tile
 */
class ChunkedTerrain(
    private val chunkSize: Int = 16,
    private val tileSize: Float = 1f
) {
    private val chunks = mutableMapOf<Long, TerrainChunk>()
    private val visibleChunks = mutableListOf<TerrainChunk>()

    // Identity matrix for chunks at world origin
    private val identityMatrix = Matrix4f()

    // Statistics
    private var totalChunks = 0
    private var visibleChunkCount = 0
    private var lastCullTimeNanos = 0L

    /**
     * Calculate chunk key from chunk coordinates.
     */
    private fun chunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Get chunk at chunk coordinates.
     */
    fun getChunk(chunkX: Int, chunkZ: Int): TerrainChunk? {
        return chunks[chunkKey(chunkX, chunkZ)]
    }

    /**
     * Create or get a chunk at the specified coordinates.
     */
    fun getOrCreateChunk(chunkX: Int, chunkZ: Int): TerrainChunk {
        val key = chunkKey(chunkX, chunkZ)
        return chunks.getOrPut(key) {
            val worldX = chunkX * chunkSize * tileSize
            val worldZ = chunkZ * chunkSize * tileSize
            val size = chunkSize * tileSize

            val bounds = AABB(
                Vector3f(worldX, -100f, worldZ),
                Vector3f(worldX + size, 500f, worldZ + size)
            )
            val rect = Rect(worldX, worldZ, worldX + size, worldZ + size)

            totalChunks++
            TerrainChunk(chunkX, chunkZ, bounds, rect)
        }
    }

    /**
     * Add geometry to a chunk.
     */
    fun addGeometry(chunkX: Int, chunkZ: Int, key: BatchKey, geometry: TransformedGeometry) {
        val chunk = getOrCreateChunk(chunkX, chunkZ)
        // For now, we use a simple approach - the caller should batch and set meshes
    }

    /**
     * Set the meshes for a chunk.
     */
    fun setChunkMeshes(chunkX: Int, chunkZ: Int, lod: Int, meshes: Map<BatchKey, Mesh>) {
        val chunk = getOrCreateChunk(chunkX, chunkZ)
        chunk.setMeshes(lod, meshes)
    }

    /**
     * Build a chunk from a static batcher.
     */
    fun buildChunkFromBatcher(chunkX: Int, chunkZ: Int, batcher: StaticBatcher, lod: Int = 0) {
        val meshes = batcher.buildMeshes()
        setChunkMeshes(chunkX, chunkZ, lod, meshes)
    }

    /**
     * Update visibility based on frustum.
     */
    fun updateVisibility(frustum: Frustum) {
        val startTime = System.nanoTime()

        visibleChunks.clear()

        for (chunk in chunks.values) {
            chunk.visible = frustum.isAABBVisible(chunk.bounds)
            if (chunk.visible) {
                visibleChunks.add(chunk)
            }
        }

        visibleChunkCount = visibleChunks.size
        lastCullTimeNanos = System.nanoTime() - startTime
    }

    /**
     * Update LOD levels based on camera distance.
     */
    fun updateLOD(cameraPos: Vector3f, lodDistances: FloatArray = floatArrayOf(100f, 300f, 600f, 1000f)) {
        for (chunk in visibleChunks) {
            val chunkCenter = chunk.bounds.center
            val dx = cameraPos.x - chunkCenter.x
            val dz = cameraPos.z - chunkCenter.z
            val distSq = dx * dx + dz * dz

            // Determine LOD level
            chunk.currentLod = 0
            for ((i, threshold) in lodDistances.withIndex()) {
                if (distSq > threshold * threshold) {
                    chunk.currentLod = i + 1
                }
            }
        }
    }

    /**
     * Render all visible chunks.
     */
    fun render(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        bindTexture: (Int) -> Unit
    ) {
        // Render untextured geometry
        colorShader.bind()
        for (chunk in visibleChunks) {
            chunk.renderUntextured(colorShader, identityMatrix)
        }

        // Render textured geometry
        texturedShader.bind()
        for (chunk in visibleChunks) {
            chunk.renderTextured(texturedShader, identityMatrix, bindTexture)
        }
    }

    /**
     * Render only untextured terrain.
     */
    fun renderUntextured(shader: ShaderProgram) {
        shader.bind()
        for (chunk in visibleChunks) {
            chunk.renderUntextured(shader, identityMatrix)
        }
    }

    /**
     * Render only textured terrain.
     */
    fun renderTextured(shader: ShaderProgram, bindTexture: (Int) -> Unit) {
        shader.bind()
        for (chunk in visibleChunks) {
            chunk.renderTextured(shader, identityMatrix, bindTexture)
        }
    }

    /**
     * Get statistics about the terrain.
     */
    fun getStats(): ChunkedTerrainStats {
        return ChunkedTerrainStats(
            totalChunks = totalChunks,
            visibleChunks = visibleChunkCount,
            cullTimeNanos = lastCullTimeNanos,
            cullRatio = if (totalChunks > 0) 1f - (visibleChunkCount.toFloat() / totalChunks) else 0f
        )
    }

    /**
     * Get all visible chunks.
     */
    fun getVisibleChunks(): List<TerrainChunk> = visibleChunks.toList()

    /**
     * Clear all chunks.
     */
    fun clear() {
        for (chunk in chunks.values) {
            chunk.cleanup()
        }
        chunks.clear()
        visibleChunks.clear()
        totalChunks = 0
        visibleChunkCount = 0
    }

    /**
     * Clean up resources.
     */
    fun cleanup() = clear()
}

data class ChunkedTerrainStats(
    val totalChunks: Int,
    val visibleChunks: Int,
    val cullTimeNanos: Long,
    val cullRatio: Float
) {
    val cullTimeMs: Float get() = cullTimeNanos / 1_000_000f
}
