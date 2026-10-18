package org.drynor.tools.engine.render

import org.drynor.tools.cache.texture.TexturePixelStats
import org.joml.Matrix4f
import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL12.glTexSubImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
import org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL33.glVertexAttribDivisor
import org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.glCopyImageSubData
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect
import org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT
import org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.system.MemoryUtil
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Multi-Draw Indirect renderer that packs all registered model geometries into
 * a single shared VBO/IBO and issues a handful of `glMultiDrawElementsIndirect`
 * calls per frame instead of one draw per model/material pair.
 *
 * Architecture:
 * - All mesh vertex data is packed into a single shared VBO at registration time.
 * - All mesh index data is packed into a single shared IBO, addressed with baseVertex offsets.
 * - Per-frame instance data (model matrices + texture layer) is streamed into an SSBO.
 * - `DrawElementsIndirectCommand` structs are built per frame in a GL_DRAW_INDIRECT_BUFFER.
 * - Textured objects sample a `sampler2DArray`; each distinct material becomes one layer.
 *
 * ### Why array textures and not an atlas
 * RS model UVs routinely run outside `[0,1]` and rely on `GL_REPEAT` to tile. Remapping
 * those UVs into an atlas sub-rectangle breaks tiling and samples neighbouring textures,
 * so this renderer gives every material its own array layer instead. Wrapping applies
 * per-layer, so `GL_REPEAT` behaves exactly as it does for the individual 2D textures the
 * instanced path binds — the pixels and sampler state are identical, only the binding changes.
 * Array textures require every layer to share one size, so materials are bucketed by size
 * (RS uses 128x128 and 64x64), costing one extra draw call per distinct size.
 *
 * Requires OpenGL 4.3 (SSBO + `glMultiDrawElementsIndirect`) and `gl_BaseInstance`
 * (GL 4.6 / ARB_shader_draw_parameters). Use [isSupported] before constructing.
 */
class IndirectRenderer(
    /** Provider for material pixel data used to build the array textures. */
    private val texturePixelProvider: TexturePixelProvider? = null
) {
    // ====================================================================
    // Shared geometry buffer
    // ====================================================================

    /** VAO for the shared geometry. */
    private var sharedVaoId: Int = 0

    /** Shared IBO for all index data. */
    private var sharedIboId: Int = 0

    /** Current total vertex count in the shared VBO. */
    private var totalVertexCount: Int = 0

    /** Current total index count in the shared IBO. */
    private var totalIndexCount: Int = 0

    // Accumulators for vertex/index data during registration phase.
    // Cleared after buildSharedGeometry() uploads to GPU.
    private val positionAccumulator = mutableListOf<FloatArray>()
    private val colorAccumulator = mutableListOf<FloatArray>()
    private val texCoordAccumulator = mutableListOf<FloatArray?>()
    private val normalAccumulator = mutableListOf<FloatArray>()
    private val indexAccumulator = mutableListOf<IntArray>()

    // ====================================================================
    // Per-mesh metadata (registered at model registration time)
    // ====================================================================

    /**
     * Metadata for a single mesh batch within the shared geometry buffer.
     * Stores the offsets needed for a DrawElementsIndirectCommand.
     */
    private class MeshEntry(
        /** Number of indices for this mesh. */
        val indexCount: Int,
        /** Offset (in indices) into the shared IBO. */
        val firstIndex: Int,
        /** Offset (in vertices) into the shared VBO. */
        val baseVertex: Int,
        /** Material id for textured meshes, or -1 when the mesh is colour-only. */
        val textureId: Int,
        /**
         * Model-space AABB of this batch as `[minX, minY, minZ, maxX, maxY, maxZ]`.
         *
         * Captured at registration so a per-instance world bound can be derived from the
         * instance transform alone, once, instead of touching vertex data per frame.
         */
        val localBounds: FloatArray,
        /** Vertices this batch contributed to the shared VBO. Accounting only. */
        val vertexCount: Int
    ) {
        /** Index of the array texture holding this mesh's material; -1 until resolved. */
        var textureArrayIndex: Int = -1

        /** Layer within [textureArrayIndex]; -1 until resolved. */
        var textureLayer: Int = -1
    }

    /** Resolved batch for a model, referencing one or more MeshEntry objects. */
    private class ResolvedModelBatch(val meshEntryIndices: IntArray)

    /** All registered mesh entries. Index into this list is the meshEntryId. */
    private val meshEntries = mutableListOf<MeshEntry>()

    /** Model ID -> list of mesh entry indices. */
    private val modelRegistry = mutableMapOf<Int, ResolvedModelBatch>()

    // ====================================================================
    // Missing-model accounting
    // ====================================================================

    /**
     * Model ids this renderer was asked to draw without ever having been given their
     * geometry, in first-sighting order.
     *
     * Kept for the renderer's lifetime so each id is only logged once, and so a caller
     * that knows where models come from can look up why these particular ones are absent.
     */
    private val droppedModelIds = LinkedHashSet<Int>()

    /**
     * Instances discarded since the current submission began — the frame on the streaming
     * path, the scene on the static path.
     *
     * Dropping an instance whose model is unregistered is not a recoverable condition to
     * be handled quietly: it puts a hole in the scene exactly where that object stood.
     * Every drop is counted here and surfaced in the debug panel.
     */
    var droppedInstanceCount = 0
        private set

    /** Number of distinct models that have been dropped. */
    val droppedModelIdCount: Int get() = droppedModelIds.size

    /** The dropped ids, so callers can report what they know about each one. */
    fun droppedModelIdList(): List<Int> = droppedModelIds.toList()

    /**
     * Record one discarded instance and, the first time a given model is seen missing,
     * say so on stderr.
     */
    private fun noteMissingModel(modelId: Int, site: String) {
        droppedInstanceCount++
        if (!droppedModelIds.add(modelId)) return
        if (droppedModelIds.size <= MAX_DROP_LOG_LINES) {
            System.err.println(
                "[IndirectRenderer] $site: model $modelId has no registered geometry, " +
                        "its instances will not be drawn"
            )
        } else if (droppedModelIds.size == MAX_DROP_LOG_LINES + 1) {
            System.err.println(
                "[IndirectRenderer] further missing models suppressed; see the dropped " +
                        "instance counter in the debug panel for the running total"
            )
        }
    }

    // ====================================================================
    // Streaming buffers for dynamic per-frame data
    // ====================================================================

    /**
     * Streaming buffers, created lazily on first upload and grown to fit actual demand.
     *
     * Sizing them up front for a worst-case instance count wastes tens of megabytes of
     * mapped, resident memory for a scene that submits a few thousand instances, so the
     * capacities below track what the scene really submits.
     */
    private var colorSsbo: PersistentBuffer? = null
    private var texturedSsbo: PersistentBuffer? = null
    private var colorIndirectBuffer: PersistentBuffer? = null
    private var texturedIndirectBuffer: PersistentBuffer? = null

    /** Instance capacity currently backing [colorSsbo] / [texturedSsbo]. */
    private var colorInstanceCapacity = 0
    private var texturedInstanceCapacity = 0

    /** Command capacity currently backing the indirect buffers. */
    private var colorCommandCapacity = 0
    private var texturedCommandCapacity = 0

    // ====================================================================
    // Array textures
    // ====================================================================

    /**
     * One GL_TEXTURE_2D_ARRAY per distinct material size.
     *
     * @param glId   GL texture name
     * @param size   Edge length of every layer in this array
     * @param layers Number of layers actually populated
     */
    private class ArrayTexture(val glId: Int, val size: Int, val layers: Int)

    private val arrayTextures = mutableListOf<ArrayTexture>()

    /**
     * Material id -> packed `(arrayIndex shl 16) or layer`, for every material that has been
     * given an array-texture slot.
     *
     * Held for the renderer's lifetime rather than being a local of [buildTextureArrays],
     * because [registerModelLate] has to be able to tell a material that already occupies a
     * layer from one that still needs one.
     */
    private val materialSlots = HashMap<Int, Int>()

    /**
     * Whether every array texture fits in the shader's sampler set, letting the textured
     * pass be a single draw ordered by object rather than one draw per material size.
     */
    private val singleTexturedDraw: Boolean
        get() = arrayTextures.size in 1..MAX_TEXTURE_ARRAYS

    /** Materials that had no pixel data and were given a placeholder layer. */
    private var missingTextureCount = 0

    // ====================================================================
    // Per-frame state
    // ====================================================================

    // Frame instance data accumulators (mesh entry id -> instance matrices)
    private val colorInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()
    private val texturedInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()

    /** Command ranges within the textured indirect buffer, one per array texture. */
    private class TexturedBucket(var firstCommand: Int = 0, var commandCount: Int = 0)

    private var texturedBuckets = emptyArray<TexturedBucket>()

    // Stats
    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0
    private var commandsThisFrame = 0
    private var initialized = false
    private var geometryBuilt = false

    private var colorCommandCount = 0
    private var colorInstanceBytes = 0L
    private var texturedCommandCount = 0
    private var texturedInstanceBytes = 0L

    // ====================================================================
    // Constants
    // ====================================================================

    companion object {
        /** Size of DrawElementsIndirectCommand in bytes: 5 uints * 4 bytes = 20 bytes. */
        private const val INDIRECT_COMMAND_SIZE = 20

        /**
         * Array textures the textured shader can have bound at once; must match
         * `MAX_TEXTURE_ARRAYS` in `indirect_textured.frag`.
         *
         * With every array bound, a batch selects its own through the instance record and the
         * whole textured pass is a single draw whose command order is free. That freedom is
         * the point: the pass blends with depth writes on, so its result depends on
         * submission order, and only an unsplit pass can be ordered by object. RS uses two
         * material sizes, so four is already generous; more than this falls back to one draw
         * per array, which is what this renderer did before.
         */
        const val MAX_TEXTURE_ARRAYS = 4

        /** Sampler uniform names the textured shader declares, for [ShaderProgram]. */
        fun textureArrayUniformNames(): Array<String> =
            Array(MAX_TEXTURE_ARRAYS) { "textureArrays[$it]" }

        /**
         * Whether the shared colour VBO is quantised to normalized bytes; see
         * [uploadColorAttribute]. `-Ddrynor.indirectByteColors=false` restores float colours.
         *
         * Deliberately its own switch rather than [MeshBuffers.packColorBytes]: the two paths
         * own separate buffers and had to be measured separately.
         */
        private val QUANTISE_COLORS: Boolean =
            System.getProperty("drynor.indirectByteColors") != "false"

        /** Thread CPU clock for [threadCpuNanos]; measurement only. */
        private val THREAD_MX = ManagementFactory.getThreadMXBean()

        private val CPU_TIME_SUPPORTED: Boolean =
            THREAD_MX.isCurrentThreadCpuTimeSupported && THREAD_MX.isThreadCpuTimeEnabled

        /** Diagnostic: `-Ddrynor.mdiAudit=true` reports how geometry mapped to draws. */
        private val AUDIT: Boolean = System.getProperty("drynor.mdiAudit", "false").toBoolean()

        /**
         * Diagnostic: `-Ddrynor.mdiReverse=true` reverses the textured command order.
         *
         * Draws the same commands with the same instances in the opposite sequence. Any
         * pixel that moves is a pixel whose value depends on the order the blended object
         * geometry was submitted in.
         */
        private val REVERSE_COMMANDS: Boolean = System.getProperty("drynor.mdiReverse", "false").toBoolean()

        /** Size of textured instance data: mat4 (64 bytes) + ivec4 (16 bytes) = 80 bytes. */
        private const val TEXTURED_INSTANCE_STRIDE = 80

        /**
         * Size of untextured instance data.
         *
         * Identical to [TEXTURED_INSTANCE_STRIDE]: both indirect vertex shaders declare the
         * same `InstanceInfo` struct so that the static path can serve them from one
         * instance buffer, and the streaming path has to match that declaration. The
         * colour shader never reads the trailing texture layer.
         */
        private const val COLOR_INSTANCE_STRIDE = TEXTURED_INSTANCE_STRIDE

        /** SSBO binding point for instance data. */
        private const val INSTANCE_SSBO_BINDING = 1

        /** SSBO binding for the visible-instance indirection, read by the vertex shaders. */
        private const val VISIBLE_SSBO_BINDING = 2

        /** SSBO bindings used only by the culling compute passes. */
        private const val CULL_BOUNDS_BINDING = 3
        private const val CULL_ITEM_META_BINDING = 4
        private const val CULL_COMMAND_BINDING = 5
        private const val CULL_STATS_BINDING = 6

        /** Compute workgroup size; must match `local_size_x` in the cull shaders. */
        private const val COMPUTE_GROUP_SIZE = 64

        /**
         * Slots in the debug-counter ring. Four gives the GPU three frames to finish a slot
         * before the CPU samples it, so sampling never has to wait.
         */
        private const val STATS_SLOTS = 4

        /** Counters per ring slot: visible items, then visible triangles. */
        private const val STATS_UINTS_PER_SLOT = 2

        /**
         * `-Ddrynor.cullStats=true` retrieves the visible-instance counters unconditionally.
         *
         * Off by default, because retrieval is not free: the device-to-host copy is a
         * handful of bytes but the buffer-update barrier it needs costs around 0.4 ms of
         * GPU time on this driver — an order of magnitude more than the culling dispatch it
         * reports on (see [publishAndSampleStats]). Leaving it on by default meant every
         * run paid for a debug readout nobody was looking at.
         *
         * The counters themselves are always accumulated; the compute pass writes them
         * whether or not anyone reads them. Only the readback is gated, and the debug panel
         * turns it on for itself via [requestCullStats] while the culling section is on
         * screen, so the numbers are there when something is actually displaying them.
         */
        private val STATS_FORCED: Boolean = System.getProperty("drynor.cullStats", "false").toBoolean()

        /**
         * Frames a [requestCullStats] call keeps the readback alive for.
         *
         * Comfortably longer than [STATS_SAMPLE_INTERVAL] so a panel drawn every frame never
         * lets its request lapse between two samples, and short enough that closing the
         * panel stops paying the barrier within about a second.
         */
        private const val STATS_REQUEST_LINGER = 60

        @Volatile
        private var statsRequestFramesLeft = 0

        /**
         * Ask for the culling counters to be retrieved over the next [STATS_REQUEST_LINGER]
         * frames. Call once per frame from whatever is displaying them.
         */
        fun requestCullStats() {
            statsRequestFramesLeft = STATS_REQUEST_LINGER
        }

        /**
         * Frames between counter retrievals. Roughly four panel updates a second, and the
         * per-frame cost of the readback amortises to near nothing.
         */
        private const val STATS_SAMPLE_INTERVAL = 15

        /**
         * Instance stride of the static scene: `mat4` + `ivec4`.
         *
         * Colour-only items pay the extra 16 bytes for a texture layer they never sample,
         * which is a rounding error against sharing one buffer set (and therefore one pair
         * of compute dispatches) with the textured items.
         */
        private const val INSTANCE_STRIDE = TEXTURED_INSTANCE_STRIDE

        /**
         * Multiplier on every instance bounding radius.
         *
         * The bound is already conservative, but the plane test runs in float and the cost
         * of being marginally too generous is a handful of extra instances, while the cost
         * of being marginally too tight is geometry visibly popping at the screen edge.
         *
         * The 2% itself is a tuned value, not a derived one: it is the smallest margin that
         * showed no popping at the screen edge in testing, rounded up. Nothing in the client
         * or in the projection maths pins it.
         */
        private const val BOUND_SLACK = 1.02f

        /** Growth granularity so small frame-to-frame changes do not reallocate. */
        private const val CAPACITY_GRANULARITY = 1024

        /** Distinct missing models named on stderr before the log switches to a summary. */
        private const val MAX_DROP_LOG_LINES = 16

        /**
         * True when the context can run the culling compute shaders.
         *
         * Tests capabilities rather than `glGetString(GL_VERSION)`: the window is created
         * with a 3.3 core context hint, so the reported version routinely sits below what
         * the driver actually exposes and a version check would disable culling on a 4.6
         * driver.
         */
        fun isComputeSupported(): Boolean {
            val caps = GL.getCapabilities()
            return caps.OpenGL43 || caps.GL_ARB_compute_shader
        }

        /** Workgroups needed to cover [items] invocations. */
        private fun dispatchGroups(items: Int): Int =
            (items + COMPUTE_GROUP_SIZE - 1) / COMPUTE_GROUP_SIZE

        /** Model-space AABB of [positions] as `[minX, minY, minZ, maxX, maxY, maxZ]`. */
        private fun computeLocalBounds(positions: FloatArray): FloatArray {
            if (positions.size < 3) return FloatArray(0)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i + 2 < positions.size) {
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
                i += 3
            }
            return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
        }

        /** Allocate a GL buffer from raw bytes. */
        private fun createBuffer(target: Int, data: ByteBuffer, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        /** Allocate a GL buffer from ints. */
        private fun createIntBuffer(target: Int, data: IntArray, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        /** Allocate a GL buffer from floats. */
        private fun createFloatBuffer(target: Int, data: FloatArray, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        /**
         * True when the current GL context can run this renderer.
         *
         * `glMultiDrawElementsIndirect` and SSBOs need GL 4.3; the vertex shader additionally
         * needs `gl_BaseInstance`, which is core in 4.6 and otherwise comes from
         * ARB_shader_draw_parameters.
         */
        fun isSupported(): Boolean {
            val caps = GL.getCapabilities()
            // The window is created with a 3.3 core context hint, so the reported context
            // version can sit below the features the driver actually exposes. Test the
            // individual capabilities rather than a version number.
            val hasIndirect = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect
            val hasSsbo = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object
            val hasDrawParams = caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters
            if (!hasIndirect || !hasSsbo || !hasDrawParams) {
                println(
                    "[IndirectRenderer] unsupported GL context: " +
                            "version=${glGetString(GL_VERSION)} multiDrawIndirect=$hasIndirect " +
                            "ssbo=$hasSsbo shaderDrawParameters=$hasDrawParams"
                )
                return false
            }
            return true
        }

        /** Round [needed] up to the next [CAPACITY_GRANULARITY] multiple, with 25% headroom. */
        private fun growCapacity(needed: Int): Int {
            val withHeadroom = needed + (needed / 4) + 1
            return ((withHeadroom + CAPACITY_GRANULARITY - 1) / CAPACITY_GRANULARITY) * CAPACITY_GRANULARITY
        }
    }

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Initialize GL resources for the indirect renderer.
     * Call once during setup after the OpenGL context is available.
     *
     * The streaming buffers are not created here: their size depends on how many instances
     * the scene actually submits, so they are allocated on the first frame that needs them.
     */
    fun init() {
        sharedVaoId = glGenVertexArrays()
        initialized = true
        IndirectReuseStats.beginBuild()
    }

    // ====================================================================
    // Model registration
    // ====================================================================

    /**
     * Register mesh data for a model ID, appending its geometry to the shared buffers.
     *
     * UVs are kept exactly as the model loader produced them; materials are resolved to
     * array-texture layers later, in [buildSharedGeometry].
     *
     * @param modelId The model identifier
     * @param meshDataList List of mesh data (one per texture batch)
     */
    fun registerModel(modelId: Int, meshDataList: List<MeshData>) {
        if (modelRegistry.containsKey(modelId)) return

        val registerStart = if (IndirectReuseStats.ENABLED) System.nanoTime() else 0L
        val registerCpuStart = if (IndirectReuseStats.ENABLED) threadCpuNanos() else 0L
        val entryIndices = mutableListOf<Int>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            val numVertices = meshData.positions.size / 3
            // Classify exactly as InstanceManager does: on the material id alone. A mesh with
            // a material but no UVs still goes down the textured path there, so it must here
            // too, or the two paths would shade the same geometry differently.
            val textureId = meshData.textureId

            val baseVertex = totalVertexCount
            val firstIndex = totalIndexCount

            positionAccumulator.add(meshData.positions)

            // Colors: use provided or default white
            colorAccumulator.add(meshData.colors ?: FloatArray(meshData.positions.size) { 1f })

            // Texture coordinates (null entries are padded with zeros at upload time)
            texCoordAccumulator.add(meshData.textureCoords)

            // Normals: use provided or default up
            normalAccumulator.add(meshData.normals ?: FloatArray(meshData.positions.size) { i ->
                when (i % 3) { 1 -> 1f; else -> 0f }
            })

            indexAccumulator.add(meshData.indices)

            totalVertexCount += numVertices
            totalIndexCount += meshData.indices.size

            entryIndices.add(meshEntries.size)
            meshEntries.add(MeshEntry(
                indexCount = meshData.indices.size,
                firstIndex = firstIndex,
                baseVertex = baseVertex,
                textureId = textureId,
                localBounds = computeLocalBounds(meshData.positions),
                vertexCount = numVertices
            ))
        }

        if (entryIndices.isNotEmpty()) {
            modelRegistry[modelId] = ResolvedModelBatch(entryIndices.toIntArray())
        }

        if (IndirectReuseStats.ENABLED) {
            var verts = 0
            var idx = 0
            for (entryIdx in entryIndices) {
                val entry = meshEntries[entryIdx]
                idx += entry.indexCount
                verts += entry.vertexCount
            }
            IndirectReuseStats.recordModel(modelId, verts, idx)
            registerAccumulateMs += (System.nanoTime() - registerStart) / 1e6
            registerAccumulateCpuMs += (threadCpuNanos() - registerCpuStart) / 1e6
        }
    }

    /**
     * Register a model *after* [buildSharedGeometry] has already packed and uploaded the
     * shared buffers, appending its geometry to them in place.
     *
     * The shared buffers are built once per region load from the set of models that region
     * uses, which makes every model the editor introduces afterwards — a placed object, a
     * variant of one — invisible: its instances reach [addStaticInstance] with no registered
     * geometry behind them and are dropped, silently leaving a hole exactly where the user
     * put something. This is the ingest that closes that gap.
     *
     * The geometry is appended rather than rebuilt. Each attribute VBO and the IBO are
     * reallocated one size larger, the existing contents copied device-to-device with
     * `glCopyBufferSubData`, and only the new tail uploaded from the CPU; the mesh entries
     * already handed out keep their `firstIndex`/`baseVertex`, so nothing that references
     * them has to be rebuilt. Materials the new geometry needs and the array textures do
     * not already hold are appended as extra layers the same way.
     *
     * Before the first [buildSharedGeometry] this is just [registerModel] — the accumulate
     * path is still open and the caller will upload everything at once.
     *
     * @return true when the model has registered geometry afterwards, whether it was
     *         already registered or was ingested here.
     */
    fun registerModelLate(modelId: Int, meshDataList: List<MeshData>): Boolean {
        if (modelRegistry.containsKey(modelId)) return true
        if (!geometryBuilt) {
            registerModel(modelId, meshDataList)
            return modelRegistry.containsKey(modelId)
        }

        val start = System.nanoTime()
        val firstNewEntry = meshEntries.size
        val baseVertex = totalVertexCount
        val baseIndex = totalIndexCount

        registerModel(modelId, meshDataList)
        if (!modelRegistry.containsKey(modelId)) {
            // Nothing in the list carried both positions and indices. Leave the accumulators
            // clean so the next late registration does not inherit a partial model.
            clearGeometryAccumulators()
            return false
        }

        appendSharedGeometry(baseVertex, baseIndex)
        resolveLateMaterials(firstNewEntry)

        // Once its geometry is here the id is no longer missing, so let it be reported again
        // if it ever does go missing later rather than staying permanently on the list.
        droppedModelIds.remove(modelId)

        println(
            "[IndirectRenderer] ingested model $modelId after the build: " +
                    "${meshEntries.size - firstNewEntry} batch(es), " +
                    "${totalVertexCount - baseVertex} vertices, " +
                    "${totalIndexCount - baseIndex} indices in " +
                    "%.1f ms".format((System.nanoTime() - start) / 1e6)
        )
        return true
    }

    /** Drop the CPU-side registration accumulators. */
    private fun clearGeometryAccumulators() {
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()
    }

    /**
     * Append whatever the accumulators are currently holding to the live shared buffers.
     *
     * [baseVertex] and [baseIndex] are the totals as they stood before the accumulate, i.e.
     * exactly the contents already resident on the GPU.
     */
    private fun appendSharedGeometry(baseVertex: Int, baseIndex: Int) {
        val addedVertices = totalVertexCount - baseVertex
        val addedIndices = totalIndexCount - baseIndex
        if (addedVertices <= 0 || addedIndices <= 0 || vboIds.size < 4) {
            clearGeometryAccumulators()
            return
        }

        glBindVertexArray(sharedVaoId)

        vboIds[0] = growFloatAttribute(vboIds[0], 0, 3, baseVertex, addedVertices) { buf ->
            for (arr in positionAccumulator) buf.put(arr)
        }
        vboIds[1] = if (QUANTISE_COLORS) {
            growColorAttribute(vboIds[1], 1, baseVertex, addedVertices)
        } else {
            growFloatAttribute(vboIds[1], 1, 3, baseVertex, addedVertices) { buf ->
                for (arr in colorAccumulator) buf.put(arr)
            }
        }
        vboIds[2] = growFloatAttribute(vboIds[2], 2, 2, baseVertex, addedVertices) { buf ->
            for (i in texCoordAccumulator.indices) {
                val tc = texCoordAccumulator[i]
                if (tc != null) {
                    buf.put(tc)
                } else {
                    repeat((positionAccumulator[i].size / 3) * 2) { buf.put(0f) }
                }
            }
        }
        vboIds[3] = growFloatAttribute(vboIds[3], 3, 3, baseVertex, addedVertices) { buf ->
            for (arr in normalAccumulator) buf.put(arr)
        }

        growIndexBuffer(baseIndex, addedIndices)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        clearGeometryAccumulators()

        val colorBytes = if (QUANTISE_COLORS) MeshBuffers.COLOR_BYTES else 3 * Float.SIZE_BYTES
        val vertexBytes = (3 + 2 + 3) * Float.SIZE_BYTES + colorBytes
        sharedGeometryBytes =
            totalVertexCount.toLong() * vertexBytes + totalIndexCount.toLong() * Int.SIZE_BYTES
    }

    /**
     * Replace the buffer behind a vertex attribute with a larger one holding the same data
     * plus room for [addedVertices] more, and re-point the VAO at it.
     *
     * The existing contents never leave the device: `glCopyBufferSubData` moves them into
     * the replacement, and only the tail is written from host memory. The shared VAO must
     * be bound.
     *
     * @param uploadTail writes the new vertices at the given target and byte offset.
     * @return the new buffer name.
     */
    private fun growVertexAttribute(
        oldVbo: Int,
        location: Int,
        components: Int,
        type: Int,
        normalized: Boolean,
        bytesPerVertex: Int,
        existingVertices: Int,
        addedVertices: Int,
        uploadTail: (target: Int, offset: Long) -> Unit
    ): Int {
        val existingBytes = existingVertices.toLong() * bytesPerVertex
        val totalBytes = existingBytes + addedVertices.toLong() * bytesPerVertex

        val newVbo = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, newVbo)
        glBufferData(GL_COPY_WRITE_BUFFER, totalBytes, GL_STATIC_DRAW)
        if (existingBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, oldVbo)
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingBytes)
            glBindBuffer(GL_COPY_READ_BUFFER, 0)
        }
        uploadTail(GL_COPY_WRITE_BUFFER, existingBytes)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
        glDeleteBuffers(oldVbo)

        glBindBuffer(GL_ARRAY_BUFFER, newVbo)
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(location, components, type, normalized, 0, 0)
        glVertexAttribDivisor(location, 0)
        return newVbo
    }

    /** [growVertexAttribute] for a float attribute, with the tail filled by [fill]. */
    private fun growFloatAttribute(
        oldVbo: Int,
        location: Int,
        components: Int,
        existingVertices: Int,
        addedVertices: Int,
        fill: (FloatBuffer) -> Unit
    ): Int {
        val tail = MemoryUtil.memAllocFloat(addedVertices * components)
        try {
            fill(tail)
            tail.flip()
            return growVertexAttribute(
                oldVbo, location, components, GL_FLOAT, false,
                components * Float.SIZE_BYTES, existingVertices, addedVertices
            ) { target, offset -> glBufferSubData(target, offset, tail) }
        } finally {
            MemoryUtil.memFree(tail)
        }
    }

    /**
     * [growVertexAttribute] for the RGBA8 colour attribute, packed by the same
     * [MeshBuffers.writeColorBytes] the initial upload uses.
     *
     * The staging buffer is written through its raw address, so it is pinned to native byte
     * order explicitly: the packing writes assembled 32-bit words, and a big-endian view
     * would swap every component on the way to the GPU.
     */
    private fun growColorAttribute(
        oldVbo: Int,
        location: Int,
        existingVertices: Int,
        addedVertices: Int
    ): Int {
        val totalBytes = addedVertices * MeshBuffers.COLOR_BYTES
        val tail = MemoryUtil.memAlloc(totalBytes).order(ByteOrder.nativeOrder())
        try {
            val baseAddress = MemoryUtil.memAddress(tail)
            var vertexOffset = 0
            for (arr in colorAccumulator) {
                val vertices = arr.size / 3
                MeshBuffers.writeColorBytes(
                    baseAddress + vertexOffset.toLong() * MeshBuffers.COLOR_BYTES,
                    arr, vertices, 3
                )
                vertexOffset += vertices
            }
            tail.position(0)
            tail.limit(totalBytes)
            return growVertexAttribute(
                oldVbo, location, 4, GL_UNSIGNED_BYTE, true,
                MeshBuffers.COLOR_BYTES, existingVertices, addedVertices
            ) { target, offset -> glBufferSubData(target, offset, tail) }
        } finally {
            MemoryUtil.memFree(tail)
        }
    }

    /**
     * Grow the shared IBO by [addedIndices], preserving the [existingIndices] already there.
     *
     * The element array binding is VAO state, so the replacement is bound while the shared
     * VAO is current — which the caller guarantees — and only then is the old buffer retired.
     */
    private fun growIndexBuffer(existingIndices: Int, addedIndices: Int) {
        val existingBytes = existingIndices.toLong() * Int.SIZE_BYTES
        val totalBytes = existingBytes + addedIndices.toLong() * Int.SIZE_BYTES

        val newIbo = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, newIbo)
        glBufferData(GL_COPY_WRITE_BUFFER, totalBytes, GL_STATIC_DRAW)
        if (existingBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, sharedIboId)
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingBytes)
            glBindBuffer(GL_COPY_READ_BUFFER, 0)
        }
        val tail = MemoryUtil.memAllocInt(addedIndices)
        try {
            for (arr in indexAccumulator) tail.put(arr)
            tail.flip()
            glBufferSubData(GL_COPY_WRITE_BUFFER, existingBytes, tail)
        } finally {
            MemoryUtil.memFree(tail)
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)

        val oldIbo = sharedIboId
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, newIbo)
        sharedIboId = newIbo
        if (oldIbo != 0) glDeleteBuffers(oldIbo)
    }

    /**
     * Give every mesh entry from [fromEntry] onwards its (array texture, layer) pair,
     * creating layers for materials that do not have one yet.
     */
    private fun resolveLateMaterials(fromEntry: Int) {
        val provider = texturePixelProvider
        val arraysBefore = arrayTextures.size

        if (provider != null) {
            val needed = LinkedHashSet<Int>()
            for (i in fromEntry until meshEntries.size) {
                val entry = meshEntries[i]
                if (entry.textureId >= 0 && !materialSlots.containsKey(entry.textureId)) {
                    needed.add(entry.textureId)
                }
            }
            if (needed.isNotEmpty()) {
                // Bucket by size exactly as the initial build does: every layer of an array
                // texture has to share one edge length.
                val bySize = LinkedHashMap<Int, MutableList<Pair<Int, IntArray>>>()
                for (id in needed) {
                    val pixelData = provider.getPixels(id)
                    if (pixelData == null || pixelData.second != pixelData.third) {
                        // Same placeholder the initial build falls back to.
                        missingTextureCount++
                        val size = arrayTextures.maxOfOrNull { it.size } ?: 64
                        bySize.getOrPut(size) { mutableListOf() }.add(id to checkerPixels(size))
                    } else {
                        bySize.getOrPut(pixelData.second) { mutableListOf() }
                            .add(id to pixelData.first)
                    }
                }
                for ((size, materials) in bySize) appendMaterialLayers(size, materials)
                println(
                    "[IndirectRenderer] ingest added ${needed.size} material layer(s): " +
                            bySize.entries.joinToString { "${it.value.size}x${it.key}px" }
                )
            }
        }

        assignMaterialSlots(fromEntry)

        if (arrayTextures.size != arraysBefore) {
            // The streaming path indexes its buckets by array texture, so the array has to
            // keep pace with however many array textures now exist.
            texturedBuckets = Array(arrayTextures.size) { TexturedBucket() }
            println(
                "[IndirectRenderer] array textures after ingest: " +
                        arrayTextures.joinToString { "${it.size}px x${it.layers}" }
            )
        }
    }

    /** Copy the resolved (array, layer) pair into every mesh entry from [fromEntry] on. */
    private fun assignMaterialSlots(fromEntry: Int) {
        for (i in fromEntry until meshEntries.size) {
            val entry = meshEntries[i]
            if (entry.textureId < 0) continue
            val packed = materialSlots[entry.textureId] ?: continue
            entry.textureArrayIndex = (packed shr 16)
            entry.textureLayer = packed and 0xFFFF
        }
    }

    /**
     * Place [materials] — all of edge length [size] — into array-texture layers.
     *
     * Prefers growing an existing array of the same size, which leaves every layer index
     * already handed out untouched and keeps the textured pass a single draw. Falls back to
     * a fresh array texture when no array of that size exists, when the one that does is
     * full, or when the driver cannot copy image data between textures.
     */
    private fun appendMaterialLayers(size: Int, materials: List<Pair<Int, IntArray>>) {
        val maxLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS).coerceAtLeast(1)
        var remaining = materials
        while (remaining.isNotEmpty()) {
            val target =
                if (canCopyImages()) arrayTextures.indexOfFirst { it.size == size && it.layers < maxLayers }
                else -1

            val chunk: List<Pair<Int, IntArray>>
            if (target >= 0) {
                chunk = remaining.take(maxLayers - arrayTextures[target].layers)
                if (!growArrayTexture(target, chunk)) {
                    // The copy failed, so the existing array is untouched and the new
                    // materials still have nowhere to live. Give them their own array.
                    addMaterialArray(size, chunk)
                }
            } else {
                chunk = remaining.take(maxLayers)
                addMaterialArray(size, chunk)
            }
            remaining = remaining.drop(chunk.size)
        }
    }

    /** Put [materials] in a brand new array texture of edge length [size]. */
    private fun addMaterialArray(size: Int, materials: List<Pair<Int, IntArray>>) {
        val arrayIndex = arrayTextures.size
        val glId = createArrayTexture(size, materials.map { it.second })
        arrayTextures.add(ArrayTexture(glId, size, materials.size))
        for ((layer, pair) in materials.withIndex()) {
            materialSlots[pair.first] = (arrayIndex shl 16) or layer
            IndirectReuseStats.recordMaterial(pair.first, size)
        }
    }

    /**
     * Rebuild array texture [index] with [materials] appended as extra layers.
     *
     * The existing layers are copied device-to-device, so the pixels are never read back and
     * the materials already living there keep both their array index and their layer index —
     * every mesh entry pointing at them stays correct without being revisited.
     *
     * The copy is checked rather than assumed. A `glCopyImageSubData` that is rejected does
     * nothing at all and reports it only through `glGetError`, which leaves every existing
     * layer of the replacement holding whatever `glTexImage3D` left there — in practice fully
     * transparent, so the textured shader discards it and every object using those materials
     * silently vanishes from the scene. That is precisely the failure this path must never
     * introduce, so a failed copy retires the replacement and reports false, leaving the
     * original array texture in place for the caller to work around.
     *
     * @return true when the grown texture is in place, false when nothing was changed.
     */
    private fun growArrayTexture(index: Int, materials: List<Pair<Int, IntArray>>): Boolean {
        val existing = arrayTextures[index]
        val size = existing.size
        val texCpuStart = threadCpuNanos()

        val texId = createEmptyArrayTexture(size, existing.layers + materials.size)
        while (glGetError() != GL_NO_ERROR) { /* discard anything inherited from earlier */ }
        glCopyImageSubData(
            existing.glId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
            texId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
            size, size, existing.layers
        )
        val copyError = glGetError()
        if (copyError != GL_NO_ERROR) {
            System.err.println(
                "[IndirectRenderer] could not copy ${existing.layers} layer(s) of the ${size}px " +
                        "array texture into its replacement (GL error 0x${copyError.toString(16)}); " +
                        "giving the new materials an array of their own"
            )
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
            glDeleteTextures(texId)
            return false
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, texId)
        uploadArrayLayers(size, existing.layers, materials.map { it.second })
        applyArrayTextureState()
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        texArrayCpuTimeMs += (threadCpuNanos() - texCpuStart) / 1e6

        glDeleteTextures(existing.glId)
        arrayTextures[index] = ArrayTexture(texId, size, existing.layers + materials.size)
        for ((offset, pair) in materials.withIndex()) {
            materialSlots[pair.first] = (index shl 16) or (existing.layers + offset)
            IndirectReuseStats.recordMaterial(pair.first, size)
        }
        return true
    }

    /** Whether the driver can copy texel data straight between textures. */
    private fun canCopyImages(): Boolean {
        val caps = GL.getCapabilities()
        return caps.OpenGL43 || caps.GL_ARB_copy_image
    }

    /**
     * CPU time of the calling thread, in nanoseconds, or 0 when the JVM will not report it.
     *
     * Wall time on this thread is not the same thing as work: the whole indirect build runs
     * from `loadRegionAsync` while the background loader pool holds most of the machine, so a
     * phase whose wall doubled may have done exactly the same work and simply waited longer.
     * Only the CPU figure can be given back by *not doing the work*, which is what reuse
     * would do, so it is the honest input to any reuse ceiling.
     */
    private fun threadCpuNanos(): Long =
        if (CPU_TIME_SUPPORTED) THREAD_MX.currentThreadCpuTime else 0L

    /** Wall of the registration accumulate loop, summed over every [registerModel] call. */
    private var registerAccumulateMs = 0.0

    /** CPU time of the registration accumulate loop, summed the same way. */
    private var registerAccumulateCpuMs = 0.0

    /** CPU time of the shared geometry upload. */
    private var geometryUploadCpuMs = 0.0

    /** CPU time of the array-texture row-flip and upload calls. */
    private var texArrayCpuTimeMs = 0.0

    /** Wall of the CPU row-flip that packs array-texture layers, this build. */
    private var texArrayCpuMs = 0.0

    /** Wall of the `glTexImage3D`/`glTexSubImage3D` calls that fill array textures. */
    private var texArrayUploadMs = 0.0

    /** Wall of `glGenerateMipmap` across every array texture. */
    private var texArrayMipmapMs = 0.0

    /**
     * Check if a model is registered.
     */
    fun isModelRegistered(modelId: Int): Boolean = modelRegistry.containsKey(modelId)

    /**
     * Whether [entry] is drawn by the textured shader.
     *
     * A mesh whose material never resolved to an array layer has no texture to sample and
     * falls back to the colour path, which at least draws its vertex colours instead of
     * dropping it.
     */
    private fun isTextured(entry: MeshEntry): Boolean = entry.textureArrayIndex >= 0

    /**
     * Submission order key for the batch at [entryIdx]: material first, then registration
     * order to break ties.
     *
     * The object pass blends with depth writes on, so a fragment it keeps also occludes what
     * is behind it and the image depends on the order batches arrive in. Ordering by material
     * is what [InstanceManager.ensureSortedTexturedEntries] does, so keying on it here puts
     * the two object paths on the same sequence and makes them comparable — the previous key
     * grouped by array texture first, which meant every 64px material in the scene was drawn
     * before any 128px one and a tree's foliage landed in front of a *different* tree's trunk.
     */
    private fun batchOrderKey(entryIdx: Int): Long {
        val entry = meshEntries[entryIdx]
        return (entry.textureId.toLong() shl 32) or entryIdx.toLong()
    }

    /**
     * The textured batches of [entries] in the order their commands should be submitted.
     *
     * Object order alone when a single draw can cover every array texture. Otherwise the
     * array index has to lead, because each array is then its own draw and a draw can only
     * submit a contiguous run of commands — which is exactly the split that made foliage
     * blend over other objects' geometry, and the reason [singleTexturedDraw] is preferred.
     */
    private fun texturedCommandOrder(entries: Collection<Int>): List<Int> =
        if (singleTexturedDraw) {
            entries.sortedBy { batchOrderKey(it) }
        } else {
            entries.sortedWith(compareBy({ meshEntries[it].textureArrayIndex }, { batchOrderKey(it) }))
        }

    /**
     * Bind every array texture to its own unit and point the shader's samplers at them.
     *
     * Unused sampler slots are left pointing at unit 0: a sampler that is never reached still
     * has to name a complete texture or the draw is undefined.
     */
    private fun bindArrayTextures(shader: ShaderProgram) {
        for (i in 0 until MAX_TEXTURE_ARRAYS) {
            val unit = if (i < arrayTextures.size) i else 0
            if (i < arrayTextures.size) {
                glActiveTexture(GL_TEXTURE0 + i)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[i].glId)
            }
            shader.setUniform("textureArrays[$i]", unit)
        }
        glActiveTexture(GL_TEXTURE0)
    }

    /**
     * Which bound sampler an instance of [entry] should read.
     *
     * In the fallback mode each array is bound on its own to slot 0 for its own draw, so the
     * instance records have to name slot 0 rather than the array's real index.
     */
    private fun instanceArraySlot(entry: MeshEntry): Int =
        if (singleTexturedDraw) entry.textureArrayIndex else 0

    /** Release the units [bindArrayTextures] claimed. */
    private fun unbindArrayTextures() {
        for (i in arrayTextures.indices) {
            if (i >= MAX_TEXTURE_ARRAYS) break
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        }
        glActiveTexture(GL_TEXTURE0)
    }

    // ====================================================================
    // Geometry upload
    // ====================================================================

    /**
     * Build the shared VBO/IBO from all accumulated vertex/index data, set up the VAO and
     * build the array textures. Call once after all models have been registered.
     */
    fun buildSharedGeometry() {
        if (totalVertexCount == 0 || totalIndexCount == 0) return

        val uploadStart = System.nanoTime()
        val uploadCpuStart = threadCpuNanos()
        glBindVertexArray(sharedVaoId)

        // Separate attribute streams keep the layout identical to the instanced meshes:
        // position (0), color (1), texCoord (2), normal (3), all per-vertex.
        val posVbo = uploadFloatAttribute(0, 3, totalVertexCount * 3) { buf ->
            for (arr in positionAccumulator) buf.put(arr)
        }
        val colorVbo = if (QUANTISE_COLORS) uploadColorAttribute(1, totalVertexCount)
        else uploadFloatAttribute(1, 3, totalVertexCount * 3) { buf ->
            for (arr in colorAccumulator) buf.put(arr)
        }
        val texVbo = uploadFloatAttribute(2, 2, totalVertexCount * 2) { buf ->
            for (i in texCoordAccumulator.indices) {
                val tc = texCoordAccumulator[i]
                if (tc != null) {
                    buf.put(tc)
                } else {
                    // Untextured entries still need to occupy their vertex range
                    repeat((positionAccumulator[i].size / 3) * 2) { buf.put(0f) }
                }
            }
        }
        val normalVbo = uploadFloatAttribute(3, 3, totalVertexCount * 3) { buf ->
            for (arr in normalAccumulator) buf.put(arr)
        }

        // Build shared IBO
        sharedIboId = glGenBuffers()
        val idxBuffer = MemoryUtil.memAllocInt(totalIndexCount)
        try {
            for (arr in indexAccumulator) idxBuffer.put(arr)
            idxBuffer.flip()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, sharedIboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, idxBuffer, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(idxBuffer)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        vboIds.clear()
        vboIds.addAll(listOf(posVbo, colorVbo, texVbo, normalVbo))

        // Clear CPU-side accumulators to free memory
        clearGeometryAccumulators()

        val colorBytes =
            if (QUANTISE_COLORS) MeshBuffers.COLOR_BYTES else 3 * Float.SIZE_BYTES
        val vertexBytes = (3 + 2 + 3) * Float.SIZE_BYTES + colorBytes
        val geometryUploadMs = (System.nanoTime() - uploadStart) / 1e6
        geometryUploadCpuMs = (threadCpuNanos() - uploadCpuStart) / 1e6
        IndirectReuseStats.vertexBytes = vertexBytes
        sharedGeometryBytes =
            totalVertexCount.toLong() * vertexBytes + totalIndexCount.toLong() * Int.SIZE_BYTES
        println(
            "[IndirectRenderer] shared geometry: $totalVertexCount vertices x $vertexBytes B " +
                    "(colour ${if (QUANTISE_COLORS) "RGBA8" else "float32"}) + " +
                    "$totalIndexCount indices = ${sharedGeometryBytes / 1024} KiB in " +
                    "%.1f ms".format((System.nanoTime() - uploadStart) / 1e6)
        )

        buildTextureArrays()
        texturedBuckets = Array(arrayTextures.size) { TexturedBucket() }

        // GL upload calls return before the driver has finished with the data, so the phase
        // walls above under-report by whatever the driver deferred. Draining once here says
        // how much that is, and is only paid when the accounting is switched on.
        var drainMs = 0.0
        if (IndirectReuseStats.ENABLED) {
            val drainStart = System.nanoTime()
            glFinish()
            drainMs = (System.nanoTime() - drainStart) / 1e6
        }

        IndirectReuseStats.finishBuild(
            drainMs = drainMs,
            geometryUploadMs = geometryUploadMs,
            registerMs = registerAccumulateMs,
            texCpuMs = texArrayCpuMs,
            texUploadMs = texArrayUploadMs,
            texMipmapMs = texArrayMipmapMs,
            texGenMs = textureGenMs,
            registerCpuMs = registerAccumulateCpuMs,
            geometryUploadCpuMs = geometryUploadCpuMs,
            texArrayCpuMs = texArrayCpuTimeMs
        )

        geometryBuilt = true
    }

    /**
     * Wall time of the material-pixel generation loop inside [buildTextureArrays], in ms.
     *
     * Exposed so the navigation benchmark can attribute the sync half of a jump without
     * re-deriving it from the log line next to it.
     */
    var textureGenMs: Double = 0.0
        private set

    /** VBO IDs for cleanup. */
    private val vboIds = mutableListOf<Int>()

    /** Vertex + index bytes of the shared geometry buffers, set by [buildSharedGeometry]. */
    var sharedGeometryBytes: Long = 0L
        private set

    /**
     * Quantise the accumulated vertex colours to RGBA8 and upload them to [location].
     *
     * Same trade the batch path makes in [MeshBuffers], on the object path's own buffers: a
     * vertex here carries position, colour, UV and normal, so moving colour from three floats
     * to four normalized bytes takes it from 44 to 36 bytes. The shaders are untouched — a
     * `vec3 aColor` input reads the first three components of a four-component normalized
     * attribute, and normalized bytes arrive as the same floats in [0,1].
     *
     * The packing loop itself is [MeshBuffers.writeColorBytes] rather than a copy of it, so
     * the rounding, the opaque-alpha default and the native-order component assembly are
     * identical on both paths and a diff between them can only come from the geometry.
     *
     * Unlike the batch path this is a one-shot upload of *unique* model geometry, so the
     * saving is a few megabytes rather than a few hundred (Canifis at render distance 3:
     * 47,045 -> 39,204 KiB). Treat it as a memory change, not a speed change — the packing
     * is free within the noise, but the bytes it avoids moving are a fraction of a percent
     * of the load.
     *
     * `-Ddrynor.indirectByteColors=false` restores the float layout for an A/B.
     *
     * The VAO must already be bound.
     */
    private fun uploadColorAttribute(location: Int, vertexCount: Int): Int {
        val vbo = glGenBuffers()
        val totalBytes = vertexCount * MeshBuffers.COLOR_BYTES
        val buffer = MemoryUtil.memAlloc(totalBytes)
        try {
            // Written through the raw address, not the ByteBuffer: memAlloc hands back a view
            // onto malloc'd memory in native order, and writeColorBytes takes an address
            // precisely so it can be aimed at an offset inside somebody else's allocation.
            val baseAddress = MemoryUtil.memAddress(buffer)
            var vertexOffset = 0
            for (arr in colorAccumulator) {
                val vertices = arr.size / 3
                MeshBuffers.writeColorBytes(
                    baseAddress + vertexOffset.toLong() * MeshBuffers.COLOR_BYTES,
                    arr, vertices, 3
                )
                vertexOffset += vertices
            }
            buffer.position(0)
            buffer.limit(totalBytes)
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, 4, GL_UNSIGNED_BYTE, true, 0, 0)
            glVertexAttribDivisor(location, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        return vbo
    }

    /**
     * Allocate a float VBO, fill it via [fill], upload it and wire it to [location].
     * The VAO must already be bound.
     */
    private fun uploadFloatAttribute(
        location: Int,
        components: Int,
        floatCount: Int,
        fill: (FloatBuffer) -> Unit
    ): Int {
        val vbo = glGenBuffers()
        val buffer = MemoryUtil.memAllocFloat(floatCount)
        try {
            fill(buffer)
            buffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, components, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(location, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        return vbo
    }

    /**
     * Build one GL_TEXTURE_2D_ARRAY per distinct material size and assign every textured
     * mesh entry its (array, layer) slot.
     *
     * Pixels come straight from [texturePixelProvider], the same source the individual 2D
     * textures are generated from, so no GPU readback is involved.
     */
    private fun buildTextureArrays() {
        val provider = texturePixelProvider ?: return

        // Collect the distinct materials actually referenced by registered geometry.
        val materialIds = LinkedHashSet<Int>()
        for (entry in meshEntries) {
            if (entry.textureId >= 0) materialIds.add(entry.textureId)
        }
        if (materialIds.isEmpty()) return

        // Bucket by texture size: every layer of an array texture must share one size.
        //
        // This loop stays serial deliberately. Generating these pixels is pure CPU and
        // thread-safe, and fanning it out does cut the phase itself roughly in half
        // (1082 -> 477 ms at Lumbridge). It still is not worth doing: unlike the terrain
        // atlas, this runs from loadRegionAsync while the background region load is already
        // holding 11-12 of 16 cores, so the extra workers come out of that load rather than
        // out of idle capacity. Measured end to end it moved the region-load wall the wrong
        // way, 3350 -> 3469 ms, and gave back a third of the win the atlas change had
        // banked. Parallelise a phase only where the cores it wants are actually free.
        val genStart = System.nanoTime()
        val statsBefore = TexturePixelStats.snapshot()
        val bySize = LinkedHashMap<Int, MutableList<Pair<Int, IntArray>>>()
        val missing = mutableListOf<Int>()
        for (id in materialIds) {
            val pixelData = provider.getPixels(id)
            if (pixelData == null || pixelData.second != pixelData.third) {
                missing.add(id)
                continue
            }
            bySize.getOrPut(pixelData.second) { mutableListOf() }.add(id to pixelData.first)
        }
        textureGenMs = (System.nanoTime() - genStart) / 1e6
        println(
            "[IndirectRenderer] material pixels: %d in %.0f ms wall"
                .format(materialIds.size, textureGenMs)
        )
        println(TexturePixelStats.snapshot().since(statsBefore).describe("objectMaterials"))

        // Materials with no generated pixels get a checkerboard, matching the placeholder
        // the individual-texture path falls back to.
        if (missing.isNotEmpty()) {
            missingTextureCount = missing.size
            val checkerSize = bySize.keys.maxOrNull() ?: 64
            val bucket = bySize.getOrPut(checkerSize) { mutableListOf() }
            val checker = checkerPixels(checkerSize)
            for (id in missing) bucket.add(id to checker)
            System.err.println("[IndirectRenderer] $missingTextureCount material(s) had no pixel data; using placeholder")
        }

        // An array texture cannot hold more layers than the implementation allows, so a size
        // bucket is split across as many arrays as it needs. Each array costs one draw call.
        val maxLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS).coerceAtLeast(1)

        for ((size, entries) in bySize) {
            for (chunk in entries.chunked(maxLayers)) {
                val arrayIndex = arrayTextures.size
                val glId = createArrayTexture(size, chunk.map { it.second })
                arrayTextures.add(ArrayTexture(glId, size, chunk.size))
                for ((layer, pair) in chunk.withIndex()) {
                    // Pack (arrayIndex, layer) into one int: layer counts stay well under 2^16.
                    materialSlots[pair.first] = (arrayIndex shl 16) or layer
                    IndirectReuseStats.recordMaterial(pair.first, size)
                }
            }
        }
        println(
            "[IndirectRenderer] array textures: " +
                    arrayTextures.joinToString { "${it.size}px x${it.layers}" }
        )

        assignMaterialSlots(0)

        if (AUDIT) {
            auditMaterials(materialSlots)
            auditLayerAlpha(bySize)
        }
    }

    /**
     * Report the alpha coverage of every material that became an array layer.
     *
     * The textured shader discards below 0.01, so a material whose pixels come back with no
     * alpha anywhere draws nothing at all — which looks exactly like missing geometry and is
     * otherwise indistinguishable from it in a screenshot.
     */
    private fun auditLayerAlpha(bySize: Map<Int, MutableList<Pair<Int, IntArray>>>) {
        val empty = mutableListOf<Int>()
        val sparse = mutableListOf<String>()
        for ((size, entries) in bySize) {
            for ((id, pixels) in entries) {
                var opaqueTexels = 0
                for (px in pixels) {
                    if (((px shr 24) and 0xFF) >= 3) opaqueTexels++
                }
                val fraction = opaqueTexels.toDouble() / pixels.size
                if (opaqueTexels == 0) empty.add(id)
                else if (fraction < 0.05) sparse.add("$id@${size}px=%.3f".format(fraction))
            }
        }
        println("[MDI-AUDIT] fully transparent materials: ${empty.size} $empty")
        println("[MDI-AUDIT] near transparent materials: ${sparse.size} $sparse")
    }

    /**
     * Report how the registered mesh entries split across the colour and textured paths,
     * and name any entry that asked for a material and did not get an array layer.
     */
    private fun auditMaterials(slots: Map<Int, Int>) {
        var colorEntries = 0
        var texturedEntries = 0
        var colorTris = 0L
        var texturedTris = 0L
        val unresolved = LinkedHashSet<Int>()
        for (entry in meshEntries) {
            val tris = entry.indexCount / 3
            if (entry.textureId < 0) {
                colorEntries++
                colorTris += tris
                continue
            }
            if (entry.textureArrayIndex < 0) {
                unresolved.add(entry.textureId)
                colorEntries++
                colorTris += tris
            } else {
                texturedEntries++
                texturedTris += tris
            }
        }
        println(
            "[MDI-AUDIT] entries: colour=$colorEntries ($colorTris tris) " +
                    "textured=$texturedEntries ($texturedTris tris); " +
                    "materials=${slots.size} unresolved=${unresolved.size} " +
                    "${unresolved.take(16)}"
        )
    }

    /**
     * Create a GL_TEXTURE_2D_ARRAY of [size] x [size] layers from ARGB [layers].
     *
     * Sampler state mirrors `TextureLoader.createTextureFromArgb` (GL_REPEAT, trilinear,
     * anisotropy, generated mipmaps) and rows are flipped the same way, so a layer samples
     * identically to the standalone 2D texture for the same material.
     */
    private fun createArrayTexture(size: Int, layers: List<IntArray>): Int {
        val texCpuStart = threadCpuNanos()
        val texId = createEmptyArrayTexture(size, layers.size)
        uploadArrayLayers(size, 0, layers)
        applyArrayTextureState()
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        texArrayCpuTimeMs += (threadCpuNanos() - texCpuStart) / 1e6
        return texId
    }

    /**
     * Allocate level 0 of a GL_TEXTURE_2D_ARRAY with [layerCount] uninitialised layers.
     *
     * Leaves the new texture bound so the caller can go straight on to filling it; the rest
     * of the sampler state and the mip chain come later, from [applyArrayTextureState].
     *
     * The minification filter is set here, and not left at its default, because the default
     * `GL_NEAREST_MIPMAP_LINEAR` makes a texture with only level 0 defined *mipmap
     * incomplete* — and an incomplete texture cannot be the destination of
     * `glCopyImageSubData`, which rejects the call with GL_INVALID_OPERATION and copies
     * nothing. [growArrayTexture] would then hand back a texture whose existing layers were
     * never written: every material already living in that array turns fully transparent,
     * the textured shader discards it, and objects all over the scene disappear as if their
     * geometry had gone missing. Asking for plain `GL_LINEAR` until the mips exist costs
     * nothing and keeps the texture complete from the moment it is allocated.
     */
    private fun createEmptyArrayTexture(size: Int, layerCount: Int): Int {
        val texId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D_ARRAY, texId)
        val allocStart = System.nanoTime()
        glTexImage3D(
            GL_TEXTURE_2D_ARRAY, 0, GL_RGBA,
            size, size, layerCount,
            0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?
        )
        texArrayUploadMs += (System.nanoTime() - allocStart) / 1e6
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        return texId
    }

    /**
     * Fill layers `[firstLayer, firstLayer + layers.size)` of the bound array texture from
     * ARGB pixel data. The texture must already be bound to GL_TEXTURE_2D_ARRAY.
     */
    private fun uploadArrayLayers(size: Int, firstLayer: Int, layers: List<IntArray>) {
        if (layers.isEmpty()) return
        val buffer = MemoryUtil.memAlloc(size * size * 4)
        try {
            for ((layerIndex, pixels) in layers.withIndex()) {
                val flipStart = System.nanoTime()
                buffer.clear()
                // Flip vertically: OpenGL's V=0 is the bottom row, RS pixel row 0 is the top.
                for (y in (size - 1) downTo 0) {
                    val row = y * size
                    for (x in 0 until size) {
                        val pixel = pixels[row + x]
                        buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                        buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
                        buffer.put((pixel and 0xFF).toByte())          // B
                        buffer.put(((pixel shr 24) and 0xFF).toByte()) // A
                    }
                }
                buffer.flip()
                texArrayCpuMs += (System.nanoTime() - flipStart) / 1e6
                val subStart = System.nanoTime()
                glTexSubImage3D(
                    GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, firstLayer + layerIndex,
                    size, size, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, buffer
                )
                texArrayUploadMs += (System.nanoTime() - subStart) / 1e6
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    /**
     * Apply the sampler state and build the mip chain of the bound array texture.
     *
     * Mirrors `TextureLoader.createTextureFromArgb` (GL_REPEAT, trilinear, anisotropy) so a
     * layer samples identically to the standalone 2D texture for the same material.
     */
    private fun applyArrayTextureState() {
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }
        val mipStart = System.nanoTime()
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        texArrayMipmapMs += (System.nanoTime() - mipStart) / 1e6
    }

    /** Grey checkerboard placeholder, matching `TextureLoader`'s missing-texture pattern. */
    private fun checkerPixels(size: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val gray = if (((x / 8) + (y / 8)) % 2 == 0) 200 else 100
                pixels[y * size + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return pixels
    }

    // ====================================================================
    // Per-frame rendering
    // ====================================================================

    /**
     * Zero the per-frame draw-call and command tallies.
     *
     * The static path has no per-frame instance data to clear, but it still reports how
     * much it drew, so the counters have to be reset somewhere both paths reach.
     */
    fun resetFrameCounters() {
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0
        commandsThisFrame = 0
    }

    /**
     * Prepare for a new frame. Clears all per-frame instance data.
     */
    fun prepareFrame() {
        resetFrameCounters()
        droppedInstanceCount = 0
        colorCommandCount = 0
        texturedCommandCount = 0
        colorInstanceBytes = 0L
        texturedInstanceBytes = 0L
        for (list in colorInstanceData.values) list.clear()
        for (list in texturedInstanceData.values) list.clear()
        for (bucket in texturedBuckets) {
            bucket.firstCommand = 0
            bucket.commandCount = 0
        }
    }

    /**
     * Add an instance of a model with pre-computed raw matrix data.
     *
     * @param modelId The model to instance
     * @param matrixData 16 floats representing a 4x4 column-major matrix
     * @return true if added successfully
     */
    fun addInstanceRaw(modelId: Int, matrixData: FloatArray): Boolean {
        val batch = modelRegistry[modelId] ?: run {
            noteMissingModel(modelId, "addInstanceRaw")
            return false
        }

        for (entryIdx in batch.meshEntryIndices) {
            val entry = meshEntries[entryIdx]
            if (isTextured(entry)) {
                texturedInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            } else {
                // Colour-only, or a textured mesh whose material could not be resolved
                colorInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            }
        }

        totalInstancesThisFrame++
        return true
    }

    /**
     * Add an instance of a model with a Matrix4f.
     */
    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val matrixData = FloatArray(16)
        modelMatrix.get(matrixData)
        return addInstanceRaw(modelId, matrixData)
    }

    /**
     * Upload all per-frame instance data and build indirect draw commands.
     * Call after adding all instances for the frame.
     */
    fun uploadAll() {
        if (!geometryBuilt) return
        uploadColorInstances()
        uploadTexturedInstances()
    }

    /**
     * Ensure [buffer] can hold [neededUnits] entries of [stride] bytes, reallocating when it
     * cannot. Returns the (possibly new) buffer and its capacity.
     *
     * This used to return null for a non-positive [neededUnits] and both callers wrote
     * `?: return`, which would have abandoned a frame's already-accumulated instance data
     * without a word. Callers decide up front whether they have anything to upload; by the
     * time they get here a non-positive size is a bug in that decision, not a quiet no-op.
     */
    private fun ensureBuffer(
        buffer: PersistentBuffer?,
        currentCapacity: Int,
        neededUnits: Int,
        stride: Int,
        target: Int,
        label: String
    ): Pair<PersistentBuffer, Int> {
        require(neededUnits > 0) { "[IndirectRenderer] $label: asked to size for $neededUnits entries" }
        if (buffer != null && currentCapacity >= neededUnits) return buffer to currentCapacity
        buffer?.cleanup()
        val capacity = growCapacity(neededUnits)
        val fresh = PersistentBuffer(target, capacity.toLong() * stride)
        fresh.init()
        println(
            "[IndirectRenderer] $label ring sized for $capacity entries " +
                    "(${fresh.totalSize / 1024} KiB, ${if (fresh.isPersistent) "persistent" else "bufferSubData"})"
        )
        return fresh to capacity
    }

    /**
     * Upload untextured instance data and build the colour draw commands.
     */
    private fun uploadColorInstances() {
        var instanceCount = 0
        var commandCount = 0
        for (instances in colorInstanceData.values) {
            if (instances.isEmpty()) continue
            instanceCount += instances.size
            commandCount++
        }
        if (instanceCount == 0) return

        val ssboPair = ensureBuffer(colorSsbo, colorInstanceCapacity, instanceCount, COLOR_INSTANCE_STRIDE, GL_SHADER_STORAGE_BUFFER, "color instance")
        colorSsbo = ssboPair.first
        colorInstanceCapacity = ssboPair.second
        val cmdPair = ensureBuffer(colorIndirectBuffer, colorCommandCapacity, commandCount, INDIRECT_COMMAND_SIZE, GL_DRAW_INDIRECT_BUFFER, "color command")
        colorIndirectBuffer = cmdPair.first
        colorCommandCapacity = cmdPair.second

        val instanceBuf = colorSsbo!!.beginFrame()
        val cmdBuf = colorIndirectBuffer!!.beginFrame()

        var instanceOffset = 0
        var written = 0

        // Sort by mesh entry ID for deterministic ordering
        for (entryIdx in colorInstanceData.keys.sortedBy { batchOrderKey(it) }) {
            val instances = colorInstanceData[entryIdx] ?: continue
            if (instances.isEmpty()) continue

            val entry = meshEntries[entryIdx]

            for (matrix in instances) {
                for (f in matrix) instanceBuf.putFloat(f)
                // Padding to the shared InstanceInfo stride; the colour shader ignores it.
                instanceBuf.putInt(-1)
                instanceBuf.putInt(0)
                instanceBuf.putInt(0)
                instanceBuf.putInt(0)
            }

            writeCommand(cmdBuf, entry.indexCount, instances.size, entry.firstIndex, entry.baseVertex, instanceOffset)

            instanceOffset += instances.size
            written++
        }

        colorInstanceBytes = instanceOffset.toLong() * COLOR_INSTANCE_STRIDE
        colorCommandCount = written

        colorSsbo!!.endWrite(colorInstanceBytes)
        colorIndirectBuffer!!.endWrite(written.toLong() * INDIRECT_COMMAND_SIZE)
    }

    /**
     * Upload textured instance data and build the textured draw commands, grouped so that
     * each array texture owns one contiguous run of commands.
     */
    private fun uploadTexturedInstances() {
        if (arrayTextures.isEmpty()) return

        var instanceCount = 0
        var commandCount = 0
        for (instances in texturedInstanceData.values) {
            if (instances.isEmpty()) continue
            instanceCount += instances.size
            commandCount++
        }
        if (instanceCount == 0) return

        val ssboPair = ensureBuffer(texturedSsbo, texturedInstanceCapacity, instanceCount, TEXTURED_INSTANCE_STRIDE, GL_SHADER_STORAGE_BUFFER, "textured instance")
        texturedSsbo = ssboPair.first
        texturedInstanceCapacity = ssboPair.second
        val cmdPair = ensureBuffer(texturedIndirectBuffer, texturedCommandCapacity, commandCount, INDIRECT_COMMAND_SIZE, GL_DRAW_INDIRECT_BUFFER, "textured command")
        texturedIndirectBuffer = cmdPair.first
        texturedCommandCapacity = cmdPair.second

        val instanceBuf = texturedSsbo!!.beginFrame()
        val cmdBuf = texturedIndirectBuffer!!.beginFrame()

        val sortedEntries = texturedCommandOrder(
            texturedInstanceData.keys.filter { texturedInstanceData[it]?.isNotEmpty() == true }
        )

        var instanceOffset = 0
        var written = 0
        var currentArray = -1

        for (entryIdx in sortedEntries) {
            val instances = texturedInstanceData[entryIdx] ?: continue
            val entry = meshEntries[entryIdx]

            // Buckets only carry a draw when the pass is split per array texture; a single
            // textured draw covers the whole run and leaves them alone.
            if (!singleTexturedDraw) {
                if (entry.textureArrayIndex != currentArray) {
                    currentArray = entry.textureArrayIndex
                    texturedBuckets[currentArray].firstCommand = written
                }
                texturedBuckets[currentArray].commandCount++
            }

            val layer = entry.textureLayer
            val arraySlot = instanceArraySlot(entry)
            for (matrix in instances) {
                for (f in matrix) instanceBuf.putFloat(f)
                instanceBuf.putInt(layer)
                instanceBuf.putInt(arraySlot)
                instanceBuf.putInt(0) // padding
                instanceBuf.putInt(0) // padding
            }

            writeCommand(cmdBuf, entry.indexCount, instances.size, entry.firstIndex, entry.baseVertex, instanceOffset)

            instanceOffset += instances.size
            written++
        }

        texturedInstanceBytes = instanceOffset.toLong() * TEXTURED_INSTANCE_STRIDE
        texturedCommandCount = written

        texturedSsbo!!.endWrite(texturedInstanceBytes)
        texturedIndirectBuffer!!.endWrite(written.toLong() * INDIRECT_COMMAND_SIZE)
    }

    /** Write one `DrawElementsIndirectCommand` (5 uints, tightly packed). */
    private fun writeCommand(
        buf: ByteBuffer,
        count: Int,
        instanceCount: Int,
        firstIndex: Int,
        baseVertex: Int,
        baseInstance: Int
    ) {
        buf.putInt(count)
        buf.putInt(instanceCount)
        buf.putInt(firstIndex)
        buf.putInt(baseVertex)
        buf.putInt(baseInstance)
    }

    /**
     * Render all untextured instances with a single multi-draw-indirect call.
     *
     * In streaming mode no `glMemoryBarrier` is needed: the instance and command data are
     * written by the CPU through a coherent persistent mapping (or by `glBufferSubData`),
     * never by a shader, so GL already orders them ahead of the draw. In static mode
     * [cullFrame] issues the barrier that covers its own compute writes.
     *
     * @param shader The indirect color shader program
     */
    fun renderUntextured(shader: ShaderProgram) {
        if (!geometryBuilt) return
        if (staticSceneBuilt) {
            renderStaticUntextured(shader)
            return
        }
        if (colorCommandCount == 0) return

        val ssbo = colorSsbo ?: return
        val indirect = colorIndirectBuffer ?: return

        shader.bind()
        ssbo.bindRange(INSTANCE_SSBO_BINDING, colorInstanceBytes)
        bindStreamingIdentity()

        glBindVertexArray(sharedVaoId)
        indirect.bind()

        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, indirect.readOffset(), colorCommandCount, 0)

        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)

        drawCallsThisFrame++
        commandsThisFrame += colorCommandCount
    }

    /**
     * Render all textured instances: one multi-draw-indirect call per array texture
     * (i.e. one per distinct material size, normally one or two).
     *
     * @param shader The indirect textured shader program
     */
    fun renderTextured(shader: ShaderProgram) {
        if (!geometryBuilt) return
        if (staticSceneBuilt) {
            renderStaticTextured(shader)
            return
        }
        if (texturedCommandCount == 0) return

        val ssbo = texturedSsbo ?: return
        val indirect = texturedIndirectBuffer ?: return

        shader.bind()
        ssbo.bindRange(INSTANCE_SSBO_BINDING, texturedInstanceBytes)
        bindStreamingIdentity()

        glBindVertexArray(sharedVaoId)
        indirect.bind()
        bindArrayTextures(shader)

        val baseOffset = indirect.readOffset()
        if (singleTexturedDraw) {
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, baseOffset, texturedCommandCount, 0)
            drawCallsThisFrame++
            commandsThisFrame += texturedCommandCount
        } else {
            for ((arrayIndex, bucket) in texturedBuckets.withIndex()) {
                if (bucket.commandCount == 0) continue
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[arrayIndex].glId)
                shader.setUniform("textureArrays[0]", 0)
                val offset = baseOffset + bucket.firstCommand.toLong() * INDIRECT_COMMAND_SIZE
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, bucket.commandCount, 0)
                drawCallsThisFrame++
                commandsThisFrame += bucket.commandCount
            }
        }

        unbindArrayTextures()
        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
    }

    /**
     * Close out the frame once every draw that reads this frame's streamed data has been
     * issued. This is where the ring's fences are placed, so it must not be called earlier:
     * fencing at write time would let the CPU reclaim a section the GPU is still reading.
     */
    fun endFrame() {
        colorSsbo?.endFrame()
        colorIndirectBuffer?.endFrame()
        texturedSsbo?.endFrame()
        texturedIndirectBuffer?.endFrame()
    }

    // ====================================================================
    // Static scene + GPU frustum culling
    // ====================================================================

    /**
     * The static object scene: every draw item, its bounds, and the indirect commands the
     * culling pass rebuilds each frame.
     *
     * A *draw item* is one (object instance, mesh batch) pair. Items are laid out in
     * command order, so command `c` owns items `[baseInstance_c, baseInstance_c + n_c)`
     * and each item's reserved slot in [visibleSsbo] is its own index. That reservation is
     * what lets the cull shader compact with nothing but an `atomicAdd` per survivor: a
     * survivor claims the next slot in its command's range and writes its index there, and
     * the command's `instanceCount` ends the dispatch holding exactly how many survived.
     * Commands nobody survived draw zero instances, which costs the GPU a command fetch
     * and nothing else.
     *
     * Colour-only and textured items share one set of buffers even though they are drawn
     * by different shaders and different `glMultiDrawElementsIndirect` calls. Splitting
     * them would double the number of compute dispatches, and dispatch launch overhead
     * dominates a pass this small — the culling arithmetic itself is a few microseconds.
     * The commands are simply ordered colour-first, so each draw covers a contiguous run.
     */
    private class StaticScene {
        var itemCount = 0
        var commandCount = 0

        /** Commands `[0, colorCommandCount)` are the colour-only ones. */
        var colorCommandCount = 0
        var triangleCount = 0

        var instanceSsbo = 0
        var boundsSsbo = 0
        var itemMetaSsbo = 0
        var visibleSsbo = 0
        var commandBuffer = 0

        /** Command ranges per array texture, with absolute command offsets. */
        var buckets = emptyArray<TexturedBucket>()

        fun cleanup() {
            for (id in intArrayOf(instanceSsbo, boundsSsbo, itemMetaSsbo, visibleSsbo, commandBuffer)) {
                if (id != 0) glDeleteBuffers(id)
            }
            instanceSsbo = 0
            boundsSsbo = 0
            itemMetaSsbo = 0
            visibleSsbo = 0
            commandBuffer = 0
            itemCount = 0
            commandCount = 0
            colorCommandCount = 0
            triangleCount = 0
            buckets = emptyArray()
        }
    }

    private val staticScene = StaticScene()

    /** True once [buildStaticScene] has produced GPU buffers. */
    var staticSceneBuilt: Boolean = false
        private set

    /** True when the compute programs are loaded and culling should run each frame. */
    var cullingActive: Boolean = false
        private set

    private var cullProgram: ShaderProgram? = null
    private var cullResetProgram: ShaderProgram? = null

    /** Accumulators for the static scene under construction. */
    private val pendingModelIds = mutableListOf<Int>()
    private val pendingMatrices = mutableListOf<FloatArray>()

    /** Identity indirection used by the streaming path, which does no culling. */
    private var streamingIdentityBuffer = 0
    private var streamingIdentityCapacity = 0

    /**
     * Debug counters.
     *
     * [statsDeviceBuffer] is ordinary device-local storage because the cull shader hammers
     * it with one atomic per surviving item; when those atomics landed in a coherent
     * host-mapped buffer instead they cost the frame several hundred microseconds, which
     * was more than the culling saved. The finished slot is copied into
     * [statsReadbackBuffer] — mapped for reading — one frame later, so the CPU still never
     * issues a readback command or waits on the GPU.
     */
    private var statsDeviceBuffer = 0
    private var statsReadbackBuffer = 0
    private var statsMap: ByteBuffer? = null
    private var statsSlot = 0
    private var statsFramesDispatched = 0

    /** Slot currently in flight to the readback ring, and the fence guarding it. */
    private var pendingStatsSlot = -1
    private var pendingStatsFence = 0L
    private var framesSinceStatsCopy = 0

    /** Last successfully sampled counters, and how many frames old they were. */
    var visibleInstancesStale: Int = -1
        private set
    var visibleTrianglesStale: Int = -1
        private set
    var statsAgeFrames: Int = 0
        private set

    /** Object instances in the static scene, retained for reporting after the build. */
    private var staticInstanceCount = 0

    /** Total draw items and triangles in the static scene. */
    val staticItemCount: Int get() = staticScene.itemCount
    val staticTriangleCount: Int get() = staticScene.triangleCount

    /**
     * Load the culling compute programs.
     *
     * @return true when culling is available; false leaves the static path drawing
     *         everything through the identity indirection, which is exactly what the
     *         renderer did before culling existed.
     */
    fun initCulling(cullSource: String, resetSource: String): Boolean {
        if (!isComputeSupported()) {
            println("[IndirectRenderer] compute shaders unavailable; GPU culling disabled")
            return false
        }
        return try {
            val cullStart = System.nanoTime()
            cullProgram = ShaderProgram.createCompute(cullSource, "frustumPlanes", "itemCount", "statsIndex")
            cullResetProgram = ShaderProgram.createCompute(resetSource, "commandCount", "statsIndex")
            val compileMs = (System.nanoTime() - cullStart) / 1e6
            createStatsBuffers()
            cullingActive = true
            println(
                "[IndirectRenderer] GPU frustum culling enabled (counter readback: " +
                        "${if (statsMap != null) "mapped ring" else "unavailable"}), " +
                        "compute programs compiled in %.1f ms".format(compileMs)
            )
            true
        } catch (e: Exception) {
            System.err.println("[IndirectRenderer] cull shader build failed, drawing unculled: $e")
            cullProgram?.cleanup()
            cullProgram = null
            cullResetProgram?.cleanup()
            cullResetProgram = null
            cullingActive = false
            false
        }
    }

    /** Discard any previously built static scene and start accumulating a new one. */
    fun beginStaticScene() {
        pendingModelIds.clear()
        pendingMatrices.clear()
        droppedInstanceCount = 0
    }

    /**
     * Queue one object instance for the static scene.
     *
     * @param matrixData 16 floats, column-major, as produced by `Matrix4f.get(FloatArray)`.
     *        The array is retained until [buildStaticScene], so pass a fresh copy.
     */
    fun addStaticInstance(modelId: Int, matrixData: FloatArray): Boolean {
        if (!modelRegistry.containsKey(modelId)) {
            noteMissingModel(modelId, "addStaticInstance")
            return false
        }
        pendingModelIds.add(modelId)
        pendingMatrices.add(matrixData)
        return true
    }

    /**
     * Upload the accumulated instances as GPU state that survives across frames:
     * transforms, world bounding spheres, the item -> command map and the command template.
     *
     * Everything here is paid once per scene change instead of once per frame, which is
     * the point: the CPU stops transforming and re-uploading several thousand matrices
     * every frame and stops deciding what is visible at all.
     */
    fun buildStaticScene() {
        val sceneStart = System.nanoTime()
        releaseStaticScene()
        if (!geometryBuilt || pendingModelIds.isEmpty()) return

        // Bucket draw items by mesh entry, keeping colour-only and textured entries apart
        // so the commands can be ordered colour-first.
        val colorItems = LinkedHashMap<Int, MutableList<Int>>()
        val texturedItems = LinkedHashMap<Int, MutableList<Int>>()

        for (instanceIndex in pendingModelIds.indices) {
            val modelId = pendingModelIds[instanceIndex]
            val batch = modelRegistry[modelId] ?: run {
                // addStaticInstance already refuses unregistered models, so reaching here
                // would mean the registry lost an entry between queueing and building.
                noteMissingModel(modelId, "buildStaticScene")
                null
            } ?: continue
            for (entryIdx in batch.meshEntryIndices) {
                val target = if (isTextured(meshEntries[entryIdx])) texturedItems else colorItems
                target.getOrPut(entryIdx) { mutableListOf() }.add(instanceIndex)
            }
        }

        // Textured commands must be contiguous per array texture so each bucket stays one
        // MDI call; colour commands come first so they are one contiguous run as well.
        val texturedOrder = texturedCommandOrder(texturedItems.keys)
        val commandOrder = colorItems.keys.sortedBy { batchOrderKey(it) } +
                (if (REVERSE_COMMANDS) texturedOrder.reversed() else texturedOrder)
        val allItems = LinkedHashMap<Int, MutableList<Int>>(colorItems)
        allItems.putAll(texturedItems)

        buildScene(allItems, commandOrder, colorItems.size)

        staticSceneBuilt = staticScene.itemCount > 0
        staticInstanceCount = pendingModelIds.size
        pendingModelIds.clear()
        pendingMatrices.clear()

        println(
            "[IndirectRenderer] static scene: ${staticScene.itemCount} draw items in " +
                    "${staticScene.commandCount} commands (${staticScene.colorCommandCount} colour), " +
                    "${staticScene.triangleCount} triangles, ${staticSceneBytes() / 1024} KiB in " +
                    "%.1f ms".format((System.nanoTime() - sceneStart) / 1e6)
        )
    }

    /** Build and upload every buffer of the static scene. */
    private fun buildScene(
        items: Map<Int, MutableList<Int>>,
        commandOrder: List<Int>,
        colorCommandCount: Int
    ) {
        val itemCount = items.values.sumOf { it.size }
        if (itemCount == 0) return

        val scene = staticScene
        val commandCount = commandOrder.size
        scene.itemCount = itemCount
        scene.commandCount = commandCount
        scene.colorCommandCount = colorCommandCount

        val commands = IntArray(commandCount * 5)
        val bounds = FloatArray(itemCount * 4)
        val itemMeta = IntArray(itemCount * 2)
        val identity = IntArray(itemCount) { it }
        val instanceData = MemoryUtil.memAlloc(itemCount * INSTANCE_STRIDE).order(ByteOrder.nativeOrder())

        // Indexed by array-texture index, not densely packed: renderStaticTextured pairs
        // bucket i with arrayTextures[i], so a texture array with no commands this scene
        // has to stay an empty slot rather than shift the ones after it.
        val buckets = Array(arrayTextures.size) { TexturedBucket() }

        var itemIndex = 0
        var triangles = 0
        try {
            for ((commandIndex, entryIdx) in commandOrder.withIndex()) {
                val entry = meshEntries[entryIdx]
                val instances = items[entryIdx] ?: continue
                val trianglesPerItem = entry.indexCount / 3

                // Buckets only mean anything when each array texture owns a contiguous run of
                // commands, which is exactly the case a single textured draw does not need.
                if (!singleTexturedDraw && isTextured(entry)) {
                    val bucket = buckets[entry.textureArrayIndex]
                    if (bucket.commandCount == 0) bucket.firstCommand = commandIndex
                    bucket.commandCount++
                }

                val base = commandIndex * 5
                commands[base] = entry.indexCount
                commands[base + 1] = 0            // instanceCount: filled in by the GPU
                commands[base + 2] = entry.firstIndex
                commands[base + 3] = entry.baseVertex
                commands[base + 4] = itemIndex    // baseInstance == start of this command's slots

                for (instanceIdx in instances) {
                    val matrix = pendingMatrices[instanceIdx]
                    for (f in matrix) instanceData.putFloat(f)
                    instanceData.putInt(entry.textureLayer)
                    instanceData.putInt(instanceArraySlot(entry))
                    instanceData.putInt(0)
                    instanceData.putInt(0)

                    writeWorldSphere(entry.localBounds, matrix, bounds, itemIndex * 4)
                    itemMeta[itemIndex * 2] = commandIndex
                    itemMeta[itemIndex * 2 + 1] = trianglesPerItem
                    triangles += trianglesPerItem
                    itemIndex++
                }
            }
            instanceData.flip()

            scene.triangleCount = triangles
            scene.instanceSsbo = createBuffer(GL_SHADER_STORAGE_BUFFER, instanceData, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(instanceData)
        }

        scene.boundsSsbo = createFloatBuffer(GL_SHADER_STORAGE_BUFFER, bounds, GL_STATIC_DRAW)
        scene.itemMetaSsbo = createIntBuffer(GL_SHADER_STORAGE_BUFFER, itemMeta, GL_STATIC_DRAW)
        // Seeded with the identity map so a frame that never runs the cull pass -- culling
        // switched off, or unsupported -- draws the whole scene rather than nothing.
        scene.visibleSsbo = createIntBuffer(GL_SHADER_STORAGE_BUFFER, identity, GL_DYNAMIC_DRAW)

        if (!cullingActive) {
            // No compute pass will fill these in, so bake the full instance counts.
            for ((commandIndex, entryIdx) in commandOrder.withIndex()) {
                commands[commandIndex * 5 + 1] = items[entryIdx]?.size ?: 0
            }
        }
        scene.commandBuffer = createIntBuffer(GL_DRAW_INDIRECT_BUFFER, commands, GL_DYNAMIC_DRAW)
        scene.buckets = buckets
    }

    /**
     * Enclose the transformed model-space AABB in a world-space sphere.
     *
     * The eight corners are transformed rather than the centre plus a scaled radius: the
     * instance transforms carry non-uniform scale, mirroring and terrain tilt, and a
     * sphere derived from the transformed extents stays correct under all of them. Being
     * conservative matters far more than being tight -- a bound that is slightly too small
     * pops geometry out of view, which is precisely the bug class this has to avoid.
     */
    private fun writeWorldSphere(local: FloatArray, m: FloatArray, out: FloatArray, offset: Int) {
        if (local.isEmpty()) {
            // No usable geometry bounds: make the item unbounded so it is never culled.
            out[offset] = 0f
            out[offset + 1] = 0f
            out[offset + 2] = 0f
            out[offset + 3] = Float.MAX_VALUE
            return
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        for (corner in 0 until 8) {
            val lx = if ((corner and 1) == 0) local[0] else local[3]
            val ly = if ((corner and 2) == 0) local[1] else local[4]
            val lz = if ((corner and 4) == 0) local[2] else local[5]

            val wx = m[0] * lx + m[4] * ly + m[8] * lz + m[12]
            val wy = m[1] * lx + m[5] * ly + m[9] * lz + m[13]
            val wz = m[2] * lx + m[6] * ly + m[10] * lz + m[14]

            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wz < minZ) minZ = wz
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
            if (wz > maxZ) maxZ = wz
        }

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val hx = maxX - cx
        val hy = maxY - cy
        val hz = maxZ - cz

        out[offset] = cx
        out[offset + 1] = cy
        out[offset + 2] = cz
        // Half-diagonal, nudged up so float error in the plane test can never clip a
        // silhouette pixel off the edge of the screen.
        out[offset + 3] = sqrt(hx * hx + hy * hy + hz * hz) * BOUND_SLACK
    }

    /**
     * Run the culling passes for this frame.
     *
     * Two dispatches: one to zero the instance counts (they live inside the packed
     * indirect commands, so a plain buffer clear would destroy the index count, offsets
     * and base instance alongside them), then the cull itself.
     *
     * No barrier is issued here — see [cullBarrier], which the caller places immediately
     * before the draws so everything in between overlaps with the compute.
     *
     * @param planes six frustum planes as 24 packed floats, see
     *        [org.drynor.tools.engine.spatial.Frustum.writePlanes]
     */
    fun cullFrame(planes: FloatArray) {
        if (!staticSceneBuilt || !cullingActive) return
        val cull = cullProgram ?: return
        val reset = cullResetProgram ?: return
        val scene = staticScene

        publishAndSampleStats()

        val statsIndex = statsSlot * STATS_UINTS_PER_SLOT

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_STATS_BINDING, statsDeviceBuffer)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_COMMAND_BINDING, scene.commandBuffer)

        reset.bind()
        reset.setUniformUInt("commandCount", scene.commandCount)
        reset.setUniformUInt("statsIndex", statsIndex)
        glDispatchCompute(dispatchGroups(scene.commandCount), 1, 1)

        // The cull pass increments the very counts the reset just zeroed.
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_BOUNDS_BINDING, scene.boundsSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_ITEM_META_BINDING, scene.itemMetaSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        cull.bind()
        cull.setUniformVec4Array("frustumPlanes", planes)
        cull.setUniformUInt("itemCount", scene.itemCount)
        cull.setUniformUInt("statsIndex", statsIndex)
        glDispatchCompute(dispatchGroups(scene.itemCount), 1, 1)

        for (binding in intArrayOf(
            CULL_COMMAND_BINDING, CULL_STATS_BINDING, CULL_BOUNDS_BINDING, CULL_ITEM_META_BINDING
        )) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, 0)
        }

        statsSlot = (statsSlot + 1) % STATS_SLOTS
        statsFramesDispatched++
    }

    /**
     * Order the culling writes ahead of the draws that consume them: the visible-index
     * buffer through the vertex shaders' SSBO reads, and the commands through the indirect
     * fetch.
     *
     * Deliberately separate from [cullFrame] so the caller can dispatch the cull early in
     * the frame and only pay for the barrier immediately before the object draws. Issuing
     * it straight after the dispatch instead makes every pass in between wait on the
     * compute, which measurably inflates the terrain passes.
     */
    fun cullBarrier() {
        if (!staticSceneBuilt || !cullingActive) return
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
    }

    /** Draw the static colour path: one MDI call over the colour run of the commands. */
    private fun renderStaticUntextured(shader: ShaderProgram) {
        val scene = staticScene
        if (scene.colorCommandCount == 0) return

        shader.bind()
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, scene.instanceSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        glBindVertexArray(sharedVaoId)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, scene.commandBuffer)
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, scene.colorCommandCount, 0)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)
        glBindVertexArray(0)

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, 0)

        drawCallsThisFrame++
        commandsThisFrame += scene.colorCommandCount
    }

    /** Draw the static textured path: one MDI call per array texture. */
    private fun renderStaticTextured(shader: ShaderProgram) {
        val scene = staticScene
        val texturedCommands = scene.commandCount - scene.colorCommandCount
        if (texturedCommands == 0) return

        shader.bind()
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, scene.instanceSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        glBindVertexArray(sharedVaoId)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, scene.commandBuffer)
        bindArrayTextures(shader)

        if (singleTexturedDraw) {
            // Every array is bound at once, so the whole textured run goes in one draw and
            // its commands stay in object order.
            val offset = scene.colorCommandCount.toLong() * INDIRECT_COMMAND_SIZE
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, texturedCommands, 0)
            drawCallsThisFrame++
            commandsThisFrame += texturedCommands
        } else {
            for ((arrayIndex, bucket) in scene.buckets.withIndex()) {
                if (bucket.commandCount == 0) continue
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[arrayIndex].glId)
                shader.setUniform("textureArrays[0]", 0)
                val offset = bucket.firstCommand.toLong() * INDIRECT_COMMAND_SIZE
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, bucket.commandCount, 0)
                drawCallsThisFrame++
                commandsThisFrame += bucket.commandCount
            }
        }

        unbindArrayTextures()
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, 0)
    }

    /**
     * Bind an identity indirection for the streaming path, which culls nothing.
     *
     * The vertex shaders take the same indirection in both modes on purpose: an A/B of
     * culled against unculled then differs only in what the compute pass wrote, not in
     * which shader ran.
     */
    private fun bindStreamingIdentity() {
        val needed = maxOf(colorInstanceCapacity, texturedInstanceCapacity)
        if (needed > streamingIdentityCapacity) {
            if (streamingIdentityBuffer != 0) glDeleteBuffers(streamingIdentityBuffer)
            streamingIdentityBuffer = createIntBuffer(
                GL_SHADER_STORAGE_BUFFER, IntArray(needed) { it }, GL_STATIC_DRAW
            )
            streamingIdentityCapacity = needed
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, streamingIdentityBuffer)
    }

    /**
     * Create the device-local counter ring the cull shader writes, plus the host-mapped
     * ring the CPU reads finished slots from.
     */
    private fun createStatsBuffers() {
        val bytes = (STATS_SLOTS * STATS_UINTS_PER_SLOT * 4).toLong()
        statsDeviceBuffer = createIntBuffer(
            GL_SHADER_STORAGE_BUFFER, IntArray(STATS_SLOTS * STATS_UINTS_PER_SLOT), GL_DYNAMIC_COPY
        )

        val caps = GL.getCapabilities()
        if (!caps.OpenGL44 && !caps.GL_ARB_buffer_storage) {
            // Culling still works; the panel just cannot report how much it removed.
            return
        }
        statsReadbackBuffer = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
        val flags = GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT
        glBufferStorage(GL_COPY_WRITE_BUFFER, bytes, flags)
        val mapped = glMapBufferRange(GL_COPY_WRITE_BUFFER, 0, bytes, flags)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
        // slice()/duplicate() reset to big-endian regardless of the parent's order, so the
        // order is pinned explicitly here rather than inherited.
        statsMap = mapped?.order(ByteOrder.nativeOrder())
    }

    /**
     * Move the counters the GPU produced into host-visible memory and pick them up, in two
     * steps spread over separate frames and repeated only every [STATS_SAMPLE_INTERVAL]
     * frames.
     *
     * The counters themselves are accumulated every frame; only their *retrieval* is
     * throttled. The device-to-host copy is a handful of bytes but the buffer-update
     * barrier it needs costs around 0.4 ms of GPU time on this driver — an order of
     * magnitude more than the culling dispatch itself — so paying it every frame would
     * have made a debug readout more expensive than the optimisation it reports on.
     *
     * Neither step ever blocks: the copy is issued and fenced, and the pickup is a zero
     * timeout `glClientWaitSync` that leaves the previous numbers up if the copy has not
     * landed. The figures are therefore tens of frames old, which is exactly what the
     * panel says about them.
     */
    private fun publishAndSampleStats() {
        if (statsMap == null || statsReadbackBuffer == 0) return

        // Retrieval is opt-in; see STATS_FORCED. The request decays so a panel that stops
        // being drawn stops the cost on its own.
        val wanted = STATS_FORCED || statsRequestFramesLeft > 0
        if (statsRequestFramesLeft > 0) statsRequestFramesLeft--
        if (!wanted) {
            // Abandon anything in flight and report the figures as unavailable rather than
            // leaving whatever was on screen when the panel was last open.
            if (pendingStatsFence != 0L) {
                glDeleteSync(pendingStatsFence)
                pendingStatsFence = 0L
                pendingStatsSlot = -1
            }
            visibleInstancesStale = -1
            visibleTrianglesStale = -1
            framesSinceStatsCopy = 0
            return
        }

        framesSinceStatsCopy++

        // Step 2: a copy is in flight; take it as soon as the GPU has finished it.
        if (pendingStatsFence != 0L) {
            val result = glClientWaitSync(pendingStatsFence, 0, 0L)
            if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) return

            glDeleteSync(pendingStatsFence)
            pendingStatsFence = 0L

            val map = statsMap!!
            val base = pendingStatsSlot * STATS_UINTS_PER_SLOT * 4
            visibleInstancesStale = map.getInt(base)
            visibleTrianglesStale = map.getInt(base + 4)
            statsAgeFrames = framesSinceStatsCopy + 1
            return
        }

        // Step 1: start a fresh copy of the slot the previous frame filled.
        if (statsFramesDispatched == 0 || framesSinceStatsCopy < STATS_SAMPLE_INTERVAL) return

        val previous = (statsSlot + STATS_SLOTS - 1) % STATS_SLOTS
        val offset = (previous * STATS_UINTS_PER_SLOT * 4).toLong()
        // Orders the previous frame's shader writes ahead of the copy below.
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_COPY_READ_BUFFER, statsDeviceBuffer)
        glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
        glCopyBufferSubData(
            GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER,
            offset, offset, (STATS_UINTS_PER_SLOT * 4).toLong()
        )
        glBindBuffer(GL_COPY_READ_BUFFER, 0)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)

        pendingStatsSlot = previous
        pendingStatsFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        framesSinceStatsCopy = 0
    }

    /** Bytes of GPU memory held by the static scene. */
    fun staticSceneBytes(): Long {
        val scene = staticScene
        // instance data + bounds (vec4) + item meta (uvec2) + visible index (uint)
        return scene.itemCount.toLong() * (INSTANCE_STRIDE + 16 + 8 + 4) +
                scene.commandCount.toLong() * INDIRECT_COMMAND_SIZE
    }

    private fun releaseStaticScene() {
        staticScene.cleanup()
        staticSceneBuilt = false
        visibleInstancesStale = -1
        visibleTrianglesStale = -1
        statsFramesDispatched = 0
        if (pendingStatsFence != 0L) {
            glDeleteSync(pendingStatsFence)
            pendingStatsFence = 0L
        }
        pendingStatsSlot = -1
        framesSinceStatsCopy = 0
        statsSlot = 0
    }
    // ====================================================================
    // Statistics
    // ====================================================================

    /** Number of `glMultiDrawElementsIndirect` calls issued this frame. */
    fun getDrawCallCount(): Int = drawCallsThisFrame

    /** Number of DrawElementsIndirectCommand structs submitted this frame. */
    fun getCommandCount(): Int = commandsThisFrame

    /** Total instances submitted this frame. */
    fun getTotalInstances(): Int =
        if (staticSceneBuilt) staticInstanceCount else totalInstancesThisFrame

    /**
     * Total triangles submitted by indirect rendering this frame.
     *
     * In static mode this is the whole scene, not the culled subset: the culled counts are
     * only knowable on the GPU and are surfaced separately as [visibleTrianglesStale].
     */
    fun getTotalTrianglesSubmitted(): Int {
        if (staticSceneBuilt) return staticTriangleCount
        var total = 0
        for ((entryIdx, instances) in colorInstanceData) {
            if (instances.isNotEmpty()) total += (meshEntries[entryIdx].indexCount / 3) * instances.size
        }
        for ((entryIdx, instances) in texturedInstanceData) {
            if (instances.isNotEmpty()) total += (meshEntries[entryIdx].indexCount / 3) * instances.size
        }
        return total
    }

    /** Number of registered models. */
    fun getRegisteredModelCount(): Int = modelRegistry.size

    /** Number of mesh entries (total geometry batches across all models). */
    fun getMeshEntryCount(): Int = meshEntries.size

    /** Number of array textures built, i.e. distinct material sizes. */
    fun getTextureArrayCount(): Int = arrayTextures.size

    /** Total material layers across all array textures. */
    fun getTextureLayerCount(): Int = arrayTextures.sumOf { it.layers }

    /**
     * Whether the indirect renderer is initialized and has geometry to draw.
     */
    fun isReady(): Boolean = initialized && geometryBuilt

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Clean up all GPU resources.
     */
    fun cleanup() {
        releaseStaticScene()
        cullProgram?.cleanup()
        cullProgram = null
        cullResetProgram?.cleanup()
        cullResetProgram = null
        cullingActive = false
        if (statsReadbackBuffer != 0) {
            if (statsMap != null) {
                glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
                glUnmapBuffer(GL_COPY_WRITE_BUFFER)
                glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
                statsMap = null
            }
            glDeleteBuffers(statsReadbackBuffer)
            statsReadbackBuffer = 0
        }
        if (statsDeviceBuffer != 0) {
            glDeleteBuffers(statsDeviceBuffer)
            statsDeviceBuffer = 0
        }
        if (streamingIdentityBuffer != 0) {
            glDeleteBuffers(streamingIdentityBuffer)
            streamingIdentityBuffer = 0
            streamingIdentityCapacity = 0
        }
        pendingModelIds.clear()
        pendingMatrices.clear()

        colorSsbo?.cleanup()
        colorSsbo = null
        texturedSsbo?.cleanup()
        texturedSsbo = null
        colorIndirectBuffer?.cleanup()
        colorIndirectBuffer = null
        texturedIndirectBuffer?.cleanup()
        texturedIndirectBuffer = null
        colorInstanceCapacity = 0
        texturedInstanceCapacity = 0
        colorCommandCapacity = 0
        texturedCommandCapacity = 0

        for (vbo in vboIds) glDeleteBuffers(vbo)
        vboIds.clear()

        if (sharedIboId != 0) {
            glDeleteBuffers(sharedIboId)
            sharedIboId = 0
        }
        if (sharedVaoId != 0) {
            glDeleteVertexArrays(sharedVaoId)
            sharedVaoId = 0
        }

        for (tex in arrayTextures) glDeleteTextures(tex.glId)
        arrayTextures.clear()
        materialSlots.clear()
        texturedBuckets = emptyArray()

        meshEntries.clear()
        modelRegistry.clear()
        droppedModelIds.clear()
        droppedInstanceCount = 0
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()
        colorInstanceData.clear()
        texturedInstanceData.clear()

        totalVertexCount = 0
        totalIndexCount = 0
        colorCommandCount = 0
        texturedCommandCount = 0
        colorInstanceBytes = 0L
        texturedInstanceBytes = 0L
        missingTextureCount = 0
        initialized = false
        geometryBuilt = false
    }
}
