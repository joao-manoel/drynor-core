package org.drynor.tools.engine.render

/**
 * Centralized performance counters for the rendering pipeline.
 *
 * Per-frame counters are reset at the start of each frame via [resetFrame].
 * Load-time counters (terrain load time, atlas stats) persist across frames
 * and are updated during region loading.
 *
 * All timing is done with [System.nanoTime] and stored in milliseconds.
 */
object RenderMetrics {

    // ====================================================================
    // GPU pass identifiers
    // ====================================================================

    const val GPU_PASS_CULL = 0
    const val GPU_PASS_TERRAIN_OPAQUE = 1
    const val GPU_PASS_TERRAIN_ALPHA = 2
    const val GPU_PASS_OBJECTS = 3
    const val GPU_PASS_STATIC_BATCH = 4
    const val GPU_PASS_SKY = 5
    const val GPU_PASS_UI = 6

    /** Display names for the GPU passes, in pass-index order. */
    val GPU_PASS_NAMES = listOf(
        "Cull (compute)",
        "Terrain opaque",
        "Terrain alpha",
        "Objects (MDI)",
        "Static batch",
        "Sky",
        "UI"
    )

    // ====================================================================
    // Draw call counters (per frame)
    // ====================================================================

    /** Terrain opaque draw calls (atlas path = 1, legacy = many). */
    var terrainOpaqueDrawCalls = 0

    /** Terrain alpha draw calls (atlas path = 1, legacy = many). */
    var terrainAlphaDrawCalls = 0

    /** Object instanced draw calls (center region, via InstanceManager). */
    var instancedDrawCalls = 0

    /**
     * `glMultiDrawElementsIndirect` calls issued for center-region objects.
     *
     * This is the real GL draw-call count, which is what [totalDrawCalls] sums. The number of
     * per-mesh commands packed into those calls is reported separately as [indirectCommands]:
     * the GPU still walks one command per mesh batch, but the driver only sees these few calls.
     */
    var indirectDrawCalls = 0

    /** DrawElementsIndirectCommand structs submitted inside [indirectDrawCalls] this frame. */
    var indirectCommands = 0

    /** Object static batched draw calls (context regions, via StaticBatcher). */
    var staticBatchDrawCalls = 0

    /** Sky draw calls: one attribute-less triangle when the sky pass runs. */
    var skyDrawCalls = 0

    /** Total draw calls for this frame (sum of the above). */
    val totalDrawCalls: Int
        get() = terrainOpaqueDrawCalls + terrainAlphaDrawCalls + instancedDrawCalls + indirectDrawCalls +
                staticBatchDrawCalls + skyDrawCalls

    // ====================================================================
    // Geometry counters (per frame)
    // ====================================================================

    /** Total triangles submitted to the GPU this frame. */
    var trianglesSubmitted = 0

    /** Number of object instances submitted to the GPU this frame (center region). */
    var instanceCount = 0

    /** Number of static batch meshes rendered (context regions). */
    var staticBatchMeshCount = 0

    /**
     * Object instances the renderer was asked to draw but discarded, because the model
     * they name was never registered with it.
     *
     * This is never expected to be non-zero. It exists because the discard used to be
     * silent: the scene simply came up with tile-sized holes in it, which reads as a
     * culling or shading bug and costs hours to trace back to a registration ordering
     * problem. Anything above zero here means geometry is missing from the frame.
     */
    var droppedInstances = 0

    /** Distinct model ids behind [droppedInstances], accumulated over the scene's life. */
    var droppedModelIds = 0

    /**
     * How many of [droppedModelIds] the cache simply has no geometry for.
     *
     * These are a data gap, not a rendering fault: no path can draw a model that does not
     * exist. Split out so the remainder — models that do have geometry and still did not
     * get drawn — stays a number that is supposed to be zero.
     */
    var droppedModelsUnavailable = 0

    /**
     * Triangles submitted by the static batch pass alone.
     *
     * Split out of [trianglesSubmitted] because that figure sums every pass, and the
     * question the static batch pass keeps raising — is it paying for draw calls or for
     * geometry — cannot be answered without knowing how much geometry it actually submits.
     */
    var staticBatchTriangles = 0

    // ====================================================================
    // Culling counters (per frame)
    // ====================================================================

    /** Draw items (instance x mesh batch) in the static object scene. */
    var cullItemsTotal = 0

    /**
     * Draw items that survived GPU culling, or -1 when unknown.
     *
     * Written by the culling compute shader into a ring buffer that the CPU samples
     * several frames later, so this always lags the picture on screen. Reading it any
     * sooner would mean synchronising with the GPU, which is exactly what culling on the
     * GPU is meant to avoid.
     */
    var cullVisibleItemsStale = -1

    /** Triangles behind [cullVisibleItemsStale], same staleness. */
    var cullVisibleTrianglesStale = -1

    /** How many frames behind [cullVisibleItemsStale] is. */
    var cullStatsAgeFrames = 0

    /** Whether the GPU culling pass ran this frame. */
    var cullActive = false

    /** Static batch meshes considered and skipped by CPU frustum culling. */
    var batchesConsidered = 0
    var batchesCulled = 0

    /**
     * Spatial cell ranges inside surviving batches, considered and skipped.
     *
     * A batch spans a whole 64x64 region; these are the sub-region index ranges it is
     * culled at instead. [staticBatchRanges] is how many were actually submitted, which is
     * larger than the draw-call count because contiguous survivors are fused into one
     * command and a whole-batch survivor is drawn as a plain draw.
     */
    var batchRangesConsidered = 0
    var batchRangesCulled = 0
    var staticBatchRanges = 0

    /**
     * Regions whose static batches were rejected wholesale by one test against the union
     * of their batch bounds, out of the regions that had batches to test.
     *
     * The gap between this and [batchesCulled] is the point of the two-level test: a
     * rejected region contributes its whole batch count to [batchesCulled] for the price of
     * a single box test.
     */
    var batchRegionsConsidered = 0
    var batchRegionsCulled = 0

    /** Context regions considered and skipped entirely by CPU frustum culling. */
    var contextRegionsConsidered = 0
    var contextRegionsCulled = 0

    /**
     * Regions covered by the merged atlas terrain, and how many of them survived frustum
     * culling to be submitted inside this frame's terrain multi-draws.
     *
     * These are index ranges inside one buffer, not draw calls: the whole set goes to the
     * driver as one `glMultiDrawElements` per pass, which is why the terrain draw counts
     * stay flat as render distance grows.
     */
    var terrainMergedRegionsTotal = 0
    var terrainMergedRegionsDrawn = 0

    /**
     * Terrain batches that had to keep their own texture bind because the atlas could not
     * serve their texture from the bound page. Zero unless the atlas overflowed.
     */
    var terrainAtlasOverflowDrawCalls = 0

    /** Time spent on CPU-side frustum work this frame (ms). */
    var cullCpuTimeMs = 0.0

    // ====================================================================
    // Per-object cull probe (per frame; zero unless -Ddrynor.cullProbe=true)
    // ====================================================================

    /**
     * What a *per-object* frustum cull of the context regions would submit, measured
     * without changing what is actually drawn.
     *
     * The static batch pass draws one merged mesh per (region, texture) pair, so its
     * finest culling unit spans a whole 64x64 region. These counters replay the same
     * frustum against the per-object boxes the merge flattened away, which is the only
     * honest way to price finer granularity before building it: [probeTrianglesVisible]
     * against [staticBatchTriangles] is the whole of the available win.
     */
    var probeObjectsTotal = 0
    var probeObjectsVisible = 0
    var probeTrianglesTotal = 0L
    var probeTrianglesVisible = 0L

    /** Wall-clock cost of the probe walk itself (ms). Not part of the render budget. */
    var probeCpuMs = 0.0

    /** Upper edge, in tiles from the camera, of each [probeTrianglesByDistance] bucket. */
    val PROBE_DISTANCE_EDGES_TILES = intArrayOf(16, 32, 48, 64, 96, 128, 192, Int.MAX_VALUE)

    /** Triangles of frustum-visible objects, bucketed by camera distance. */
    val probeTrianglesByDistance = LongArray(PROBE_DISTANCE_EDGES_TILES.size)

    /** Objects of the frustum-visible set, bucketed by camera distance. */
    val probeObjectsByDistance = IntArray(PROBE_DISTANCE_EDGES_TILES.size)

    /**
     * Upper edge, in screen pixels, of each [probeTrianglesByPixels] bucket.
     *
     * The distance histogram says where the triangles are, but not whether they are worth
     * drawing: an object 200 tiles away is only cuttable if it is also small on screen.
     * This measures the projected height of each visible object's bounding box, which is
     * the quantity any distance cutoff or LOD switch would really be keyed on.
     */
    val PROBE_PIXEL_EDGES = intArrayOf(1, 2, 4, 8, 16, 32, 64, Int.MAX_VALUE)

    /** Triangles of frustum-visible objects, bucketed by projected screen height. */
    val probeTrianglesByPixels = LongArray(PROBE_PIXEL_EDGES.size)

    /** Objects behind [probeTrianglesByPixels], same bucketing. */
    val probeObjectsByPixels = IntArray(PROBE_PIXEL_EDGES.size)

    /** Upper edge, in triangles, of each [probeObjectsBySize] bucket. */
    val PROBE_SIZE_EDGES = intArrayOf(16, 64, 256, 1024, 4096, Int.MAX_VALUE)

    /** Frustum-visible objects bucketed by their own triangle count. */
    val probeObjectsBySize = IntArray(PROBE_SIZE_EDGES.size)

    /** Triangles behind [probeObjectsBySize], same bucketing. */
    val probeTrianglesBySize = LongArray(PROBE_SIZE_EDGES.size)

    // ====================================================================
    // Timing counters (per frame, in milliseconds)
    // ====================================================================

    /** Time spent computing object transforms (ms), per frame. */
    var transformTimeMs = 0.0

    /** Time spent uploading instance data to the GPU (ms), per frame. */
    var uploadTimeMs = 0.0

    /** Total render time for the frame (ms). */
    var renderTimeMs = 0.0

    /** Time spent in the sky pass (ms), per frame. */
    var skyTimeMs = 0.0

    /**
     * Time spent in the buffer swap (ms).
     *
     * Deliberately not cleared by [resetFrame]: the swap happens after the frame's render,
     * so this is always the previous frame's figure by the time anything reads it.
     */
    var presentTimeMs = 0.0

    /**
     * Time the optional `-Ddrynor.gpuSync=true` probe blocked for (ms), per frame.
     *
     * Zero when the probe is off. When it is on this is the wait for the previous frame to
     * finish and present, drained up front so it cannot hide inside a draw call — see the
     * viewer's `gpuSyncProbe` for why that matters when reading [renderTimeMs].
     */
    var gpuSyncWaitMs = 0.0

    // ====================================================================
    // CPU phase breakdown (per frame, in milliseconds)
    // ====================================================================

    const val CPU_PHASE_UNIFORMS = 0
    const val CPU_PHASE_CULL = 1
    const val CPU_PHASE_OBJECT_SCENE = 2
    const val CPU_PHASE_TERRAIN_MERGED = 3
    const val CPU_PHASE_TERRAIN_REGIONS = 4
    const val CPU_PHASE_OVERLAYS = 5
    const val CPU_PHASE_OBJECTS = 6
    const val CPU_PHASE_STATIC_BATCH = 7
    const val CPU_PHASE_SKY = 8

    /** Display names for the CPU phases, in phase-index order. */
    val CPU_PHASE_NAMES = listOf(
        "uniforms",
        "cull",
        "objectScene",
        "terrainMerged",
        "terrainRegions",
        "overlays",
        "objects",
        "staticBatch",
        "sky"
    )

    /**
     * Wall-clock milliseconds spent in each phase of `renderScene` on the render thread.
     *
     * Purely a CPU measurement: GL calls are asynchronous, so a phase's number is the cost
     * of building and submitting its work, not of the GPU executing it. Read alongside
     * [gpuPassMs], which measures the other half.
     */
    val cpuPhaseMs = DoubleArray(CPU_PHASE_NAMES.size)

    /** Accumulate [startNanos]..now into a [cpuPhaseMs] slot. */
    fun endPhase(phase: Int, startNanos: Long) {
        cpuPhaseMs[phase] += (System.nanoTime() - startNanos) / 1_000_000.0
    }

    // ====================================================================
    // GPU pass timing (per frame, in milliseconds, several frames stale)
    // ====================================================================

    /**
     * Per-pass GPU milliseconds from [GpuTimerPool], indexed by the pass constants below.
     *
     * These come from `GL_TIME_ELAPSED` queries harvested a few frames after they were
     * issued, so like the culling counters they describe a recent frame rather than the
     * one being drawn.
     */
    val gpuPassMs = DoubleArray(GPU_PASS_NAMES.size)

    /** Sum of [gpuPassMs] — the instrumented part of the GPU frame, not the whole of it. */
    val gpuTotalMs: Double get() = gpuPassMs.sum()

    /** True when GPU timer queries are running. */
    var gpuTimingActive = false

    // ====================================================================
    // Load-time counters (persist across frames, updated on region load)
    // ====================================================================

    /** Time to generate terrain meshes during the most recent region load (ms). */
    var terrainLoadTimeMs = 0.0

    /** Time to load and register objects during the most recent region load (ms). */
    var objectLoadTimeMs = 0.0

    // ====================================================================
    // Atlas stats (persist across frames, updated on region load)
    // ====================================================================

    /** Terrain atlas: number of textures packed. */
    var terrainAtlasTextures = 0

    /** Terrain atlas: number of pages used. */
    var terrainAtlasPages = 0

    /** Terrain atlas: utilization percentage (0-100). */
    var terrainAtlasUtilization = 0f

    /** Object atlas: number of textures packed. */
    var objectAtlasTextures = 0

    /** Object atlas: number of pages used. */
    var objectAtlasPages = 0

    /** Object atlas: utilization percentage (0-100). */
    var objectAtlasUtilization = 0f

    // ====================================================================
    // Async loading counters (per frame)
    // ====================================================================

    /** Time spent draining the GPU upload queue this frame (ms). */
    var gpuUploadDrainTimeMs = 0.0

    /** Number of GPU upload tasks processed this frame. */
    var gpuUploadTasksProcessed = 0

    /** Number of pending GPU upload tasks (snapshot). */
    var pendingGpuTasks = 0

    /** Number of regions currently loading in background. */
    var regionsLoading = 0

    /** Number of regions fully loaded and ready for rendering. */
    var regionsReady = 0

    // ====================================================================
    // Methods
    // ====================================================================

    /**
     * Reset all per-frame counters. Call at the start of each frame.
     */
    fun resetFrame() {
        terrainOpaqueDrawCalls = 0
        terrainAlphaDrawCalls = 0
        instancedDrawCalls = 0
        indirectDrawCalls = 0
        indirectCommands = 0
        staticBatchDrawCalls = 0
        skyDrawCalls = 0
        trianglesSubmitted = 0
        instanceCount = 0
        staticBatchMeshCount = 0
        staticBatchTriangles = 0
        batchesConsidered = 0
        batchesCulled = 0
        batchRangesConsidered = 0
        batchRangesCulled = 0
        staticBatchRanges = 0
        batchRegionsConsidered = 0
        batchRegionsCulled = 0
        contextRegionsConsidered = 0
        contextRegionsCulled = 0
        terrainMergedRegionsTotal = 0
        terrainMergedRegionsDrawn = 0
        terrainAtlasOverflowDrawCalls = 0
        cullCpuTimeMs = 0.0
        probeObjectsTotal = 0
        probeObjectsVisible = 0
        probeTrianglesTotal = 0L
        probeTrianglesVisible = 0L
        probeCpuMs = 0.0
        probeTrianglesByDistance.fill(0L)
        probeObjectsByDistance.fill(0)
        probeObjectsBySize.fill(0)
        probeTrianglesBySize.fill(0L)
        probeObjectsByPixels.fill(0)
        probeTrianglesByPixels.fill(0L)
        transformTimeMs = 0.0
        uploadTimeMs = 0.0
        renderTimeMs = 0.0
        skyTimeMs = 0.0
        gpuUploadDrainTimeMs = 0.0
        gpuUploadTasksProcessed = 0
        cpuPhaseMs.fill(0.0)
    }

    /**
     * One-line-per-topic dump of the current frame's counters.
     *
     * Printed on a timer when `-Ddrynor.metricsEvery=<frames>` is set. The debug panel
     * shows the same numbers, but a headless capture run cannot scroll it, and stdout is a
     * far more reliable place to read a measurement from than a screenshot.
     */
    fun summary(fps: Int): String = buildString {
        appendLine("---- RenderMetrics ----")
        appendLine("fps=$fps  cpuRender=%.3fms cpuTransform=%.3fms cpuUpload=%.3fms cpuCull=%.3fms present=%.3fms gpuSyncWait=%.3fms"
            .format(renderTimeMs, transformTimeMs, uploadTimeMs, cullCpuTimeMs, presentTimeMs, gpuSyncWaitMs))
        appendLine("draws: terrainOpaque=$terrainOpaqueDrawCalls terrainAlpha=$terrainAlphaDrawCalls " +
                "indirect=$indirectDrawCalls (cmds=$indirectCommands) instanced=$instancedDrawCalls " +
                "staticBatch=$staticBatchDrawCalls sky=$skyDrawCalls total=$totalDrawCalls")
        appendLine("geometry: triangles=$trianglesSubmitted batchTriangles=$staticBatchTriangles " +
                "batchRanges=$staticBatchRanges instances=$instanceCount batchMeshes=$staticBatchMeshCount")
        appendLine("terrain: mergedRegions=$terrainMergedRegionsDrawn/$terrainMergedRegionsTotal " +
                "atlasOverflowDraws=$terrainAtlasOverflowDrawCalls")
        appendLine("cull: active=$cullActive items=$cullVisibleItemsStale/$cullItemsTotal " +
                "visibleTris=$cullVisibleTrianglesStale (stale ${cullStatsAgeFrames}f) " +
                "regions=${contextRegionsConsidered - contextRegionsCulled}/$contextRegionsConsidered " +
                "batches=${batchesConsidered - batchesCulled}/$batchesConsidered " +
                "ranges=${batchRangesConsidered - batchRangesCulled}/$batchRangesConsidered " +
                "batchRegions=${batchRegionsConsidered - batchRegionsCulled}/$batchRegionsConsidered")
        append("cpu: ")
        for (phase in CPU_PHASE_NAMES.indices) {
            append("%s=%.3f  ".format(CPU_PHASE_NAMES[phase], cpuPhaseMs[phase]))
        }
        appendLine("SUM=%.3f ms".format(cpuPhaseMs.sum()))
        if (gpuTimingActive) {
            append("gpu: ")
            for (pass in GPU_PASS_NAMES.indices) {
                append("%s=%.3f  ".format(GPU_PASS_NAMES[pass], gpuPassMs[pass]))
            }
            appendLine("TOTAL=%.3f ms".format(gpuTotalMs))
        } else {
            appendLine("gpu: timing unavailable")
        }
    }

    /**
     * Convenience: start a timing section. Returns the nanoTime.
     */
    fun startTiming(): Long = System.nanoTime()

    /**
     * Convenience: end a timing section and return elapsed milliseconds.
     */
    fun endTiming(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0
}
