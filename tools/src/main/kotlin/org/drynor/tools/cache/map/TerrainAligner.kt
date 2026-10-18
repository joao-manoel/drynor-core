package org.drynor.tools.cache.map

import org.drynor.tools.util.CoordinateUtil
import org.joml.Matrix4f
import kotlin.math.atan2
import kotlin.math.min

/**
 * Utility for aligning objects to terrain slopes.
 *
 * The RS client uses several ground contour types:
 * - Type 0: No alignment (object is just positioned at average terrain height)
 * - Type 1: Full vertex displacement (each vertex Y is adjusted to terrain height)
 * - Type 2: Partial vertex displacement (lower vertices follow terrain, upper fixed)
 * - Type 3: Hill change (whole model rotates to match terrain slope)
 * - Type 4: Next level (uses height from the level above)
 * - Type 5: Stretch (stretches model between current and next level)
 *
 * Type 3 (hillChange) uses whole-model rotation, which we implement exactly here.
 *
 * Types 1 and 2 require per-vertex displacement and are handled separately by
 * VertexDisplacer. This class returns NONE for those types so that the object
 * remains upright when rendered via the instanced path. The per-vertex displacement
 * is applied as a mesh modification before rendering, not as a matrix transform.
 *
 * The hillChange algorithm (type 3 in RS) works by:
 * 1. Sampling heights at 4 corners around the object center
 * 2. Computing X-axis rotation from north/south slope (pitch tilt)
 * 3. Computing Z-axis rotation from east/west slope (roll tilt)
 * 4. Optionally clamping rotation angles from groundContourModifier
 * 5. Adjusting Y position to sit on the lowest corner average
 *
 * RS rotation units: 16384 = 360 degrees (0x3FFF mask)
 * Conversion: radians = rsUnits * (2 * PI / 16384)
 *
 * The magic number 2607.5945876176133 in RS code is: 16384 / (2 * PI)
 */
object TerrainAligner {

    /**
     * Enable terrain alignment globally. When disabled, all objects remain upright.
     * Useful for debugging terrain alignment issues.
     */
    var enabled = true

    /**
     * Negate X-axis tilt. Needed because RS models have Z negated in our system,
     * so X-axis rotation has opposite effect.
     */
    var negateXTilt = true

    /**
     * Negate Z-axis tilt. Needed because RS models have Z negated in our system,
     * so Z-axis rotation has opposite effect.
     */
    var negateZTilt = true

    /** RS uses 16384 units per full rotation */
    private const val RS_ROTATION_UNITS = 16384

    /** Half rotation in RS units */
    private const val RS_HALF_ROTATION = 8192

    /** Conversion factor: RS units to radians */
    private const val RS_TO_RADIANS = (Math.PI * 2.0 / RS_ROTATION_UNITS).toFloat()

    /** Conversion factor: radians to RS units (used in atan2 calculation) */
    private const val RADIANS_TO_RS = (RS_ROTATION_UNITS / (Math.PI * 2.0)).toFloat()

    /**
     * Terrain alignment result containing tilt rotations and height adjustment.
     *
     * @param tiltX Rotation around X axis in radians (pitch - north/south tilt)
     * @param tiltZ Rotation around Z axis in radians (roll - east/west tilt)
     * @param heightAdjustment Y position adjustment to sit on lowest corner
     */
    data class AlignmentResult(
        val tiltX: Float,
        val tiltZ: Float,
        val heightAdjustment: Float
    ) {
        companion object {
            /** No alignment needed - flat terrain */
            val NONE = AlignmentResult(0f, 0f, 0f)
        }

        /**
         * Apply this alignment to a transformation matrix.
         * The matrix should be in the state: identity -> translate(worldPos)
         * After this call: translate(worldPos) -> translate(heightAdj) -> rotateX -> rotateZ
         *
         * @param matrix The matrix to modify
         * @return The modified matrix for chaining
         */
        fun applyTo(matrix: Matrix4f): Matrix4f {
            if (heightAdjustment != 0f) {
                matrix.translate(0f, heightAdjustment, 0f)
            }
            if (tiltX != 0f) {
                matrix.rotateX(tiltX)
            }
            if (tiltZ != 0f) {
                matrix.rotateZ(tiltZ)
            }
            return matrix
        }

        /** Check if any alignment is needed */
        fun needsAlignment(): Boolean = tiltX != 0f || tiltZ != 0f || heightAdjustment != 0f
    }

    /**
     * Calculate terrain alignment for an object using the RS hillChange algorithm.
     *
     * This implements the exact algorithm from Model.hillChange in the RS client:
     * 1. Sample heights at 4 corners: SW, SE, NW, NE
     * 2. Find lowest height on each edge pair
     * 3. Calculate tilt angles from height differences using atan2
     * 4. Optionally clamp angles if xan/zan limits are provided
     * 5. Calculate height adjustment to sit on lowest corner
     *
     * @param centerX Object center X position in local tile units
     * @param centerY Object center Y position in local tile units (RS Y = terrain Y direction)
     * @param width Sampling width in local units (from groundContourModifier or object bounds)
     * @param length Sampling length in local units
     * @param maxTiltX Maximum X-axis tilt in RS units (0 = no limit)
     * @param maxTiltZ Maximum Z-axis tilt in RS units (0 = no limit)
     * @param currentHeight Current Y position (for height adjustment calculation)
     * @param heightSampler Function to sample terrain height at (localX, localY)
     * @return AlignmentResult with tilt angles and height adjustment
     */
    fun calculateAlignment(
        centerX: Float,
        centerY: Float,
        width: Float,
        length: Float,
        maxTiltX: Int,
        maxTiltZ: Int,
        currentHeight: Float,
        heightSampler: (Float, Float) -> Float
    ): AlignmentResult {
        // Skip if dimensions are zero
        if (width == 0f && length == 0f) {
            return AlignmentResult.NONE
        }

        // Sample heights at 4 corners around center
        // RS coordinate system: +X = east, +Y = north (in terrain space)
        // OpenGL: +X = east, -Z = north
        val halfWidth = width / 2f
        val halfLength = length / 2f

        // SW corner: (-width/2, -length/2)
        val heightSW = heightSampler(centerX - halfWidth, centerY - halfLength)
        // SE corner: (+width/2, -length/2)
        val heightSE = heightSampler(centerX + halfWidth, centerY - halfLength)
        // NW corner: (-width/2, +length/2)
        val heightNW = heightSampler(centerX - halfWidth, centerY + halfLength)
        // NE corner: (+width/2, +length/2)
        val heightNE = heightSampler(centerX + halfWidth, centerY + halfLength)

        // Find lowest height on each edge pair (RS algorithm)
        val lowestSouth = min(heightSW, heightSE)
        val lowestNorth = min(heightNW, heightNE)
        val lowestEast = min(heightSE, heightNE)
        val lowestWest = min(heightSW, heightNW)

        // Calculate X-axis rotation (pitch) from north/south slope
        var tiltXRs = 0
        if (length != 0f) {
            // atan2(height_diff, distance) gives angle in radians
            // Multiply by RS conversion factor to get RS units
            tiltXRs = (atan2((lowestSouth - lowestNorth).toDouble(), length.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            // Apply maximum tilt constraint if specified
            if (maxTiltX != 0 && tiltXRs != 0) {
                if (tiltXRs > RS_HALF_ROTATION) {
                    // Negative rotation (wraps around)
                    val minAngle = RS_ROTATION_UNITS - maxTiltX
                    if (tiltXRs < minAngle) {
                        tiltXRs = minAngle
                    }
                } else if (tiltXRs > maxTiltX) {
                    tiltXRs = maxTiltX
                }
            }
        }

        // Calculate Z-axis rotation (roll) from east/west slope
        var tiltZRs = 0
        if (width != 0f) {
            tiltZRs = (atan2((lowestWest - lowestEast).toDouble(), width.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            // Apply maximum tilt constraint if specified
            if (maxTiltZ != 0 && tiltZRs != 0) {
                if (tiltZRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltZ
                    if (tiltZRs < minAngle) {
                        tiltZRs = minAngle
                    }
                } else if (tiltZRs > maxTiltZ) {
                    tiltZRs = maxTiltZ
                }
            }
        }

        // Calculate height adjustment to sit on lowest corner diagonal
        // RS uses the minimum of (SW+NE) and (SE+NW) divided by 2
        var lowestAverage = heightSW + heightNE
        if (heightSE + heightNW < lowestAverage) {
            lowestAverage = heightSE + heightNW
        }
        val targetHeight = lowestAverage / 2f
        val heightAdjustment = targetHeight - currentHeight

        // Convert RS rotation units to radians
        // Note: RS uses CCW rotation in their coordinate system
        // OpenGL rotateX is also CCW when looking down +X axis
        val tiltXRadians = tiltXRs * RS_TO_RADIANS
        val tiltZRadians = tiltZRs * RS_TO_RADIANS

        return AlignmentResult(tiltXRadians, tiltZRadians, heightAdjustment)
    }

    /**
     * Calculate terrain alignment using default sampling dimensions based on object size.
     *
     * For objects without explicit groundContourModifier dimensions, uses the object's
     * tile size multiplied by tile scale to determine sampling area.
     *
     * @param localTileX Object local tile X coordinate
     * @param localTileY Object local tile Y coordinate
     * @param sizeX Object size in X direction (tiles)
     * @param sizeY Object size in Y direction (tiles)
     * @param groundContourModifier Packed width/length/maxTilt values from ObjectDefinition
     *                              Format: bits 0-7 = width/4, bits 8-15 = length/4,
     *                                      bits 16-23 = maxTiltX>>6, bits 24-31 = maxTiltZ>>6
     * @param tileSize Size of one tile in world units
     * @param heightSampler Function to sample terrain height at (localTileX, localTileY)
     * @return AlignmentResult with tilt angles and height adjustment
     */
    fun calculateAlignmentForObject(
        localTileX: Int,
        localTileY: Int,
        sizeX: Int,
        sizeY: Int,
        groundContourModifier: Int,
        tileSize: Float,
        heightSampler: (Float, Float) -> Float
    ): AlignmentResult {
        // Unpack groundContourModifier (RS format)
        // Width and length are stored as value/4, so multiply by 4
        // Max tilt angles are stored shifted right by 6, so shift left by 6
        val width: Float
        val length: Float
        val maxTiltX: Int
        val maxTiltZ: Int

        if (groundContourModifier > 0) {
            width = ((groundContourModifier and 0xFF) * 4).toFloat()
            length = (((groundContourModifier shr 8) and 0xFF) * 4).toFloat()
            maxTiltX = ((groundContourModifier shr 16) and 0xFF) shl 6
            maxTiltZ = ((groundContourModifier shr 24) and 0xFF) shl 6
        } else {
            // Default: use object tile size
            width = (sizeX * 512).toFloat()  // RS uses 512 units per tile
            length = (sizeY * 512).toFloat()
            maxTiltX = 0
            maxTiltZ = 0
        }

        // Convert RS units to our coordinate system
        // RS: 512 units = 1 tile, scaled by CoordinateUtil.DEFAULT_SCALE
        val scaledWidth = width * CoordinateUtil.DEFAULT_SCALE
        val scaledLength = length * CoordinateUtil.DEFAULT_SCALE

        // Object center in local tile coordinates
        val centerX = (localTileX + sizeX * 0.5f)
        val centerY = (localTileY + sizeY * 0.5f)

        // Create a height sampler that works with tile coordinates
        val tileHeightSampler: (Float, Float) -> Float = { x, y ->
            // Convert from RS-style offset to tile coordinates
            val tileX = centerX + (x / tileSize)
            val tileY = centerY + (y / tileSize)
            heightSampler(tileX, tileY)
        }

        // Get current height at center for adjustment calculation
        val currentHeight = heightSampler(centerX, centerY)

        return calculateAlignment(
            0f, 0f,  // Center is at origin for local sampling
            scaledWidth, scaledLength,
            maxTiltX, maxTiltZ,
            currentHeight,
            tileHeightSampler
        )
    }

    // Debug counters
    private var debugEnabled = false
    private var alignedTypeCount = 0  // Types 1, 2, 3 that get alignment
    private var nonZeroTiltCount = 0
    private var totalProcessed = 0
    private var type1Count = 0
    private var type2Count = 0
    private var type3Count = 0
    private var exemptedCount = 0  // Objects with modifier >= 16384 that are exempt

    /** Enable debug logging for terrain alignment */
    fun enableDebug(enable: Boolean) {
        debugEnabled = enable
        if (enable) {
            alignedTypeCount = 0
            nonZeroTiltCount = 0
            totalProcessed = 0
            type1Count = 0
            type2Count = 0
            type3Count = 0
            exemptedCount = 0
        }
    }

    /** Get debug stats as string */
    fun getDebugStats(): String {
        return "Processed: $totalProcessed, Aligned: $alignedTypeCount (type1: $type1Count, type2: $type2Count, type3: $type3Count), Exempted: $exemptedCount, NonZeroTilt: $nonZeroTiltCount"
    }

    /**
     * Threshold above which groundContourModifier exempts an object from terrain contouring.
     * From RS client: `if (hasGroundContour && this.goundContourModifier < 16384)`
     * Objects with modifier >= 16384 skip the contourToGround call entirely.
     */
    private const val CONTOUR_MODIFIER_EXEMPT_THRESHOLD = 16384

    /**
     * Calculate terrain alignment for an object using rotation-based slope matching.
     *
     * This function ONLY applies rotation for groundContourType 3 (hillChange).
     * Types 1 and 2 require per-vertex displacement handled by VertexDisplacer,
     * so this function returns NONE for those types. The object will remain upright
     * at the correct average terrain height, and per-vertex displacement is applied
     * separately as a mesh modification.
     *
     * Types 0, 4, and 5 also return NONE (no rotation alignment).
     *
     * IMPORTANT: Objects with groundContourModifier >= 16384 are EXEMPT from contouring,
     * even if they have a non-zero groundContourType. This is used by the RS client
     * to mark objects that should not be terrain-contoured (e.g., large trees, bridges).
     * See ObjectType.getPair() in drynor-bot: `if (hasGroundContour && this.goundContourModifier < 16384)`
     *
     * @param localTileX Object local tile X coordinate
     * @param localTileY Object local tile Y coordinate
     * @param sizeX Object size in X direction (tiles)
     * @param sizeY Object size in Y direction (tiles)
     * @param groundContourType Type of terrain contouring from ObjectDefinition
     * @param groundContourModifier Packed parameters for type 3 (width/length/maxTilt), or >= 16384 to exempt
     * @param averageHeight The average height the object is positioned at (from 4 corner sampling)
     * @param heightSampler Function to sample terrain height at (tileX, tileY) returning scaled height
     * @return AlignmentResult with tilt angles and height adjustment
     */
    fun calculateObjectTilt(
        localTileX: Int,
        localTileY: Int,
        sizeX: Int,
        sizeY: Int,
        groundContourType: Int,
        groundContourModifier: Int,
        averageHeight: Float,
        heightSampler: (Int, Int) -> Float
    ): AlignmentResult {
        totalProcessed++

        // Ground contour types:
        // 0 = No alignment (but still positioned at terrain height)
        // 1 = Full vertex displacement (each vertex follows terrain) - handled by VertexDisplacer
        // 2 = Partial vertex displacement (lower vertices follow terrain) - handled by VertexDisplacer
        // 3 = Hill change (whole model rotates to match slope) - exact implementation here
        // 4 = Next level (uses height from level above) - not supported in viewer
        // 5 = Stretch (stretches between levels) - not supported in viewer
        //
        // Only type 3 uses whole-model rotation. Types 1 and 2 use per-vertex
        // displacement which is applied separately as mesh modifications (see
        // VertexDisplacer). Applying rotation to type 1/2 objects produces incorrect
        // results (e.g., tall trees visibly tilting, bridge walls not connecting).

        if (groundContourType != 3) {
            // Only type 3 uses rotation-based alignment.
            // Types 1 and 2 are handled by VertexDisplacer.
            // Types 0, 4, 5 need no alignment at all.
            return AlignmentResult.NONE
        }

        // RS client exemption: objects with groundContourModifier >= 16384 are not contoured
        // This is used for objects like large trees, bridges, etc. that should remain upright
        // See ObjectType.getPair(): if (hasGroundContour && this.goundContourModifier < 16384)
        if (groundContourModifier >= CONTOUR_MODIFIER_EXEMPT_THRESHOLD) {
            exemptedCount++
            return AlignmentResult.NONE
        }

        alignedTypeCount++
        type3Count++

        // Unpack modifier for type 3
        val width: Float
        val length: Float
        val maxTiltX: Int
        val maxTiltZ: Int

        if (groundContourModifier > 0) {
            // Type 3 with explicit modifier: unpack width/length and max angles
            // Standard format: width/length in units of 4, angles shifted by 6
            width = ((groundContourModifier and 0xFF) * 4).toFloat()
            length = (((groundContourModifier shr 8) and 0xFF) * 4).toFloat()
            maxTiltX = ((groundContourModifier shr 16) and 0xFF) shl 6
            maxTiltZ = ((groundContourModifier shr 24) and 0xFF) shl 6
        } else {
            // Type 3 without modifier: use object tile size
            // RS uses 512 units per tile
            width = (sizeX * 512).toFloat()
            length = (sizeY * 512).toFloat()
            maxTiltX = 0  // No angle clamping
            maxTiltZ = 0
        }

        // Calculate sampling offsets in tile units
        // RS samples at center +/- width/2, length/2
        // With 512 units per tile: offsetTiles = (width/2) / 512
        val halfWidthTiles = width / 1024f
        val halfLengthTiles = length / 1024f

        // Object center tile
        val centerX = localTileX + sizeX * 0.5f
        val centerY = localTileY + sizeY * 0.5f

        // Sample 4 corners, clamped into a single region's tile range.
        //
        // This clamp is the same defect that VertexDisplacer's height field had: an object
        // whose footprint reaches tile 64 gets both of its opposing corners folded onto tile
        // 63, and a footprint with two identical corners has no slope, so atan2 returns zero
        // and the object stands level on a hillside. Note that it is *this* clamp that
        // governs, not the ones at the call sites in ObjectRenderer -- those re-clamp an
        // already-clamped coordinate, so removing them alone would change nothing.
        //
        // It is left in place deliberately, because it is unreachable. TiltProbe swept all
        // 2,108 readable regions (5.1M objects, 2,794 of contour type 3) and found exactly
        // one type 3 object seated on a region edge -- 73135 at (63,0) of region 50,96 --
        // whose groundContourModifier of 8224 gives it a quarter-tile sampling extent, too
        // small to cross the seam. Nothing in the cache trips this.
        //
        // What it would cost if something did is not small: `tiltprobe whatif 45 49` reports
        // 17 of 64 tiles on that region's eastern seam losing up to 31.99 degrees of roll,
        // every one of them rendering at exactly 0.000 degrees instead. So if object
        // placement ever changes -- a map edit, a newer cache, a hand-placed ramp across a
        // boundary -- re-run `tiltprobe scan`, and if it reports a non-zero count, route
        // these samples through TerrainLoader.getExtendedTileHeightScaled the way the
        // average-height corner sampling in ObjectRenderer already is.
        fun sampleHeight(offsetX: Float, offsetY: Float): Float {
            val tx = (centerX + offsetX).coerceIn(0f, 63f).toInt()
            val ty = (centerY + offsetY).coerceIn(0f, 63f).toInt()
            return heightSampler(tx, ty)
        }

        val heightSW = sampleHeight(-halfWidthTiles, -halfLengthTiles)
        val heightSE = sampleHeight(halfWidthTiles, -halfLengthTiles)
        val heightNW = sampleHeight(-halfWidthTiles, halfLengthTiles)
        val heightNE = sampleHeight(halfWidthTiles, halfLengthTiles)

        // Find lowest on each edge pair
        val lowestSouth = min(heightSW, heightSE)
        val lowestNorth = min(heightNW, heightNE)
        val lowestEast = min(heightSE, heightNE)
        val lowestWest = min(heightSW, heightNW)

        // Calculate X-axis tilt (pitch) from N/S slope
        // Note: We work in scaled height units, so use scaled length for distance
        val scaledLength = length * CoordinateUtil.DEFAULT_SCALE
        val scaledWidth = width * CoordinateUtil.DEFAULT_SCALE

        var tiltXRs = 0
        if (scaledLength != 0f) {
            val heightDiff = lowestSouth - lowestNorth
            tiltXRs = (atan2(heightDiff.toDouble(), scaledLength.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltX != 0 && tiltXRs != 0) {
                if (tiltXRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltX
                    if (tiltXRs < minAngle) tiltXRs = minAngle
                } else if (tiltXRs > maxTiltX) {
                    tiltXRs = maxTiltX
                }
            }
        }

        // Calculate Z-axis tilt (roll) from E/W slope
        var tiltZRs = 0
        if (scaledWidth != 0f) {
            val heightDiff = lowestWest - lowestEast
            tiltZRs = (atan2(heightDiff.toDouble(), scaledWidth.toDouble()) * RADIANS_TO_RS).toInt() and 0x3FFF

            if (maxTiltZ != 0 && tiltZRs != 0) {
                if (tiltZRs > RS_HALF_ROTATION) {
                    val minAngle = RS_ROTATION_UNITS - maxTiltZ
                    if (tiltZRs < minAngle) tiltZRs = minAngle
                } else if (tiltZRs > maxTiltZ) {
                    tiltZRs = maxTiltZ
                }
            }
        }

        // Convert to radians
        // Both X and Z tilts need to be negated because RS models have their Z axis
        // negated in our coordinate conversion, which reverses rotation directions
        val tiltXRadians = (if (negateXTilt) -tiltXRs else tiltXRs) * RS_TO_RADIANS
        val tiltZRadians = (if (negateZTilt) -tiltZRs else tiltZRs) * RS_TO_RADIANS

        // Height adjustment: use lowest diagonal average - averageHeight
        // This matches RS client's hillChange algorithm exactly:
        //   height = (height shr 1) - y  // where y = averageHeight
        // The object is already positioned at averageHeight, so we adjust by the difference
        // to the minimum diagonal average (which is where RS places tilted objects)
        var lowestAvg = heightSW + heightNE
        if (heightSE + heightNW < lowestAvg) {
            lowestAvg = heightSE + heightNW
        }
        val targetHeight = lowestAvg / 2f
        val heightAdjustment = targetHeight - averageHeight

        // Return NONE if alignment is disabled
        if (!enabled) {
            return AlignmentResult.NONE
        }

        val result = AlignmentResult(tiltXRadians, tiltZRadians, heightAdjustment)
        if (result.needsAlignment()) {
            nonZeroTiltCount++
        }
        return result
    }
}
