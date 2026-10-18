package org.drynor.tools.cache.map

import org.drynor.core.model.ObjectShape
import org.drynor.core.model.Vars
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.model.ModelModifications
import org.drynor.tools.cache.model.TexturedModel
import org.drynor.tools.engine.render.InstanceManager
import org.drynor.tools.engine.render.MeshData
import org.drynor.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition

/**
 * Represents a renderable map object instance.
 *
 * The offset field contains the definition's model offset (offsetX, offsetY, offsetZ)
 * converted to OpenGL coordinates. These offsets should be applied AFTER rotation
 * to match the client's behavior.
 *
 * Terrain alignment is applied for objects with groundContourType 3 (hillChange)
 * via the terrainAlignment field, which provides rotation-based slope matching.
 *
 * Objects with groundContourType 1 use per-vertex terrain displacement instead
 * of rotation. These objects carry their RS-space positioning data (rsSceneX,
 * rsSceneY, rsAverageHeight) so that VertexDisplacer can create displaced mesh
 * copies during region loading.
 *
 * Model modifications (retexturing/recoloring) are applied when loading the mesh data.
 * These come from ObjectDefinition.retextureSrc/Dst and recolorSrc/Dst arrays.
 *
 * [modifications] and [cachedCombinedModelId] are mutable because [NormalMerger] rewrites them
 * once, right after the whole region's instances exist, to fold in the normals an object receives
 * from its neighbours. Nothing may read [cachedCombinedModelId] before that pass has run.
 */
data class ObjectInstance(
    val mapObject: MapObject,
    val definition: ObjectDefinition,
    val modelIds: List<Int>,
    var worldPosition: Vector3f,
    val rotation: Float,  // Y-axis rotation in radians
    val scale: Vector3f,
    val offset: Vector3f = Vector3f(),  // Model offset applied after rotation
    var terrainAlignment: TerrainAligner.AlignmentResult = TerrainAligner.AlignmentResult.NONE,  // Terrain tilt (type 3 only)
    val mirrored: Boolean = false,  // Whether to mirror the model (negate Z)
    var modifications: ModelModifications = ModelModifications.NONE,  // Retexture/recolor/normal-merge modifications
    val groundContourType: Int = 0,  // Ground contour type from ObjectDefinition (0-5)
    val groundContourModifier: Int = -1,  // Ground contour modifier from ObjectDefinition
    val rsSceneX: Int = 0,  // RS scene X position (baseX * 512 + sizeX * 256), region-local
    val rsSceneY: Int = 0,  // RS scene Y position (baseY * 512 + sizeY * 256), region-local
    var rsAverageHeight: Int = 0,  // Average terrain height in RS units at the object's centre
    val rsRotation: Int = mapObject.rotation,  // RS rotation 0-3; differs from mapObject for the
                                              // secondary segment of a WALL_WHOLE_CORNER
    var cachedCombinedModelId: Int = ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)
)

/**
 * Converts MapObjects into renderable instances with proper model selection
 * based on object shape and transforms.
 *
 * Following the RS client (SceneGraph.addObject), ALL objects are positioned
 * at the CENTER of their tile footprint. For walls (size 1x1), this is the
 * tile center. The wall model geometry extends from this center point to the
 * appropriate tile edge based on rotation.
 */
object ObjectRenderer {
    // Cached model meshes
    private val loadedModels = mutableSetOf<Int>()

    /**
     * Reference to the game cache, stored during [loadDefinitions].
     * Used internally for model definition lookups (e.g., alpha transition checks).
     */
    private lateinit var gameCache: Cache

    /**
     * Whether ground decorations are rendered.
     *
     * When true (default), ALL ground decorations render -- matching the RS client's
     * default groundDecoration preference (value 1 = ON).
     *
     * When false, only interactive, movement-blocking, or forceDisplayDecoration
     * ground decorations render (matching the client's preference value 0 = OFF).
     */
    var groundDecorationEnabled: Boolean = true

    /**
     * Vars instance for object transform resolution.
     *
     * Uses the core engine's Vars class for var/varbit access. Initialized
     * without a session since the map viewer doesn't need network sync.
     *
     * For the map viewer, all vars default to 0, which shows the default
     * (first) transform variant. This can be modified via setVar/setVarBit
     * to see different object states.
     */
    val vars: Vars = Vars().initWithoutSession()

    // Wall shape IDs that need edge positioning
    private val WALL_SHAPES = setOf(
        ObjectShape.WALL_STRAIGHT.id,           // 0
        ObjectShape.WALL_DIAGONAL_CORNER.id,    // 1
        ObjectShape.WALL_WHOLE_CORNER.id,       // 2
        ObjectShape.WALL_STRAIGHT_CORNER.id,    // 3
        ObjectShape.WALL_INTERACT.id            // 9
    )

    // Wall decoration shapes that also use edge positioning
    private val WALL_DECORATION_SHAPES = setOf(
        ObjectShape.STRAIGHT_INSIDE_WALL_DEC.id,    // 4
        ObjectShape.STRAIGHT_OUSIDE_WALL_DEC.id,    // 5
        ObjectShape.DIAGONAL_OUTSIDE_WALL_DEC.id,   // 6
        ObjectShape.DIAGONAL_INSIDE_WALL_DEC.id,    // 7
        ObjectShape.DIAGONAL_INWALL_DEC.id          // 8
    )

    /**
     * Load object definitions from the cache.
     * Uses the pre-loaded Cache.objects for efficiency.
     */
    fun loadDefinitions(cache: Cache) {
        gameCache = cache
        // Cache.objects is lazy-loaded, just trigger initialization
        Cache.objects
    }

    /**
     * Get object definition by ID (raw, without transform resolution).
     */
    fun getRawDefinition(objectId: Int): ObjectDefinition? {
        val defs = Cache.objects
        return if (objectId in defs.indices) defs[objectId] else null
    }

    /**
     * Get object definition by ID, resolving any transforms.
     *
     * Objects can transform based on player variables (varbits/varps).
     * This method resolves the transform using the core engine's var system.
     *
     * @param objectId The object ID to look up
     * @return The resolved ObjectDefinition, or null if the object should not be rendered
     */
    fun getDefinition(objectId: Int): ObjectDefinition? {
        val def = getRawDefinition(objectId) ?: return null
        return resolveTransform(def)
    }

    /**
     * Resolve transform for an object definition.
     *
     * Uses the core engine's varbit/varp system (Cache.varbits, Vars.BIT_MASKS)
     * to resolve object transforms based on variable values.
     *
     * Transform logic (from RS client ObjectType.getMultiLoc):
     * 1. If varbitId != -1, get var value from varbit
     * 2. Else if varpId != -1, get var value from varp
     * 3. If var value is a valid index into transforms array (excluding last element), use that transform ID
     * 4. Otherwise, use the last element of transforms array as fallback
     * 5. If transform ID is -1, the object should not be rendered
     *
     * @param def The object definition (may have transforms)
     * @return The resolved definition, or null if transform says don't render
     */
    fun resolveTransform(def: ObjectDefinition): ObjectDefinition? {
        val transforms = def.transforms
        // No transforms - return original definition
        if (transforms == null || transforms.isEmpty()) return def

        // Get var value using core engine's Vars class
        val varValue = when {
            def.varbit != -1 -> vars.getVarBit(def.varbit)
            def.varp != -1 -> vars.getVar(def.varp)
            else -> 0
        }

        // Resolve transform ID
        // transforms array has N+1 elements where the last is the fallback
        // Valid indices are 0 to transforms.size - 2
        val transformId = if (varValue >= 0 && varValue < transforms.size - 1) {
            transforms[varValue]
        } else {
            // Use last element as fallback
            transforms[transforms.size - 1]
        }

        // Transform ID of -1 means don't render
        if (transformId == -1) return null

        // Look up the transformed definition
        return getRawDefinition(transformId)
    }

    /**
     * Get model IDs for an object based on its shape.
     *
     * The shapes array in ObjectDefinition contains the ObjectShape for each model group.
     * modelIds is an array of IntArrays where each sub-array contains model IDs for a shape.
     *
     * @param def The object definition
     * @param shape The object shape (0-22)
     * @return List of model IDs to render for this object
     */
    fun getModelIds(def: ObjectDefinition, shape: Int): List<Int> {
        val modelIds = def.modelIds ?: return emptyList()
        val modelTypes = def.modelTypes

        // Matching client's getStationaryModel(): if modelTypes is null, no shape-specific
        // models exist, so no model can be resolved for any shape.
        // (In practice, modelTypes and modelIds are always decoded together from opcode 1/5,
        // so modelTypes==null implies modelIds==null, but be safe.)
        if (modelTypes == null) return emptyList()

        // Find model group matching this shape
        val targetShapeId = shape.toByte()

        for (i in modelTypes.indices) {
            if (modelTypes[i] == targetShapeId && i < modelIds.size) {
                return modelIds[i].toList()
            }
        }

        // Fallback: shapes 10 and 11 are interchangeable in the client
        // (both handled as interactive objects in SceneGraph.addObject)
        if (shape == 10 || shape == 11) {
            for (i in modelTypes.indices) {
                if ((modelTypes[i] == ObjectShape.SCENERY_INTERACT.id.toByte() || modelTypes[i] == ObjectShape.GROUND_INTERACT.id.toByte())
                    && i < modelIds.size) {
                    return modelIds[i].toList()
                }
            }
        }

        // No matching shape found - client's getStationaryModel() returns null here
        return emptyList()
    }

    /**
     * Convert rotation value (0-3) to radians.
     *
     * RS rotation (when viewed from above +Y axis):
     * - 0 = facing south (toward RS Z-)
     * - 1 = facing west (toward RS X-)  - 90 degrees CCW from 0
     * - 2 = facing north (toward RS Z+) - 180 degrees from 0
     * - 3 = facing east (toward RS X+)  - 270 degrees CCW from 0
     *
     * Since model coordinates are converted with Z negated (rsToOpenGL),
     * the rotation direction is inverted. RS CCW becomes OpenGL CW.
     * Therefore we use NEGATIVE angles in OpenGL.
     *
     * JOML rotateY uses CCW rotation (looking down -Y toward origin),
     * so negative angles produce CW rotation (matching RS CCW with flipped Z).
     */
    fun rotationToRadians(rotation: Int): Float {
        return when (rotation and 3) {
            0 -> 0f                          // No rotation
            1 -> -Math.PI.toFloat() / 2      // -90 degrees (CW in OpenGL = CCW in RS)
            2 -> -Math.PI.toFloat()          // -180 degrees
            3 -> -Math.PI.toFloat() * 3 / 2  // -270 degrees (or +90 degrees)
            else -> 0f
        }
    }

    /**
     * Calculate the position offset for objects.
     *
     * In the RS client (see SceneGraph.addObject in drynor-bot), ALL objects are
     * positioned at the CENTER of their tile footprint using:
     *   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + locSizeX * 256
     *   sceneY = (baseY << 9) + (locSizeY << 8) = baseY * 512 + locSizeY * 256
     *
     * For walls (size 1x1), this equals: baseX * 512 + 256, baseY * 512 + 256
     * This places them at the CENTER of their tile, not at the corner.
     *
     * The wall model geometry is designed to extend from this center point to the
     * appropriate tile edge based on rotation.
     *
     * @param shape The object shape ID
     * @param rotation The rotation value (0-3)
     * @param tileSize The size of one tile in world units
     * @param sizeX Object size in X direction (tiles)
     * @param sizeY Object size in Y direction (tiles)
     * @return Pair of (offsetX, offsetZ) to add to the base tile position
     */
    private fun getObjectPositionOffset(
        shape: Int,
        rotation: Int,
        tileSize: Float,
        sizeX: Int = 1,
        sizeY: Int = 1
    ): Pair<Float, Float> {
        // ALL objects (including walls) are centered within their tile footprint
        // RS formula: sceneX = baseX * 512 + sizeX * 256
        // In our coords with Z negated: offsetX = sizeX * tileSize / 2, offsetZ = -sizeY * tileSize / 2
        return Pair(sizeX * tileSize * 0.5f, -sizeY * tileSize * 0.5f)
    }

    /**
     * Calculate the decoration displacement offset for wall decorations.
     *
     * NOTE: Wall decoration models in RS are already positioned correctly relative
     * to the tile origin. The decorDisplacement value is used by the client for
     * other purposes (like collision), not for rendering offset.
     *
     * Wall decorations are placed at the same tile position as walls, and the model
     * geometry itself handles the visual offset from the wall surface.
     *
     * @param shape The decoration shape (4-8)
     * @param rotation The rotation value (0-3)
     * @param decorDisplacement The displacement value from object definition (default 64) - currently unused
     * @return Vector3f offset (always zero - model handles its own positioning)
     */
    fun getWallDecorationOffset(shape: Int, rotation: Int, decorDisplacement: Int): Vector3f {
        // Wall decoration models are already positioned correctly relative to tile origin
        // No additional offset needed - the model geometry handles visual placement
        return Vector3f(0f, 0f, 0f)
    }

    /**
     * Check if an object shape is a wall that needs edge positioning.
     */
    fun isWallShape(shape: Int): Boolean {
        return shape in WALL_SHAPES || shape in WALL_DECORATION_SHAPES
    }

    /**
     * Determine whether a ground decoration object should be rendered when
     * [groundDecorationEnabled] is false.
     *
     * Matches the RS727 client's SceneGraph.addObject() filtering for shape 22
     * (GROUND_DECORATION). When the ground decoration preference is OFF (value 0),
     * the client only renders ground decorations that are interactive, block
     * movement, or have forceDisplayDecoration set.
     *
     * This method is only called when [groundDecorationEnabled] is false (the user
     * has toggled ground decorations off). When the preference is ON (default),
     * all ground decorations render unconditionally, matching the RS client's
     * default groundDecoration preference (value 1).
     *
     * The hasActions computation mirrors the client's ObjectType.postDecode():
     * if interactive == -1 (not set by cache decode), it is computed from whether
     * the object has a single SCENERY_INTERACT shape or has any non-null options.
     *
     * @param def The resolved object definition
     * @return true if the ground decoration should be rendered, false to skip it
     */
    fun shouldRenderGroundDecoration(def: ObjectDefinition): Boolean {
        // Compute hasActions matching client ObjectType.postDecode()
        var hasActions = def.interactive
        if (hasActions == -1) {
            hasActions = 0
            // Check if the object has exactly one model type and it's SCENERY_INTERACT (10)
            val modelTypes = def.modelTypes
            if (modelTypes != null && modelTypes.size == 1 && modelTypes[0] == ObjectShape.SCENERY_INTERACT.id.toByte()) {
                hasActions = 1
            }
            // Check if the object has any non-null options (indices 0..4)
            if (hasActions == 0) {
                val options = def.options
                if (options != null) {
                    for (i in 0..minOf(4, options.size - 1)) {
                        if (options[i] != null) {
                            hasActions = 1
                            break
                        }
                    }
                }
            }
        }

        // Client filter: render if hasActions != 0 OR blocksMovement == 1 OR blocksLand
        return hasActions != 0 || def.solid == 1 || def.blocksLand
    }

    /**
     * Check whether a ground decoration's models have alpha transition, which
     * determines if the decoration would be visible in the RS client.
     *
     * In the RS client's Scene.drawTiles() rendering pipeline:
     * 1. Non-alpha objects are drawn BEFORE terrain (front-to-back depth sort)
     * 2. Terrain is drawn second, overwriting non-alpha objects at the same depth
     * 3. Alpha objects are drawn AFTER terrain (back-to-front depth sort)
     *
     * In the RS client, entirely untextured ground decoration models (like model 1105
     * for object 16438) sit at terrain level and are hidden by the terrain mesh due
     * to GL_LEQUAL depth testing. These are engine markers/placeholders, not meant
     * to be visually rendered. Ground decorations with textured faces (farming patches,
     * floor markings, etc.) render correctly above terrain.
     *
     * @param cache The game cache (for loading model definitions)
     * @param modelIds The model IDs for the ground decoration object
     * @return true if ANY model has textured faces (decoration is visible),
     *         false if all models are entirely untextured (hidden marker)
     */
    fun isGroundDecorationVisible(cache: Cache, modelIds: List<Int>): Boolean {
        for (modelId in modelIds) {
            if (ModelLoader.modelHasTextures(cache, modelId)) return true
        }
        return false
    }

    /**
     * Create renderable instances from a list of MapObjects for ALL planes.
     *
     * The client places objects using the average height of 4 corner tiles
     * at the object's bounding box center (see SceneGraph.addObject in drynor-bot).
     * For objects spanning multiple tiles, it samples the center tiles.
     *
     * Positions are computed in WORLD coordinates (based on regionX, regionY).
     * The tileHeights callback receives LOCAL coordinates (0-63).
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param tileHeights Optional height data callback (takes localX, localY, plane). Returns scaled height.
     *                    Coordinates may be 64, one past the region edge: pass a
     *                    neighbour-aware sampler (TerrainLoader.getExtendedTileHeightScaled)
     *                    or edge objects sample the wrong corner.
     * @param rsHeightSampler Optional callback returning raw RS height (unscaled int) at (localX, localY, plane).
     *                        Used for per-vertex displacement (contourType 1) to match RS integer arithmetic exactly.
     *                        Same 0..64 coordinate range as [tileHeights].
     * @return Map of plane -> list of ObjectInstance
     */
    fun createInstancesAllPlanes(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        tileHeights: ((Int, Int, Int) -> Float)? = null,
        rsHeightSampler: ((Int, Int, Int) -> Int)? = null
    ): Map<Int, List<ObjectInstance>> {
        val instancesByPlane = mutableMapOf<Int, MutableList<ObjectInstance>>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (obj in objects) {
            val plane = obj.level
            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Filter non-interactive ground decorations when preference is off
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            // Skip untextured ground decoration markers (hidden under terrain in client due to GL_LEQUAL)
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, modelIds)) continue

            // Convert to local tile coordinates
            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            // Skip if outside region bounds
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate the 4 corner tile coordinates for height sampling (in local coords)
            // Following client logic: sample center tiles of object bounding box
            val xA: Int
            val xB: Int
            if (localTileX + sizeX <= 64) {
                xA = localTileX + (sizeX shr 1)
                xB = localTileX + ((sizeX + 1) shr 1)
            } else {
                xA = localTileX
                xB = localTileX + 1
            }

            val yA: Int
            val yB: Int
            if (localTileY + sizeY <= 64) {
                yA = localTileY + (sizeY shr 1)
                yB = localTileY + ((sizeY + 1) shr 1)
            } else {
                yA = localTileY
                yB = localTileY + 1
            }

            // Deliberately NOT clamped to 0..63. For an object on the region's last row
            // or column the far corner is tile 64, which the client resolves out of the
            // *neighbouring* region: its height array spans the whole loaded scene plus a
            // guard row and column. Clamping re-reads the near corner instead, so the
            // averaged height -- and with it the object's Y position -- comes out wrong on
            // any sloped region edge. The samplers handle the overhang; see
            // TerrainLoader.getExtendedTileHeight.
            val x0 = xA
            val x1 = xB
            val y0 = yA
            val y1 = yB

            // Get average height of the 4 corners (matching client algorithm)
            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0, plane)
                val h10 = tileHeights.invoke(x1, y0, plane)
                val h01 = tileHeights.invoke(x0, y1, plane)
                val h11 = tileHeights.invoke(x1, y1, plane)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            // Compute RS-unit scene position and average height for per-vertex displacement.
            // These match the RS client's SceneGraph.addObject:
            //   sceneX = (baseX << 9) + (locSizeX << 8)
            //   sceneY = (baseY << 9) + (locSizeY << 8)
            //   averageHeight = (h00 + h10 + h01 + h11) shr 2
            // The sceneX/Y are region-local (relative to region's scene origin).
            // Computed for every object, not just the displaced ones: NormalMerger needs the same
            // integer scene placement to decide which vertices two neighbours share.
            val contourType = def.contouredGround.toInt()
            val contourMod = def.anInt3023
            val rsSceneX = (localTileX shl 9) + (sizeX shl 8)
            val rsSceneY = (localTileY shl 9) + (sizeY shl 8)
            val rsAvgHeight = if (rsHeightSampler != null) {
                (rsHeightSampler.invoke(x0, y0, plane) +
                    rsHeightSampler.invoke(x1, y0, plane) +
                    rsHeightSampler.invoke(x0, y1, plane) +
                    rsHeightSampler.invoke(x1, y1, plane)) shr 2
            } else {
                0
            }

            // Calculate WORLD position
            // World coords: starts at region origin, not (0,0)
            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            // Position in OpenGL coords
            // RS client places ALL objects at tile CENTER using:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            // Get position offset (centers object within its tile footprint)
            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            // Y position: terrain height (definition offsets applied separately in transform)
            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            // Calculate scale from definition (default is 128 = 1.0 scale)
            val scaleX = def.modelSizeX / 128f
            val scaleY = def.modelSizeZ / 128f
            val scaleZ = def.modelSizeY / 128f

            // Definition offsets (already in RS units * 4, convert to OpenGL units)
            // Applied AFTER rotation in the client (in world space)
            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

            // Calculate wall decoration offset if applicable
            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.offsetMultiplier)

            // Calculate terrain alignment for objects with groundContourType 1, 2, or 3
            // Must pass the same averageHeight that the object is positioned at
            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.contouredGround.toInt(),
                    groundContourModifier = def.anInt3023,
                    averageHeight = terrainHeight,  // Pass the same height object is positioned at
                    // Unlike the average-height sampling above, this clamp is not a bug worth
                    // removing on its own: calculateObjectTilt clamps its own sample
                    // coordinates before this lambda ever sees them, so this is dead weight
                    // over a guard that is itself unreachable. See the note on sampleHeight in
                    // TerrainAligner for the sweep that established that, and for what to do
                    // if `tiltprobe scan` ever stops reporting zero.
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY, plane)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            // Check if model should be mirrored (following RS client logic)
            // RS mirrors when: definition.inverted OR (WALL_WHOLE_CORNER and rotation > 3)
            val baseMirror = def.mirrored

            // Get model modifications (retexturing/recoloring) from definition
            val modifications = getModifications(def)

            // WALL_WHOLE_CORNER (shape 2) creates TWO wall segments at the same position:
            // - Primary wall: mirrored, using the original rotation
            // - Secondary wall: not mirrored, using (rotation + 1) & 3
            // This creates an L-shaped corner wall.
            val isWallWholeCorner = obj.shape == ObjectShape.WALL_WHOLE_CORNER.id

            if (isWallWholeCorner) {
                // Primary wall - mirrored (rotation + 4 in client causes inversion)
                val primaryInstance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = true,  // Primary wall is always mirrored for WALL_WHOLE_CORNER
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(primaryInstance)

                // Secondary wall - not mirrored, rotated by +90 degrees
                val secondaryRotation = (obj.rotation + 1) and 3
                val secondaryInstance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(secondaryRotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,  // Secondary wall uses definition.mirrored only
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight,
                    rsRotation = secondaryRotation
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(secondaryInstance)
            } else {
                // Normal object - single instance
                val instance = ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = position,
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                )
                instancesByPlane.getOrPut(plane) { mutableListOf() }.add(instance)
            }
        }

        // Merge vertex normals between adjacent objects before anyone reads the model ids the
        // meshes are cached under -- the merge is part of that identity. Spans every plane, since
        // an object and the one it shares a vertex with need not be on the same one.
        NormalMerger.apply(gameCache, instancesByPlane.values.flatten())

        return instancesByPlane
    }

    /**
     * Create renderable instances from a list of MapObjects for a specific plane.
     *
     * The client places objects using the average height of 4 corner tiles
     * at the object's bounding box center (see SceneGraph.addObject in drynor-bot).
     *
     * Positions are computed in WORLD coordinates (based on regionX, regionY).
     * The tileHeights callback receives LOCAL coordinates (0-63).
     *
     * @param objects List of map objects from RegionLoader
     * @param regionX Region X coordinate (for world position calculation)
     * @param regionY Region Y coordinate
     * @param plane The plane level
     * @param tileHeights Optional height data callback (takes localX, localY). Returns scaled height.
     *                    Coordinates may be 64, one past the region edge; see
     *                    [createInstancesAllPlanes].
     * @param rsHeightSampler Optional callback returning raw RS height (unscaled int) at (localX, localY).
     *                        Used for per-vertex displacement (contourType 1) to match RS integer arithmetic exactly.
     *                        Same 0..64 coordinate range as [tileHeights].
     * @return List of ObjectInstance ready for rendering
     */
    fun createInstances(
        objects: List<MapObject>,
        regionX: Int,
        regionY: Int,
        plane: Int,
        tileHeights: ((Int, Int) -> Float)? = null,
        rsHeightSampler: ((Int, Int) -> Int)? = null
    ): List<ObjectInstance> {
        val instances = mutableListOf<ObjectInstance>()
        val baseWorldX = regionX * 64
        val baseWorldY = regionY * 64
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        for (obj in objects) {
            if (obj.level != plane) continue

            val def = getDefinition(obj.id) ?: continue
            val modelIds = getModelIds(def, obj.shape)
            if (modelIds.isEmpty()) continue

            // Filter non-interactive ground decorations when preference is off
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            // Skip untextured ground decoration markers (hidden under terrain in client due to GL_LEQUAL)
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, modelIds)) continue

            // Convert to local tile coordinates
            val localTileX = obj.x - baseWorldX
            val localTileY = obj.y - baseWorldY

            // Skip if outside region bounds
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate the 4 corner tile coordinates for height sampling (in local coords)
            val xA: Int
            val xB: Int
            if (localTileX + sizeX <= 64) {
                xA = localTileX + (sizeX shr 1)
                xB = localTileX + ((sizeX + 1) shr 1)
            } else {
                xA = localTileX
                xB = localTileX + 1
            }

            val yA: Int
            val yB: Int
            if (localTileY + sizeY <= 64) {
                yA = localTileY + (sizeY shr 1)
                yB = localTileY + ((sizeY + 1) shr 1)
            } else {
                yA = localTileY
                yB = localTileY + 1
            }

            // Deliberately NOT clamped to 0..63. For an object on the region's last row
            // or column the far corner is tile 64, which the client resolves out of the
            // *neighbouring* region: its height array spans the whole loaded scene plus a
            // guard row and column. Clamping re-reads the near corner instead, so the
            // averaged height -- and with it the object's Y position -- comes out wrong on
            // any sloped region edge. The samplers handle the overhang; see
            // TerrainLoader.getExtendedTileHeight.
            val x0 = xA
            val x1 = xB
            val y0 = yA
            val y1 = yB

            // Get average height of the 4 corners (matching client algorithm)
            val terrainHeight = if (tileHeights != null) {
                val h00 = tileHeights.invoke(x0, y0)
                val h10 = tileHeights.invoke(x1, y0)
                val h01 = tileHeights.invoke(x0, y1)
                val h11 = tileHeights.invoke(x1, y1)
                (h00 + h10 + h01 + h11) / 4f
            } else {
                0f
            }

            // Computed for every object, not just the displaced ones: NormalMerger needs the same
            // integer scene placement to decide which vertices two neighbours share.
            val contourType = def.contouredGround.toInt()
            val contourMod = def.anInt3023
            val rsSceneX = (localTileX shl 9) + (sizeX shl 8)
            val rsSceneY = (localTileY shl 9) + (sizeY shl 8)
            val rsAvgHeight = if (rsHeightSampler != null) {
                (rsHeightSampler.invoke(x0, y0) +
                    rsHeightSampler.invoke(x1, y0) +
                    rsHeightSampler.invoke(x0, y1) +
                    rsHeightSampler.invoke(x1, y1)) shr 2
            } else {
                0
            }

            // Calculate WORLD position
            val worldTileX = regionX * 64 + localTileX
            val worldTileY = regionY * 64 + localTileY

            // Position in OpenGL coords
            // RS client places ALL objects at tile CENTER using:
            //   sceneX = (baseX << 9) + (locSizeX << 8) = baseX * 512 + sizeX * 256
            val basePosX = worldTileX * tileSize
            val basePosZ = -worldTileY * tileSize

            // Get position offset (centers object within its tile footprint)
            val (posOffsetX, posOffsetZ) = getObjectPositionOffset(obj.shape, obj.rotation, tileSize, sizeX, sizeY)
            val posX = basePosX + posOffsetX
            val posZ = basePosZ + posOffsetZ

            // Y position: terrain height (definition offsets applied separately in transform)
            val posY = terrainHeight

            val position = Vector3f(posX, posY, posZ)

            // Calculate scale from definition (default is 128 = 1.0 scale)
            val scaleX = def.modelSizeX / 128f
            val scaleY = def.modelSizeZ / 128f
            val scaleZ = def.modelSizeY / 128f

            // Definition offsets (already in RS units * 4, convert to OpenGL units)
            // Applied AFTER rotation in the client (in world space)
            val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
            val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
            val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

            // Calculate wall decoration offset if applicable
            val decorOffset = getWallDecorationOffset(obj.shape, obj.rotation, def.offsetMultiplier)

            // Calculate terrain alignment for objects with groundContourType 1, 2, or 3
            // Must pass the same averageHeight that the object is positioned at
            val terrainAlignment = if (tileHeights != null) {
                TerrainAligner.calculateObjectTilt(
                    localTileX = localTileX,
                    localTileY = localTileY,
                    sizeX = sizeX,
                    sizeY = sizeY,
                    groundContourType = def.contouredGround.toInt(),
                    groundContourModifier = def.anInt3023,
                    averageHeight = terrainHeight,  // Pass the same height object is positioned at
                    // Redundant over calculateObjectTilt's own clamp, which is itself
                    // unreachable; see the note on sampleHeight in TerrainAligner.
                    heightSampler = { tx, ty ->
                        val clampedX = tx.coerceIn(0, 63)
                        val clampedY = ty.coerceIn(0, 63)
                        tileHeights.invoke(clampedX, clampedY)
                    }
                )
            } else {
                TerrainAligner.AlignmentResult.NONE
            }

            // Check if model should be mirrored (following RS client logic)
            val baseMirror = def.mirrored

            // Get model modifications (retexturing/recoloring) from definition
            val modifications = getModifications(def)

            // WALL_WHOLE_CORNER (shape 2) creates TWO wall segments at the same position:
            // - Primary wall: mirrored, using the original rotation
            // - Secondary wall: not mirrored, using (rotation + 1) & 3
            // This creates an L-shaped corner wall.
            val isWallWholeCorner = obj.shape == ObjectShape.WALL_WHOLE_CORNER.id

            if (isWallWholeCorner) {
                // Primary wall - mirrored (rotation + 4 in client causes inversion)
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = true,  // Primary wall is always mirrored for WALL_WHOLE_CORNER
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))

                // Secondary wall - not mirrored, rotated by +90 degrees
                val secondaryRotation = (obj.rotation + 1) and 3
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = Vector3f(position),
                    rotation = rotationToRadians(secondaryRotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,  // Secondary wall uses definition.mirrored only
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight,
                    rsRotation = secondaryRotation
                ))
            } else {
                // Normal object - single instance
                instances.add(ObjectInstance(
                    mapObject = obj,
                    definition = def,
                    modelIds = modelIds,
                    worldPosition = position,
                    rotation = rotationToRadians(obj.rotation),
                    scale = Vector3f(scaleX, scaleY, scaleZ),
                    offset = Vector3f(
                        defOffsetX + decorOffset.x,
                        -defOffsetY + decorOffset.y,
                        -defOffsetZ + decorOffset.z
                    ),
                    terrainAlignment = terrainAlignment,
                    mirrored = baseMirror,
                    modifications = modifications,
                    groundContourType = contourType,
                    groundContourModifier = contourMod,
                    rsSceneX = rsSceneX,
                    rsSceneY = rsSceneY,
                    rsAverageHeight = rsAvgHeight
                ))
            }
        }

        // See createInstancesAllPlanes: must run before the model ids are read.
        NormalMerger.apply(gameCache, instances)

        return instances
    }

    /**
     * Register object models with an InstanceManager for instanced rendering.
     *
     * @param cache The game cache
     * @param instanceManager The instance manager to register with
     * @param modelIds Set of model IDs to register
     */
    fun registerModels(
        cache: Cache,
        instanceManager: InstanceManager,
        modelIds: Set<Int>
    ) {
        for (modelId in modelIds) {
            if (loadedModels.contains(modelId)) continue

            val texturedModel = ModelLoader.loadTexturedModel(cache, modelId) ?: continue
            val meshDataList = texturedModelToMeshData(texturedModel)

            if (meshDataList.isNotEmpty()) {
                instanceManager.registerModel(modelId, meshDataList)
                loadedModels.add(modelId)
            }
        }
    }

    /**
     * Convert a TexturedModel to MeshData list for instancing.
     */
    private fun texturedModelToMeshData(model: TexturedModel): List<MeshData> {
        val result = mutableListOf<MeshData>()

        // Add color batch if present
        model.colorBatch?.let { mesh ->
            // Extract data from mesh - we need to read it back
            // Since Mesh is a GPU resource, we'll track this differently
            // For now, we'll mark textureId as -1 for untextured
            result.add(MeshData(
                positions = floatArrayOf(), // Will be filled during actual registration
                colors = floatArrayOf(),
                textureCoords = null,
                normals = floatArrayOf(),
                indices = intArrayOf(),
                textureId = -1
            ))
        }

        // Add texture batches
        for (batch in model.textureBatches) {
            result.add(MeshData(
                positions = floatArrayOf(),
                colors = null,
                textureCoords = floatArrayOf(),
                normals = floatArrayOf(),
                indices = intArrayOf(),
                textureId = batch.textureId
            ))
        }

        return result
    }

    /**
     * Add object instances to an InstanceManager for instanced rendering.
     *
     * This handles the standard instanced path where all instances of a model
     * share the same geometry. Objects with groundContourType 1 that need
     * per-vertex displacement are also added here (their displaced meshes are
     * registered separately by MapScene).
     *
     * Transform order matches the client:
     * 0. Mirror (negate Z) if definition.mirrored is true
     * 1. Rotate around Y axis (object orientation)
     * 2. Apply terrain tilt (X and Z rotations) for groundContourType=3 objects
     * 3. Scale by resizeX/Y/Z
     * 4. Apply definition offset (offsetX/Y/Z) AFTER rotation
     * 5. Apply terrain height adjustment
     * 6. Translate to world position
     *
     * @param instanceManager The instance manager
     * @param instances List of ObjectInstance from createInstances()
     */
    fun addInstances(
        instanceManager: InstanceManager,
        instances: List<ObjectInstance>
    ) {
        val modelMatrix = Matrix4f()

        for (instance in instances) {
            for (modelId in instance.modelIds) {
                if (!instanceManager.isModelRegistered(modelId)) continue

                // Build model matrix matching client order:
                // RS applies: Mirror -> RotateY -> TiltX -> TiltZ (in that order on vertices)
                // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY * Mirror
                modelMatrix.identity()
                    .translate(instance.worldPosition)  // 6. Move to world position

                // Apply terrain alignment height adjustment
                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.translate(0f, instance.terrainAlignment.heightAdjustment, 0f)
                }

                modelMatrix
                    .translate(instance.offset)         // 5. Apply definition offset
                    .scale(instance.scale)              // 4. Scale

                // Apply terrain tilt rotations
                if (instance.terrainAlignment.needsAlignment()) {
                    modelMatrix.rotateZ(instance.terrainAlignment.tiltZ)  // 3. Roll tilt
                    modelMatrix.rotateX(instance.terrainAlignment.tiltX)  // 2. Pitch tilt
                }

                modelMatrix.rotateY(instance.rotation)  // 1. Object Y rotation

                // Apply mirroring if needed (negates Z coordinates)
                // This is applied last in the matrix chain = first on vertices
                if (instance.mirrored) {
                    modelMatrix.scale(1f, 1f, -1f)  // 0. Mirror across XY plane
                }

                instanceManager.addInstance(modelId, modelMatrix)
            }
        }
    }

    /**
     * Get all unique model IDs needed for a list of objects.
     */
    fun collectModelIds(objects: List<MapObject>): Set<Int> {
        val modelIds = mutableSetOf<Int>()

        for (obj in objects) {
            val def = getDefinition(obj.id) ?: continue
            val objModelIds = getModelIds(def, obj.shape)
            if (objModelIds.isEmpty()) continue

            // Filter non-interactive ground decorations when preference is off
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !groundDecorationEnabled && !shouldRenderGroundDecoration(def)) continue
            // Skip untextured ground decoration markers (hidden under terrain in client due to GL_LEQUAL)
            if (obj.shape == ObjectShape.GROUND_DECORATION.id && !isGroundDecorationVisible(gameCache, objModelIds)) continue

            modelIds.addAll(objModelIds)
        }

        return modelIds
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        loadedModels.clear()
    }

    /**
     * Get the combined model ID for an object's models.
     * For objects with multiple model IDs, this returns a unique ID that represents
     * all models combined into a single mesh (matching RS client behavior).
     *
     * @param modelIds The list of model IDs for the object
     * @return A unique ID for the combined model (negative for multi-model, positive for single)
     */
    fun getCombinedModelId(modelIds: List<Int>): Int {
        return ModelLoader.getCombinedModelId(modelIds)
    }

    /**
     * Get the combined model ID for an object's models with modifications.
     * This includes retexturing/recoloring in the unique ID so that objects
     * with different modifications get separate mesh registrations.
     *
     * @param modelIds The list of model IDs for the object
     * @param modifications The retexture/recolor modifications
     * @return A unique ID for the model+modifications combination
     */
    fun getCombinedModelIdWithModifications(modelIds: List<Int>, modifications: ModelModifications): Int {
        return ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)
    }

    /**
     * Create ModelModifications from an ObjectDefinition.
     * Extracts retextureSrc/Dst, recolorSrc/Dst arrays, and ambient lightness factor.
     *
     * The ambient value matches the RS client's formula: adjustedAmbient = 64 + def.brightness.
     * The RS client always applies blendColorLightness(hsl, adjustedAmbient) to every face
     * color, which scales the lightness component by adjustedAmbient/128. With the default
     * ambient of 0, this halves the lightness (64/128 = 0.5).
     *
     * @param def The object definition
     * @return ModelModifications instance (may be NONE if no modifications)
     */
    fun getModifications(def: ObjectDefinition): ModelModifications {
        val hasRetexture = def.originalTextureColours != null && def.modifiedTextureColours != null
        val hasRecolor = def.originalColours != null && def.modifiedColours != null
        val adjustedAmbient = ModelModifications.DEFAULT_AMBIENT + def.brightness

        if (!hasRetexture && !hasRecolor && adjustedAmbient == ModelModifications.DEFAULT_AMBIENT) {
            return ModelModifications.NONE
        }

        return ModelModifications(
            retextureSrc = def.originalTextureColours,
            retextureDst = def.modifiedTextureColours,
            recolorSrc = def.originalColours,
            recolorDst = def.modifiedColours,
            ambient = adjustedAmbient
        )
    }
}
