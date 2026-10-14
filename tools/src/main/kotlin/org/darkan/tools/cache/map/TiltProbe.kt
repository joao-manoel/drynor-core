package org.darkan.tools.cache.map

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.terrain.TerrainLoader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import kotlin.math.abs

/**
 * Headless audit of ground-contour-type-3 (hillChange) tilt sampling at region edges.
 *
 * Type 3 objects do not displace vertices; the whole model rotates to match the slope under
 * its footprint, sampled at four corners around the object centre. Those samples used to be
 * clamped into `0..63`, so an object on the region's last row or column sampled the *same*
 * tile for two opposing corners: a degenerate footprint, `atan2(0, d) == 0`, and no tilt at
 * all no matter how steep the terrain continued into the neighbouring region.
 *
 * The clamp is the same defect [VertexDisplacer]'s height field had, and the fix is the same
 * shape: sample through [TerrainLoader.getExtendedTileHeightScaled], which resolves tile 64
 * out of the neighbouring region and falls back to the edge tile only when that neighbour is
 * genuinely absent.
 *
 * Two subcommands:
 * - `scan`   sweeps every region in the cache and reports which type 3 objects actually sample
 *            past their region, because a fix to an unreachable path is worth nothing. A
 *            contour histogram is printed alongside so "no hits" can be told apart from "no
 *            objects decoded".
 * - `whatif` measures what the clamp would cost along one region's seams if such an object
 *            were placed there, so "leave it alone" is a decision made against a number rather
 *            than a shrug. It runs the real [TerrainAligner.calculateObjectTilt] both ways.
 *
 * The sweep's verdict as of this writing: 2,794 type 3 objects exist across 2,108 readable
 * regions, exactly one is seated on a region edge, and that one's sampling extent is a quarter
 * of a tile -- too small to cross the seam. The clamp is unreachable, so it is documented
 * rather than changed.
 *
 * Run with:
 * ```
 * ./gradlew :tools:run --args="tiltprobe scan [maxRegions]"
 * ./gradlew :tools:run --args="tiltprobe whatif <regionX> <regionY>"
 * ```
 */
object TiltProbe {

    /** Ground contour type that rotates the whole model to the slope. */
    private const val CONTOUR_HILL_CHANGE = 3

    /** Objects at or above this modifier are exempt from contouring; see [TerrainAligner]. */
    private const val CONTOUR_MODIFIER_EXEMPT_THRESHOLD = 16384

    /** Radians -> degrees, for reporting tilt differences in units a human can judge. */
    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    /**
     * A tilt in degrees, wrapped into (-180, 180].
     *
     * [TerrainAligner] carries angles as RS rotation units masked with `0x3FFF`, so a small
     * negative tilt comes back just under a full turn: a 3.5 degree downward roll reads as
     * 356.4. Printing that raw makes a gentle slope look like a catastrophe and makes
     * differences between two tilts meaningless, so unwrap before reporting.
     */
    private fun signedDegrees(radians: Float): Float {
        var degrees = radians * RAD_TO_DEG % 360f
        if (degrees > 180f) degrees -= 360f
        if (degrees <= -180f) degrees += 360f
        return degrees
    }

    /** Objects whose varbit transform could not be resolved; see [resolveDefinition]. */
    private var transformFailures = 0L

    /**
     * [ObjectRenderer.getDefinition] with the varbit lookup made survivable.
     *
     * A sweep of the whole cache reaches objects the viewer never loads, and some of them
     * name a varbit past the end of the cache's varbit table -- `Vars.getVarBit` indexes it
     * unguarded and throws. That is a real latent crash in the load path, but it belongs to
     * `core/` and is not this probe's business; here it must merely not abort the sweep.
     * Falling back to the untransformed definition is the same thing the transform resolver
     * does for an out-of-range var value.
     */
    private fun resolveDefinition(objectId: Int): ObjectDefinition? = try {
        ObjectRenderer.getDefinition(objectId)
    } catch (e: IndexOutOfBoundsException) {
        transformFailures++
        ObjectRenderer.getRawDefinition(objectId)
    }

    /** A non-exempt type 3 object: the population this fix can possibly affect. */
    private class Candidate(
        val obj: MapObject,
        val def: ObjectDefinition,
        val localX: Int,
        val localY: Int,
        val sizeX: Int,
        val sizeY: Int
    ) {
        /**
         * Whether the `0..63` clamp actually alters a sample coordinate for this object.
         *
         * Seating on `x == 63` is *not* the right test. The sampler reads at
         * `centre ± half-extent`, and the extent grows with the object, so a 3x3 object
         * seated at `x == 61` reaches tile 64 just as surely as a 1x1 at 63 does. Conversely
         * the low side never trips: the largest half-extent the modifier can encode is
         * `255 * 4 / 1024 < 1` tile, and the smallest centre is `0.5`, so the lowest raw
         * coordinate reachable is `> -0.5`, which truncates to 0 clamped or not.
         *
         * See [TerrainAligner.calculateObjectTilt] for the extents this mirrors.
         */
        val clampReaches: Boolean

        /** Highest raw sample coordinate on each axis, before any clamping. */
        val highSampleX: Float
        val highSampleY: Float

        /** Sample extents in tiles, mirroring calculateObjectTilt's unpacking. */
        init {
            val width: Float
            val length: Float
            if (def.anInt3023 > 0) {
                width = ((def.anInt3023 and 0xFF) * 4).toFloat()
                length = (((def.anInt3023 shr 8) and 0xFF) * 4).toFloat()
            } else {
                width = (sizeX * 512).toFloat()
                length = (sizeY * 512).toFloat()
            }
            val maxSampleX = localX + sizeX * 0.5f + width / 1024f
            val maxSampleY = localY + sizeY * 0.5f + length / 1024f
            clampReaches = maxSampleX >= 64f || maxSampleY >= 64f
            highSampleX = maxSampleX
            highSampleY = maxSampleY
        }

        fun describeSampling(): String =
            "modifier=%d maxSample=(%.3f,%.3f) clampReaches=%b"
                .format(def.anInt3023, highSampleX, highSampleY, clampReaches)
    }

    // ------------------------------------------------------------------ scan

    /**
     * Sweep the cache for type 3 objects whose tilt sampling reaches past the region edge.
     *
     * Reports the contour histogram over every object examined so a zero result is
     * distinguishable from a failure to decode anything at all -- the failure mode that
     * would make this sweep silently and wrongly report "unreachable".
     */
    fun scan(cache: Cache, maxRegions: Int?) {
        if (!MapXteas.isLoaded()) MapXteas.load()
        ObjectRenderer.loadDefinitions(cache)

        val regions = RegionLoader.getAvailableRegions(cache)
            .let { if (maxRegions != null) it.take(maxRegions) else it }
        println("[tiltprobe] sweeping ${regions.size} regions for contour-type-3 objects whose " +
                "tilt sampling reaches past the region edge")

        val contourHistogram = HashMap<Int, Long>()
        var regionsDecoded = 0
        var regionsEmpty = 0
        var regionsUnread = 0
        var regionsNoKeys = 0
        var objectsSeen = 0L
        var type3Total = 0L
        var type3Exempt = 0L
        var type3Live = 0L
        var type3Candidates = 0L
        var type3Seated = 0L
        var type3Edge = 0L
        val hitsByRegion = LinkedHashMap<Pair<Int, Int>, Int>()

        for ((regionX, regionY) in regions) {
            val region = RegionLoader.loadRegion(cache, regionX, regionY, useCache = false)
            // A region that yields no objects is the sweep's one blind spot: an undecryptable
            // object archive looks exactly like an empty one, and a "nothing found" verdict
            // drawn over regions that were never read is worthless. Split the two apart and
            // report them, so the coverage of the conclusion is visible rather than assumed.
            if (region == null || region.objects.isEmpty()) {
                regionsEmpty++
                if (RegionLoader.regionExists(cache, regionX, regionY) &&
                    cache.archiveId(Index.MAPS, "l${regionX}_$regionY") != -1) {
                    regionsUnread++
                    if (!MapXteas.hasKeys(regionX, regionY)) regionsNoKeys++
                }
                continue
            }
            regionsDecoded++
            var regionHits = 0
            for (obj in region.objects) {
                val def = resolveDefinition(obj.id) ?: continue
                objectsSeen++
                val contour = def.contouredGround.toInt()
                contourHistogram[contour] = (contourHistogram[contour] ?: 0L) + 1L
                if (contour != CONTOUR_HILL_CHANGE) continue
                type3Total++
                // Exempt objects return NONE before any sampling happens, so the clamp
                // cannot reach them; counting them as hits would overstate the blast radius.
                if (def.anInt3023 >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) {
                    type3Exempt++
                    continue
                }
                type3Live++
            }
            for (c in collectCandidates(region.objects, regionX, regionY)) {
                type3Candidates++
                // Printed in full: seat-on-edge is the intuitive test, clampReaches the
                // correct one, and every case where they disagree has to be inspectable or
                // the "unreachable" verdict rests on unaudited arithmetic.
                if (c.localX == 63 || c.localY == 63) {
                    type3Seated++
                    println("    seated on edge: region %d,%d id=%d tile=(%d,%d) plane=%d size=%dx%d %s"
                        .format(regionX, regionY, c.obj.id, c.localX, c.localY, c.obj.level,
                            c.sizeX, c.sizeY, c.describeSampling()))
                }
                if (!c.clampReaches) continue
                type3Edge++
                regionHits++
            }
            if (regionHits > 0) hitsByRegion[Pair(regionX, regionY)] = regionHits
        }

        println()
        println("  regions with objects: $regionsDecoded   regions yielding none: $regionsEmpty")
        println("  ...of which have an object archive that did not decode: $regionsUnread " +
                "($regionsNoKeys of them for want of XTEA keys)")
        println("  objects examined:     $objectsSeen")
        if (transformFailures > 0) {
            println("  varbit transforms that threw (see resolveDefinition): $transformFailures")
        }
        println()
        println("  contouredGround histogram over every object examined")
        for (key in contourHistogram.keys.sorted()) {
            println("    = %-3d -> %d".format(key, contourHistogram[key]))
        }
        println()
        println("  type 3 total:                                       $type3Total")
        println("  ...exempt (modifier >= $CONTOUR_MODIFIER_EXEMPT_THRESHOLD):                   $type3Exempt")
        println("  ...live, i.e. actually tilted:                       $type3Live")
        println("  ...with models, so reaching the tilt call at all:    $type3Candidates")
        println("  ...of those, seated on x==63 or y==63:               $type3Seated")
        println("  ...of those, whose sampler the clamp actually alters: $type3Edge")

        if (hitsByRegion.isEmpty()) {
            println()
            println("  UNREACHABLE  no type 3 object anywhere in the cache samples past its")
            println("               region's edge; the clamp in the tilt sampler is dead code.")
            return
        }
        println()
        println("  affected regions: ${hitsByRegion.size}")
        for ((key, count) in hitsByRegion.entries.sortedByDescending { it.value }.take(30)) {
            println("    region %d,%d  %d type 3 object(s) sampling past the edge".format(key.first, key.second, count))
        }
        if (hitsByRegion.size > 30) println("    ... and ${hitsByRegion.size - 30} more")
    }

    // --------------------------------------------------------------- whatif

    /**
     * Quantify what the clamp *would* cost, along one region's northern and eastern seams.
     *
     * The sweep says no object in the cache currently trips the clamp, so there is nothing to
     * fix and nothing to measure on real geometry. That is not the same as the clamp being
     * harmless: it is a trap armed for whoever next places a ramp or stair across a region
     * boundary, or edits terrain under one. This reports how much tilt such an object would
     * lose, tile by tile, so the decision to leave the clamp alone is made against a number.
     *
     * The trick that makes this measurable without touching production code: an object seated
     * at `x == 63` samples tiles 63 and 64, and the clamp collapses that to 63 and 63. Seating
     * the hypothetical object at `x == 62` instead and handing
     * [TerrainAligner.calculateObjectTilt] a sampler shifted one tile east makes it sample 63
     * and 64 for real -- same arithmetic, same function, no clamp in the way. The two runs
     * therefore differ in exactly the one respect being studied.
     */
    fun whatIf(cache: Cache, regionX: Int, regionY: Int) {
        if (!MapXteas.isLoaded()) MapXteas.load()
        TerrainLoader.loadDefinitions(cache)
        ObjectRenderer.loadDefinitions(cache)

        val terrain = TerrainLoader.loadRegion(cache, regionX, regionY)
        if (terrain == null) {
            println("[tiltprobe] region $regionX,$regionY has no terrain data")
            return
        }
        val neighbors = TerrainLoader.buildNeighborData(cache, regionX, regionY)
        val tiles = terrain.tiles
        if (neighbors == null) {
            println("[tiltprobe] region $regionX,$regionY has no neighbour data; the seam cannot")
            println("            be read and this comparison would be vacuous")
            return
        }

        println("[tiltprobe] region $regionX,$regionY: tilt a hypothetical 1x1 contour-type-3 object")
        println("            would lose to the 0..63 clamp, at each seam tile of plane 0")

        var eastLost = 0
        var northLost = 0
        var worstEast = 0f
        var worstNorth = 0f
        val examples = ArrayList<String>()

        for (i in 0..63) {
            // Eastern seam: object at (63, i), sampling columns 63 and 64.
            val east = seamTilt(tiles, neighbors, localX = 63, localY = i, shiftX = 1, shiftY = 0)
            if (east.changed) {
                eastLost++
                val magnitude = maxOf(abs(east.dTiltX), abs(east.dTiltZ))
                if (magnitude > worstEast) worstEast = magnitude
                if (examples.size < 12) examples.add(
                    "    x=63 y=%-2d  tiltZ clamped %7.3f°  unclamped %7.3f°  (seam step %d RS units)"
                        .format(i, signedDegrees(east.clamped.tiltZ), signedDegrees(east.extended.tiltZ),
                            seamStep(tiles, neighbors, 63, i, 1, 0)))
            }
            // Northern seam: object at (i, 63), sampling rows 63 and 64.
            val north = seamTilt(tiles, neighbors, localX = i, localY = 63, shiftX = 0, shiftY = 1)
            if (north.changed) {
                northLost++
                val magnitude = maxOf(abs(north.dTiltX), abs(north.dTiltZ))
                if (magnitude > worstNorth) worstNorth = magnitude
            }
        }

        println()
        println("  eastern seam (x==63):  %2d of 64 tiles would tilt differently, worst %.3f°"
            .format(eastLost, worstEast))
        println("  northern seam (y==63): %2d of 64 tiles would tilt differently, worst %.3f°"
            .format(northLost, worstNorth))
        if (examples.isNotEmpty()) {
            println()
            println("  sample of the eastern seam:")
            examples.forEach { println(it) }
        }
        if (eastLost == 0 && northLost == 0) {
            println()
            println("  this region's seams are level; the clamp would cost nothing here")
        }
    }

    /** One seam tile tilted both ways, and the difference between them. */
    private class Diff(
        val clamped: TerrainAligner.AlignmentResult,
        val extended: TerrainAligner.AlignmentResult
    ) {
        /** Differences in degrees, unwrapped; see [signedDegrees]. */
        val dTiltX = signedDegrees(extended.tiltX) - signedDegrees(clamped.tiltX)
        val dTiltZ = signedDegrees(extended.tiltZ) - signedDegrees(clamped.tiltZ)
        val changed: Boolean get() = dTiltX != 0f || dTiltZ != 0f
    }

    /** Raw height step across the seam at one tile, in RS units. */
    private fun seamStep(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        localX: Int,
        localY: Int,
        shiftX: Int,
        shiftY: Int
    ): Int = TerrainLoader.getExtendedTileHeight(tiles, neighbors, 0, localX + shiftX, localY + shiftY) -
            TerrainLoader.getExtendedTileHeight(tiles, neighbors, 0, localX, localY)

    /**
     * Tilt a hypothetical 1x1 type-3 object at a seam tile, clamped and unclamped.
     *
     * `shiftX`/`shiftY` displace both the seat and the sampler by one tile, which is what lets
     * the unclamped run reach tile 64 through a function that clamps at 63. See [whatIf].
     */
    private fun seamTilt(
        tiles: Array<Array<Array<TerrainLoader.TileData>>>,
        neighbors: TerrainLoader.NeighborData?,
        localX: Int,
        localY: Int,
        shiftX: Int,
        shiftY: Int
    ): Diff {
        val average = TerrainLoader.getExtendedTileHeightScaled(tiles, neighbors, 0, localX, localY)
        // As placed: seat on the seam, sampler clamped, both corners collapse onto tile 63.
        val clamped = TerrainAligner.calculateObjectTilt(
            localTileX = localX,
            localTileY = localY,
            sizeX = 1,
            sizeY = 1,
            groundContourType = CONTOUR_HILL_CHANGE,
            groundContourModifier = 0,
            averageHeight = average,
            heightSampler = { tx, ty ->
                TerrainLoader.getExtendedTileHeightScaled(
                    tiles, neighbors, 0, tx.coerceIn(0, 63), ty.coerceIn(0, 63))
            }
        )
        // Seated one tile back with the sampler shifted forward: identical geometry, but the
        // far corner now genuinely resolves into the neighbouring region.
        val extended = TerrainAligner.calculateObjectTilt(
            localTileX = localX - shiftX,
            localTileY = localY - shiftY,
            sizeX = 1,
            sizeY = 1,
            groundContourType = CONTOUR_HILL_CHANGE,
            groundContourModifier = 0,
            averageHeight = average,
            heightSampler = { tx, ty ->
                TerrainLoader.getExtendedTileHeightScaled(
                    tiles, neighbors, 0, tx + shiftX, ty + shiftY)
            }
        )
        return Diff(clamped, extended)
    }

    private fun collectCandidates(objects: List<MapObject>, regionX: Int, regionY: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (obj in objects) {
            val def = resolveDefinition(obj.id) ?: continue
            if (def.contouredGround.toInt() != CONTOUR_HILL_CHANGE) continue
            if (def.anInt3023 >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) continue
            if (ObjectRenderer.getModelIds(def, obj.shape).isEmpty()) continue
            val localX = obj.x - regionX * 64
            val localY = obj.y - regionY * 64
            if (localX !in 0..63 || localY !in 0..63) continue
            // Rotation swaps the footprint, exactly as ObjectRenderer does before sampling.
            val sizeX: Int
            val sizeY: Int
            if (obj.rotation == 1 || obj.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }
            out.add(Candidate(obj, def, localX, localY, sizeX, sizeY))
        }
        return out
    }
}
