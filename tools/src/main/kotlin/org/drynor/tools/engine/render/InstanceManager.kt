package org.drynor.tools.engine.render

import org.joml.Matrix4f

/**
 * Data for creating an instanced mesh from model geometry.
 */
data class MeshData(
    val positions: FloatArray,
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    val textureId: Int = -1 // -1 for untextured
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshData) return false
        return positions.contentEquals(other.positions) &&
               indices.contentEquals(other.indices) &&
               textureId == other.textureId
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + textureId
        return result
    }

    /**
     * This mesh with every triangle's last two vertices swapped.
     *
     * Instances whose model matrix mirrors an axis have a negative determinant, which
     * reverses the winding of everything they draw. Back-face culling does not shade
     * such an object incorrectly — it erases it — so the geometry those instances point
     * at has to carry the compensating flip. Vertex data is shared with the original;
     * only the index array is rebuilt.
     */
    fun withReversedWinding(): MeshData {
        val flipped = indices.copyOf()
        var t = 0
        while (t + 2 < flipped.size) {
            val tmp = flipped[t + 1]
            flipped[t + 1] = flipped[t + 2]
            flipped[t + 2] = tmp
            t += 3
        }
        return copy(indices = flipped)
    }
}

/** [MeshData.withReversedWinding] applied to every mesh of a model. */
fun List<MeshData>.withReversedWinding(): List<MeshData> = map { it.withReversedWinding() }

/**
 * Batch key for grouping instances by model and material.
 * Used for the public API (stats, registration). Hot-path lookups
 * use packed Long keys via [packKey] for zero-allocation performance.
 */
data class InstanceBatchKey(
    val modelId: Int,
    val textureId: Int
)

/**
 * Callback interface for providing texture pixel data to the object atlas.
 * The InstanceManager calls this during [registerModel] to obtain raw ARGB
 * pixel data for each unique textureId so it can pack them into the atlas.
 *
 * @see InstanceManager
 */
fun interface TexturePixelProvider {
    /**
     * Get raw ARGB pixel data for a texture/material ID.
     *
     * @param textureId The texture/material ID from MeshData
     * @return Triple of (ARGB pixels, width, height) or null if unavailable
     */
    fun getPixels(textureId: Int): Triple<IntArray, Int, Int>?
}

/**
 * Manages instanced rendering for many objects sharing the same model.
 *
 * When an [objectAtlas] is provided, textured MeshData entries are packed into
 * the atlas at registration time, their UV coordinates are remapped to atlas space,
 * and entries sharing the same atlas page are merged into a single InstancedMesh.
 * This collapses texture-based splits, dramatically reducing draw calls from
 * ~300-600 (one per unique modelId+textureId) to ~50-150 (one per unique model
 * geometry, since most textures share one atlas page).
 *
 * Usage:
 * 1. Call prepareFrame() at the start of each frame
 * 2. Call addInstance() for each object to render
 * 3. Call uploadAll() to send data to GPU
 * 4. Call renderAll() to draw all instances
 */
class InstanceManager(
    private val maxInstancesPerMesh: Int = 10000,
    /** Object texture atlas for collapsing texture-based splits. */
    val objectAtlas: TextureAtlas? = null,
    /** Provider for texture pixel data, required when objectAtlas is set. */
    private val texturePixelProvider: TexturePixelProvider? = null
) {
    /**
     * Resolved key for a mesh batch after atlas lookup.
     * Stores the packed Long key used in [instancedMeshes].
     */
    private class ResolvedBatch(val packedKey: Long)

    // Registered resolved batch keys by model ID.
    // Each model maps to a list of packed keys (one per merged geometry group).
    private val meshRegistry = mutableMapOf<Int, List<ResolvedBatch>>()

    // Instanced meshes by packed Long key.
    // Without atlas: key = packKey(modelId, textureId)
    // With atlas: key = packKey(modelId, atlasPageIndex) for textured, packKey(modelId, UNTEXTURED_SENTINEL) for untextured
    private val instancedMeshes = HashMap<Long, InstancedMesh>()

    // Pre-sorted list of textured entries for rendering (rebuilt on model registration)
    private var sortedTexturedEntries: List<Map.Entry<Long, InstancedMesh>> = emptyList()
    private var texturedEntriesDirty = true

    // Statistics
    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0

    companion object {
        /** Sentinel value for untextured (color-only) mesh batches in the packed key. */
        const val UNTEXTURED_SENTINEL = -1
    }

    /** Pack modelId and a secondary key (textureId or atlasPageIndex) into a single Long key. */
    private fun packKey(modelId: Int, secondaryKey: Int): Long =
        (modelId.toLong() shl 32) or (secondaryKey.toLong() and 0xFFFFFFFFL)

    /** Extract the secondary key (textureId or atlasPageIndex) from a packed Long key. */
    private fun unpackSecondaryKey(key: Long): Int = key.toInt()

    /** Extract modelId from a packed Long key. */
    private fun unpackModelId(key: Long): Int = (key ushr 32).toInt()

    /** Check if a packed key represents an untextured batch. */
    private fun isUntexturedKey(key: Long): Boolean = unpackSecondaryKey(key) == UNTEXTURED_SENTINEL

    /**
     * Register mesh data for a model ID.
     * Call this once when loading models.
     *
     * When [objectAtlas] is configured, textured MeshData entries are:
     * 1. Packed into the atlas (texture pixels obtained via [texturePixelProvider])
     * 2. UV coordinates remapped from [0,1] to atlas space
     * 3. Merged by atlas page index into combined geometry
     *
     * This means entries that previously created separate InstancedMeshes per textureId
     * now share a single InstancedMesh per atlas page, dramatically reducing draw calls.
     *
     * @param modelId The model identifier
     * @param meshDataList List of mesh data (one per texture batch)
     * @param maxInstances Override max instances for this model (defaults to [maxInstancesPerMesh])
     */
    fun registerModel(modelId: Int, meshDataList: List<MeshData>, maxInstances: Int = maxInstancesPerMesh) {
        val atlas = objectAtlas
        val pixelProvider = texturePixelProvider

        if (atlas != null && pixelProvider != null) {
            registerModelWithAtlas(modelId, meshDataList, maxInstances, atlas, pixelProvider)
        } else {
            registerModelDirect(modelId, meshDataList, maxInstances)
        }

        texturedEntriesDirty = true
    }

    /**
     * Register model WITHOUT atlas -- original behavior.
     * Each MeshData gets its own InstancedMesh keyed by (modelId, textureId).
     */
    private fun registerModelDirect(modelId: Int, meshDataList: List<MeshData>, maxInstances: Int) {
        val resolvedBatches = mutableListOf<ResolvedBatch>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            val key = packKey(modelId, meshData.textureId)
            if (!instancedMeshes.containsKey(key)) {
                instancedMeshes[key] = InstancedMesh(
                    positions = meshData.positions,
                    colors = meshData.colors,
                    textureCoords = meshData.textureCoords,
                    normals = meshData.normals,
                    indices = meshData.indices,
                    maxInstances = maxInstances
                )
            }
            resolvedBatches.add(ResolvedBatch(key))
        }

        meshRegistry[modelId] = resolvedBatches
    }

    /**
     * Register model WITH atlas -- pack textures, remap UVs, merge by atlas page.
     *
     * Algorithm:
     * 1. For each MeshData, determine its resolved key:
     *    - Untextured (textureId == -1): key = packKey(modelId, UNTEXTURED_SENTINEL)
     *    - Textured with successful atlas pack: key = packKey(modelId, atlasPageIndex)
     *    - Textured but atlas pack failed: key = packKey(modelId, textureId) (fallback)
     * 2. Group MeshData entries by resolved key
     * 3. Merge groups with multiple entries into combined geometry
     * 4. Create one InstancedMesh per unique key
     */
    private fun registerModelWithAtlas(
        modelId: Int,
        meshDataList: List<MeshData>,
        maxInstances: Int,
        atlas: TextureAtlas,
        pixelProvider: TexturePixelProvider
    ) {
        // Phase 1: Resolve each MeshData to its atlas-aware key and remap UVs
        data class ResolvedMeshData(val key: Long, val meshData: MeshData)

        val resolvedEntries = mutableListOf<ResolvedMeshData>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            if (meshData.textureId == -1) {
                // Untextured -- no atlas needed
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, UNTEXTURED_SENTINEL),
                    meshData
                ))
                continue
            }

            // Try to pack texture into atlas
            val region = getOrPackTexture(atlas, pixelProvider, meshData.textureId)

            if (region != null) {
                // Remap UV coordinates to atlas space
                val remappedMeshData = remapUVsToAtlas(meshData, region)
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, region.atlasPageIndex),
                    remappedMeshData
                ))
            } else {
                // Fallback: texture couldn't be packed, use original textureId as key.
                // This will be rendered via the legacy per-texture binding path.
                // Use a fallback key that won't collide with atlas page indices.
                // Atlas page indices are 0, 1, 2... so we use negative textureId space
                // shifted to avoid collision with UNTEXTURED_SENTINEL (-1).
                // Since textureIds are non-negative (>= 0), we encode them as -(textureId + 2)
                // to keep them distinct from UNTEXTURED_SENTINEL (-1) and atlas page indices (>= 0).
                val fallbackKey = -(meshData.textureId + 2)
                resolvedEntries.add(ResolvedMeshData(
                    packKey(modelId, fallbackKey),
                    meshData
                ))
            }
        }

        // Phase 2: Group by resolved key and merge
        val grouped = resolvedEntries.groupBy { it.key }
        val resolvedBatches = mutableListOf<ResolvedBatch>()

        for ((key, entries) in grouped) {
            if (instancedMeshes.containsKey(key)) {
                // Already registered (shared key between models on same atlas page with same modelId)
                resolvedBatches.add(ResolvedBatch(key))
                continue
            }

            val mergedMeshData = if (entries.size == 1) {
                entries[0].meshData
            } else {
                mergeMeshDataList(entries.map { it.meshData })
            }

            instancedMeshes[key] = InstancedMesh(
                positions = mergedMeshData.positions,
                colors = mergedMeshData.colors,
                textureCoords = mergedMeshData.textureCoords,
                normals = mergedMeshData.normals,
                indices = mergedMeshData.indices,
                maxInstances = maxInstances
            )
            resolvedBatches.add(ResolvedBatch(key))
        }

        meshRegistry[modelId] = resolvedBatches
    }

    /**
     * Get an existing atlas region for a texture, or pack it into the atlas.
     * Returns null if the texture pixels can't be obtained or the atlas is full.
     */
    private fun getOrPackTexture(
        atlas: TextureAtlas,
        pixelProvider: TexturePixelProvider,
        textureId: Int
    ): AtlasRegion? {
        // Check if already in atlas
        atlas.getRegion(textureId)?.let { return it }

        // Get pixel data from provider
        val (pixels, width, height) = pixelProvider.getPixels(textureId) ?: return null

        return atlas.addTexture(textureId, pixels, width, height)
    }

    /**
     * Remap UV coordinates in a MeshData from local [0,1] space to atlas UV space.
     *
     * For each vertex: newU = region.u0 + u * (region.u1 - region.u0)
     *                  newV = region.v0 + v * (region.v1 - region.v0)
     *
     * Returns a new MeshData with remapped UVs and textureId set to -1 (since
     * the atlas page handles texturing, individual textureIds are no longer needed
     * for rendering).
     */
    private fun remapUVsToAtlas(meshData: MeshData, region: AtlasRegion): MeshData {
        val texCoords = meshData.textureCoords ?: return meshData

        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0

        var i = 0
        while (i < texCoords.size - 1) {
            val u = texCoords[i]
            val v = texCoords[i + 1]
            remapped[i] = region.u0 + u * uScale
            remapped[i + 1] = region.v0 + v * vScale
            i += 2
        }

        return MeshData(
            positions = meshData.positions,
            colors = meshData.colors,
            textureCoords = remapped,
            normals = meshData.normals,
            indices = meshData.indices,
            textureId = meshData.textureId // Keep original textureId for fallback identification
        )
    }

    /**
     * Merge multiple MeshData entries into a single combined MeshData.
     * Concatenates vertex data and re-indexes indices.
     *
     * This is used when multiple texture batches for the same model land on
     * the same atlas page -- their remapped UVs already point to the correct
     * atlas regions, so we can render them together in a single draw call.
     */
    private fun mergeMeshDataList(entries: List<MeshData>): MeshData {
        if (entries.size == 1) return entries[0]

        // Calculate total sizes
        var totalPositions = 0
        var totalIndices = 0
        var hasColors = false
        var hasTexCoords = false
        var hasNormals = false

        for (md in entries) {
            totalPositions += md.positions.size
            totalIndices += md.indices.size
            if (md.colors != null) hasColors = true
            if (md.textureCoords != null) hasTexCoords = true
            if (md.normals != null) hasNormals = true
        }

        val positions = FloatArray(totalPositions)
        val colors = if (hasColors) FloatArray(totalPositions) else null // Same size as positions (3 per vertex)
        val texCoords = if (hasTexCoords) FloatArray(totalPositions / 3 * 2) else null // 2 per vertex
        val normals = if (hasNormals) FloatArray(totalPositions) else null // Same size as positions
        val indices = IntArray(totalIndices)

        var posOffset = 0
        var colorOffset = 0
        var texOffset = 0
        var normalOffset = 0
        var indexOffset = 0
        var vertexOffset = 0 // Running count of vertices for re-indexing

        for (md in entries) {
            // Copy positions
            System.arraycopy(md.positions, 0, positions, posOffset, md.positions.size)
            posOffset += md.positions.size

            // Copy colors (or fill with white if this entry has no colors but others do)
            if (hasColors) {
                if (md.colors != null) {
                    System.arraycopy(md.colors, 0, colors!!, colorOffset, md.colors.size)
                    colorOffset += md.colors.size
                } else {
                    // Fill with white (1.0f) for vertices that have no color data
                    val numFloats = md.positions.size // 3 floats per vertex, same as positions
                    for (j in 0 until numFloats) {
                        colors!![colorOffset + j] = 1f
                    }
                    colorOffset += numFloats
                }
            }

            // Copy texture coordinates (or fill with 0 if this entry has none)
            if (hasTexCoords) {
                val numTexFloats = md.positions.size / 3 * 2
                if (md.textureCoords != null) {
                    System.arraycopy(md.textureCoords, 0, texCoords!!, texOffset, md.textureCoords.size)
                    texOffset += md.textureCoords.size
                } else {
                    // Fill with (0, 0) for vertices without tex coords
                    for (j in 0 until numTexFloats) {
                        texCoords!![texOffset + j] = 0f
                    }
                    texOffset += numTexFloats
                }
            }

            // Copy normals (or fill with up vector if this entry has none)
            if (hasNormals) {
                if (md.normals != null) {
                    System.arraycopy(md.normals, 0, normals!!, normalOffset, md.normals.size)
                    normalOffset += md.normals.size
                } else {
                    val numFloats = md.positions.size
                    var j = 0
                    while (j < numFloats) {
                        normals!![normalOffset + j] = 0f     // X
                        normals[normalOffset + j + 1] = 1f   // Y (up)
                        normals[normalOffset + j + 2] = 0f   // Z
                        j += 3
                    }
                    normalOffset += numFloats
                }
            }

            // Copy indices with vertex offset for re-indexing
            val numVertices = md.positions.size / 3
            for (j in md.indices.indices) {
                indices[indexOffset + j] = md.indices[j] + vertexOffset
            }
            indexOffset += md.indices.size
            vertexOffset += numVertices
        }

        return MeshData(
            positions = positions,
            colors = colors,
            textureCoords = texCoords,
            normals = normals,
            indices = indices,
            textureId = entries[0].textureId // Use first entry's textureId (arbitrary for merged)
        )
    }

    /**
     * Check if a model is registered.
     */
    fun isModelRegistered(modelId: Int): Boolean = meshRegistry.containsKey(modelId)

    /**
     * Prepare for a new frame. Clears all pending instances.
     */
    fun prepareFrame() {
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0

        // Begin batch on all instanced meshes
        for (mesh in instancedMeshes.values) {
            mesh.beginBatch()
        }
    }

    /**
     * Add an instance of a model with the given transform.
     *
     * @param modelId The model to instance
     * @param modelMatrix The transform matrix for this instance
     * @return true if added successfully, false if model not registered or buffer full
     */
    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstance(modelMatrix)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    /**
     * Add an instance of a model with a pre-computed raw matrix.
     * Avoids per-instance Matrix4f allocation on the hot path.
     *
     * @param modelId The model to instance
     * @param matrixData 16 floats representing a 4x4 column-major matrix
     * @return true if added successfully, false if model not registered or buffer full
     */
    fun addInstanceRaw(modelId: Int, matrixData: FloatArray): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstanceRaw(matrixData)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    /**
     * Add an instance at a simple position.
     */
    fun addInstance(modelId: Int, x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        val batches = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (batch in batches) {
            val mesh = instancedMeshes[batch.packedKey] ?: continue
            if (!mesh.addInstance(x, y, z, scale)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    /**
     * Upload all instance data to GPU.
     * Call this after adding all instances for the frame.
     */
    fun uploadAll() {
        for (mesh in instancedMeshes.values) {
            mesh.endBatch()
        }
    }

    /**
     * Render all instanced meshes without textures (color shader).
     *
     * @param shader The shader to use (should have model matrix support disabled for instancing)
     * @param setupCallback Called before rendering each batch for shader setup
     */
    fun renderUntextured(
        shader: ShaderProgram,
        setupCallback: ((InstanceBatchKey) -> Unit)? = null
    ) {
        shader.bind()

        for ((key, mesh) in instancedMeshes) {
            if (!isUntexturedKey(key)) continue // Skip textured batches
            if (mesh.getInstanceCount() == 0) continue

            setupCallback?.invoke(InstanceBatchKey(unpackModelId(key), UNTEXTURED_SENTINEL))
            mesh.render()
            drawCallsThisFrame++
        }
    }

    /** Rebuild the sorted textured entries list if dirty. */
    private fun ensureSortedTexturedEntries() {
        if (!texturedEntriesDirty) return
        sortedTexturedEntries = instancedMeshes.entries
            .filter { !isUntexturedKey(it.key) }
            .sortedBy { unpackSecondaryKey(it.key) }
        texturedEntriesDirty = false
    }

    /**
     * Render all textured instanced meshes using the object atlas.
     *
     * When the object atlas is active, textured entries are keyed by atlas page index.
     * This method binds each atlas page once and renders all meshes on that page,
     * resulting in far fewer texture binds than the per-texture approach.
     *
     * Entries that failed atlas packing (fallback keys, encoded as negative values < -1)
     * are rendered via the [fallbackBindTexture] callback with their original textureId.
     *
     * @param shader The shader to use
     * @param fallbackBindTexture Callback to bind individual textures for entries that
     *        could not be packed into the atlas (called with the original textureId)
     */
    fun renderTexturedWithAtlas(
        shader: ShaderProgram,
        fallbackBindTexture: (textureId: Int) -> Unit
    ) {
        val atlas = objectAtlas ?: return
        shader.bind()

        ensureSortedTexturedEntries()

        var lastBoundPage = -1
        for (entry in sortedTexturedEntries) {
            val mesh = entry.value
            if (mesh.getInstanceCount() == 0) continue

            val secondaryKey = unpackSecondaryKey(entry.key)

            if (secondaryKey < -1) {
                // Fallback entry: decode original textureId from -(textureId + 2)
                val originalTextureId = -(secondaryKey + 2)
                fallbackBindTexture(originalTextureId)
                lastBoundPage = -1 // Reset so atlas rebinds after fallback
            } else {
                // Atlas page entry: secondaryKey is the atlas page index
                if (secondaryKey != lastBoundPage) {
                    atlas.bindPage(secondaryKey, 0)
                    lastBoundPage = secondaryKey
                }
            }

            mesh.render()
            drawCallsThisFrame++
        }
    }

    /**
     * Render all instanced meshes with textures, sorted by texture ID
     * to minimize texture state changes.
     *
     * This is the legacy rendering path used when no object atlas is configured.
     *
     * @param shader The shader to use
     * @param bindTexture Callback to bind texture for each batch
     */
    fun renderTextured(
        shader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        shader.bind()

        ensureSortedTexturedEntries()

        var lastTextureId = -1
        for (entry in sortedTexturedEntries) {
            val mesh = entry.value
            if (mesh.getInstanceCount() == 0) continue

            val textureId = unpackSecondaryKey(entry.key)
            if (textureId != lastTextureId) {
                bindTexture(textureId)
                lastTextureId = textureId
            }
            mesh.render()
            drawCallsThisFrame++
        }
    }

    /**
     * Render all instances (both textured and untextured).
     *
     * @param colorShader Shader for untextured geometry
     * @param texturedShader Shader for textured geometry
     * @param bindTexture Callback to bind texture
     */
    fun renderAll(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        renderUntextured(colorShader)
        renderTextured(texturedShader, bindTexture)
    }

    /**
     * Get statistics for the current frame.
     */
    fun getStats(): InstanceStats {
        val instanceCounts = mutableMapOf<InstanceBatchKey, Int>()
        for ((key, mesh) in instancedMeshes) {
            instanceCounts[InstanceBatchKey(unpackModelId(key), unpackSecondaryKey(key))] = mesh.getInstanceCount()
        }
        return InstanceStats(
            totalInstances = totalInstancesThisFrame,
            drawCalls = drawCallsThisFrame,
            registeredModels = meshRegistry.size,
            instancedMeshCount = instancedMeshes.size,
            instancesPerBatch = instanceCounts
        )
    }

    /**
     * Get the total number of triangles submitted by instanced rendering this frame.
     * For each instanced mesh with active instances, the triangle count is
     * (vertexCount / 3) * instanceCount.
     */
    fun getTotalTrianglesSubmitted(): Int {
        var total = 0
        for (mesh in instancedMeshes.values) {
            val count = mesh.getInstanceCount()
            if (count > 0) {
                total += (mesh.vertexCount / 3) * count
            }
        }
        return total
    }

    /**
     * Clean up all GPU resources.
     * Note: Does NOT clean up the [objectAtlas] -- the caller owns its lifecycle.
     */
    fun cleanup() {
        for (mesh in instancedMeshes.values) {
            mesh.cleanup()
        }
        instancedMeshes.clear()
        meshRegistry.clear()
        sortedTexturedEntries = emptyList()
        texturedEntriesDirty = true
    }

    /**
     * Unregister a model and clean up its resources.
     */
    fun unregisterModel(modelId: Int) {
        val batches = meshRegistry.remove(modelId) ?: return

        for (batch in batches) {
            instancedMeshes.remove(batch.packedKey)?.cleanup()
        }

        texturedEntriesDirty = true
    }
}

data class InstanceStats(
    val totalInstances: Int,
    val drawCalls: Int,
    val registeredModels: Int,
    val instancedMeshCount: Int,
    val instancesPerBatch: Map<InstanceBatchKey, Int>
)
