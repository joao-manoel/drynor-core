package org.darkan.tools.cache.map

import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.engine.render.MeshData
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements per-vertex terrain displacement for ground contour type 1 objects.
 *
 * In the RS client (ModelSM.contourToGround, type 1), each vertex's Y coordinate
 * is individually adjusted to follow the terrain height at that vertex's XZ position.
 * This is different from type 3 (hillChange) which rotates the entire model.
 *
 * Type 1 displacement cannot be done via GPU instancing because each instance needs
 * unique vertex data. Instead, this class creates modified MeshData copies with
 * displaced vertices for each object instance.
 *
 * IMPORTANT: In the RS client, contourToGround is called AFTER the model has been
 * rotated, scaled, and translated in getStationaryModel. The order is:
 *   1. mirror (negate Z if inverted)
 *   2. rotateWithNormals (rotation 0-3 applied to X/Z vertex data)
 *   3. scale (resizeX/Y/Z applied to vertices, >> 7)
 *   4. translate (offsetX/Y/Z added to vertices)
 *   5. contourToGround (operates on fully-transformed vertices)
 *
 * Since our renderer stores raw model vertices and applies transforms via GPU matrix,
 * the VertexDisplacer must replicate the client's pre-displacement transforms on the
 * XZ coordinates for correct terrain height lookup. The Y displacement delta is
 * computed to account for the GPU scale factor.
 *
 * Algorithm (from ModelSM.contourToGround in darkan-bot):
 * 1. For each vertex, compute its absolute scene position: sceneX + transformedX, sceneY + transformedZ
 * 2. Find which tile the vertex is in and the sub-tile remainder
 * 3. Bilinearly interpolate terrain height at the vertex position
 * 4. New vertexY = interpolatedHeight + transformedY - averageHeight
 *
 * RS coordinate system: X = east, Y = height (positive DOWN), Z = north
 * RS tile: 512 units, tileScale = 9, tileMask = 511
 *
 * ## Cost structure
 *
 * The displacement loop runs once per *object instance*, not per unique model, so a
 * region with 25k instances pays it 25k times even though only ~700 distinct models
 * exist. Three things keep that affordable:
 *
 *  - [HeightField] flattens the `[plane][x][z]` array-of-`TileData` into one `IntArray`
 *    per plane, built once per region load. The inner loop then does a single indexed
 *    read instead of two array derefs plus an object field load.
 *  - [modelBounds] caches each model's RS-space XZ extent, so [footprintIsFlat] can
 *    decide in O(footprint tiles) whether an object stands on ground that is uniformly
 *    at its average height. When it does, *every* vertex would displace by zero, so the
 *    whole per-vertex pass is skipped. Most objects in a town sit on flat ground.
 *  - The output array is only allocated once a vertex actually moves.
 */
object VertexDisplacer {

    /** RS units per tile */
    private const val TILE_UNITS = 512

    /** Bit shift for tile scale (2^9 = 512) */
    private const val TILE_SCALE = 9

    /** Bitmask for sub-tile position (512 - 1 = 511) */
    private const val TILE_MASK = TILE_UNITS - 1

    /** Inverse scale for converting OpenGL coords back to RS */
    private const val INV_SCALE = 128f  // 1 / DEFAULT_SCALE

    /**
     * Upper bound on cached model bounds entries. Each entry is a 4-int array keyed by
     * combined model id, so the cache is tiny, but a long session panning across the
     * world would otherwise grow it without limit.
     */
    private const val BOUNDS_CACHE_LIMIT = 65536

    /**
     * Terrain heights around one region, flattened per plane.
     *
     * `heights[plane][x * depth + z]` is the corner height of *padded* tile (x, z),
     * where padded tile `(t + PAD_TILES)` holds region tile `t`. Built once per region
     * load and shared, read-only, by every displacement worker.
     *
     * ## Why the field is padded
     *
     * Bilinear interpolation at a vertex needs the `tile + 1` corners, so
     * [displaceVertices] rejects any vertex whose tile is not in `[0, size - 2]`. The
     * RS client applies an *identical* guard, but against a height array sized to the
     * whole loaded scene plus one guard row and column (`MapLoader`:
     * `Array(planes) { Array(sceneX + 1) { IntArray(sceneY + 1) } }`), so a
     * region-internal tile 63 is nowhere near that array's edge.
     *
     * Built from a bare 64x64 region the same guard rejects the entire last row and
     * column, and every object standing on `x == 63` or `y == 63` receives no
     * displacement at all -- which is what made one rail of a Duel Arena ramp render as
     * flat bars beside its fully-contoured twin. **The specification is the array
     * extent, not the test**: the guard is correct once the array reaches past the
     * region, so the padding is what gets fixed here.
     *
     * [PAD_TILES] tiles per side comfortably covers any real object's footprint plus
     * model overhang, at ~102 KB per region across four planes.
     */
    class HeightField private constructor(
        val width: Int,
        val depth: Int,
        /** Tiles of neighbour terrain carried on each side; see the class docs. */
        val pad: Int,
        private val planes: Array<IntArray>
    ) {
        /**
         * RS units to add to a region-local scene coordinate before indexing this field.
         *
         * Callers work in region-local scene units (tile 0 at 0); the field's origin sits
         * [pad] tiles further out, so both consumers shift by this amount.
         */
        val rsOrigin: Int = pad * TILE_UNITS

        /** Flat height array for [plane]; index a padded tile as `x * depth + z`. */
        fun heights(plane: Int): IntArray = planes[plane]

        companion object {
            private val EMPTY = IntArray(0)

            /** Neighbour tiles carried on each side of the region. See [HeightField]. */
            const val PAD_TILES = 8

            /**
             * Flatten a `[plane][x][z]` tile array into per-plane height arrays, extended
             * by [PAD_TILES] on every side.
             *
             * The border is filled from [neighbors] via
             * [TerrainLoader.getExtendedTileHeight], which falls back to this region's own
             * edge tile wherever a neighbour is absent. Passing null neighbours therefore
             * still produces a correctly *sized* field -- edge objects displace against a
             * clamped skirt rather than against nothing, which is strictly better than the
             * unpadded behaviour but worse than supplying neighbours.
             */
            fun from(
                tiles: Array<Array<Array<TerrainLoader.TileData>>>,
                neighbors: TerrainLoader.NeighborData? = null
            ): HeightField {
                val regionWidth = if (tiles.isEmpty()) 0 else tiles[0].size
                val regionDepth = if (regionWidth > 0) tiles[0][0].size else 0
                if (regionWidth <= 0 || regionDepth <= 0) {
                    return HeightField(0, 0, 0, Array(tiles.size) { EMPTY })
                }

                val pad = PAD_TILES
                val width = regionWidth + 2 * pad
                val depth = regionDepth + 2 * pad
                val planes = Array(tiles.size) { plane ->
                    val planeTiles = tiles[plane]
                    if (planeTiles.size != regionWidth) return@Array EMPTY
                    val out = IntArray(width * depth)
                    for (x in 0 until width) {
                        val tileX = x - pad
                        val base = x * depth
                        if (tileX in 0 until regionWidth) {
                            // Interior column: the region's own tiles in the middle, the
                            // neighbour lookup only for the two padded end runs.
                            val column = planeTiles[tileX]
                            for (z in 0 until depth) {
                                val tileZ = z - pad
                                out[base + z] = if (tileZ in 0 until regionDepth) {
                                    column[tileZ].height
                                } else {
                                    TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, tileX, tileZ)
                                }
                            }
                        } else {
                            for (z in 0 until depth) {
                                out[base + z] =
                                    TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, tileX, z - pad)
                            }
                        }
                    }
                    out
                }
                return HeightField(width, depth, pad, planes)
            }
        }
    }

    /**
     * Cached RS-space XZ extent per combined model id, as `[minX, maxX, minZ, maxZ]`.
     *
     * The extent is a property of the decoded mesh alone, so it is shared across
     * regions exactly like the decoded mesh cache it mirrors. Populated with
     * `putIfAbsent` so a concurrent duplicate computation wastes a few microseconds
     * rather than holding a bin lock.
     */
    private val boundsCache = ConcurrentHashMap<Int, IntArray>()

    /** Drop the cached model bounds (e.g. when the cache backing the models changes). */
    fun clearCaches() {
        boundsCache.clear()
    }

    /**
     * RS-space XZ extent of every mesh in [meshes] combined, as `[minX, maxX, minZ, maxZ]`.
     *
     * Returns null when the model has no vertices. Values are in the same RS model-local
     * units the displacement loop derives per vertex, so transforming this box reproduces
     * the exact tile span the loop would touch.
     */
    fun modelBounds(combinedModelId: Int, meshes: List<MeshData>): IntArray? {
        boundsCache[combinedModelId]?.let { return if (it.isEmpty()) null else it }
        val computed = computeBounds(meshes)
        if (boundsCache.size < BOUNDS_CACHE_LIMIT) {
            val stored = boundsCache.putIfAbsent(combinedModelId, computed ?: IntArray(0))
            if (stored != null) return if (stored.isEmpty()) null else stored
        }
        return computed
    }

    private fun computeBounds(meshes: List<MeshData>): IntArray? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (mesh in meshes) {
            val positions = mesh.positions
            var idx = 0
            while (idx < positions.size) {
                val rsX = (positions[idx] * INV_SCALE).toInt()
                val rsZ = (-positions[idx + 2] * INV_SCALE).toInt()
                if (rsX < minX) minX = rsX
                if (rsX > maxX) maxX = rsX
                if (rsZ < minZ) minZ = rsZ
                if (rsZ > maxZ) maxZ = rsZ
                idx += 3
            }
        }
        if (minX > maxX) return null
        return intArrayOf(minX, maxX, minZ, maxZ)
    }

    /**
     * Whether every terrain corner an object could sample sits at its average height.
     *
     * When true, the bilinear interpolation at every vertex resolves to exactly
     * [averageHeight] (interpolating four equal integers is exact), so every
     * displacement delta is zero and [displaceVertices] would return null for all of
     * the object's meshes. The caller can skip the per-vertex pass entirely.
     *
     * The test is deliberately conservative: it checks a *superset* of the corners the
     * vertex loop would read (the transformed bounding box, clamped to the terrain), so
     * a false answer only ever costs a fallthrough to the exact path.
     *
     * @param bounds model extent from [modelBounds]
     */
    fun footprintIsFlat(
        bounds: IntArray,
        sceneX: Int,
        sceneY: Int,
        averageHeight: Int,
        plane: Int,
        field: HeightField,
        rsRotation: Int,
        mirrored: Boolean,
        resizeX: Int,
        resizeY: Int,
        resizeZ: Int,
        rsOffsetX: Int,
        rsOffsetZ: Int
    ): Boolean {
        // Negative scales would invert the interval endpoints; no RS definition uses
        // them, but fall back to the exact path rather than reasoning about it.
        if (resizeX < 0 || resizeZ < 0) return false
        // A zero Y scale makes the delta 0/0 = NaN rather than 0, which is a change.
        // No real definition does this; take the exact path if one ever does.
        if (resizeY == 0) return false

        val width = field.width
        val depth = field.depth
        if (width <= 0 || depth <= 0) return false
        val heights = field.heights(plane)
        if (heights.isEmpty()) return false

        var minZ = bounds[2]
        var maxZ = bounds[3]
        if (mirrored) {
            val swapped = -maxZ
            maxZ = -minZ
            minZ = swapped
        }
        val minX = bounds[0]
        val maxX = bounds[1]

        // Rotation maps each output axis to one input axis with a sign; scaling by a
        // non-negative factor followed by an arithmetic shift is monotone, so the
        // extremes of the transformed box come from the extremes of the source box.
        val loX: Int
        val hiX: Int
        val loZ: Int
        val hiZ: Int
        when (rsRotation and 3) {
            1 -> { loX = minZ; hiX = maxZ; loZ = -maxX; hiZ = -minX }
            2 -> { loX = -maxX; hiX = -minX; loZ = -maxZ; hiZ = -minZ }
            3 -> { loX = -maxZ; hiX = -minZ; loZ = minX; hiZ = maxX }
            else -> { loX = minX; hiX = maxX; loZ = minZ; hiZ = maxZ }
        }

        // Scene coordinates are region-local; the field's origin sits `pad` tiles further
        // out, so shift by the same amount [displaceVertices] does.
        val padUnits = field.rsOrigin
        val localLoX = ((resizeX * loX) shr 7) + rsOffsetX + sceneX + padUnits
        val localHiX = ((resizeX * hiX) shr 7) + rsOffsetX + sceneX + padUnits
        val localLoZ = ((resizeZ * loZ) shr 7) + rsOffsetZ + sceneY + padUnits
        val localHiZ = ((resizeZ * hiZ) shr 7) + rsOffsetZ + sceneY + padUnits

        // Corners read for a vertex in tile t are t and t + 1, so this range is over
        // corner indices and its far end may legitimately reach width - 1.
        var tx0 = localLoX shr TILE_SCALE
        var tx1 = (localHiX shr TILE_SCALE) + 1
        var tz0 = localLoZ shr TILE_SCALE
        var tz1 = (localHiZ shr TILE_SCALE) + 1
        if (tx0 < 0) tx0 = 0
        if (tz0 < 0) tz0 = 0
        if (tx1 > width - 1) tx1 = width - 1
        if (tz1 > depth - 1) tz1 = depth - 1
        // Wholly outside the *padded* field, so [displaceVertices] would reject every
        // vertex on the same bounds and return null: nothing moves either way.
        if (tx0 > tx1 || tz0 > tz1) return true

        for (tx in tx0..tx1) {
            val base = tx * depth
            for (tz in tz0..tz1) {
                if (heights[base + tz] != averageHeight) return false
            }
        }
        return true
    }

    /**
     * Create displaced MeshData for a contourType 1 object.
     *
     * Takes the original model MeshData (in OpenGL coordinates) and displaces
     * each vertex's Y coordinate based on bilinear interpolation of terrain
     * heights at the vertex's world XZ position.
     *
     * The vertex XZ positions are transformed by the object's mirror, rotation,
     * scale, and offset to match the RS client's behavior where contourToGround
     * operates on fully-transformed vertex data.
     *
     * The Y displacement delta is computed as (interpolatedHeight - averageHeight) / resizeY
     * to account for the GPU scale factor that will be applied during rendering.
     *
     * @param meshData The original model mesh data (OpenGL coordinates, model-local space)
     * @param sceneX Object center X position in region-local RS scene units
     *               (localTileX * 512 + sizeX * 256)
     * @param sceneY Object center Y position in region-local RS scene units
     *               (localTileY * 512 + sizeY * 256)
     * @param averageHeight Average terrain height at object center in RS units
     * @param plane Terrain plane (0-3)
     * @param field Flattened terrain heights for the region, from [HeightField.from]
     * @param rsRotation Object rotation (0-3) matching RS client rotation values.
     *                   Applied as Y-axis rotation to vertex X/Z before terrain lookup.
     *                   Rotation 0=none, 1=90deg CCW, 2=180deg, 3=270deg CCW (in RS coords).
     * @param mirrored Whether the model is mirrored (negates Z before rotation).
     *                 From ObjectDefinition.inverted or WALL_WHOLE_CORNER primary wall.
     * @param resizeX RS X scale factor (128 = 1.0). From ObjectDefinition.resizeX.
     * @param resizeY RS Y scale factor (128 = 1.0). From ObjectDefinition.resizeY.
     *                Used to compensate for GPU scale on the displacement delta.
     * @param resizeZ RS Z scale factor (128 = 1.0). From ObjectDefinition.resizeZ.
     * @param rsOffsetX RS X translation offset. From ObjectDefinition.offsetX.
     * @param rsOffsetZ RS Z translation offset. From ObjectDefinition.offsetZ.
     * @return New MeshData with displaced vertex Y positions, or null if displacement
     *         produces no change (flat terrain under object)
     */
    fun displaceVertices(
        meshData: MeshData,
        sceneX: Int,
        sceneY: Int,
        averageHeight: Int,
        plane: Int,
        field: HeightField,
        rsRotation: Int = 0,
        mirrored: Boolean = false,
        resizeX: Int = 128,
        resizeY: Int = 128,
        resizeZ: Int = 128,
        rsOffsetX: Int = 0,
        rsOffsetZ: Int = 0
    ): MeshData? {
        val positions = meshData.positions
        if (positions.isEmpty()) return null

        val width = field.width
        val depth = field.depth
        if (width <= 0 || depth <= 0) return null
        val heights = field.heights(plane)
        if (heights.isEmpty()) return null

        // The sceneX/sceneY parameters are region-local RS scene coordinates:
        //   sceneX = (localTileX << 9) + (sizeX << 8)
        //   sceneY = (localTileY << 9) + (sizeY << 8)
        //
        // After applying mirror/rotate/scale/translate to model-local vertex coords
        // and adding to sceneX/sceneY, the result is region-local RS scene coords.
        // Adding the field's pad origin and shifting right by tileScale (9) gives the
        // padded tile index, where region tile t lives at t + pad.
        //
        // Mirror, rotation and scale together are a per-object constant: each output
        // axis takes exactly one input axis, times a signed factor. Folding them into
        // four coefficients hoists the whole `when (rot)` out of the vertex loop, and
        // because one coefficient of each pair is always zero, summing the two products
        // before the >> 7 is bit-identical to shifting the single non-zero term (which
        // is what the RS client does).
        val mirrorSign = if (mirrored) -1 else 1
        val factorXX: Int
        val factorXZ: Int
        val factorZX: Int
        val factorZZ: Int
        when (rsRotation and 3) {
            1 -> { factorXX = 0; factorXZ = resizeX * mirrorSign; factorZX = -resizeZ; factorZZ = 0 }
            2 -> { factorXX = -resizeX; factorXZ = 0; factorZX = 0; factorZZ = -resizeZ * mirrorSign }
            3 -> { factorXX = 0; factorXZ = -resizeX * mirrorSign; factorZX = resizeZ; factorZZ = 0 }
            else -> { factorXX = resizeX; factorXZ = 0; factorZX = 0; factorZZ = resizeZ * mirrorSign }
        }
        // Region-local scene units -> padded field units. The field carries
        // [HeightField.pad] tiles of neighbour terrain on every side, so an object on
        // the region's last row still has the `tile + 1` corners the guard below wants;
        // see [HeightField] for why that padding is the whole point.
        val padUnits = field.rsOrigin
        val baseX = rsOffsetX + sceneX + padUnits
        val baseZ = rsOffsetZ + sceneY + padUnits
        val maxTileX = width - 1
        val maxTileZ = depth - 1
        val resizeYf = resizeY.toFloat()
        // With resizeY == 0 an equal-height vertex divides 0 by 0, which is NaN and so
        // *is* a change. Only short-circuit the equal case when the division is sane.
        val zeroDeltaIsNoop = resizeY != 0

        // Only materialised once a vertex actually moves; a model standing on flat
        // ground returns null without allocating anything.
        var newPositions: FloatArray? = null

        var idx = 0
        val limit = positions.size
        while (idx < limit) {
            val oglX = positions[idx]
            val oglZ = positions[idx + 2]

            // Convert OpenGL model-local coords back to RS model-local coords
            val rsX = (oglX * INV_SCALE).toInt()
            val rsZ = (-oglZ * INV_SCALE).toInt()  // Negate to reverse the Z flip

            // mirror -> rotate -> scale -> translate, folded (see above), then offset
            // into region-local scene space.
            val localX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
            val localZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ

            // Bounds check: skip vertices outside the terrain data range.
            // The client also does this check for vertices beyond maxDepth (line 3320).
            val tileX = localX shr TILE_SCALE
            val tileZ = localZ shr TILE_SCALE
            if (tileX < 0 || tileX >= maxTileX || tileZ < 0 || tileZ >= maxTileZ) {
                idx += 3
                continue
            }

            // Tile heights at the 4 surrounding corners (in RS height units)
            val corner = tileX * depth + tileZ
            val h00 = heights[corner]
            val h01 = heights[corner + 1]
            val h10 = heights[corner + depth]
            val h11 = heights[corner + depth + 1]

            val interpolatedHeight: Int
            if (h00 == h01 && h00 == h10 && h00 == h11) {
                // Interpolating four equal integers is exact, and skipping the six
                // multiplies matters because most vertices of a contoured model still
                // stand over locally level tiles.
                interpolatedHeight = h00
            } else {
                // Sub-tile remainders for interpolation
                val remainderX = localX and TILE_MASK
                val remainderZ = localZ and TILE_MASK

                // Bilinear interpolation matching RS client exactly:
                // height1 = (h00 * (512 - remainderX) + remainderX * h10) >> 9
                // height2 = ((512 - remainderX) * h01 + remainderX * h11) >> 9
                // interpolated = (height1 * (512 - remainderZ) + height2 * remainderZ) >> 9
                //
                // Note: Kotlin shr/and are infix with lower precedence than +/-/*,
                // so parentheses are used for clarity and correctness.
                val height1 = (h00 * (TILE_UNITS - remainderX) + remainderX * h10) shr TILE_SCALE
                val height2 = ((TILE_UNITS - remainderX) * h01 + remainderX * h11) shr TILE_SCALE
                interpolatedHeight =
                    (height1 * (TILE_UNITS - remainderZ) + height2 * remainderZ) shr TILE_SCALE
            }

            if (interpolatedHeight == averageHeight && zeroDeltaIsNoop) {
                idx += 3
                continue
            }

            // Apply displacement delta to the OpenGL Y coordinate.
            //
            // In the RS client, the displacement is:
            //   newTransformedY = interpolatedHeight + transformedY - averageHeight
            //
            // Our terrain heights are stored POSITIVE (inverted from RS negative convention).
            // Higher terrain = larger positive value. Since OpenGL Y is up, the vertex
            // should move UP (more positive) when terrain is higher than average:
            //   delta_ogl = (interpolatedHeight - averageHeight) / resizeY
            //
            // The division by resizeY compensates for the GPU scaleY factor applied
            // during rendering: rendered_Y = posY + scaleY * (oglY + delta).
            // Kept as a division rather than a reciprocal multiply so the result is
            // bit-identical to the original implementation.
            val oglY = positions[idx + 1]
            val newOglY = oglY + (interpolatedHeight - averageHeight).toFloat() / resizeYf

            if (newOglY != oglY) {
                val target = newPositions ?: positions.copyOf().also { newPositions = it }
                target[idx + 1] = newOglY
            }
            idx += 3
        }

        val displaced = newPositions ?: return null

        return MeshData(
            positions = displaced,
            colors = meshData.colors,
            textureCoords = meshData.textureCoords,
            normals = meshData.normals,
            indices = meshData.indices,
            textureId = meshData.textureId
        )
    }

    /**
     * Check if an object should have per-vertex displacement applied.
     *
     * This checks:
     * 1. groundContourType must be 1 (full vertex displacement)
     * 2. groundContourModifier must be < 16384 (RS client exemption check)
     *
     * From ObjectType.getPair() in darkan-bot:
     *   if (hasGroundContour && this.goundContourModifier < 16384)
     *
     * @param groundContourType The object's ground contour type
     * @param groundContourModifier The object's ground contour modifier (-1 is default, always < 16384)
     * @return true if per-vertex displacement should be applied
     */
    fun needsDisplacement(groundContourType: Int, groundContourModifier: Int): Boolean {
        return groundContourType == 1 && groundContourModifier < 16384
    }
}
