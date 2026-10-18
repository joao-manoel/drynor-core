package org.drynor.tools.cache.map

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.drynor.tools.cache.model.ModelLoader
import org.drynor.tools.cache.model.NormalMerge
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cross-object vertex normal merging, the scene-level half of the client's
 * `Scene.applyShading()` -> `ModelGL.applyShading(other, dx, dy, dz)` pass.
 *
 * ## Why this exists
 *
 * RS scenery is tiled. A Canifis roof is 780 one-tile objects, all object id 24937, resolving
 * to seven models of two to seven faces apiece. [ModelLoader] smooths vertex normals within a
 * model, so those seams never reconcile, and two measurable artefacts follow:
 *
 *  - **Eaves.** Model 15234's shingle band has a true plane normal of `(+0.371, -0.928, 0)`, but
 *    its vertices are dragged to `(+0.931, -0.365, 0)` — 68 degrees off — by the vertical fascia
 *    trim sharing those corners. Under Canifis' lighting that is diffuse 1.17 against the 2.75 of
 *    the plain slope tile next to it: a dark rim around every roof edge.
 *  - **Hips.** Models 15224/15225 are two triangles folded 50 degrees apart; both vertices on the
 *    hip diagonal average to `(+0.331, -0.883, -0.331)` and ramp 2.17 -> 2.75 across the tile
 *    between two uniform neighbours: a smeared corner wedge.
 *
 * Feeding each vertex the normals of the *adjacent object's* faces at the same world position
 * fixes both, because the eave piece's polluted corner then also sees the pure slope normals of
 * the tile uphill from it.
 *
 * ## What the client does, and what this does
 *
 * `Scene.applyShading()` walks the tile grid and, for every object flagged `delayShading`
 * (object opcode 22), calls `castShadow` over the `+X`/`+Y` half of its tile neighbourhood on its
 * own plane and the one above. Each surviving pair reaches `ModelGL.applyShading`, which matches
 * vertices by exact integer coordinate equality and adds each model's accumulated normal *and*
 * contribution count into the other's merged copy. Summing counts, not directions, is the part
 * that matters: the final average then spans the union of both models' faces.
 *
 * The half-neighbourhood walk exists only so each pair is visited once. Since a shared world-space
 * vertex already implies adjacency, this implementation instead hashes every participating vertex
 * by its exact integer scene position and merges whatever lands in the same bucket — same result,
 * one pass, no quadratic tile scan.
 *
 * ## Integer positions, not transformed floats
 *
 * Coincidence is decided in RS integer scene units using the same mirror/rotate/scale/translate
 * fold [VertexDisplacer] uses, rather than by comparing post-transform floats. Two neighbouring
 * objects reach a shared corner through different translations, so float results agree only to
 * within an ulp and would straddle any quantisation boundary. The client's arithmetic is exact and
 * reproducing it is both cheaper and stricter.
 *
 * A consequence worth stating: objects whose four sampled terrain corners differ sit at different
 * heights, their vertices genuinely do not coincide, and no merge happens. That is the client's
 * behaviour too, not an approximation.
 *
 * ## Cost and mesh sharing
 *
 * The result rides in [org.drynor.tools.cache.model.ModelModifications.normalMerge], so it lands
 * in the mesh cache key automatically and instances with identical neighbourhoods keep sharing a
 * single mesh. Objects render through the static batcher and the indirect renderer either way, so
 * the draw call structure is untouched.
 *
 * The sharing is real but weaker than a regular roof field suggests. Measured over render
 * distance 3 at Canifis, the scene's unique mesh count rises from 3,060 with the merge off to
 * about 8,800 with it on, across some 16,000 merged instances — under two instances per variant.
 * That diversity is *structural*, not numerical noise, and quantising the identity does almost
 * nothing about it: relaxing
 * [NORMAL_QUANT] from 1/4096 to 1/128 merged 8,863 variants down to 8,821, a 0.5% saving. The
 * cause is that a vertex only merges when its neighbour's vertex lands on the *exact* same
 * integer scene position, and terrain height enters that position — so along undulating ground
 * two adjacent copies of one roof piece genuinely see different subsets of their neighbours and
 * genuinely need different normals. Making them share would mean shading them the same, which is
 * the artefact this pass exists to remove.
 *
 * What that leaves is per-variant cost rather than variant count, which is why the merged result
 * is published as finished normals (see [org.drynor.tools.cache.model.NormalMerge]) rather than
 * as a contribution the mesh builder has to re-average.
 *
 * ## Limits
 *
 * The pass runs over one region's instances, because that is the unit the async region pipeline
 * loads and meshes independently. Objects on a region boundary do not see their neighbours across
 * it and keep their unmerged normals.
 *
 * Objects tilted to the terrain (ground contour type 3) are excluded: their geometry is rotated by
 * a float angle that the integer position fold cannot reproduce, so their vertices would be keyed
 * to the wrong place.
 */
object NormalMerger {

    /**
     * Fixed-point resolution of a merged unit normal, in steps per unit component.
     *
     * This is the resolution at which two neighbourhoods count as the same mesh. Quantising a unit
     * vector to `1/N` steps bounds the angular error at about `sqrt(3) / (2N)` radians — 0.39
     * degrees at the default 128, well under [SIGNIFICANT_COS]'s two-degree floor, so it can only
     * collapse differences the significance test already calls invisible.
     *
     * It looks like it should be the main cost control, since every distinct outcome is a separate
     * mesh with its own decode, GPU registration and draw command. It is not: the variants differ
     * by far more than a quantiser can close (see the class docs for the measurement). Treat this
     * as insurance against sign-of-zero and last-ulp noise splitting one variant into two, not as
     * a tuning knob. `-Ddrynor.mergeNormals.quant` exists to re-run that experiment, not because
     * a coarser setting is expected to pay.
     */
    private val NORMAL_QUANT: Float =
        System.getProperty("drynor.mergeNormals.quant", "128").toFloat()

    // Two independent 64-bit mixes give the signature 128 bits, which for the ~10^4 distinct
    // neighbourhoods a large scene produces makes a collision (and with it a mesh wearing the
    // wrong neighbour's normals) a ~10^-30 event.
    private const val HASH_SEED_A = -0x61c8864680b583ebL
    private const val HASH_SEED_B = 0x2545f4914f6cdd1dL
    private const val HASH_PRIME_A = 0x100000001b3L
    private const val HASH_PRIME_B = 0x5851f42d4c957f2dL

    /**
     * Smallest normal deflection a contribution has to produce before it is worth keeping,
     * as a cosine.
     *
     * Most merges are no-ops by construction: a straight run of wall segments meets at vertices
     * where both models already carry the same normal, so averaging them changes nothing. Keeping
     * those contributions would still fork the mesh cache, and each fork costs a full re-decode
     * and its own indirect draw command — on Canifis that was 2184 mesh variants and a 27% longer
     * region load for shading that is bit-identical almost everywhere.
     *
     * Dropping deflections under two degrees leaves the creases the merge exists for (the roof
     * eave case is 68 degrees) while collapsing the rest back onto the shared mesh. Two degrees
     * moves a Lambert term by at most ~0.03 of its range, which is below an 8-bit colour step.
     *
     * Raising it buys less than it looks like it should — most abutting RS scenery really does
     * meet at a hard crease. Measured on Canifis: 2 degrees gives 1999 mesh variants, 5 gives
     * 1881, 10 gives 1469. Tunable via `-Ddrynor.mergeNormals.minAngle` for that kind of
     * experiment, but two degrees is the setting that changes nothing visible.
     */
    private val SIGNIFICANT_COS =
        cos(Math.toRadians(System.getProperty("drynor.mergeNormals.minAngle", "2").toDouble())).toFloat()

    /**
     * Runtime kill switch (`-Ddrynor.mergeNormals=false`) so a before/after comparison can be made
     * from one build rather than by checking out a second copy of the tree.
     */
    val enabled: Boolean = System.getProperty("drynor.mergeNormals", "true") != "false"

    /** Verbose per-region stats on stdout (`-Ddrynor.mergeNormals.debug=true`). */
    private val debug: Boolean = System.getProperty("drynor.mergeNormals.debug") == "true"

    private val totalEligible = AtomicLong()
    private val totalMerged = AtomicLong()
    private val totalVariants = AtomicLong()
    private val totalNanos = AtomicLong()

    /** Aggregate counters since process start, for the debug panel and load profiling. */
    class Stats(
        val eligibleInstances: Long,
        val mergedInstances: Long,
        val distinctSignatures: Long,
        val totalMillis: Double
    )

    fun stats(): Stats = Stats(
        eligibleInstances = totalEligible.get(),
        mergedInstances = totalMerged.get(),
        distinctSignatures = totalVariants.get(),
        totalMillis = totalNanos.get() / 1_000_000.0
    )

    fun resetStats() {
        totalEligible.set(0)
        totalMerged.set(0)
        totalVariants.set(0)
        totalNanos.set(0)
    }

    /**
     * One participating vertex of one instance, flattened into parallel arrays so the whole pass
     * allocates a fixed number of buffers rather than an object per vertex.
     */
    private class Entries(capacity: Int) {
        var size = 0
        var key = LongArray(capacity)
        var owner = IntArray(capacity)
        var vertex = IntArray(capacity)
        var normalX = FloatArray(capacity)
        var normalY = FloatArray(capacity)
        var normalZ = FloatArray(capacity)
        var magnitude = IntArray(capacity)

        fun add(k: Long, ownerIdx: Int, vertexIdx: Int, nx: Float, ny: Float, nz: Float, mag: Int) {
            if (size == key.size) grow()
            key[size] = k
            owner[size] = ownerIdx
            vertex[size] = vertexIdx
            normalX[size] = nx
            normalY[size] = ny
            normalZ[size] = nz
            magnitude[size] = mag
            size++
        }

        private fun grow() {
            val n = if (key.size == 0) 64 else key.size * 2
            key = key.copyOf(n)
            owner = owner.copyOf(n)
            vertex = vertex.copyOf(n)
            normalX = normalX.copyOf(n)
            normalY = normalY.copyOf(n)
            normalZ = normalZ.copyOf(n)
            magnitude = magnitude.copyOf(n)
        }
    }

    /**
     * Attach cross-object normal contributions to every instance in [instances] that has any.
     *
     * Mutates [ObjectInstance.modifications] and [ObjectInstance.cachedCombinedModelId] in place.
     * Call once, immediately after the instances are built and before anything reads their model
     * ids — the region pipeline decodes meshes from those ids straight afterwards.
     */
    fun apply(cache: Cache, instances: List<ObjectInstance>) {
        if (!enabled || instances.size < 2) return
        val startNanos = System.nanoTime()

        // Which instances take part, and their vertex/normal source.
        val ownerInstance = ArrayList<ObjectInstance>()
        val ownerField = ArrayList<ModelLoader.VertexNormalField>()
        var vertexBudget = 0
        for (obj in instances) {
            if (!participates(obj)) continue
            val field = ModelLoader.vertexNormalField(cache, obj.modelIds) ?: continue
            ownerInstance.add(obj)
            ownerField.add(field)
            vertexBudget += field.vertexCount
        }
        if (ownerInstance.size < 2) return

        // Pass 1: every participating vertex, keyed by exact integer scene position, with its
        // accumulated normal rotated out of model space into the shared scene frame.
        val entries = Entries(vertexBudget.coerceAtLeast(64))
        for (ownerIdx in ownerInstance.indices) {
            collectVertices(ownerInstance[ownerIdx], ownerField[ownerIdx], ownerIdx, entries)
        }
        if (entries.size < 2) return

        // Pass 2: chain entries sharing a key. A map of lists would allocate one list per distinct
        // world-space vertex, of which a town region has hundreds of thousands; head + next
        // indices give the same grouping with two int arrays. The map itself is primitive-keyed
        // for the same reason — boxing every vertex position dominated an early version.
        // Sized by element count, not table size: fastutil derives the table from the expected
        // count and the load factor, so doubling it here would allocate twice the memory a region
        // needs and, with every region loading at once, turn the pass into a GC storm.
        val head = Long2IntOpenHashMap(entries.size)
        head.defaultReturnValue(-1)
        val next = IntArray(entries.size)
        for (e in 0 until entries.size) {
            next[e] = head.put(entries.key[e], e)
        }

        // Pass 3: for each shared position, hand every instance the sum of the *other* instances'
        // normals there. Chains are two to a handful of entries long, so the inner rescan to
        // subtract an instance's own duplicate vertices is cheaper than indexing them.
        val addX = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addY = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addZ = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addMag = arrayOfNulls<IntArray>(ownerInstance.size)

        val chainHeads = head.values.toIntArray()
        for (chainHead in chainHeads) {
            if (next[chainHead] == -1) continue  // Lone vertex, nothing to merge with.

            var totalX = 0f
            var totalY = 0f
            var totalZ = 0f
            var totalMag = 0
            var distinctOwner = -1
            var singleOwner = true
            var e = chainHead
            while (e != -1) {
                totalX += entries.normalX[e]
                totalY += entries.normalY[e]
                totalZ += entries.normalZ[e]
                totalMag += entries.magnitude[e]
                if (distinctOwner == -1) {
                    distinctOwner = entries.owner[e]
                } else if (entries.owner[e] != distinctOwner) {
                    singleOwner = false
                }
                e = next[e]
            }
            // A model touching its own vertex twice is not a neighbour of itself; the client's
            // tile walk never pairs an object with itself either.
            if (singleOwner) continue

            e = chainHead
            while (e != -1) {
                val ownerIdx = entries.owner[e]

                // Subtract every entry belonging to this instance, not just this one, so a model
                // with duplicate vertices at the position does not merge with itself.
                var selfX = 0f
                var selfY = 0f
                var selfZ = 0f
                var selfMag = 0
                var s = chainHead
                while (s != -1) {
                    if (entries.owner[s] == ownerIdx) {
                        selfX += entries.normalX[s]
                        selfY += entries.normalY[s]
                        selfZ += entries.normalZ[s]
                        selfMag += entries.magnitude[s]
                    }
                    s = next[s]
                }

                val contribMag = totalMag - selfMag
                if (contribMag != 0) {
                    val obj = ownerInstance[ownerIdx]
                    val field = ownerField[ownerIdx]
                    val v = entries.vertex[e]

                    // Back out of the shared scene frame into this instance's model space, so the
                    // contribution can be added to the model-local accumulated normals.
                    val wx = totalX - selfX
                    val wy = totalY - selfY
                    val wz = totalZ - selfZ
                    val localX = unrotateX(wx, wz, obj.rsRotation)
                    val localZ = unrotateZ(wx, wz, obj.rsRotation, obj.mirrored)

                    if (deflects(field, v, localX, wy, localZ, contribMag)) {
                        val count = field.vertexCount
                        val ax = addX[ownerIdx] ?: FloatArray(count).also { addX[ownerIdx] = it }
                        val ay = addY[ownerIdx] ?: FloatArray(count).also { addY[ownerIdx] = it }
                        val az = addZ[ownerIdx] ?: FloatArray(count).also { addZ[ownerIdx] = it }
                        val am = addMag[ownerIdx] ?: IntArray(count).also { addMag[ownerIdx] = it }
                        ax[v] += localX
                        ay[v] += wy
                        az[v] += localZ
                        am[v] += contribMag
                    }
                }
                e = next[e]
            }
        }

        // Pass 4: publish. Each instance's merged normals become part of its mesh cache identity.
        var mergedCount = 0
        val signatures = HashSet<String>()
        val scratch = Vector3f()
        for (ownerIdx in ownerInstance.indices) {
            val mags = addMag[ownerIdx] ?: continue
            val merge = buildMerge(
                ownerField[ownerIdx], addX[ownerIdx]!!, addY[ownerIdx]!!, addZ[ownerIdx]!!, mags, scratch
            ) ?: continue
            val obj = ownerInstance[ownerIdx]
            obj.modifications = obj.modifications.withNormalMerge(merge)
            obj.cachedCombinedModelId =
                ModelLoader.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
            mergedCount++
            signatures.add(merge.signature)
        }

        totalEligible.addAndGet(ownerInstance.size.toLong())
        totalMerged.addAndGet(mergedCount.toLong())
        totalVariants.addAndGet(signatures.size.toLong())
        val elapsed = System.nanoTime() - startNanos
        totalNanos.addAndGet(elapsed)

        if (debug) {
            println(
                "[NORMAL-MERGE] eligible=${ownerInstance.size} merged=$mergedCount " +
                    "signatures=${signatures.size} vertices=${entries.size} " +
                    "in ${"%.2f".format(elapsed / 1_000_000.0)}ms"
            )
        }
    }

    /**
     * Whether adding this contribution actually turns vertex [v]'s normal by more than
     * [SIGNIFICANT_COS].
     *
     * Both directions are formed the way the mesh builder forms them — sum divided by count, then
     * normalised — so this measures the deflection that would really be rendered, not the size of
     * the contribution. See [SIGNIFICANT_COS] for why the negligible ones are worth discarding.
     */
    private fun deflects(
        field: ModelLoader.VertexNormalField,
        v: Int,
        addX: Float,
        addY: Float,
        addZ: Float,
        addMag: Int
    ): Boolean {
        val baseMag = field.magnitude[v]
        if (baseMag == 0) return false

        val bx = field.normalX[v] / baseMag
        val by = field.normalY[v] / baseMag
        val bz = field.normalZ[v] / baseMag
        val baseLen = sqrt(bx * bx + by * by + bz * bz)
        if (baseLen <= 0f) return false

        val mergedMag = baseMag + addMag
        if (mergedMag == 0) return true
        val mx = (field.normalX[v] + addX) / mergedMag
        val my = (field.normalY[v] + addY) / mergedMag
        val mz = (field.normalZ[v] + addZ) / mergedMag
        val mergedLen = sqrt(mx * mx + my * my + mz * mz)
        if (mergedLen <= 0f) return true

        val dot = (bx * mx + by * my + bz * mz) / (baseLen * mergedLen)
        return dot < SIGNIFICANT_COS
    }

    /**
     * Whether an object takes part in the merge.
     *
     * `delayShading` is object config opcode 22 — the flag the client itself gates
     * `Scene.applyShading` on. Honouring it keeps this faithful and keeps the participating set
     * small: buildings and walls carry it, most clutter does not.
     */
    private fun participates(obj: ObjectInstance): Boolean {
        if (!obj.definition.delayShading) return false
        // Terrain-tilted objects (contour type 3) are rotated by a float angle the integer
        // position fold below cannot reproduce; keying them would place their vertices wrong.
        if (obj.terrainAlignment.needsAlignment()) return false
        return obj.modelIds.isNotEmpty()
    }

    /**
     * Emit every smooth-shaded vertex of [obj] into [entries], keyed by exact integer scene
     * position and carrying its accumulated normal rotated into the scene frame.
     *
     * Vertices with zero magnitude are skipped: no render-type-0 face touches them, so the client
     * would find no normal index to pair and the vertex takes a flat face normal anyway.
     */
    private fun collectVertices(
        obj: ObjectInstance,
        field: ModelLoader.VertexNormalField,
        ownerIdx: Int,
        entries: Entries
    ) {
        val def = obj.definition
        val resizeX = def.modelSizeX
        val resizeY = def.modelSizeZ
        val resizeZ = def.modelSizeY

        // mirror -> rotate -> scale folded into four coefficients, exactly as VertexDisplacer
        // does it; one coefficient of each pair is always zero, so summing both products before
        // the shift is bit-identical to shifting the single non-zero term (what the client does).
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

        val baseX = def.offsetX + obj.rsSceneX
        val baseZ = def.offsetY + obj.rsSceneY
        // RS model Y and the definition's vertical offset both grow downwards, while our terrain
        // heights grow upwards, hence the subtraction. Only self-consistency across instances
        // matters here — this value is a hash key, never a rendered coordinate.
        val baseY = obj.rsAverageHeight - def.offsetZ

        for (v in 0 until field.vertexCount) {
            val mag = field.magnitude[v]
            if (mag == 0) continue

            val rsX = field.vertexX[v]
            val rsY = field.vertexY[v]
            val rsZ = field.vertexZ[v]

            val sceneX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
            val sceneZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ
            val sceneY = baseY - ((resizeY * rsY) shr 7)

            val nx = field.normalX[v]
            val ny = field.normalY[v]
            val nz = field.normalZ[v]

            entries.add(
                packKey(sceneX, sceneY, sceneZ),
                ownerIdx,
                v,
                rotateX(nx, nz, obj.rsRotation, obj.mirrored),
                ny,
                rotateZ(nx, nz, obj.rsRotation, obj.mirrored),
                mag
            )
        }
    }

    /**
     * Resolve an instance's accumulated contributions into the finished normals it should render
     * with, or null when nothing actually landed on it.
     *
     * The contribution is folded into the model's own accumulated field here rather than in
     * [ModelLoader], because everything needed is already in hand: doing it downstream would mean
     * re-running the model's whole face loop once per distinct neighbourhood.
     *
     * The stored direction is the *quantised* one, re-normalised, so it is a pure function of the
     * integers the signature is taken over. A cache key must not be coarser than the data behind
     * it, or two instances sharing a signature would render whichever one's mesh was built first.
     */
    private fun buildMerge(
        field: ModelLoader.VertexNormalField,
        addX: FloatArray,
        addY: FloatArray,
        addZ: FloatArray,
        addMag: IntArray,
        scratch: Vector3f
    ): NormalMerge? {
        var used = 0
        for (mag in addMag) {
            if (mag != 0) used++
        }
        if (used == 0) return null

        val indices = IntArray(used)
        val outX = FloatArray(used)
        val outY = FloatArray(used)
        val outZ = FloatArray(used)

        var hashA = HASH_SEED_A
        var hashB = HASH_SEED_B
        var out = 0
        for (v in addMag.indices) {
            if (addMag[v] == 0) continue

            val merged = ModelLoader.smoothedNormal(
                field.normalX[v] + addX[v],
                field.normalY[v] + addY[v],
                field.normalZ[v] + addZ[v],
                field.magnitude[v] + addMag[v],
                scratch
            )

            val qx = (merged.x * NORMAL_QUANT).roundToInt()
            val qy = (merged.y * NORMAL_QUANT).roundToInt()
            val qz = (merged.z * NORMAL_QUANT).roundToInt()

            var nx = qx.toFloat()
            var ny = qy.toFloat()
            var nz = qz.toFloat()
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) {
                val invLen = 1f / len
                nx *= invLen
                ny *= invLen
                nz *= invLen
            }

            indices[out] = v
            outX[out] = nx
            outY[out] = ny
            outZ[out] = nz

            val packed = (v.toLong() shl 48) xor
                ((qx.toLong() and 0xFFFFL) shl 32) xor
                ((qy.toLong() and 0xFFFFL) shl 16) xor
                (qz.toLong() and 0xFFFFL)
            hashA = mix(hashA, packed, HASH_PRIME_A)
            hashB = mix(hashB, packed, HASH_PRIME_B)
            out++
        }

        val signature = hashA.toULong().toString(16) + ':' + hashB.toULong().toString(16)
        return NormalMerge(indices, outX, outY, outZ, signature)
    }

    /** One round of the signature hash: xor in the value, multiply, fold the high bits down. */
    private fun mix(hash: Long, value: Long, prime: Long): Long {
        var h = hash xor value
        h *= prime
        h = h xor (h ushr 31)
        return h
    }

    /**
     * Pack an integer scene position into a hash key.
     *
     * Region-local X/Z span roughly 0..32768 RS units (64 tiles of 512, plus model
     * overhang), and heights stay within a few thousand, so 18/18/20 bits are ample. Values
     * outside those ranges would alias rather than collide with a real neighbour, which at worst
     * costs a merge that should not have happened at a position no geometry occupies.
     */
    private fun packKey(x: Int, y: Int, z: Int): Long {
        val px = (x + 131072).toLong() and 0x3FFFFL
        val pz = (z + 131072).toLong() and 0x3FFFFL
        val py = (y + 524288).toLong() and 0xFFFFFL
        return (px shl 38) or (pz shl 20) or py
    }

    // Rotation about Y by a quarter turn, with an optional Z mirror applied first. This is the
    // same map the position fold applies, minus the scale factors: R(rot) . M(mirror).
    private fun rotateX(nx: Float, nz: Float, rotation: Int, mirrored: Boolean): Float {
        val mz = if (mirrored) -nz else nz
        return when (rotation and 3) {
            1 -> mz
            2 -> -nx
            3 -> -mz
            else -> nx
        }
    }

    private fun rotateZ(nx: Float, nz: Float, rotation: Int, mirrored: Boolean): Float {
        val mz = if (mirrored) -nz else nz
        return when (rotation and 3) {
            1 -> -nx
            2 -> -mz
            3 -> nx
            else -> mz
        }
    }

    // Inverse of the above. R and M are orthogonal and M is an involution, so
    // (R(rot) . M)^-1 = M . R(4 - rot).
    private fun unrotateX(wx: Float, wz: Float, rotation: Int): Float {
        return when ((4 - (rotation and 3)) and 3) {
            1 -> wz
            2 -> -wx
            3 -> -wz
            else -> wx
        }
    }

    private fun unrotateZ(wx: Float, wz: Float, rotation: Int, mirrored: Boolean): Float {
        val z = when ((4 - (rotation and 3)) and 3) {
            1 -> -wx
            2 -> -wz
            3 -> wx
            else -> wz
        }
        return if (mirrored) -z else z
    }
}
