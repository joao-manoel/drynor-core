package org.darkan.tools.engine.async

import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.MergedGeometry
import org.darkan.tools.engine.render.MeshData

/**
 * A unit of GPU work that must be executed on the main GL thread.
 * Background threads produce these; the main thread consumes them via [GpuUploadQueue].
 */
sealed interface GpuUploadTask {
    /** Priority for ordering: lower value = higher priority. */
    val priority: Int
    /** Region this task belongs to (for cancellation). */
    val regionKey: Long
    /** Whether this task has been cancelled. */
    var cancelled: Boolean
}

data class UploadTerrainMesh(
    override val regionKey: Long,
    override val priority: Int,
    val meshData: Any,  // TerrainLoader.TerrainMeshData - use Any to avoid circular dep
    val plane: Int,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class RegisterModel(
    override val regionKey: Long,
    override val priority: Int,
    val modelId: Int,
    val meshDataList: List<MeshData>,
    val maxInstances: Int = 50000,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class UploadAtlasPage(
    override val regionKey: Long,
    override val priority: Int,
    override var cancelled: Boolean = false,
) : GpuUploadTask

/**
 * Upload one already-merged static batch.
 *
 * The vertex/index concatenation is done by the background loader, not here — all
 * the GL thread has to do is pack [merged] into native memory and upload it.
 */
data class BuildRegionBatchMeshes(
    override val regionKey: Long,
    override val priority: Int,
    val batchKey: BatchKey,
    val merged: MergedGeometry,
    override var cancelled: Boolean = false,
) : GpuUploadTask

data class BuildMergedTerrain(
    override val regionKey: Long,
    override val priority: Int,
    val opaqueData: MergedTerrainData?,
    val alphaData: MergedTerrainData?,
    override var cancelled: Boolean = false,
) : GpuUploadTask

/**
 * CPU-side data for a merged terrain mesh, ready for GPU upload.
 */
data class MergedTerrainData(
    val positions: FloatArray,
    val colors: FloatArray,
    val texCoords: FloatArray,
    val normals: FloatArray,
    val atlasTransforms: FloatArray,
    val indices: IntArray,
    val alphas: FloatArray? = null  // Only for alpha meshes
)
