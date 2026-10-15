package org.drynor.tools.engine.region

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drynor.core.map.MapXteas
import org.drynor.core.map.RegionData
import org.drynor.core.map.RegionLoader
import org.drynor.tools.cache.map.ObjectInstance
import org.drynor.tools.cache.map.ObjectRenderer
import org.drynor.tools.cache.map.VertexDisplacer
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.terrain.TerrainLoader
import org.drynor.tools.engine.async.*
import org.drynor.tools.engine.render.*
import org.drynor.tools.engine.spatial.AABB
import org.drynor.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/**
 * Per-region loading state.
 */
enum class RegionState {
    NOT_LOADED,
    CPU_LOADING,
    GPU_PENDING,
    READY,
    UNLOADING
}

/**
 * Holds per-region data through the loading pipeline.
 */
class AsyncRegionData(
    val key: RegionKey,
    @Volatile var state: RegionState = RegionState.NOT_LOADED,
    // CPU-side computed data (set when CPU_LOADING completes)
    var terrainMeshData: TerrainLoader.TerrainMeshData? = null,
    var objectInstances: List<ObjectInstance> = emptyList(),
    var modelMeshData: Map<Int, List<MeshData>> = emptyMap(),
    var displacedMeshData: Map<Int, List<MeshData>> = emptyMap(),
    // GPU-side resources (set when GPU tasks complete)
    var terrainMeshes: TerrainLoader.TerrainMeshes? = null,
    var batchMeshes: Map<BatchKey, Mesh>? = null,
    /**
     * Render-ready view of [batchMeshes]: already sorted into the order the viewer draws
     * them and carrying each batch's world-space bounds.
     *
     * The viewer used to re-derive this every frame with a `filter` + `sortedBy` per
     * region, which allocates once per region per frame and gives frustum culling nothing
     * to test against. Building it when the batch is uploaded costs nothing and makes the
     * per-frame job a plain array walk with an AABB test. See [RegionBatches] for why the
     * bounds are kept flat alongside the draw list.
     */
    var batches: RegionBatches = RegionBatches.EMPTY,
    /**
     * Per-object world-space bounds and triangle counts behind [batches].
     *
     * The batches themselves are one merged mesh per (region, texture) pair, so the
     * finest thing the viewer can cull is a whole region's worth of one material. This
     * keeps the granularity the merge threw away, which is what any measurement of
     * "how much would a per-object cull save" has to test against.
     */
    var objectGeometry: RegionObjectGeometry? = null,
    /**
     * What this region's batches would look like if the batch key carried a spatial cell
     * as well as a material. Built only under `-Ddrynor.cullProbe`; see
     * [BatchGranularitySimulation].
     */
    var batchSimulations: List<BatchGranularitySimulation> = emptyList(),
    // Tracking
    var pendingGpuTasks: AtomicInteger = AtomicInteger(0),
    var loadJob: Job? = null
)

/**
 * One region's static-batch geometry described per source object rather than per batch.
 *
 * Laid out flat, six floats of world-space AABB and one triangle count per object, for
 * the same reason [RegionBatches] flattens its bounds: the consumer is a tight frustum
 * loop over thousands of entries and the arithmetic is cheaper than the pointer chasing.
 *
 * Objects that contributed no drawable geometry are kept in place with a zero triangle
 * count and an inverted (empty) box, so indices still line up with the region's
 * `objectInstances` list.
 */
class RegionObjectGeometry(
    /** `minX, minY, minZ, maxX, maxY, maxZ` per object. */
    val bounds: FloatArray,
    /** Triangles the object contributes across every batch it lands in. */
    val triangles: IntArray,
    /** Objects with at least one triangle. */
    val drawableCount: Int,
    /** Sum of [triangles]. */
    val totalTriangles: Long
) {
    val count: Int get() = triangles.size

    companion object {
        val EMPTY = RegionObjectGeometry(FloatArray(0), IntArray(0), 0, 0L)
    }
}

/**
 * A dry run of one alternative batch granularity, for pricing it before building it.
 *
 * A batch is currently keyed by `(region, material)`, so it spans 64 tiles and the frustum
 * keeps or drops all of it. The obvious finer key is `(region cell, material)` for some
 * cell smaller than a region — which needs no change to how the geometry is stored or
 * drawn, only to how it is grouped, and so is a far cheaper thing to try than moving the
 * context regions onto per-instance geometry with a GPU cull.
 *
 * Each entry here is one batch that grouping would have produced: its world-space bounds
 * and its triangle count. Running the frame's frustum over them gives exactly the draw
 * count and triangle count the real thing would submit.
 */
class BatchGranularitySimulation(
    /** Cell edge in map tiles. 64 reproduces the current whole-region grouping. */
    val gridTiles: Int,
    /** `minX, minY, minZ, maxX, maxY, maxZ` per simulated batch. */
    val bounds: FloatArray,
    /** Triangles per simulated batch. */
    val triangles: IntArray
)

/**
 * One static batch mesh ready to draw, with the world-space bounds used to cull it.
 *
 * Batch geometry is pre-transformed to world space, so [bounds] needs no per-frame
 * transform and a plain frustum test against it is enough.
 */
class BatchDraw(
    val key: BatchKey,
    val mesh: Mesh,
    val bounds: AABB,
    /**
     * Index ranges inside [mesh], one per spatial cell the batch covers, in draw order.
     *
     * Submitting all of them is the same draw as submitting the whole mesh — they are
     * contiguous runs of the buffer in buffer order — so culling at this level changes
     * nothing except which parts are left out. One range means the batch is drawn whole,
     * as it was before sub-region ranges existed.
     */
    val rangeFirst: IntArray = intArrayOf(0),
    val rangeCount: IntArray = intArrayOf(mesh.vertexCount),
    val rangeBounds: FloatArray = FloatArray(6) { Float.NaN }
) {
    /** Per-range equivalent of [cullable]; a range with a bad box is always submitted. */
    val rangeCullable: BooleanArray = BooleanArray(rangeFirst.size) { i ->
        val o = i * 6
        rangeBounds[o].isFinite() && rangeBounds[o + 1].isFinite() && rangeBounds[o + 2].isFinite() &&
            rangeBounds[o + 3].isFinite() && rangeBounds[o + 4].isFinite() && rangeBounds[o + 5].isFinite() &&
            rangeBounds[o] <= rangeBounds[o + 3] && rangeBounds[o + 1] <= rangeBounds[o + 4] &&
            rangeBounds[o + 2] <= rangeBounds[o + 5]
    }

    /** True when every range is cullable, i.e. the per-range path may be used at all. */
    val rangesCullable: Boolean = rangeFirst.size > 1 && rangeCullable.all { it }

    /**
     * Whether [bounds] can be trusted for a frustum test.
     *
     * Model geometry occasionally produces a degenerate or non-finite vertex, and a NaN
     * makes every plane comparison false — which a frustum test reads as "outside" and
     * would silently delete the batch from the scene. A batch whose bounds are not a
     * finite, well-ordered box is drawn unconditionally instead.
     */
    val cullable: Boolean = bounds.isValid() &&
            bounds.min.x.isFinite() && bounds.min.y.isFinite() && bounds.min.z.isFinite() &&
            bounds.max.x.isFinite() && bounds.max.y.isFinite() && bounds.max.z.isFinite()
}

/**
 * One region's static batches in draw order, together with the bounds the viewer culls
 * them by, arranged for the shape of that job rather than for convenience.
 *
 * The viewer's per-frame job is to test every batch in every loaded region — thousands of
 * boxes at render distance 5 — and draw the few hundred that survive. Reading the bounds
 * off the [BatchDraw] objects means four dependent loads per test (draw, its AABB, and the
 * AABB's two Vector3f) into memory the allocator scattered, and the miss on each of those
 * costs far more than the arithmetic of the test itself.
 *
 * [bounds] is the same data flattened into one array, six floats per batch in [draws]
 * order, which the hardware prefetcher can stream. [union] then adds a level above it, so
 * a region wholly outside the frustum costs one test instead of one per batch.
 *
 * Immutable, and always replaced wholesale, so the three views can never disagree about
 * how many batches there are or what order they are in.
 */
class RegionBatches(val draws: List<BatchDraw>) {

    /** `minX, minY, minZ, maxX, maxY, maxZ` per entry of [draws], in [draws] order. */
    val bounds: FloatArray = FloatArray(draws.size * 6)

    /** Per-entry copy of [BatchDraw.cullable], read on the hot path without a dereference. */
    val cullable: BooleanArray = BooleanArray(draws.size)

    /**
     * World-space union of every entry's bounds, or null when there is no union that may
     * be trusted.
     *
     * Null means "cannot be rejected as a whole": either the region has no batches, or one
     * of them has bounds that are not a finite, well-ordered box. Both cases fall through
     * to the per-batch path, which draws an uncullable batch unconditionally. Rejecting a
     * whole region on a union that had swallowed a NaN would delete it from the scene
     * instead of just the one batch.
     */
    val union: AABB?

    init {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var allCullable = draws.isNotEmpty()
        for (i in draws.indices) {
            val draw = draws[i]
            val b = draw.bounds
            val o = i * 6
            bounds[o] = b.min.x
            bounds[o + 1] = b.min.y
            bounds[o + 2] = b.min.z
            bounds[o + 3] = b.max.x
            bounds[o + 4] = b.max.y
            bounds[o + 5] = b.max.z
            cullable[i] = draw.cullable
            if (!draw.cullable) {
                allCullable = false
            } else {
                if (b.min.x < minX) minX = b.min.x
                if (b.min.y < minY) minY = b.min.y
                if (b.min.z < minZ) minZ = b.min.z
                if (b.max.x > maxX) maxX = b.max.x
                if (b.max.y > maxY) maxY = b.max.y
                if (b.max.z > maxZ) maxZ = b.max.z
            }
        }
        union = if (allCullable) AABB(Vector3f(minX, minY, minZ), Vector3f(maxX, maxY, maxZ)) else null
    }

    companion object {
        val EMPTY = RegionBatches(emptyList())
    }
}

/**
 * One region's slice of the merged atlas terrain buffers.
 *
 * The merged meshes concatenate every covered region's geometry, already offset into
 * world space, so a region is nothing more than a contiguous index range in each of
 * the two buffers. Keeping the range and the region's terrain bounds together lets the
 * viewer frustum-cull per region and still submit the survivors as a single
 * `glMultiDrawElements` call per pass.
 *
 * Either range may be empty: a region with no alpha layers contributes
 * [alphaIndexCount] == 0, and one whose terrain is entirely alpha contributes
 * [opaqueIndexCount] == 0.
 */
class MergedTerrainRange(
    val key: RegionKey,
    val bounds: AABB,
    val opaqueFirstIndex: Int,
    val opaqueIndexCount: Int,
    val alphaFirstIndex: Int,
    val alphaIndexCount: Int
)

/**
 * Manages async loading of map regions with background CPU work
 * and time-budgeted GPU uploads.
 *
 * Uses a state machine per region:
 * ```
 * NOT_LOADED -> CPU_LOADING -> GPU_PENDING -> READY
 *                                              |
 *                                          UNLOADING -> removed
 * ```
 *
 * The center region is always loaded synchronously (for initial load / loading screen).
 * Context regions are loaded asynchronously in the background.
 */
class AsyncRegionManager(
    private val cache: Cache
) {
    private val gpuUploadQueue = GpuUploadQueue()
    private val loadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val regions = ConcurrentHashMap<RegionKey, AsyncRegionData>()

    // Configuration
    var renderDistance: Int = 1
    var showRoofs: Boolean = true
    var objectAtlas: TextureAtlas? = null
    var terrainAtlas: TextureAtlas? = null

    // Center region state (loaded synchronously, always available)
    private var centerKey: RegionKey? = null
    private var centerPlane: Int = 0

    // Synchronous RegionManager for the center region (keeps all its existing functionality)
    private lateinit var syncRegionManager: RegionManager

    // Shared registered model tracking
    private val registeredModels = ConcurrentHashMap.newKeySet<Int>()

    /**
     * `-Ddrynor.cullProbe=true` also switches on the batch-granularity dry run, which
     * costs load time and memory and produces nothing the renderer reads.
     */
    private val simulateBatchGranularity: Boolean =
        System.getProperty("drynor.cullProbe", "false").toBoolean()

    /**
     * `-Ddrynor.displaceAudit=true` logs, per region load, how many contour-type-1
     * objects were considered, how many the flat pre-test resolved, and how many ran the
     * per-vertex pass and moved *nothing*.
     *
     * That last figure is the direct signal for silently-dropped displacement. It is
     * legitimately non-zero only when a model's every vertex sits exactly at its average
     * height; a whole region's edge reporting it means the height field is not reaching
     * far enough, which is how the Duel Arena ramp railings ended up flat.
     */
    private val displaceAudit: Boolean =
        System.getProperty("drynor.displaceAudit", "false").toBoolean()

    /**
     * `-Ddrynor.batchCell=<tiles>`: edge of the spatial cell a static batch is subdivided
     * into for culling. 64 or more is one cell per region, which is the whole-region
     * granularity the pass had before sub-ranges existed.
     *
     * The batch itself is untouched — same merge, same buffer, same draw order. All this
     * decides is how finely the frustum may reject parts of it; see
     * [StaticBatcher.mergeGeometryData] and [BatchDraw.rangeFirst].
     */
    private val batchCellTiles: Int =
        (System.getProperty("drynor.batchCell")?.toIntOrNull() ?: DEFAULT_BATCH_CELL_TILES)
            .coerceAtLeast(1)

    private val batchCellWorldSize: Float = batchCellTiles * TILE_WORLD_SIZE

    /**
     * Cell of a world-space position, packed into a Long.
     *
     * Only ever compared for equality by the merge, so the packing just has to be
     * collision-free over the coordinate range a load wave covers: render distance 5 spans
     * about 700 tiles, which is far inside the 21 bits each axis gets here.
     */
    private fun cellKeyFor(worldX: Float, worldZ: Float): Long {
        if (batchCellTiles >= 64) return 0L
        val cellX = floor(worldX / batchCellWorldSize).toLong()
        val cellZ = floor(worldZ / batchCellWorldSize).toLong()
        return ((cellX and 0x1FFFFFL) shl 21) or (cellZ and 0x1FFFFFL)
    }

    /**
     * Caps how many region pipelines run at once. Each region now fans its own
     * phases out across [ParallelChunks.WORKER_COUNT] workers, so letting all 25
     * regions of render distance 2 start at once would only add memory pressure
     * and cache thrash without adding throughput.
     */
    private val regionLoadSemaphore = Semaphore(
        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    )

    /**
     * Decoded mesh data shared across every region in the current load wave,
     * keyed by combined model id.
     *
     * Neighbouring regions repeat the same models constantly (walls, trees, fences),
     * and [ModelLoader] only caches the *definition*, not the decoded [MeshData].
     * Without this each region paid the full decode again. The decoded arrays are
     * never mutated downstream — [VertexDisplacer] and [GeometryTransformer] both
     * allocate fresh arrays, and [InstanceManager] atlas UV remapping returns a new
     * [MeshData] — so sharing the instances between regions is safe.
     *
     * An absent model (decode returned null/empty) is stored as an empty list so
     * repeat lookups do not retry a decode that is known to fail.
     */
    private val sharedModelMeshData = ConcurrentHashMap<Int, List<MeshData>>()

    /**
     * Soft cap on [sharedModelMeshData] so a long editing session cannot grow it
     * without bound. Set to 0 (cache disabled) when the pipeline is forced serial
     * via `-Ddrynor.loadWorkers=1`, so that flag reproduces the pre-parallel behaviour.
     */
    private val sharedModelCacheLimit = if (ParallelChunks.WORKER_COUNT <= 1) 0 else 16384

    // Instance manager (shared across all regions, main-thread only)
    var instanceManager: InstanceManager? = null
        private set

    // Expose center region data for the viewer
    var centerObjects: List<ObjectInstance> = emptyList()
        private set
    var centerTerrainData: TerrainLoader.RegionData? = null
        private set
    var centerMapObjects: List<MapObject> = emptyList()
        private set
    var centerRegionData: RegionData? = null
        private set
    var centerModelMatrix: Matrix4f = Matrix4f()
        private set
    var mergedOpaqueTerrain: Mesh? = null
        private set
    var mergedAlphaTerrain: Mesh? = null
        private set

    /**
     * Per-region index ranges inside [mergedOpaqueTerrain] / [mergedAlphaTerrain],
     * in the order they were concatenated.
     */
    var mergedTerrainRanges: List<MergedTerrainRange> = emptyList()
        private set

    /**
     * Regions whose atlas-eligible terrain is already inside the merged meshes.
     *
     * The viewer uses this to decide which regions still need their own per-texture
     * terrain draws: a region in this set only draws the batches the atlas could not
     * represent, everything else comes from the merged buffers.
     */
    var mergedTerrainRegions: Set<RegionKey> = emptySet()
        private set

    /**
     * The eligible-region set the current merged meshes were built from.
     *
     * Compared against the live eligible set to decide whether a rebuild is due;
     * kept separate from [mergedTerrainRegions] because a region can be eligible and
     * still contribute nothing (empty or unreadable terrain), and comparing against
     * the contributing set would then ask for a rebuild on every frame forever.
     */
    private var mergedEligibleSnapshot: Set<RegionKey> = emptySet()

    /**
     * Set when something invalidated the merged terrain that a rebuild would not fix —
     * currently terrain edits, which mutate the center region's meshes in place without
     * writing back the [TerrainLoader.TerrainMeshData] the merge reads. While this is
     * set the viewer stays on the per-region path, which draws the edited geometry.
     * Cleared whenever a fresh center region is loaded.
     */
    private var mergedTerrainSuppressed = false

    /**
     * Cached terrain mesh data for the center region.
     *
     * The center region is owned by [syncRegionManager], which does not hand back the
     * intermediate [TerrainLoader.TerrainMeshData], so the merge has to recompute it
     * from cache. Doing that on the GL thread for every incremental rebuild would hitch
     * the frame, so the result is kept until the center region changes.
     */
    private var centerMergedMeshData: TerrainLoader.TerrainMeshData? = null
    private var centerMergedMeshKey: Pair<RegionKey, Int>? = null

    /**
     * `-Ddrynor.earlyContext=true` starts the context region pipelines before the
     * synchronous center load instead of after it, so their CPU phases overlap the
     * center's decode rather than waiting it out.
     *
     * Kept as a flag so the two orderings can be A/B'd inside one build; the loaded
     * result is identical either way, only the wall clock differs.
     */
    private val earlyContextKickoff: Boolean =
        System.getProperty("drynor.earlyContext", "false").toBoolean()

    // ========================================================================
    // Initialization
    // ========================================================================

    fun initialize() {
        syncRegionManager = RegionManager(cache)
        syncRegionManager.initialize()
    }

    // ========================================================================
    // Synchronous Center Region Loading
    // ========================================================================

    /**
     * Load the center region synchronously (blocks until complete).
     * This is used for the initial load and when navigating to a completely new area.
     * Context regions are kicked off as background jobs.
     */
    fun loadRegionSync(regionX: Int, regionY: Int, plane: Int) {
        // Cancel any in-progress background loading
        cancelAllLoading()

        centerKey = RegionKey(regionX, regionY)
        centerPlane = plane

        RegionLoadProfiler.beginWave("loadRegionSync $regionX,$regionY plane=$plane rd=$renderDistance",
            (renderDistance * 2 + 1) * (renderDistance * 2 + 1))

        resetMergedTerrain()

        // Use the synchronous RegionManager for the center region
        syncRegionManager.renderDistance = 0  // Only load center
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)
        RegionLoadProfiler.markRegionCpuDone()

        // Copy out center region data
        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix
        registeredModels.clear()

        // Mark center as READY
        val centerData = AsyncRegionData(RegionKey(regionX, regionY), RegionState.READY)
        regions[centerData.key] = centerData

        // Kick off async loading for context regions
        if (renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    /**
     * Navigate to a new center region. Keeps context regions that are still in view,
     * only loads new ones. Center region is still loaded synchronously.
     */
    fun navigateToRegion(regionX: Int, regionY: Int, plane: Int) {
        val newCenter = RegionKey(regionX, regionY)
        val oldCenter = centerKey

        // If same region, same plane - just return
        if (oldCenter == newCenter && plane == centerPlane) return

        // Cancel and remove in-progress regions so they can be re-loaded fresh.
        // Only keep READY regions; cancelled coroutines leave stale state.
        val inProgressKeys = regions.entries
            .filter { it.value.state == RegionState.CPU_LOADING || it.value.state == RegionState.GPU_PENDING }
            .map { it.key }
        for (key in inProgressKeys) {
            val data = regions.remove(key)
            data?.loadJob?.cancel()
            gpuUploadQueue.cancelRegion(key.toLong())
            data?.terrainMeshes?.cleanup()
            data?.batchMeshes?.values?.forEach { it.cleanup() }
            data?.batches = RegionBatches.EMPTY
            data?.objectGeometry = null
        }

        // Compute which regions to keep vs unload
        val desiredRegions = mutableSetOf<RegionKey>()
        desiredRegions.add(newCenter)
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                val rx = regionX + dx
                val ry = regionY + dy
                if (rx in 0..255 && ry in 0..255) {
                    desiredRegions.add(RegionKey(rx, ry))
                }
            }
        }

        // Unload READY regions no longer in view
        val toRemove = regions.keys.filter { it !in desiredRegions }
        for (key in toRemove) {
            unloadRegion(key)
        }

        centerKey = newCenter
        centerPlane = plane

        RegionLoadProfiler.beginWave("navigateToRegion $regionX,$regionY plane=$plane rd=$renderDistance",
            (renderDistance * 2 + 1) * (renderDistance * 2 + 1))

        // Context regions can start before the center is loaded. They share no mutable
        // state with the synchronous center load — they read the cache, the shared decoded
        // mesh cache and [registeredModels], all of which are concurrent structures already
        // exercised by the context loaders running against each other, and the center's own
        // load path touches none of them. Starting here overlaps their CPU phases with the
        // center's decode instead of idling until it finishes.
        //
        // The model-claim set has to be cleared immediately before the kickoff rather than
        // after the center load, or the first context regions would have their claims wiped
        // and the same model would be enqueued for registration twice.
        if (earlyContextKickoff) {
            registeredModels.clear()
            if (renderDistance > 0) {
                loadContextRegionsAsync(regionX, regionY, plane)
            }
        }

        // Sync load the new center
        syncRegionManager.renderDistance = 0
        syncRegionManager.showRoofs = showRoofs
        syncRegionManager.objectAtlas = objectAtlas
        syncRegionManager.terrainAtlas = terrainAtlas
        syncRegionManager.loadRegion(regionX, regionY, plane)
        RegionLoadProfiler.markRegionCpuDone()

        // Copy out center region data
        centerObjects = syncRegionManager.centerObjects
        centerTerrainData = syncRegionManager.centerTerrainData
        centerMapObjects = syncRegionManager.centerMapObjects
        centerRegionData = syncRegionManager.centerRegionData
        instanceManager = syncRegionManager.instanceManager
        centerModelMatrix = syncRegionManager.centerModelMatrix

        // Clear registered models — the new instanceManager doesn't have them.
        // Async regions will re-register as needed. Only on the late-kickoff path; the
        // early path already cleared before starting its regions and clearing again here
        // would discard their claims.
        if (!earlyContextKickoff) {
            registeredModels.clear()
        }

        // Mark center as READY
        val centerData = AsyncRegionData(newCenter, RegionState.READY)
        regions[newCenter] = centerData

        // Drop the merged terrain of the region we came from. Unlike an edit-driven
        // invalidation this does not suppress merging: the data behind the new center is
        // perfectly mergeable, it just has not been built yet.
        resetMergedTerrain()

        // Build merged terrain for just center immediately (so atlas path has something to render)
        buildMergedAtlasTerrain()

        // Kick off async loading for context regions that aren't already READY
        if (!earlyContextKickoff && renderDistance > 0) {
            loadContextRegionsAsync(regionX, regionY, plane)
        }
    }

    /**
     * Start background loading of context regions around the center.
     */
    private fun loadContextRegionsAsync(centerRegionX: Int, centerRegionY: Int, plane: Int) {
        val offsets = mutableListOf<RegionKey>()
        for (dx in -renderDistance..renderDistance) {
            for (dy in -renderDistance..renderDistance) {
                if (dx == 0 && dy == 0) continue  // Skip center
                val rx = centerRegionX + dx
                val ry = centerRegionY + dy
                if (rx in 0..255 && ry in 0..255) {
                    offsets.add(RegionKey(rx, ry))
                }
            }
        }

        // Sort by distance from center (load nearest first)
        offsets.sortBy { (it.regionX - centerRegionX) * (it.regionX - centerRegionX) +
                         (it.regionY - centerRegionY) * (it.regionY - centerRegionY) }

        for (key in offsets) {
            if (regions.containsKey(key) && regions[key]!!.state == RegionState.READY) {
                continue  // Already loaded
            }
            loadRegionAsync(key, plane)
        }
    }

    /**
     * Start background loading of a single region.
     */
    private fun loadRegionAsync(key: RegionKey, plane: Int) {
        val regionData = AsyncRegionData(key, RegionState.CPU_LOADING)
        regions[key] = regionData

        val showRoofsLocal = showRoofs
        val priority = computeRegionPriority(key)
        val regionKeyLong = key.toLong()

        regionData.loadJob = loadingScope.launch {
            regionLoadSemaphore.withPermit {
                try {
                    runRegionPipeline(key, plane, regionData, showRoofsLocal, priority, regionKeyLong)
                } catch (e: CancellationException) {
                    throw e  // Propagate cancellation
                } catch (e: Exception) {
                    println("[AsyncRegionManager] Error loading region ${key.regionX},${key.regionY}: ${e.message}")
                    e.printStackTrace()
                    // Mark as READY (empty) rather than removing — removal would cause
                    // getLoadProgress() totalRegions to never match regionsReady.
                    RegionLoadProfiler.markRegionCpuDone()
                    regionData.pendingGpuTasks.set(0)
                    regionData.state = RegionState.READY
                }
            }
        }
    }

    /**
     * The five CPU phases for one context region.
     *
     * Phase 1 loads the raw terrain data. After that the terrain tessellation and
     * the whole object pipeline (phases 2-5) are independent — both only read
     * [TerrainLoader.RegionData] — so they run side by side and each phase that
     * loops over objects is itself chunked across workers.
     */
    private suspend fun runRegionPipeline(
        key: RegionKey,
        plane: Int,
        regionData: AsyncRegionData,
        showRoofsLocal: Boolean,
        priority: Int,
        regionKeyLong: Long
    ) {
        // Phase 1: Load terrain data (CPU)
        val terrainPhaseStart = System.nanoTime()
        val terrainData = TerrainLoader.loadRegion(cache, key.regionX, key.regionY)
        if (terrainData == null) {
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_TERRAIN, System.nanoTime() - terrainPhaseStart)
            RegionLoadProfiler.markRegionCpuDone()
            regionData.state = RegionState.READY  // Empty region, nothing to load
            return
        }

        // coroutineScope joins both branches before we fall through to the state
        // transition, so every pendingGpuTasks increment is visible before the region
        // can be observed as GPU_PENDING. A failure in either branch cancels the scope
        // and rethrows to the caller's catch.
        // Shared by the terrain mesh and the object phase: object heights on the
        // region's last row/column resolve into the neighbour exactly as the client's
        // scene-wide height array does. Neighbour regions are cached by TerrainLoader,
        // so hoisting this out of the terrain branch costs at most one decode that the
        // object phase would have waited on anyway.
        val neighbors = TerrainLoader.buildNeighborData(cache, key.regionX, key.regionY)

        coroutineScope {
            launch {
                val terrainMeshData = TerrainLoader.computeTerrainMeshData(terrainData, plane, neighbors)
                regionData.terrainMeshData = terrainMeshData
                RegionLoadProfiler.record(RegionLoadProfiler.PHASE_TERRAIN, System.nanoTime() - terrainPhaseStart)

                // Enqueue terrain GPU upload
                regionData.pendingGpuTasks.incrementAndGet()
                gpuUploadQueue.enqueue(UploadTerrainMesh(
                    regionKey = regionKeyLong,
                    priority = priority,
                    meshData = terrainMeshData,
                    plane = plane
                ))
            }

            // Phase 2: Load objects (CPU)
            val objectPhaseStart = System.nanoTime()
            val ctxRegionData = RegionLoader.loadRegion(cache, key.regionX, key.regionY, useCache = false)
                ?: return@coroutineScope
            val ctxTiles = terrainData.tiles
            val allPlaneInstances = ObjectRenderer.createInstancesAllPlanes(
                objects = ctxRegionData.objects,
                regionX = key.regionX,
                regionY = key.regionY,
                tileHeights = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeightScaled(ctxTiles, neighbors, p, localX, localY)
                },
                rsHeightSampler = { localX, localY, p ->
                    TerrainLoader.getExtendedTileHeight(ctxTiles, neighbors, p, localX, localY)
                }
            )

            val instances = allPlaneInstances.values.flatten().let { insts ->
                if (!showRoofsLocal) insts.filter { it.mapObject.shape !in 12..21 }
                else insts
            }
            regionData.objectInstances = instances
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_OBJECTS, System.nanoTime() - objectPhaseStart)
            RegionLoadProfiler.noteObjects(instances.size)

            // Phase 3: Load model mesh data (CPU, fanned out over the unique model set)
            val modelPhaseStart = System.nanoTime()
            val modelMeshData = decodeModelMeshData(instances)
            regionData.modelMeshData = modelMeshData
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_MODELS, System.nanoTime() - modelPhaseStart)

            // Enqueue model registration for models nobody has claimed yet.
            // Iterating [modelMeshData] keeps first-appearance order, which is
            // exactly the order the old serial loop enqueued in.
            for ((combinedId, meshDataList) in modelMeshData) {
                if (registeredModels.add(combinedId)) {
                    regionData.pendingGpuTasks.incrementAndGet()
                    gpuUploadQueue.enqueue(RegisterModel(
                        regionKey = regionKeyLong,
                        priority = priority + 1,  // Models after terrain
                        modelId = combinedId,
                        meshDataList = meshDataList
                    ))
                }
            }

            // Phase 4: Compute vertex displacement (CPU, chunked over instances)
            val displacePhaseStart = System.nanoTime()
            val displacedMeshData = computeDisplacedMeshData(instances, modelMeshData, ctxTiles, neighbors, key.regionX, key.regionY)
            regionData.displacedMeshData = displacedMeshData
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_DISPLACE, System.nanoTime() - displacePhaseStart)

            // Phase 5: Compute static batch geometry (CPU, chunked over instances)
            val batchPhaseStart = System.nanoTime()
            val batchGeometry = buildBatchGeometry(instances, modelMeshData, displacedMeshData)
            val batcherData = batchGeometry.batches
            regionData.objectGeometry = batchGeometry.objects
            regionData.batchSimulations = batchGeometry.simulations
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_BATCH, System.nanoTime() - batchPhaseStart)

            // Merge each batch's geometry into flat arrays here, on the worker side,
            // so the GL thread only performs the buffer upload. Batches are merged
            // concurrently but enqueued in the deterministic batch key order above.
            val mergeStart = System.nanoTime()
            val batchEntries = batcherData.entries.toList()
            val mergedBatches = arrayOfNulls<MergedGeometry>(batchEntries.size)
            ParallelChunks.forEachChunk(batchEntries.size, minItemsPerChunk = 1) { from, to ->
                for (i in from until to) {
                    mergedBatches[i] = StaticBatcher.mergeGeometryData(batchEntries[i].value)
                }
            }
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_BATCH_MERGE, System.nanoTime() - mergeStart)

            for (i in batchEntries.indices) {
                val merged = mergedBatches[i] ?: continue
                regionData.pendingGpuTasks.incrementAndGet()
                gpuUploadQueue.enqueue(BuildRegionBatchMeshes(
                    regionKey = regionKeyLong,
                    priority = priority + 2,
                    batchKey = batchEntries[i].key,
                    merged = merged
                ))
            }
        }

        // Transition state
        RegionLoadProfiler.markRegionCpuDone()
        regionData.state = RegionState.GPU_PENDING
    }

    // ========================================================================
    // GPU Upload Processing (Main Thread)
    // ========================================================================

    /**
     * Process GPU upload tasks within the given time budget.
     * MUST be called on the main GL thread each frame.
     */
    fun drainGpuUploads(budgetNanos: Long) {
        val processed = gpuUploadQueue.drain(budgetNanos, processor = { task ->
            when (task) {
                is UploadTerrainMesh -> processTerrainUpload(task)
                is RegisterModel -> processModelRegistration(task)
                is BuildRegionBatchMeshes -> processBatchBuild(task)
                is UploadAtlasPage -> { /* handled elsewhere */ }
                is BuildMergedTerrain -> processMergedTerrainBuild(task)
            }
        }, onCancelled = { task ->
            // Decrement the owning region's pending counter so it isn't stuck forever
            val key = regionKeyFromLong(task.regionKey)
            regions[key]?.pendingGpuTasks?.decrementAndGet()
        })

        RenderMetrics.gpuUploadTasksProcessed = processed
        RenderMetrics.pendingGpuTasks = gpuUploadQueue.pendingCount()

        // Check for regions that have completed all GPU tasks
        for ((_, data) in regions) {
            if (data.state == RegionState.GPU_PENDING && data.pendingGpuTasks.get() <= 0) {
                data.state = RegionState.READY
            }
        }

        // Update metrics
        RenderMetrics.regionsLoading = regions.values.count {
            it.state == RegionState.CPU_LOADING || it.state == RegionState.GPU_PENDING
        }
        RenderMetrics.regionsReady = regions.values.count { it.state == RegionState.READY }
        RegionLoadProfiler.noteRegionsReady(RenderMetrics.regionsReady)
    }

    private fun processTerrainUpload(task: UploadTerrainMesh) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val meshData = task.meshData as? TerrainLoader.TerrainMeshData
        if (meshData != null) {
            val start = System.nanoTime()
            val terrainMeshes = TerrainLoader.uploadTerrainMeshData(meshData)
            regionData.terrainMeshes = terrainMeshes
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_TERRAIN, System.nanoTime() - start)
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processModelRegistration(task: RegisterModel) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val im = instanceManager
        if (im != null) {
            val start = System.nanoTime()
            im.registerModel(task.modelId, task.meshDataList, task.maxInstances)
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_REGISTER, System.nanoTime() - start)
        }
        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processBatchBuild(task: BuildRegionBatchMeshes) {
        val key = regionKeyFromLong(task.regionKey)
        val regionData = regions[key]
        if (regionData == null) return  // Region removed, task irrelevant

        val batchKey = task.batchKey
        val merged = task.merged

        // Geometry was already concatenated on the loader thread; this is a pure upload.
        val start = System.nanoTime()
        val mesh = Mesh(merged.pack())
        RegionLoadProfiler.record(RegionLoadProfiler.PHASE_GL_BATCH, System.nanoTime() - start)
        val existing = regionData.batchMeshes?.toMutableMap() ?: mutableMapOf()
        existing[batchKey]?.cleanup()
        existing[batchKey] = mesh
        regionData.batchMeshes = existing

        val draws = regionData.batches.draws.filter { it.key != batchKey }.toMutableList()
        draws.add(
            BatchDraw(
                batchKey, mesh, merged.bounds,
                rangeFirst = merged.rangeFirst,
                rangeCount = merged.rangeCount,
                rangeBounds = merged.rangeBounds
            )
        )
        // Untextured first, then by atlas page, so the viewer's shader and texture
        // binds stay in the same order they were in when it did the sorting itself.
        draws.sortWith(compareBy({ it.key != BatchKey.UNTEXTURED }, { it.key.atlasPageIndex }))
        regionData.batches = RegionBatches(draws)

        regionData.pendingGpuTasks.decrementAndGet()
    }

    private fun processMergedTerrainBuild(task: BuildMergedTerrain) {
        mergedOpaqueTerrain?.cleanup()
        mergedAlphaTerrain?.cleanup()

        task.opaqueData?.let { data ->
            mergedOpaqueTerrain = Mesh.createWithAtlasTransform(
                data.positions, data.colors, data.texCoords, data.normals,
                data.atlasTransforms, data.indices
            )
        }

        task.alphaData?.let { data ->
            mergedAlphaTerrain = Mesh.createWithAtlasAndAlpha(
                data.positions, data.colors, data.texCoords, data.normals,
                data.alphas!!, data.atlasTransforms, data.indices
            )
        }
    }

    // ========================================================================
    // Query API
    // ========================================================================

    /**
     * Get loading progress snapshot.
     */
    fun getLoadProgress(): LoadProgress {
        val total = (renderDistance * 2 + 1) * (renderDistance * 2 + 1)
        val ready = regions.values.count { it.state == RegionState.READY }
        val cpuDone = regions.values.count { it.state == RegionState.GPU_PENDING || it.state == RegionState.READY }
        val pending = gpuUploadQueue.pendingCount()

        return LoadProgress(
            totalRegions = total,
            regionsReady = ready,
            regionsCpuDone = cpuDone,
            pendingGpuTasks = pending,
            isInitialLoad = ready == 0 && total > 0,
            phase = when {
                total == 0 -> LoadPhase.IDLE
                ready >= total -> LoadPhase.DONE
                pending > 0 -> LoadPhase.GPU_UPLOAD
                cpuDone < total -> LoadPhase.OBJECTS
                else -> LoadPhase.TERRAIN
            }
        )
    }

    fun isFullyLoaded(): Boolean = getLoadProgress().isComplete

    /**
     * Get all ready context regions for rendering.
     */
    fun getReadyContextRegions(): List<AsyncRegionData> {
        val center = centerKey ?: return emptyList()
        return regions.values.filter { it.state == RegionState.READY && it.key != center }
    }

    /**
     * A single region's data if it is loaded and being drawn, else null.
     *
     * Unlike [getReadyContextRegions] this includes the centre region and allocates
     * nothing, which is what tile picking needs: it asks per ray sample whether a given
     * region may answer a click, and a region that is not on screen must not.
     */
    fun getReadyRegion(regionX: Int, regionY: Int): AsyncRegionData? =
        regions[RegionKey(regionX, regionY)]?.takeIf { it.state == RegionState.READY }

    /**
     * World-space bounds of a region's terrain, for culling its terrain draws.
     *
     * Deliberately covers the terrain only. Objects near a region border have geometry
     * that spills past the 64x64 tile footprint, so this box is not a safe gate for the
     * region's static batches — those carry their own exact world-space bounds and are
     * tested individually.
     *
     * The horizontal extent is exact — a region is always 64x64 tiles at a known origin —
     * while the vertical extent is a fixed generous span rather than the real height
     * range, which would mean walking the terrain vertices. Terrain relief inside one
     * region is a tiny fraction of [TERRAIN_Y_SPAN], and being generous vertically only
     * costs a few regions that a tighter box would have rejected; being wrong the other
     * way would punch holes in the ground.
     */
    /**
     * World-space bounds of a region's terrain, for culling its terrain draws.
     *
     * Public alias of [terrainBounds] for the viewer's per-region terrain test.
     */
    fun getRegionBounds(key: RegionKey): AABB = terrainBounds(key)

    private fun terrainBounds(key: RegionKey): AABB {
        val span = 64 * RegionManager.TILE_SIZE
        val minX = key.regionX * span
        val maxZ = -key.regionY * span
        return AABB(
            Vector3f(minX, -TERRAIN_Y_SPAN, maxZ - span),
            Vector3f(minX + span, TERRAIN_Y_SPAN, maxZ)
        )
    }

    /**
     * Get the model matrix for a region.
     */
    fun getRegionModelMatrix(key: RegionKey): Matrix4f {
        val tileSize = RegionManager.TILE_SIZE
        return Matrix4f().identity().translate(
            key.regionX * 64 * tileSize, 0f, -key.regionY * 64 * tileSize
        )
    }

    // ========================================================================
    // Render Distance Changes
    // ========================================================================

    /**
     * Change render distance asynchronously.
     * Computes a transition plan: keeps existing regions, loads new ones, unloads far ones.
     */
    fun setRenderDistanceAsync(newDistance: Int) {
        val center = centerKey ?: return
        val oldDistance = renderDistance
        renderDistance = newDistance.coerceIn(0, RegionManager.MAX_RENDER_DISTANCE)
        if (renderDistance == oldDistance) return

        val currentKeys = regions.keys.toSet()
        val plan = RegionTransitionPlan.compute(currentKeys, center.regionX, center.regionY, renderDistance)

        // Unload regions no longer in view
        for (key in plan.regionsToUnload) {
            unloadRegion(key)
        }

        // Load new regions
        for (key in plan.regionsToLoad) {
            if (key != center) {
                loadRegionAsync(key, centerPlane)
            }
        }
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    private fun unloadRegion(key: RegionKey) {
        val data = regions.remove(key) ?: return
        data.loadJob?.cancel()
        gpuUploadQueue.cancelRegion(key.toLong())

        // Clean up GPU resources on main thread
        data.terrainMeshes?.cleanup()
        data.batchMeshes?.values?.forEach { it.cleanup() }
        data.batches = RegionBatches.EMPTY
        data.objectGeometry = null
        data.state = RegionState.UNLOADING
    }

    private fun cancelAllLoading() {
        loadingScope.coroutineContext.cancelChildren()
        gpuUploadQueue.cancelAll()

        for ((_, data) in regions) {
            data.loadJob?.cancel()
            data.terrainMeshes?.cleanup()
            data.batchMeshes?.values?.forEach { it.cleanup() }
            data.batches = RegionBatches.EMPTY
            data.objectGeometry = null
        }
        regions.clear()
        registeredModels.clear()
        sharedModelMeshData.clear()
        // The cached model extents are derived from the meshes just dropped, so they go
        // with them rather than outliving the geometry they describe.
        VertexDisplacer.clearCaches()
        RegionLoadProfiler.abortWave()
    }

    fun cleanup() {
        cancelAllLoading()

        resetMergedTerrain()

        syncRegionManager.cleanup()
        instanceManager = null
        centerObjects = emptyList()
        centerTerrainData = null
        centerMapObjects = emptyList()
        centerRegionData = null
    }

    /**
     * Full cleanup including coroutine scope cancellation.
     * Call when the viewer is being destroyed.
     */
    fun destroy() {
        cleanup()
        loadingScope.cancel()
    }

    // ========================================================================
    // Delegate Methods (from RegionManager)
    // ========================================================================

    companion object {
        /** World units per map tile. */
        private const val TILE_WORLD_SIZE = 512f * CoordinateUtil.DEFAULT_SCALE

        /**
         * Default cell edge for static batch sub-ranges, in tiles.
         *
         * Chosen by measurement rather than by symmetry: over the three test regions at
         * render distances 3 and 5, 32 tiles gives most of the triangle reduction that
         * per-object culling would, for a range count the frustum can walk in a fraction
         * of the time the triangles cost. Halving it again to 16 buys a further few per
         * cent of triangles for twice the ranges, which the measured per-range submission
         * cost eats.
         */
        private const val DEFAULT_BATCH_CELL_TILES = 32

        /**
         * Cell edges, in tiles, that [simulateGranularity] prices. 64 is the current
         * whole-region grouping and is kept as a self-check: it must reproduce the live
         * batch and triangle counts exactly.
         */
        private val SIMULATED_GRID_TILES = intArrayOf(64, 32, 16, 8)

        /**
         * Half-height of the box a region's terrain is assumed to fit inside, in world
         * units.
         *
         * RS heights are 1/128 world unit each and the relief inside one region spans a
         * couple of dozen units at most, with the four planes stacked on top adding a few
         * more. 300 leaves an order of magnitude of headroom while still being tight
         * enough to matter: at 2000 the box was so tall that a region *behind* a
         * downward-pitched camera still intersected the frustum's lower cone, and almost
         * nothing was ever rejected.
         */
        private const val TERRAIN_Y_SPAN = 300f
    }

    fun getCurrentRegionX(): Int = centerKey?.regionX ?: -1
    fun getCurrentRegionY(): Int = centerKey?.regionY ?: -1
    fun getCurrentPlane(): Int = centerPlane

    fun hasBridgeFlag(localX: Int, localY: Int): Boolean = syncRegionManager.hasBridgeFlag(localX, localY)
    fun getModelIdForInstance(obj: ObjectInstance): Int = syncRegionManager.getModelIdForInstance(obj)

    /**
     * Mesh data registered for the current center region, keyed by the id
     * [getModelIdForInstance] reports. Valid until the next center region load.
     */
    val centerModelMeshData: Map<Int, List<MeshData>> get() = syncRegionManager.centerModelMeshData

    /** Model ids the center region asked for that the cache has no geometry for. */
    val unavailableModels: Set<Int> get() = syncRegionManager.unavailableModels

    val objectsByTile get() = syncRegionManager.objectsByTile

    /**
     * Drop the merged terrain meshes and everything that describes their coverage.
     *
     * Callers that expect the merge to come back (a new center region) use this
     * directly; [invalidateMergedTerrain] additionally stops it coming back.
     */
    private fun resetMergedTerrain() {
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null
        mergedTerrainRanges = emptyList()
        mergedTerrainRegions = emptySet()
        mergedEligibleSnapshot = emptySet()
        centerMergedMeshData = null
        centerMergedMeshKey = null
        mergedTerrainSuppressed = false
    }

    /**
     * Give up on merged terrain until the next center region load.
     *
     * Called when terrain edits have changed geometry the merged meshes were built from.
     * The edited geometry lives in the per-region meshes, so suppressing the merge is
     * what makes the edit visible; rebuilding instead would redraw the pre-edit terrain
     * over the top of it.
     */
    fun invalidateMergedTerrain() {
        resetMergedTerrain()
        mergedTerrainSuppressed = true
    }

    /**
     * Regions whose terrain data is available to merge right now: the center plus every
     * context region that has finished loading and kept its computed mesh data.
     */
    private fun mergedEligibleRegions(): Set<RegionKey> {
        val center = centerKey ?: return emptySet()
        val eligible = HashSet<RegionKey>()
        eligible.add(center)
        for ((key, data) in regions) {
            if (key == center) continue
            if (data.state != RegionState.READY) continue
            if (data.terrainMeshData == null) continue
            eligible.add(key)
        }
        return eligible
    }

    /**
     * Whether the merged terrain no longer covers the set of regions it could cover.
     *
     * True while context regions are still arriving, false once the merge has caught up.
     * The viewer polls this and rebuilds on a timer so a wave of regions costs a few
     * rebuilds rather than one per region.
     */
    fun mergedTerrainNeedsRebuild(): Boolean {
        if (terrainAtlas == null || mergedTerrainSuppressed) return false
        return mergedEligibleSnapshot != mergedEligibleRegions()
    }

    fun buildMergedAtlasTerrain() {
        val mergedStart = System.nanoTime()
        try {
            buildMergedAtlasTerrainInternal()
        } finally {
            RegionLoadProfiler.record(RegionLoadProfiler.PHASE_MERGED_TERRAIN, System.nanoTime() - mergedStart)
        }
    }

    private fun buildMergedAtlasTerrainInternal() {
        if (terrainAtlas == null) return

        // Collect terrain mesh data from center + all READY async regions
        data class RegionMeshEntry(
            val data: TerrainLoader.TerrainMeshData,
            val key: RegionKey
        )

        val entries = mutableListOf<RegionMeshEntry>()

        val center = centerKey ?: return
        val eligible = mergedEligibleRegions()

        // Center region - re-compute from cache (sync manager owns center data) and keep
        // the result, so the incremental rebuilds that follow each wave of context regions
        // do not pay for a cache read plus a full tessellation on the GL thread.
        val centerCacheKey = Pair(center, centerPlane)
        var centerMeshData = if (centerMergedMeshKey == centerCacheKey) centerMergedMeshData else null
        if (centerMeshData == null) {
            val centerTd = TerrainLoader.loadRegion(cache, center.regionX, center.regionY)
            if (centerTd != null) {
                val neighbors = TerrainLoader.buildNeighborData(cache, center.regionX, center.regionY)
                centerMeshData = TerrainLoader.computeTerrainMeshData(centerTd, centerPlane, neighbors)
                centerMergedMeshData = centerMeshData
                centerMergedMeshKey = centerCacheKey
            }
        }
        if (centerMeshData != null) {
            entries.add(RegionMeshEntry(centerMeshData, center))
        }

        // Async context regions - use already-computed mesh data
        for ((key, data) in regions) {
            if (key == center) continue
            if (key !in eligible) continue
            val meshData = data.terrainMeshData ?: continue
            entries.add(RegionMeshEntry(meshData, key))
        }

        if (entries.isEmpty()) {
            // Nothing to merge (the center failed to load). Record the snapshot anyway so
            // the viewer's rebuild poll does not spin on an attempt that cannot succeed.
            mergedEligibleSnapshot = eligible
            return
        }

        val tileSize = RegionManager.TILE_SIZE

        // Accumulate opaque and alpha geometry
        val allOpaquePositions = mutableListOf<FloatArray>()
        val allOpaqueColors = mutableListOf<FloatArray>()
        val allOpaqueTexCoords = mutableListOf<FloatArray>()
        val allOpaqueNormals = mutableListOf<FloatArray>()
        val allOpaqueAtlasTransforms = mutableListOf<FloatArray>()
        val allOpaqueIndices = mutableListOf<IntArray>()
        var opaqueVertexOffset = 0

        val allAlphaPositions = mutableListOf<FloatArray>()
        val allAlphaColors = mutableListOf<FloatArray>()
        val allAlphaTexCoords = mutableListOf<FloatArray>()
        val allAlphaNormals = mutableListOf<FloatArray>()
        val allAlphaAlphas = mutableListOf<FloatArray>()
        val allAlphaAtlasTransforms = mutableListOf<FloatArray>()
        val allAlphaIndices = mutableListOf<IntArray>()
        var alphaVertexOffset = 0

        // Per-region slices of the two index buffers, in concatenation order. Everything a
        // region contributes lands in one contiguous run of each buffer, which is what lets
        // the viewer cull whole regions and still issue a single multi-draw per pass.
        val ranges = ArrayList<MergedTerrainRange>(entries.size)
        var opaqueIndexCursor = 0
        var alphaIndexCursor = 0

        for (entry in entries) {
            val offsetX = entry.key.regionX * 64 * tileSize
            val offsetZ = -entry.key.regionY * 64 * tileSize
            val opaqueRangeStart = opaqueIndexCursor
            val alphaRangeStart = alphaIndexCursor

            fun offsetPositions(positions: FloatArray): FloatArray {
                val result = positions.copyOf()
                var i = 0
                while (i < result.size) {
                    result[i] += offsetX
                    result[i + 2] += offsetZ
                    i += 3
                }
                return result
            }

            fun offsetIndices(indices: IntArray, offset: Int): IntArray {
                if (offset == 0) return indices
                val result = IntArray(indices.size)
                for (j in indices.indices) {
                    result[j] = indices[j] + offset
                }
                return result
            }

            val meshDataEntry = entry.data

            // Color mesh to opaque pool
            meshDataEntry.colorArrays?.let { c ->
                if (c.positions.isNotEmpty() && c.atlasTransforms != null && c.texCoords != null) {
                    val vertCount = c.positions.size / 3
                    allOpaquePositions.add(offsetPositions(c.positions))
                    allOpaqueColors.add(c.colors)
                    allOpaqueTexCoords.add(c.texCoords)
                    allOpaqueNormals.add(c.normals)
                    allOpaqueAtlasTransforms.add(c.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(c.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                    opaqueIndexCursor += c.indices.size
                }
            }

            // Textured meshes to opaque pool
            for (t in meshDataEntry.texturedArrays) {
                if (t.positions.isNotEmpty() && t.atlasTransforms != null) {
                    val vertCount = t.positions.size / 3
                    allOpaquePositions.add(offsetPositions(t.positions))
                    allOpaqueColors.add(t.colors)
                    allOpaqueTexCoords.add(t.texCoords)
                    allOpaqueNormals.add(t.normals)
                    allOpaqueAtlasTransforms.add(t.atlasTransforms)
                    allOpaqueIndices.add(offsetIndices(t.indices, opaqueVertexOffset))
                    opaqueVertexOffset += vertCount
                    opaqueIndexCursor += t.indices.size
                }
            }

            // Alpha meshes to alpha pool
            for (a in meshDataEntry.alphaArrays) {
                if (a.positions.isNotEmpty() && a.atlasTransforms != null) {
                    val vertCount = a.positions.size / 3
                    allAlphaPositions.add(offsetPositions(a.positions))
                    allAlphaColors.add(a.colors)
                    allAlphaTexCoords.add(a.texCoords)
                    allAlphaNormals.add(a.normals)
                    allAlphaAlphas.add(a.alphas)
                    allAlphaAtlasTransforms.add(a.atlasTransforms)
                    allAlphaIndices.add(offsetIndices(a.indices, alphaVertexOffset))
                    alphaVertexOffset += vertCount
                    alphaIndexCursor += a.indices.size
                }
            }

            ranges.add(MergedTerrainRange(
                key = entry.key,
                bounds = terrainBounds(entry.key),
                opaqueFirstIndex = opaqueRangeStart,
                opaqueIndexCount = opaqueIndexCursor - opaqueRangeStart,
                alphaFirstIndex = alphaRangeStart,
                alphaIndexCount = alphaIndexCursor - alphaRangeStart
            ))
        }

        // Clean up old merged meshes
        mergedOpaqueTerrain?.cleanup()
        mergedOpaqueTerrain = null
        mergedAlphaTerrain?.cleanup()
        mergedAlphaTerrain = null

        // Build merged opaque mesh
        if (opaqueVertexOffset > 0) {
            val positions = concatFloatArrays(allOpaquePositions)
            val colors = concatFloatArrays(allOpaqueColors)
            val texCoords = concatFloatArrays(allOpaqueTexCoords)
            val normals = concatFloatArrays(allOpaqueNormals)
            val atlasTransforms = concatFloatArrays(allOpaqueAtlasTransforms)
            val indices = concatIntArrays(allOpaqueIndices)

            mergedOpaqueTerrain = Mesh.createWithAtlasTransform(
                positions, colors, texCoords, normals, atlasTransforms, indices
            )
        }

        // Build merged alpha mesh
        if (alphaVertexOffset > 0) {
            val positions = concatFloatArrays(allAlphaPositions)
            val colors = concatFloatArrays(allAlphaColors)
            val texCoords = concatFloatArrays(allAlphaTexCoords)
            val normals = concatFloatArrays(allAlphaNormals)
            val alphas = concatFloatArrays(allAlphaAlphas)
            val atlasTransforms = concatFloatArrays(allAlphaAtlasTransforms)
            val indices = concatIntArrays(allAlphaIndices)

            mergedAlphaTerrain = Mesh.createWithAtlasAndAlpha(
                positions, colors, texCoords, normals, alphas, atlasTransforms, indices
            )
        }

        // Publish coverage last: until these are set the viewer keeps drawing every
        // region's own terrain, so no frame can fall between the meshes being replaced
        // and the ranges that describe them.
        mergedTerrainRanges = ranges
        mergedTerrainRegions = ranges.mapTo(HashSet(ranges.size * 2)) { it.key }
        mergedEligibleSnapshot = eligible
    }

    // ========================================================================
    // Parallel CPU Phases
    // ========================================================================

    /**
     * Phase 3: decode the mesh data for every distinct model used by [instances].
     *
     * The instance list is deduplicated by combined model id first (thousands of
     * objects typically resolve to a few hundred unique models), then the unique
     * set is decoded concurrently. Results are inserted into the returned map in
     * first-appearance order, matching the old serial loop exactly.
     */
    private suspend fun decodeModelMeshData(instances: List<ObjectInstance>): Map<Int, List<MeshData>> {
        if (instances.isEmpty()) return emptyMap()

        // Deduplicate, preserving first-appearance order.
        val uniqueIds = ArrayList<Int>()
        val uniqueOwners = ArrayList<ObjectInstance>()
        val seen = HashSet<Int>()
        for (obj in instances) {
            if (seen.add(obj.cachedCombinedModelId)) {
                uniqueIds.add(obj.cachedCombinedModelId)
                uniqueOwners.add(obj)
            }
        }

        val decoded = arrayOfNulls<List<MeshData>>(uniqueIds.size)
        val decodeCount = AtomicInteger(0)
        val hitCount = AtomicInteger(0)

        // Workers write into disjoint slots of [decoded]; no shared mutable state.
        ParallelChunks.forEachChunk(uniqueIds.size) { from, to ->
            for (i in from until to) {
                val combinedId = uniqueIds[i]
                val cached = sharedModelMeshData[combinedId]
                if (cached != null) {
                    decoded[i] = cached
                    hitCount.incrementAndGet()
                    continue
                }

                val obj = uniqueOwners[i]
                val meshDataList = if (obj.modifications.hasModifications()) {
                    ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
                } else {
                    ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
                }
                decodeCount.incrementAndGet()

                val resolved = meshDataList ?: emptyList()
                if (sharedModelMeshData.size < sharedModelCacheLimit) {
                    // putIfAbsent rather than computeIfAbsent: a duplicate decode by a
                    // concurrent region wastes a little work but never blocks a bin.
                    decoded[i] = sharedModelMeshData.putIfAbsent(combinedId, resolved) ?: resolved
                } else {
                    decoded[i] = resolved
                }
            }
        }

        RegionLoadProfiler.noteModelDecode(decodeCount.get(), hitCount.get())

        val result = LinkedHashMap<Int, List<MeshData>>(uniqueIds.size * 2)
        for (i in uniqueIds.indices) {
            val meshDataList = decoded[i]
            if (meshDataList.isNullOrEmpty()) continue
            result[uniqueIds[i]] = meshDataList
        }
        return result
    }

    /**
     * Phase 4: per-object vertex displacement for contour-type 1 objects.
     *
     * Chunked over the instance list. Each worker fills its own map; the maps are
     * merged in chunk order so the result is independent of scheduling.
     */
    private suspend fun computeDisplacedMeshData(
        instances: List<ObjectInstance>,
        modelMeshData: Map<Int, List<MeshData>>,
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        regionX: Int,
        regionY: Int
    ): Map<Int, List<MeshData>> {
        if (instances.isEmpty()) return emptyMap()

        // One flat height array per plane for the whole region, built once and read
        // concurrently by every worker. Replaces two array derefs plus a TileData field
        // load with a single indexed read in the inner loop. Padded with neighbour
        // terrain so objects on the region's last row/column displace at all.
        val field = VertexDisplacer.HeightField.from(tiles, neighbors)

        val auditObjects = AtomicLong(0)
        val auditFlatSkips = AtomicLong(0)
        val auditZeroMoved = AtomicLong(0)

        val chunkResults = ParallelChunks.mapChunks(instances.size) { from, to ->
            val local = LinkedHashMap<Int, List<MeshData>>()
            var objCount = 0L
            var flatSkips = 0L
            var meshCalls = 0L
            var vertices = 0L
            var zeroMoved = 0L

            for (objIndex in from until to) {
                val obj = instances[objIndex]
                if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue

                val baseMeshDataList = modelMeshData[obj.cachedCombinedModelId] ?: continue
                objCount++

                val definition = obj.definition
                // Ground that is uniformly at the object's average height displaces every
                // vertex by zero, so the whole per-vertex pass can be skipped. The test
                // costs O(footprint tiles) against O(vertices) for the pass it replaces.
                val bounds = VertexDisplacer.modelBounds(obj.cachedCombinedModelId, baseMeshDataList)
                if (bounds == null || VertexDisplacer.footprintIsFlat(
                        bounds = bounds,
                        sceneX = obj.rsSceneX,
                        sceneY = obj.rsSceneY,
                        averageHeight = obj.rsAverageHeight,
                        plane = obj.mapObject.level,
                        field = field,
                        rsRotation = obj.rsRotation,
                        mirrored = obj.mirrored,
                        resizeX = definition.modelSizeX,
                        resizeY = definition.modelSizeZ,
                        resizeZ = definition.modelSizeY,
                        rsOffsetX = definition.offsetX,
                        rsOffsetZ = definition.offsetY
                    )
                ) {
                    flatSkips++
                    continue
                }

                val displaced = ArrayList<MeshData>(baseMeshDataList.size)
                var anyDisplaced = false

                for (meshData in baseMeshDataList) {
                    meshCalls++
                    vertices += meshData.positions.size / 3
                    val displacedMesh = VertexDisplacer.displaceVertices(
                        meshData = meshData,
                        sceneX = obj.rsSceneX,
                        sceneY = obj.rsSceneY,
                        averageHeight = obj.rsAverageHeight,
                        plane = obj.mapObject.level,
                        field = field,
                        rsRotation = obj.rsRotation,
                        mirrored = obj.mirrored,
                        resizeX = definition.modelSizeX,
                        resizeY = definition.modelSizeZ,
                        resizeZ = definition.modelSizeY,
                        rsOffsetX = definition.offsetX,
                        rsOffsetZ = definition.offsetY
                    )

                    if (displacedMesh != null) {
                        displaced.add(displacedMesh)
                        anyDisplaced = true
                    } else {
                        displaced.add(meshData)
                    }
                }

                if (anyDisplaced) {
                    local[objIndex] = displaced
                } else {
                    // Asked to displace, not resolved as flat, yet not one vertex moved.
                    // Legitimate only when every vertex sits exactly at the object's
                    // average height; otherwise the object is being silently skipped,
                    // which is precisely how the unpadded HeightField dropped every
                    // object on x == 63 / y == 63 without a trace.
                    zeroMoved++
                }
            }
            RegionLoadProfiler.noteDisplace(objCount, flatSkips, meshCalls, vertices, zeroMoved)
            auditObjects.addAndGet(objCount)
            auditFlatSkips.addAndGet(flatSkips)
            auditZeroMoved.addAndGet(zeroMoved)
            local
        }

        // A displacement that runs and moves nothing is invisible in every other
        // counter, and is what an under-sized height field looks like from the outside.
        // Three atomic adds per worker chunk, so the accounting itself can stay on.
        if (displaceAudit) {
            val zero = auditZeroMoved.get()
            println("[displace] region $regionX,$regionY considered=${auditObjects.get()} " +
                    "flat-skipped=${auditFlatSkips.get()} moved-nothing=$zero")
        }

        if (chunkResults.size == 1) return chunkResults[0]

        val merged = LinkedHashMap<Int, List<MeshData>>()
        for (chunk in chunkResults) {
            merged.putAll(chunk)
        }
        return merged
    }

    /**
     * Phase 5: world-space transform of every object's geometry, grouped by batch key.
     *
     * Chunked over the instance list. Every worker gets its own [GeometryTransformer]
     * and scratch [Matrix4f] — both carry mutable per-call state and must never be
     * shared. Merging the per-chunk maps in chunk order reproduces the serial
     * insertion order for both the batch keys and the geometry within each batch.
     */
    private suspend fun buildBatchGeometry(
        instances: List<ObjectInstance>,
        modelMeshData: Map<Int, List<MeshData>>,
        displacedMeshData: Map<Int, List<MeshData>>
    ): BatchGeometryResult {
        if (instances.isEmpty()) {
            return BatchGeometryResult(emptyMap(), RegionObjectGeometry.EMPTY, emptyList())
        }

        val atlas = objectAtlas

        // Per-object bounds and triangle counts, written by disjoint index ranges from
        // the chunk workers below. Six floats and one int per object -- 28 bytes -- which
        // is nothing beside the geometry itself and is the only record of which triangle
        // came from which object once the merge has flattened them.
        val objectBounds = FloatArray(instances.size * 6)
        val objectTriangles = IntArray(instances.size)

        // Under the probe only: which materials each object contributed to and how many
        // triangles went to each, which is what [BatchGranularitySimulation] regroups.
        val objectMaterials: Array<HashMap<BatchKey, Int>?>? =
            if (simulateBatchGranularity) arrayOfNulls(instances.size) else null

        val chunkResults = ParallelChunks.mapChunks(instances.size) { from, to ->
            val transformer = GeometryTransformer()
            val tempMatrix = Matrix4f()
            val local = LinkedHashMap<BatchKey, MutableList<TransformedGeometry>>()

            for (objIndex in from until to) {
                val boundsOffset = objIndex * 6
                objectBounds[boundsOffset] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 1] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 2] = Float.POSITIVE_INFINITY
                objectBounds[boundsOffset + 3] = Float.NEGATIVE_INFINITY
                objectBounds[boundsOffset + 4] = Float.NEGATIVE_INFINITY
                objectBounds[boundsOffset + 5] = Float.NEGATIVE_INFINITY

                val obj = instances[objIndex]
                val meshDataList = displacedMeshData[objIndex]
                    ?: modelMeshData[obj.cachedCombinedModelId]
                if (meshDataList == null || meshDataList.isEmpty()) continue

                // Cell from the object's own position rather than from its geometry, so an
                // object always lands in one cell and the assignment does not depend on
                // which of its submeshes is being processed.
                val cellKey = cellKeyFor(obj.worldPosition.x, obj.worldPosition.z)

                tempMatrix.identity()
                    .translate(obj.worldPosition)

                if (obj.terrainAlignment.needsAlignment()) {
                    tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
                }

                tempMatrix
                    .translate(obj.offset)
                    .scale(obj.scale)

                if (obj.terrainAlignment.needsAlignment()) {
                    tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)
                    tempMatrix.rotateX(obj.terrainAlignment.tiltX)
                }

                tempMatrix.rotateY(obj.rotation)

                if (obj.mirrored) {
                    tempMatrix.scale(1f, 1f, -1f)
                }

                for (meshData in meshDataList) {
                    if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

                    val batchKey = resolveBatchKey(meshData, atlas)

                    val texCoords = if (batchKey.atlasPageIndex >= 0 && atlas != null && meshData.textureId >= 0) {
                        remapTexCoordsToAtlas(meshData.textureCoords, meshData.textureId, atlas)
                    } else {
                        meshData.textureCoords
                    }

                    val transformed = transformer.transformMatchingInstanced(
                        meshData.positions, meshData.colors, texCoords,
                        meshData.normals, meshData.indices, tempMatrix
                    )

                    // One walk of the positions the transform just wrote, still hot in
                    // cache, serving both the per-piece box the merge turns into a range
                    // bound and the per-object box the cull probe tests.
                    val positions = transformed.positions
                    var minX = Float.POSITIVE_INFINITY
                    var minY = Float.POSITIVE_INFINITY
                    var minZ = Float.POSITIVE_INFINITY
                    var maxX = Float.NEGATIVE_INFINITY
                    var maxY = Float.NEGATIVE_INFINITY
                    var maxZ = Float.NEGATIVE_INFINITY
                    var p = 0
                    while (p < positions.size) {
                        val x = positions[p]
                        val y = positions[p + 1]
                        val z = positions[p + 2]
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (z < minZ) minZ = z
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        if (z > maxZ) maxZ = z
                        p += 3
                    }
                    if (minX < objectBounds[boundsOffset]) objectBounds[boundsOffset] = minX
                    if (minY < objectBounds[boundsOffset + 1]) objectBounds[boundsOffset + 1] = minY
                    if (minZ < objectBounds[boundsOffset + 2]) objectBounds[boundsOffset + 2] = minZ
                    if (maxX > objectBounds[boundsOffset + 3]) objectBounds[boundsOffset + 3] = maxX
                    if (maxY > objectBounds[boundsOffset + 4]) objectBounds[boundsOffset + 4] = maxY
                    if (maxZ > objectBounds[boundsOffset + 5]) objectBounds[boundsOffset + 5] = maxZ

                    local.getOrPut(batchKey) { mutableListOf() }.add(
                        transformed.copy(
                            cellKey = cellKey,
                            bounds = floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
                        )
                    )

                    val pieceTriangles = transformed.indices.size / 3
                    objectTriangles[objIndex] += pieceTriangles
                    if (objectMaterials != null) {
                        val materials = objectMaterials[objIndex] ?: HashMap<BatchKey, Int>(4).also {
                            objectMaterials[objIndex] = it
                        }
                        materials[batchKey] = (materials[batchKey] ?: 0) + pieceTriangles
                    }
                }
            }
            local
        }

        var drawable = 0
        var total = 0L
        for (count in objectTriangles) {
            if (count > 0) {
                drawable++
                total += count
            }
        }
        val objects = RegionObjectGeometry(objectBounds, objectTriangles, drawable, total)
        val simulations = if (objectMaterials == null) emptyList() else {
            SIMULATED_GRID_TILES.map { simulateGranularity(it, objectBounds, objectTriangles, objectMaterials) }
        }

        if (chunkResults.size == 1) return BatchGeometryResult(chunkResults[0], objects, simulations)

        val merged = LinkedHashMap<BatchKey, MutableList<TransformedGeometry>>()
        for (chunk in chunkResults) {
            for ((batchKey, geometries) in chunk) {
                merged.getOrPut(batchKey) { mutableListOf() }.addAll(geometries)
            }
        }
        return BatchGeometryResult(merged, objects, simulations)
    }

    /** Return of [buildBatchGeometry]: the merged-by-material view and the per-object view. */
    private class BatchGeometryResult(
        val batches: Map<BatchKey, MutableList<TransformedGeometry>>,
        val objects: RegionObjectGeometry,
        val simulations: List<BatchGranularitySimulation>
    )

    /**
     * Regroup one region's objects by `(cell, material)` and report the batches that
     * grouping would produce. See [BatchGranularitySimulation].
     *
     * An object belongs wholly to the cell containing its bounding-box centre, so an
     * object straddling a cell edge inflates its cell's box rather than being split —
     * which is what a real implementation would have to do too, since splitting geometry
     * across cells would mean re-welding vertices.
     */
    private fun simulateGranularity(
        gridTiles: Int,
        objectBounds: FloatArray,
        objectTriangles: IntArray,
        objectMaterials: Array<HashMap<BatchKey, Int>?>
    ): BatchGranularitySimulation {
        val cellWorldSize = gridTiles * TILE_WORLD_SIZE
        val boundsByKey = LinkedHashMap<Long, FloatArray>()
        val trianglesByKey = LinkedHashMap<Long, Int>()

        for (objIndex in objectTriangles.indices) {
            if (objectTriangles[objIndex] == 0) continue
            val materials = objectMaterials[objIndex] ?: continue
            val offset = objIndex * 6
            val centerX = (objectBounds[offset] + objectBounds[offset + 3]) * 0.5f
            val centerZ = (objectBounds[offset + 2] + objectBounds[offset + 5]) * 0.5f
            val cellX = floor(centerX / cellWorldSize).toInt()
            val cellZ = floor(centerZ / cellWorldSize).toInt()

            for ((batchKey, triangles) in materials) {
                // Cell coordinates are small and signed; the material contributes the low
                // bits. Packing into a Long keeps the grouping allocation-free per entry.
                val key = ((cellX.toLong() and 0xFFFFL) shl 48) or
                        ((cellZ.toLong() and 0xFFFFL) shl 32) or
                        ((batchKey.textureId.toLong() and 0xFFFFL) shl 16) or
                        (batchKey.atlasPageIndex.toLong() and 0xFFFFL)
                val box = boundsByKey.getOrPut(key) {
                    floatArrayOf(
                        Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
                    )
                }
                for (axis in 0..2) {
                    if (objectBounds[offset + axis] < box[axis]) box[axis] = objectBounds[offset + axis]
                    if (objectBounds[offset + 3 + axis] > box[3 + axis]) box[3 + axis] = objectBounds[offset + 3 + axis]
                }
                trianglesByKey[key] = (trianglesByKey[key] ?: 0) + triangles
            }
        }

        val count = boundsByKey.size
        val bounds = FloatArray(count * 6)
        val triangles = IntArray(count)
        var i = 0
        for ((key, box) in boundsByKey) {
            System.arraycopy(box, 0, bounds, i * 6, 6)
            triangles[i] = trianglesByKey[key] ?: 0
            i++
        }
        return BatchGranularitySimulation(gridTiles, bounds, triangles)
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private fun computeRegionPriority(key: RegionKey): Int {
        val center = centerKey ?: return Int.MAX_VALUE
        val dx = key.regionX - center.regionX
        val dy = key.regionY - center.regionY
        return dx * dx + dy * dy  // Distance squared = priority
    }

    private fun regionKeyFromLong(regionKeyLong: Long): RegionKey {
        val rx = (regionKeyLong shr 32).toInt()
        val ry = (regionKeyLong and 0xFFFFFFFFL).toInt()
        return RegionKey(rx, ry)
    }

    private fun resolveBatchKey(meshData: MeshData, atlas: TextureAtlas?): BatchKey {
        if (meshData.textureId == -1) return BatchKey.UNTEXTURED
        if (atlas != null) {
            val region = atlas.getRegion(meshData.textureId)
            if (region != null) {
                return BatchKey(textureId = -1, atlasPageIndex = region.atlasPageIndex)
            }
        }
        return BatchKey(textureId = meshData.textureId, atlasPageIndex = -1)
    }

    private fun remapTexCoordsToAtlas(
        texCoords: FloatArray?,
        textureId: Int,
        atlas: TextureAtlas
    ): FloatArray? {
        if (texCoords == null) return null
        val region = atlas.getRegion(textureId) ?: return texCoords
        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0
        var i = 0
        while (i < texCoords.size - 1) {
            remapped[i] = region.u0 + texCoords[i] * uScale
            remapped[i + 1] = region.v0 + texCoords[i + 1] * vScale
            i += 2
        }
        return remapped
    }

    private fun concatFloatArrays(arrays: List<FloatArray>): FloatArray {
        val totalSize = arrays.sumOf { it.size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }
        return result
    }

    private fun concatIntArrays(arrays: List<IntArray>): IntArray {
        val totalSize = arrays.sumOf { it.size }
        val result = IntArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }
        return result
    }
}
