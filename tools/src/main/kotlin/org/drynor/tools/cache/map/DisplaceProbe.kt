package org.drynor.tools.cache.map

import org.drynor.core.map.MapXteas
import org.drynor.core.map.RegionLoader
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.terrain.TerrainLoader
import org.drynor.tools.engine.render.MeshData
import world.gregs.voidps.cache.Cache

/**
 * Headless audit of ground-contour-type-1 vertex displacement for one region.
 *
 * Displacement failures are invisible: a model that should hug a slope but receives no
 * displacement still renders, just flat, and nothing in the load path complains. This
 * probe makes the failure a number.
 *
 * For every contour-type-1 object in a region it reports how many of the object's
 * vertices actually moved, and -- crucially -- how many of them sit on a tile the
 * *unpadded* height field used to reject outright. Before
 * [VertexDisplacer.HeightField] was padded with neighbour terrain, every vertex on
 * region tile 63 fell outside the `[0, size - 2]` guard that bilinear interpolation
 * needs, so every object on `x == 63` or `y == 63` silently displaced nothing. The two
 * columns together read as a before/after in a single run: `rejected-by-unpadded` is what
 * the old code skipped, `moved` is what the padded field now displaces.
 *
 * Run with:
 * ```
 * ./gradlew :tools:run --args="displaceprobe <regionX> <regionY> [objectId]"
 * ```
 */
object DisplaceProbe {

    /** Bit shift for RS tile scale (2^9 = 512). Mirrors [VertexDisplacer]. */
    private const val TILE_SCALE = 9

    /** Inverse of CoordinateUtil.DEFAULT_SCALE; OpenGL model units -> RS model units. */
    private const val INV_SCALE = 128f

    /** RS resize unit; a definition scale of 128 renders at 1.0. */
    private const val RESIZE_UNIT = 128f

    private class Audit(
        val obj: ObjectInstance,
        val localX: Int,
        val localY: Int,
        val flat: Boolean,
        val totalVertices: Int,
        val movedVertices: Int,
        /** Vertices whose region tile lies outside `[0, 62]`, i.e. what the unpadded field dropped. */
        val rejectedByUnpadded: Int,
        /** Vertices the *current* padded field still rejects. Must be zero. */
        val rejectedByPadded: Int,
        /** Largest |Y| change applied, in OpenGL world units. */
        val maxDeltaWorld: Float,
        /** Spread of terrain heights under the object's footprint corners, in RS units. */
        val footprintRelief: Int
    ) {
        val onEdge: Boolean get() = localX == 63 || localY == 63
    }

    fun run(cache: Cache, regionX: Int, regionY: Int, focusObjectId: Int?) {
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val audits = auditRegion(cache, regionX, regionY)
        if (audits == null) {
            println("[displaceprobe] region $regionX,$regionY has no terrain or object data")
            return
        }

        println("[displaceprobe] region $regionX,$regionY: ${audits.size} contour-type-1 objects")
        println("[displaceprobe] height field: ${VertexDisplacer.HeightField.PAD_TILES} tiles of " +
                "neighbour padding per side")

        // --- The reported bug: objects on the region's last row/column ---
        val edge = audits.filter { it.onEdge }
        val interior = audits.filter { !it.onEdge }
        println()
        println("  %-22s %6s %10s %10s".format("group", "objs", "moved/tot", "moved=0"))
        printGroup("on x==63 or y==63", edge)
        printGroup("interior", interior)

        // --- What the unpadded field used to drop ---
        val wouldHaveBeenDropped = audits.filter { it.rejectedByUnpadded == it.totalVertices && it.totalVertices > 0 }
        println()
        println("  objects whose every vertex the unpadded field rejected: ${wouldHaveBeenDropped.size}")
        val nowMoving = wouldHaveBeenDropped.count { it.movedVertices > 0 }
        println("  ...of which the padded field now displaces: $nowMoving")
        val worstStep = wouldHaveBeenDropped.maxOfOrNull { it.maxDeltaWorld } ?: 0f
        println("  largest displacement recovered: %.3f world units".format(worstStep))

        // --- The regression assertion ---
        //
        // The failure mode being fixed is specific and mechanical: an object whose every
        // vertex the old guard rejected, which the flat pre-test did NOT resolve, and
        // which still moves nothing. That combination cannot occur once the field reaches
        // past the region, and it is what every Duel Arena edge railing looked like.
        //
        // "moved nothing" on its own is NOT a failure: footprintIsFlat tests the model's
        // transformed bounding box, a deliberate superset of the tiles the vertices
        // actually touch, so a model can be reported non-flat and still have every vertex
        // over level ground. Those are listed separately as information.
        val outOfReach = audits.filter { it.rejectedByPadded > 0 }
        println()
        if (outOfReach.isEmpty()) {
            println("  PASS  every vertex of every contour-1 object is inside the height field; " +
                    "no displacement is silently dropped")
        } else {
            println("  FAIL  ${outOfReach.size} object(s) have vertices the field cannot reach:")
            for (a in outOfReach.take(20)) {
                println("        id=%d at (%d,%d) plane=%d unreachable=%d/%d"
                    .format(a.obj.mapObject.id, a.localX, a.localY,
                        a.obj.mapObject.level, a.rejectedByPadded, a.totalVertices))
            }
        }

        // Objects that ran the pass and moved nothing. Legitimate whenever the terrain the
        // vertices actually stand on is level at the object's average height -- the flat
        // pre-test works on the transformed bounding box, a deliberate superset. Reported
        // only so the count above can be read in context.
        val movedNothing = audits.filter { !it.flat && it.movedVertices == 0 && it.totalVertices > 0 }
        println("  moved nothing despite failing the flat pre-test: ${movedNothing.size} " +
                "(bounding-box overshoot; all reachable)")

        if (focusObjectId != null) {
            println()
            println("[displaceprobe] object $focusObjectId:")
            val hits = audits.filter { it.obj.mapObject.id == focusObjectId }
            if (hits.isEmpty()) {
                println("  not present as a contour-type-1 object in this region")
            }
            for (a in hits.sortedWith(compareBy({ it.localY }, { it.localX }))) {
                println(("  (%2d,%2d) plane=%d  moved=%d/%d  unpadded-would-reject=%d  " +
                        "maxDelta=%.3f world units  2x2 relief=%d RS")
                    .format(a.localX, a.localY, a.obj.mapObject.level,
                        a.movedVertices, a.totalVertices, a.rejectedByUnpadded,
                        a.maxDeltaWorld, a.footprintRelief))
            }
        }
    }

    private fun printGroup(label: String, group: List<Audit>) {
        if (group.isEmpty()) {
            println("  %-22s %6d %10s %10s".format(label, 0, "-", "-"))
            return
        }
        val moved = group.sumOf { it.movedVertices.toLong() }
        val total = group.sumOf { it.totalVertices.toLong() }
        val zero = group.count { it.movedVertices == 0 }
        println("  %-22s %6d %10s %10d".format(
            label, group.size,
            if (total == 0L) "-" else "%.1f%%".format(100.0 * moved / total),
            zero))
    }

    /**
     * Compare a region's northern edge row against the first row of the region above it.
     *
     * A ramp railing that straddles the boundary is one continuous run of geometry split
     * across two regions; if one half displaces and the other does not, the joint steps.
     * Matching is by object id and shared X, which is enough for the ramp rails the bug
     * report is about.
     */
    fun runJoint(cache: Cache, regionX: Int, regionY: Int, objectId: Int) {
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val south = auditRegion(cache, regionX, regionY) ?: emptyList()
        val north = auditRegion(cache, regionX, regionY + 1) ?: emptyList()

        val southEdge = south.filter { it.localY == 63 && it.obj.mapObject.id == objectId }
        val northEdge = north.filter { it.localY == 0 && it.obj.mapObject.id == objectId }

        println("[displaceprobe] joint across $regionX,$regionY | $regionX,${regionY + 1} for object $objectId")
        println("  %-28s %10s %12s".format("side", "moved/tot", "maxDelta(w)"))
        for (a in southEdge.sortedBy { it.localX }) {
            println("  region %d,%d y=63 x=%-8d %6d/%-4d %10.3f"
                .format(regionX, regionY, a.localX, a.movedVertices, a.totalVertices, a.maxDeltaWorld))
        }
        for (a in northEdge.sortedBy { it.localX }) {
            println("  region %d,%d y=0  x=%-8d %6d/%-4d %10.3f"
                .format(regionX, regionY + 1, a.localX, a.movedVertices, a.totalVertices, a.maxDeltaWorld))
        }

        // The joint is continuous when both halves displace the same proportion of their
        // vertices; one half at 0% beside a twin at ~100% is the visible step.
        val sRatio = ratio(southEdge)
        val nRatio = ratio(northEdge)
        println()
        println("  south edge displaced %.1f%%, north edge displaced %.1f%%, difference %.1f points"
            .format(sRatio * 100, nRatio * 100, kotlin.math.abs(sRatio - nRatio) * 100))
    }

    private fun ratio(group: List<Audit>): Double {
        val total = group.sumOf { it.totalVertices.toLong() }
        if (total == 0L) return 0.0
        return group.sumOf { it.movedVertices.toLong() }.toDouble() / total
    }

    private fun auditRegion(cache: Cache, regionX: Int, regionY: Int): List<Audit>? {
        // Object archives are XTEA-encrypted; without the keys the region decodes to no
        // objects at all and every count below would read as a silent pass.
        if (!MapXteas.isLoaded()) MapXteas.load()
        val terrain = TerrainLoader.loadRegion(cache, regionX, regionY) ?: return null
        val region = RegionLoader.loadRegion(cache, regionX, regionY, useCache = false) ?: return null
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val tiles = terrain.tiles

        val instances = ObjectRenderer.createInstancesAllPlanes(
            objects = region.objects,
            regionX = regionX,
            regionY = regionY,
            tileHeights = { x, y, p ->
                TerrainLoader.getExtendedTileHeightScaled(tiles, neighbors, p, x, y)
            },
            rsHeightSampler = { x, y, p ->
                TerrainLoader.getExtendedTileHeight(tiles, neighbors, p, x, y)
            }
        ).values.flatten()

        println("[displaceprobe] region $regionX,$regionY: ${region.objects.size} map objects, " +
                "${instances.size} instances")

        val field = VertexDisplacer.HeightField.from(tiles, neighbors)
        val out = ArrayList<Audit>()

        for (obj in instances) {
            if (!VertexDisplacer.needsDisplacement(obj.groundContourType, obj.groundContourModifier)) continue
            val meshes = loadMeshes(cache, obj) ?: continue
            if (meshes.isEmpty()) continue

            val def = obj.definition
            val bounds = VertexDisplacer.modelBounds(obj.cachedCombinedModelId, meshes) ?: continue
            val flat = VertexDisplacer.footprintIsFlat(
                bounds = bounds,
                sceneX = obj.rsSceneX,
                sceneY = obj.rsSceneY,
                averageHeight = obj.rsAverageHeight,
                plane = obj.mapObject.level,
                field = field,
                rsRotation = obj.rsRotation,
                mirrored = obj.mirrored,
                resizeX = def.modelSizeX,
                resizeY = def.modelSizeZ,
                resizeZ = def.modelSizeY,
                rsOffsetX = def.offsetX,
                rsOffsetZ = def.offsetY
            )

            var total = 0
            var moved = 0
            var maxDelta = 0f
            for (mesh in meshes) {
                total += mesh.positions.size / 3
                val displaced = VertexDisplacer.displaceVertices(
                    meshData = mesh,
                    sceneX = obj.rsSceneX,
                    sceneY = obj.rsSceneY,
                    averageHeight = obj.rsAverageHeight,
                    plane = obj.mapObject.level,
                    field = field,
                    rsRotation = obj.rsRotation,
                    mirrored = obj.mirrored,
                    resizeX = def.modelSizeX,
                    resizeY = def.modelSizeZ,
                    resizeZ = def.modelSizeY,
                    rsOffsetX = def.offsetX,
                    rsOffsetZ = def.offsetY
                ) ?: continue
                var i = 1
                while (i < mesh.positions.size) {
                    val delta = displaced.positions[i] - mesh.positions[i]
                    if (delta != 0f) {
                        moved++
                        val magnitude = if (delta < 0f) -delta else delta
                        if (magnitude > maxDelta) maxDelta = magnitude
                    }
                    i += 3
                }
            }

            val localX = obj.mapObject.x - regionX * 64
            val localY = obj.mapObject.y - regionY * 64
            val rejected = countRejectedVertices(obj, meshes, field, def.modelSizeX, def.modelSizeY,
                def.offsetX, def.offsetY)
            out.add(Audit(
                obj = obj,
                localX = localX,
                localY = localY,
                flat = flat,
                totalVertices = total,
                movedVertices = moved,
                rejectedByUnpadded = rejected.first,
                rejectedByPadded = rejected.second,
                // displaceVertices divides the height delta by resizeY so the GPU's
                // scaleY undoes it; multiply back to get the on-screen movement.
                maxDeltaWorld = maxDelta * def.modelSizeZ / RESIZE_UNIT,
                footprintRelief = footprintRelief(tiles, neighbors, obj.mapObject.level, localX, localY)
            ))
        }
        return out
    }

    /**
     * Vertices the height-field guard rejects, counted twice: against the old bare 64-wide
     * field, and against the padded field in force now.
     *
     * The first number reconstructs the "before" figure without needing the old code -- its
     * guard rejected `tile >= size - 1` against a 64-wide array, which is the whole of row
     * and column 63. The second is the invariant that must hold: a vertex the field cannot
     * reach receives no displacement at all, silently, so it must be zero everywhere.
     *
     * The transform below intentionally mirrors [VertexDisplacer.displaceVertices]; if
     * that fold ever changes, this must follow or the comparison stops meaning anything.
     *
     * @return (rejected by the unpadded field, rejected by the padded field)
     */
    private fun countRejectedVertices(
        obj: ObjectInstance,
        meshes: List<MeshData>,
        field: VertexDisplacer.HeightField,
        resizeX: Int,
        resizeZ: Int,
        rsOffsetX: Int,
        rsOffsetZ: Int
    ): Pair<Int, Int> {
        val mirrorSign = if (obj.mirrored) -1 else 1
        val factorXX: Int
        val factorXZ: Int
        val factorZX: Int
        val factorZZ: Int
        when (obj.rsRotation and 3) {
            1 -> { factorXX = 0; factorXZ = resizeX * mirrorSign; factorZX = -resizeZ; factorZZ = 0 }
            2 -> { factorXX = -resizeX; factorXZ = 0; factorZX = 0; factorZZ = -resizeZ * mirrorSign }
            3 -> { factorXX = 0; factorXZ = -resizeX * mirrorSign; factorZX = resizeZ; factorZZ = 0 }
            else -> { factorXX = resizeX; factorXZ = 0; factorZX = 0; factorZZ = resizeZ * mirrorSign }
        }
        val baseX = rsOffsetX + obj.rsSceneX
        val baseZ = rsOffsetZ + obj.rsSceneY

        val pad = field.rsOrigin
        val maxPaddedX = field.width - 1
        val maxPaddedZ = field.depth - 1

        var rejectedUnpadded = 0
        var rejectedPadded = 0
        for (mesh in meshes) {
            val positions = mesh.positions
            var idx = 0
            while (idx < positions.size) {
                val rsX = (positions[idx] * INV_SCALE).toInt()
                val rsZ = (-positions[idx + 2] * INV_SCALE).toInt()
                val localX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
                val localZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ

                // The old guard: `tile < 0 || tile >= 63` against a 64-wide field.
                val tileX = localX shr TILE_SCALE
                val tileZ = localZ shr TILE_SCALE
                if (tileX < 0 || tileX >= 63 || tileZ < 0 || tileZ >= 63) rejectedUnpadded++

                // The guard as it stands, against the padded field.
                val paddedX = (localX + pad) shr TILE_SCALE
                val paddedZ = (localZ + pad) shr TILE_SCALE
                if (paddedX < 0 || paddedX >= maxPaddedX || paddedZ < 0 || paddedZ >= maxPaddedZ) {
                    rejectedPadded++
                }
                idx += 3
            }
        }
        return Pair(rejectedUnpadded, rejectedPadded)
    }

    /** Spread of terrain heights over the 2x2 tiles under an object, in raw RS units. */
    private fun footprintRelief(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        plane: Int,
        localX: Int,
        localY: Int
    ): Int {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (dx in 0..1) {
            for (dy in 0..1) {
                val h = TerrainLoader.getExtendedTileHeight(tiles, neighbors, plane, localX + dx, localY + dy)
                if (h < min) min = h
                if (h > max) max = h
            }
        }
        return max - min
    }

    private fun loadMeshes(cache: Cache, obj: ObjectInstance): List<MeshData>? =
        if (obj.modifications.hasModifications()) {
            ModelLoader.createCombinedMeshDataListWithModifications(cache, obj.modelIds, obj.modifications)
        } else {
            ModelLoader.createCombinedMeshDataList(cache, obj.modelIds)
        }
}
