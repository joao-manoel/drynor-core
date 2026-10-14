package org.darkan.tools.engine.render

/**
 * Accounting for what an [IndirectRenderer] rebuild actually re-does that the previous build
 * had already done.
 *
 * The renderer is thrown away and rebuilt from scratch on every navigation jump: every model
 * of the new centre region is re-registered into fresh shared buffers and every material it
 * references is regenerated into fresh array textures. Some of that is genuinely new work —
 * a region the camera has never seen brings models and materials the GPU has never held. The
 * rest is the same geometry and the same layers uploaded again under new GL names.
 *
 * This object measures the split, so the question "is reusing the renderer across jumps worth
 * the refactor?" is answered by a reusable fraction and a phase cost rather than by intuition.
 * It is the same trade [org.darkan.tools.cache.texture.TexturePixelStats] makes: **ids and
 * counts only, never geometry and never pixels**, so measuring the path cannot change the cost
 * of the path being measured.
 *
 * Two baselines are reported, and they answer different questions:
 * - *vs previous build* is what a renderer that only remembered the last jump could skip.
 * - *vs all builds so far* is the ceiling for one that remembers everything it has ever
 *   uploaded, which is the design the refactor would actually produce.
 */
object IndirectReuseStats {

    /** `-Ddarkan.irReuseStats=true` turns the accounting on. Off by default. */
    val ENABLED: Boolean = System.getProperty("darkan.irReuseStats", "false").toBoolean()

    /** Vertex and index counts a single model contributed to the shared buffers. */
    private class ModelSize(val vertices: Int, val indices: Int)

    /** What one build registered, by id. No geometry and no pixels are retained. */
    private class Build {
        val models = HashMap<Int, ModelSize>()
        val materialSizes = HashMap<Int, Int>()
        var vertices = 0L
        var indices = 0L
    }

    /** The build currently being registered, or null outside a build. */
    private var current: Build? = null

    /** The last completed build, for the "vs previous" baseline. */
    private var previous: Build? = null

    /** Every model id ever registered, with the size it contributed. */
    private val everModels = HashMap<Int, ModelSize>()

    /** Every material id ever turned into an array layer, with its edge length. */
    private val everMaterials = HashMap<Int, Int>()

    private var buildIndex = 0

    /** Bytes per vertex in the shared buffers, set by the renderer so the two agree. */
    var vertexBytes: Int = 36

    fun beginBuild() {
        if (!ENABLED) return
        current = Build()
    }

    /** Record that [modelId] contributed [vertices] vertices and [indices] indices. */
    fun recordModel(modelId: Int, vertices: Int, indices: Int) {
        val build = current ?: return
        // A model registers once per build; its mesh batches accumulate into one entry.
        val existing = build.models[modelId]
        build.models[modelId] = if (existing == null) ModelSize(vertices, indices)
        else ModelSize(existing.vertices + vertices, existing.indices + indices)
        build.vertices += vertices
        build.indices += indices
    }

    /** Record that [materialId] became an array layer of [size] x [size] texels. */
    fun recordMaterial(materialId: Int, size: Int) {
        val build = current ?: return
        build.materialSizes[materialId] = size
    }

    /**
     * Close the current build and print the reuse split against both baselines.
     *
     * @param geometryUploadMs wall of the shared VBO/IBO upload
     * @param registerMs wall of the accumulate loop that fed it
     * @param texCpuMs wall of the CPU row-flip that packs array layers
     * @param texUploadMs wall of the `glTexImage3D`/`glTexSubImage3D` calls
     * @param texMipmapMs wall of `glGenerateMipmap` across all array textures
     * @param texGenMs wall of material pixel generation (memoised; reported for context)
     * @param drainMs wall of the `glFinish` that follows the build, i.e. whatever the driver
     *        deferred past the upload calls the phase walls above measured
     * @param registerCpuMs CPU time of the accumulate loop
     * @param geometryUploadCpuMs CPU time of the shared geometry upload
     * @param texArrayCpuMs CPU time of the whole array-texture build
     */
    fun finishBuild(
        drainMs: Double,
        geometryUploadMs: Double,
        registerMs: Double,
        texCpuMs: Double,
        texUploadMs: Double,
        texMipmapMs: Double,
        texGenMs: Double,
        registerCpuMs: Double,
        geometryUploadCpuMs: Double,
        texArrayCpuMs: Double
    ) {
        val build = current ?: return
        current = null
        buildIndex++

        val prev = previous
        var prevSharedModels = 0
        var prevSharedVertices = 0L
        for ((id, size) in build.models) {
            if (prev != null && prev.models.containsKey(id)) {
                prevSharedModels++
                prevSharedVertices += size.vertices
            }
        }

        var everSharedModels = 0
        var everSharedVertices = 0L
        for ((id, size) in build.models) {
            if (everModels.containsKey(id)) {
                everSharedModels++
                everSharedVertices += size.vertices
            }
        }

        var prevSharedMaterials = 0
        var everSharedMaterials = 0
        val newLayerBytesEver = HashMap<Int, Long>()
        var totalLayerBytes = 0L
        for ((id, size) in build.materialSizes) {
            val bytes = size.toLong() * size * 4
            totalLayerBytes += bytes
            if (prev != null && prev.materialSizes.containsKey(id)) prevSharedMaterials++
            if (everMaterials.containsKey(id)) everSharedMaterials++
            else newLayerBytesEver[size] = (newLayerBytesEver[size] ?: 0L) + bytes
        }

        val geometryBytes = build.vertices * vertexBytes + build.indices * Int.SIZE_BYTES
        val everNewVertices = build.vertices - everSharedVertices
        val everNewLayerBytes = newLayerBytesEver.values.sum()

        // The ceiling on what reuse can give back: the phase's wall scaled by the share of
        // its input that a renderer remembering every previous build would not have to touch
        // again. It is a ceiling and not an estimate -- an append-based rebuild still has to
        // grow and re-bind buffers, which this credits as free.
        // Deliberately CPU time and not wall: the build runs while the loader pool holds most
        // of the machine, so a phase's wall includes waiting that not doing the work cannot
        // give back. Only the CPU figure is a saving reuse could actually bank.
        val geometryPhaseMs = geometryUploadCpuMs + registerCpuMs
        val geomCeilingMs =
            if (build.vertices > 0) geometryPhaseMs * everSharedVertices / build.vertices else 0.0
        val texPhaseMs = texArrayCpuMs
        val texCeilingMs =
            if (totalLayerBytes > 0) texPhaseMs * (totalLayerBytes - everNewLayerBytes) / totalLayerBytes
            else 0.0
        val geometryWallMs = geometryUploadMs + registerMs
        val texWallMs = texCpuMs + texUploadMs + texMipmapMs

        val sizeBreakdown = build.materialSizes.entries
            .groupingBy { it.value }.eachCount().entries
            .sortedBy { it.key }
            .joinToString { "${it.key}px x${it.value}" }

        println(
            "[IR-REUSE] build=%d models=%d verts=%d geomKiB=%d | vsPrev models=%d/%d (%.0f%%) verts %.0f%% mats=%d/%d | vsEver models=%d/%d (%.0f%%) verts %.0f%% mats=%d/%d"
                .format(
                    buildIndex, build.models.size, build.vertices, geometryBytes / 1024,
                    prevSharedModels, build.models.size,
                    pct(prevSharedModels.toLong(), build.models.size.toLong()),
                    pct(prevSharedVertices, build.vertices),
                    prevSharedMaterials, build.materialSizes.size,
                    everSharedModels, build.models.size,
                    pct(everSharedModels.toLong(), build.models.size.toLong()),
                    pct(everSharedVertices, build.vertices),
                    everSharedMaterials, build.materialSizes.size
                )
        )
        println(
            "[IR-REUSE] build=%d phases: register=%.1f upload=%.1f (%.0f MB/s) texCpu=%.1f texUpload=%.1f (%.0f MB/s) texMipmap=%.1f texGen=%.1f drain=%.1f ms; layers %s = %d KiB, new %d KiB"
                .format(
                    buildIndex, registerMs, geometryUploadMs,
                    rate(geometryBytes, geometryUploadMs),
                    texCpuMs, texUploadMs, rate(totalLayerBytes, texUploadMs),
                    texMipmapMs, texGenMs, drainMs,
                    sizeBreakdown, totalLayerBytes / 1024, everNewLayerBytes / 1024
                )
        )
        println(
            "[IR-REUSE] build=%d cpu: register=%.1f upload=%.1f texArray=%.1f ms (walls %.1f / %.1f)"
                .format(
                    buildIndex, registerCpuMs, geometryUploadCpuMs, texArrayCpuMs,
                    geometryWallMs, texWallMs
                )
        )
        println(
            "[IR-REUSE] build=%d ceiling(cpu): geometry %.1f of %.1f ms, arrayTex %.1f of %.1f ms, total %.1f ms avoidable; genuinely new %d verts / %d KiB layers"
                .format(
                    buildIndex, geomCeilingMs, geometryPhaseMs, texCeilingMs, texPhaseMs,
                    geomCeilingMs + texCeilingMs, everNewVertices, everNewLayerBytes / 1024
                )
        )

        for ((id, size) in build.models) {
            val existing = everModels[id]
            if (existing == null) everModels[id] = size
        }
        for ((id, size) in build.materialSizes) everMaterials.putIfAbsent(id, size)
        previous = build
    }

    private fun pct(part: Long, whole: Long): Double =
        if (whole > 0) 100.0 * part / whole else 0.0

    private fun rate(bytes: Long, ms: Double): Double =
        if (ms > 0) bytes / (ms / 1000.0) / 1e6 else 0.0
}
