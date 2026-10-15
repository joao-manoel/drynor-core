package org.drynor.tools.engine.async

/**
 * Phases of region loading.
 */
enum class LoadPhase {
    IDLE,
    TERRAIN,
    OBJECTS,
    BATCHING,
    GPU_UPLOAD,
    DONE
}

/**
 * Snapshot of the current loading progress.
 * Thread-safe: created as an immutable snapshot by [AsyncRegionManager].
 */
data class LoadProgress(
    val totalRegions: Int = 0,
    val regionsReady: Int = 0,
    val regionsCpuDone: Int = 0,
    val pendingGpuTasks: Int = 0,
    val isInitialLoad: Boolean = false,
    val phase: LoadPhase = LoadPhase.IDLE
) {
    val isLoading: Boolean get() = phase != LoadPhase.IDLE && phase != LoadPhase.DONE
    val isComplete: Boolean get() = phase == LoadPhase.DONE || (totalRegions > 0 && regionsReady >= totalRegions)
    val progress: Float get() = if (totalRegions > 0) regionsReady.toFloat() / totalRegions else 0f
}
