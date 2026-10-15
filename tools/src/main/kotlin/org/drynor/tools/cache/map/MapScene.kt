package org.drynor.tools.cache.map

import org.drynor.core.map.MapXteas
import org.drynor.core.map.RegionData
import org.drynor.core.map.RegionLoader
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.terrain.TerrainLoader
import org.drynor.tools.cache.texture.TextureLoader
import org.drynor.tools.engine.render.Camera
import org.drynor.tools.engine.render.InstanceManager
import org.drynor.tools.engine.render.MeshData
import org.drynor.tools.engine.render.ShaderProgram
import org.drynor.tools.engine.spatial.AABB
import org.drynor.tools.engine.spatial.CullResult
import org.drynor.tools.engine.spatial.CullingSystem
import org.drynor.tools.engine.spatial.Rect
import org.drynor.tools.engine.spatial.RegionChunk
import org.drynor.tools.engine.spatial.SceneObject
import org.drynor.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * Loaded region data with renderable meshes.
 */
data class LoadedRegion(
    val regionX: Int,
    val regionY: Int,
    val regionId: Int,
    val data: RegionData,
    val terrainMeshesByPlane: Array<TerrainLoader.TerrainMeshes?>,  // Batched meshes per plane (0-3)
    val objectsByPlane: Map<Int, List<ObjectInstance>>,  // Objects grouped by plane
    val chunk: RegionChunk,
    val displacedModelIds: MutableSet<Int> = mutableSetOf()  // Displaced model IDs owned by this region
) {
    // Legacy accessor for backwards compatibility
    val objects: List<ObjectInstance>
        get() = objectsByPlane.values.flatten()

    fun getObjectsForPlane(plane: Int): List<ObjectInstance> =
        objectsByPlane[plane] ?: emptyList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedRegion) return false
        return regionId == other.regionId
    }

    override fun hashCode(): Int = regionId

    fun cleanup() {
        terrainMeshesByPlane.forEach { it?.cleanup() }
    }
}

/**
 * Statistics about the map scene.
 */
data class MapSceneStats(
    val loadedRegions: Int,
    val visibleRegions: Int,
    val totalObjects: Int,
    val visibleObjects: Int,
    val loadedModels: Int,
    val cullingTimeMs: Float
)

/**
 * Manages loading and rendering of a multi-region map scene.
 *
 * Features:
 * - Region streaming based on camera position
 * - Frustum culling for regions and objects
 * - Terrain mesh generation
 * - Object instance batching
 *
 * Usage:
 * ```kotlin
 * val scene = MapScene(cache, viewRadius = 2)
 * scene.initialize()
 *
 * // In game loop:
 * scene.update(camera.position.x, camera.position.z)
 * scene.cull(camera, aspectRatio)
 * scene.render(colorShader, texturedShader, camera, bindTexture)
 * ```
 */
class MapScene(
    private val cache: Cache,
    private val viewRadius: Int = 2,  // Load (2*radius+1)^2 regions = 5x5 = 25 regions
    private val unloadRadius: Int = viewRadius + 1
) {
    // Currently loaded regions
    private val loadedRegions = mutableMapOf<Int, LoadedRegion>()

    // Spatial culling system
    private val cullingSystem = CullingSystem(
        worldBounds = Rect(-100000f, -100000f, 100000f, 100000f),
        worldYMin = -1000f,
        worldYMax = 1000f
    )

    // Instance manager for object batching
    private val instanceManager = InstanceManager(maxInstancesPerMesh = 50000)

    // Registered model IDs (standard instanced models)
    private val registeredModels = mutableSetOf<Int>()

    // Displaced model IDs for contourType 1 objects.
    // Maps ObjectInstance reference -> displaced model ID using IdentityHashMap
    // to avoid System.identityHashCode() collisions that cause wrong mesh lookups.
    // Each contourType 1 instance gets unique geometry with per-vertex terrain displacement,
    // registered with the instanceManager under a unique negative ID.
    private val displacedModelIds = IdentityHashMap<ObjectInstance, Int>()

    // Counter for generating unique displaced model IDs (starts very negative to avoid collision)
    private var nextDisplacedModelId = Int.MIN_VALUE / 2

    // Current center region
    private var centerRegionX = -1
    private var centerRegionY = -1

    // Current plane being rendered (for terrain and bridge shifting)
    var currentPlane = 0
        private set

    // Whether to render roof shapes (12-21)
    var showRoofs = true

    // Last culling result
    private var lastCullResult: CullResult? = null

    // Tile size in world units
    private val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
    private val regionSize = 64 * tileSize

    /**
     * Check if a tile has the bridge flag set on plane 1.
     * The bridge flag (0x2) on plane 1 indicates that objects from higher planes
     * should be rendered on the plane below (linkBelow behavior).
     */
    private fun hasBridgeFlag(regionData: RegionData, localX: Int, localY: Int): Boolean {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return false
        val settingsIndex = MapDefinition.index(localX, localY, 1)
        return (regionData.settings[settingsIndex].toInt() and 0x2) == 0x2
    }

    /**
     * Determine if an object should be rendered.
     *
     * Multi-plane rendering: ALL objects from all planes are rendered, each using
     * its own plane's height data for positioning.
     *
     * Roof filtering: When showRoofs is false, objects with roof shapes (12-21) are skipped.
     *
     * Bridge shifting (linkBelow): When the bridge flag (0x2) is set on plane 1,
     * objects at that tile use the height of the plane below.
     */
    private fun shouldRenderObject(sceneObj: SceneObject): Boolean {
        // Roof shapes (12-21) - skip when showRoofs is false
        val isRoofShape = sceneObj.shape in 12..21
        if (!showRoofs && isRoofShape) return false

        // Multi-plane rendering: render ALL objects from all planes
        return true
    }

    /**
     * Initialize the map scene.
     * Loads XTEA keys and floor definitions.
     */
    fun initialize() {
        // Load XTEA keys if not already loaded
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }

        // Load terrain and object definitions
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)
    }

    /**
     * Set the current plane (0-3) to render.
     */
    fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
    }

    /**
     * Update the scene based on camera position.
     * Loads new regions and unloads distant ones.
     *
     * @param cameraX Camera X position in world coordinates
     * @param cameraZ Camera Z position in world coordinates
     */
    fun update(cameraX: Float, cameraZ: Float) {
        // Convert world position to region coordinates
        val regionX = worldToRegionX(cameraX)
        val regionY = worldToRegionY(cameraZ)

        // Skip if center hasn't changed
        if (regionX == centerRegionX && regionY == centerRegionY) return

        centerRegionX = regionX
        centerRegionY = regionY

        // Determine which regions should be loaded
        val neededRegions = mutableSetOf<Int>()
        for (dx in -viewRadius..viewRadius) {
            for (dy in -viewRadius..viewRadius) {
                val rx = regionX + dx
                val ry = regionY + dy
                if (rx >= 0 && ry >= 0 && rx < 256 && ry < 256) {
                    neededRegions.add((rx shl 8) or ry)
                }
            }
        }

        // Unload distant regions
        val toUnload = loadedRegions.keys.filter { regionId ->
            val rx = regionId shr 8
            val ry = regionId and 0xff
            val dx = abs(rx - regionX)
            val dy = abs(ry - regionY)
            dx > unloadRadius || dy > unloadRadius
        }

        for (regionId in toUnload) {
            unloadRegion(regionId)
        }

        // Load new regions
        for (regionId in neededRegions) {
            if (!loadedRegions.containsKey(regionId)) {
                loadRegion(regionId)
            }
        }
    }

    /**
     * Perform frustum culling.
     */
    fun cull(camera: Camera, aspectRatio: Float): CullResult {
        lastCullResult = cullingSystem.cull(camera, aspectRatio)
        return lastCullResult!!
    }

    /**
     * Render objects only (terrain should be rendered separately with renderTerrain).
     *
     * @param instancedColorShader Shader for instanced objects without textures
     * @param instancedTexturedShader Shader for instanced objects with textures
     * @param bindTexture Callback to bind textures
     */
    fun renderObjects(
        instancedColorShader: ShaderProgram,
        instancedTexturedShader: ShaderProgram?,
        bindTexture: ((Int) -> Unit)? = null
    ) {
        val cullResult = lastCullResult ?: return
        renderObjectsInternal(instancedColorShader, instancedTexturedShader, cullResult, bindTexture)
    }

    /**
     * Render terrain using color-only shader.
     * Note: Textured terrain batches are not rendered in multi-region mode.
     * For full textured terrain support, use single-region mode.
     */
    fun renderTerrain(shader: ShaderProgram) {
        val cullResult = lastCullResult ?: return

        shader.bind()

        for (regionChunk in cullResult.visibleRegions) {
            val loaded = loadedRegions[regionChunk.regionId] ?: continue
            val terrainMeshes = loaded.terrainMeshesByPlane[currentPlane] ?: continue
            val colorMesh = terrainMeshes.colorMesh ?: continue

            val model = Matrix4f().identity()
                .translate(regionChunk.bounds.minX, 0f, regionChunk.bounds.minZ)

            shader.setUniform("model", model)
            colorMesh.render()
        }
    }

    // Temporary matrix for building instance transforms
    private val tempModelMatrix = Matrix4f()

    /**
     * Internal method to render objects using instanced rendering.
     *
     * Uses full transform data from SceneObject including terrain alignment.
     * Transform order matches RS client (right-to-left matrix operations):
     * RotateY -> TiltX -> TiltZ -> Scale -> Offset -> HeightAdjust -> Translate
     */
    private fun renderObjectsInternal(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram?,
        cullResult: CullResult,
        bindTexture: ((Int) -> Unit)?
    ) {
        // Prepare instance data
        instanceManager.prepareFrame()

        // Add visible objects to instance batches
        // Multi-plane rendering: render ALL objects from all planes
        // Roof filtering: skip roof shapes (12-21) when showRoofs is false
        var addedInstances = 0
        for (obj in cullResult.visibleObjects) {
            val sceneObj = obj as? SceneObject ?: continue
            // Check if object should be rendered (roof filtering)
            if (!shouldRenderObject(sceneObj)) continue

            // Build full transform matrix matching RS client order:
            // RS applies: Mirror -> RotateY -> TiltX -> TiltZ (in that order on vertices)
            // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY * Mirror
            tempModelMatrix.identity()
                .translate(sceneObj.position)     // 6. Move to world position

            // Apply terrain alignment height adjustment
            if (sceneObj.heightAdjustment != 0f) {
                tempModelMatrix.translate(0f, sceneObj.heightAdjustment, 0f)  // 5. Height adjustment
            }

            tempModelMatrix
                .translate(sceneObj.offset)       // 4. Apply definition offset
                .scale(sceneObj.scale)            // 3. Scale

            // Apply terrain tilt rotations (for groundContourType=3 objects)
            if (sceneObj.tiltX != 0f || sceneObj.tiltZ != 0f) {
                tempModelMatrix.rotateZ(sceneObj.tiltZ)  // 2. Roll tilt
                tempModelMatrix.rotateX(sceneObj.tiltX)  // 1b. Pitch tilt
            }

            tempModelMatrix.rotateY(sceneObj.rotation)  // 1a. Object Y rotation

            // Apply mirroring if needed (negates Z coordinates)
            // This is applied last in the matrix chain = first on vertices
            if (sceneObj.mirrored) {
                tempModelMatrix.scale(1f, 1f, -1f)  // 0. Mirror across XY plane
            }

            if (instanceManager.addInstance(sceneObj.modelId, tempModelMatrix)) {
                addedInstances++
            }
        }

        // Upload and render
        instanceManager.uploadAll()

        if (texturedShader != null && bindTexture != null) {
            // Convert material IDs to OpenGL texture IDs before binding
            instanceManager.renderAll(colorShader, texturedShader) { materialId ->
                val textureId = TextureLoader.getTextureForMaterial(cache, materialId)
                bindTexture(textureId)
            }
        } else {
            instanceManager.renderUntextured(colorShader)
        }
    }

    /**
     * Load a region by ID.
     */
    private fun loadRegion(regionId: Int) {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff

        // Load region data from core RegionLoader (has objects and settings)
        val data = RegionLoader.loadRegion(cache, regionX, regionY) ?: return

        // Load terrain data from TerrainLoader (has computed heights)
        val terrainData = TerrainLoader.loadRegion(cache, regionX, regionY)

        // Create batched terrain meshes for all planes
        val terrainMeshesByPlane = arrayOfNulls<TerrainLoader.TerrainMeshes>(4)
        for (plane in 0 until 4) {
            terrainMeshesByPlane[plane] = TerrainLoader.loadRegionMeshes(cache, regionX, regionY, plane)
        }

        // Create object instances for ALL planes
        // Use TerrainLoader's computed heights for consistency with terrain mesh
        // Also provide RS-unit height sampler for per-vertex displacement (contourType 1)
        // Objects on the region's last row/column sample a corner one tile past the
        // region edge, which the client reads out of its neighbour; see
        // TerrainLoader.getExtendedTileHeight.
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val objectsByPlane = ObjectRenderer.createInstancesAllPlanes(
            data.objects,
            regionX,
            regionY,
            tileHeights = { x, y, plane ->
                if (terrainData != null) {
                    TerrainLoader.getExtendedTileHeightScaled(terrainData.tiles, neighbors, plane, x, y)
                } else {
                    // Fallback to old method if terrain data unavailable. Clamped because
                    // this sampler now receives corner tile 64 for edge objects and the
                    // fallback has no neighbour to resolve it into; it would otherwise
                    // return 0 and drop the object to the floor.
                    getTileHeight(data, x.coerceIn(0, 63), y.coerceIn(0, 63), plane)
                }
            },
            rsHeightSampler = if (terrainData != null) {
                { x, y, plane -> TerrainLoader.getExtendedTileHeight(terrainData.tiles, neighbors, plane, x, y) }
            } else {
                null
            }
        )

        // Register needed models for all planes (standard instanced path)
        for ((_, objects) in objectsByPlane) {
            registerObjectModels(objects)
        }

        // Calculate region bounds in world space
        val worldMinX = regionX * 64 * tileSize
        val worldMinZ = -regionY * 64 * tileSize - regionSize
        val bounds = Rect(worldMinX, worldMinZ, worldMinX + regionSize, worldMinZ + regionSize)

        // Create region chunk for culling
        val chunk = RegionChunk(
            regionId = regionId,
            regionX = regionX,
            regionY = regionY,
            bounds = bounds,
            yMin = -100f,
            yMax = 500f
        )

        // Create LoadedRegion early so registerDisplacedModels can track displaced IDs on it
        val loaded = LoadedRegion(
            regionX = regionX,
            regionY = regionY,
            regionId = regionId,
            data = data,
            terrainMeshesByPlane = terrainMeshesByPlane,
            objectsByPlane = objectsByPlane,
            chunk = chunk
        )

        // Register displaced meshes for contourType 1 objects.
        // Each instance gets a unique mesh with per-vertex terrain displacement,
        // registered with a unique model ID. These use the instanced rendering path
        // with 1 instance each (the displacement is baked into the geometry).
        if (terrainData != null) {
            for ((plane, objects) in objectsByPlane) {
                registerDisplacedModels(objects, plane, terrainData, regionX, regionY, loaded)
            }
        }

        // Register with culling system
        cullingSystem.registerRegion(chunk)

        // Register objects with culling system (all planes)
        // Include full transform data (rotation, scale, offset, terrain alignment) for correct rendering
        // Use combined model ID for multi-model objects, including modifications
        // For contourType 1 objects with displacement, use the displaced model ID
        for ((plane, objects) in objectsByPlane) {
            for (obj in objects) {
                val objBounds = AABB(
                    Vector3f(obj.worldPosition.x - 2f, obj.worldPosition.y - 2f, obj.worldPosition.z - 2f),
                    Vector3f(obj.worldPosition.x + 2f, obj.worldPosition.y + 2f, obj.worldPosition.z + 2f)
                )

                // For contourType 1 objects with displacement, use the displaced model ID
                // so the rendering path picks up the displaced geometry
                val combinedModelId = getModelIdForInstance(obj)
                cullingSystem.registerObject(SceneObject(
                    objectId = obj.mapObject.id,
                    modelId = combinedModelId,
                    bounds = objBounds,
                    position = obj.worldPosition,
                    regionId = regionId,
                    plane = plane,
                    shape = obj.mapObject.shape,
                    rotation = obj.rotation,
                    scale = obj.scale,
                    offset = obj.offset,
                    tiltX = obj.terrainAlignment.tiltX,
                    tiltZ = obj.terrainAlignment.tiltZ,
                    heightAdjustment = obj.terrainAlignment.heightAdjustment,
                    mirrored = obj.mirrored
                ))
            }
        }

        loadedRegions[regionId] = loaded
    }

    /**
     * Unload a region.
     */
    private fun unloadRegion(regionId: Int) {
        val loaded = loadedRegions.remove(regionId) ?: return

        // Unregister displaced models owned by this region
        for (displacedId in loaded.displacedModelIds) {
            instanceManager.unregisterModel(displacedId)
            registeredModels.remove(displacedId)
        }

        // Clean up displacedModelIds entries for this region's objects
        for ((_, objects) in loaded.objectsByPlane) {
            for (obj in objects) {
                displacedModelIds.remove(obj)
            }
        }

        // Cleanup terrain meshes
        loaded.cleanup()

        // Unregister from culling system
        cullingSystem.unregisterRegion(regionId)
    }

    /**
     * Register models needed for object instances.
     * Uses combined models for multi-model objects (matching RS client behavior).
     * Applies retexturing/recoloring from ObjectDefinition if present.
     */
    private fun registerObjectModels(objects: List<ObjectInstance>) {
        for (obj in objects) {
            // Get the combined model ID for this object's models, including modifications
            // Objects with different retexture/recolor mappings get separate registrations
            val combinedId = ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
            if (registeredModels.contains(combinedId)) continue

            // Load and combine all models for this object with modifications applied
            val meshDataList = if (obj.modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
            }
            if (meshDataList != null && meshDataList.isNotEmpty()) {
                instanceManager.registerModel(combinedId, meshDataList)
                registeredModels.add(combinedId)
            }
        }
    }

    /**
     * Register displaced meshes for contourType 1 objects.
     *
     * For each object with groundContourType 1, this:
     * 1. Loads the base model MeshData
     * 2. Applies per-vertex terrain displacement using VertexDisplacer
     * 3. Registers the displaced mesh with a unique model ID in the instanceManager
     * 4. Stores the mapping from object identity -> displaced model ID
     *
     * These displaced meshes are rendered via the standard instanced path with
     * 1 instance each (the displacement is baked into the geometry, so the
     * transform matrix only needs translation/rotation/scale, no tilt).
     *
     * @param objects List of ObjectInstance for a single plane
     * @param plane The plane level (0-3)
     * @param terrainData Terrain data containing tile heights
     * @param regionX Region X coordinate
     * @param regionY Region Y coordinate
     */
    private fun registerDisplacedModels(
        objects: List<ObjectInstance>,
        plane: Int,
        terrainData: TerrainLoader.RegionData,
        regionX: Int,
        regionY: Int,
        loadedRegion: LoadedRegion
    ) {
        // Flattened once for the whole region; the per-vertex loop then reads a single
        // IntArray instead of walking the [plane][x][z] TileData array. Padded with
        // neighbour terrain so objects on the last row/column displace at all.
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val field = VertexDisplacer.HeightField.from(terrainData.tiles, neighbors)

        for (obj in objects) {
            if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) {
                continue
            }

            // Load base model MeshData (with modifications if any)
            val baseMeshDataList = if (obj.modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
            }
            if (baseMeshDataList == null || baseMeshDataList.isEmpty()) continue

            // Apply per-vertex displacement to each mesh batch
            val displacedMeshDataList = mutableListOf<MeshData>()
            var anyDisplaced = false

            for (meshData in baseMeshDataList) {
                val displaced = VertexDisplacer.displaceVertices(
                    meshData = meshData,
                    sceneX = obj.rsSceneX,
                    sceneY = obj.rsSceneY,
                    averageHeight = obj.rsAverageHeight,
                    plane = plane,
                    field = field,
                    rsRotation = obj.rsRotation,
                    mirrored = obj.mirrored,
                    resizeX = obj.definition.modelSizeX,
                    resizeY = obj.definition.modelSizeZ,
                    resizeZ = obj.definition.modelSizeY,
                    rsOffsetX = obj.definition.offsetX,
                    rsOffsetZ = obj.definition.offsetY
                )

                if (displaced != null) {
                    displacedMeshDataList.add(displaced)
                    anyDisplaced = true
                } else {
                    // No displacement needed for this batch (flat terrain), use original
                    displacedMeshDataList.add(meshData)
                }
            }

            if (!anyDisplaced) {
                // All batches had flat terrain, no displacement needed.
                // Object will use the standard shared model via registerObjectModels.
                continue
            }

            // Register displaced mesh with a unique model ID
            val displacedId = nextDisplacedModelId++
            instanceManager.registerModel(displacedId, displacedMeshDataList)
            registeredModels.add(displacedId)
            loadedRegion.displacedModelIds.add(displacedId)

            // Map this object instance to its displaced model ID
            displacedModelIds[obj] = displacedId
        }
    }

    /**
     * Get the model ID to use for rendering an ObjectInstance.
     *
     * For contourType 1 objects that have displaced geometry, returns the
     * displaced model ID. For all other objects, returns the standard
     * combined model ID (with modifications).
     */
    private fun getModelIdForInstance(obj: ObjectInstance): Int {
        val displacedId = displacedModelIds[obj]
        if (displacedId != null) return displacedId
        return ObjectRenderer.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
    }

    /**
     * Get tile height at LOCAL coordinates (0-63).
     * This is a FALLBACK method using core RegionLoader's raw heights.
     *
     * Prefer using TerrainLoader.getTileHeightScaled() which computes authentic
     * RS heights (cumulative, multi-octave noise) matching the terrain mesh.
     *
     * @deprecated Use TerrainLoader.getTileHeightScaled for heights matching terrain mesh
     */
    private fun getTileHeight(data: RegionData, localX: Int, localY: Int, plane: Int): Float {
        if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) return 0f

        val tileInfo = RegionLoader.getTileInfo(data.tiles, localX, localY, plane)
        // Old formula: rawHeight * 32 * DEFAULT_SCALE
        // Note: This doesn't match TerrainLoader's computed heights
        return tileInfo.height * 32f * CoordinateUtil.DEFAULT_SCALE
    }

    /**
     * Convert world X coordinate to region X.
     */
    private fun worldToRegionX(worldX: Float): Int {
        return floor(worldX / regionSize).toInt().coerceIn(0, 255)
    }

    /**
     * Convert world Z coordinate to region Y.
     */
    private fun worldToRegionY(worldZ: Float): Int {
        return floor(-worldZ / regionSize).toInt().coerceIn(0, 255)
    }

    /**
     * Jump to a specific region.
     */
    fun jumpToRegion(regionX: Int, regionY: Int): Vector3f {
        val worldX = regionX * regionSize + regionSize / 2
        val worldZ = -(regionY * regionSize + regionSize / 2)
        update(worldX, worldZ)
        return Vector3f(worldX, 50f, worldZ)
    }

    /**
     * Get scene statistics.
     */
    fun getStats(): MapSceneStats {
        val cullResult = lastCullResult
        return MapSceneStats(
            loadedRegions = loadedRegions.size,
            visibleRegions = cullResult?.visibleRegionCount ?: 0,
            totalObjects = loadedRegions.values.sumOf { it.objects.size },
            visibleObjects = cullResult?.visibleObjectCount ?: 0,
            loadedModels = registeredModels.size,
            cullingTimeMs = (cullResult?.cullingTimeNanos ?: 0) / 1_000_000f
        )
    }

    /**
     * Get list of loaded region IDs.
     */
    fun getLoadedRegionIds(): List<Int> = loadedRegions.keys.toList()

    /**
     * Check if a region is loaded.
     */
    fun isRegionLoaded(regionX: Int, regionY: Int): Boolean {
        return loadedRegions.containsKey((regionX shl 8) or regionY)
    }

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        for (region in loadedRegions.values) {
            region.cleanup()
        }
        loadedRegions.clear()
        cullingSystem.clear()
        instanceManager.cleanup()
        registeredModels.clear()
        displacedModelIds.clear()
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
    }
}
