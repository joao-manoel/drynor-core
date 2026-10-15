package org.drynor.tools.cache.texture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Accounting for [TextureLoader.getTexturePixelsForMaterial], the procedural pixel generator
 * behind both the terrain atlas and the indirect renderer's array textures.
 *
 * It exists to answer one question with numbers rather than intuition: **how much of the work
 * is repeat work?** A pixel cache was built and reverted once on cold-load evidence alone
 * (0.5-2.9% repeat rate), which said nothing about repeated navigation — where the same
 * materials are asked for again on every jump. [repeatNanos] is the arithmetic ceiling on
 * what memoising can give back over any window; [servedNanos] is what it actually gave back
 * once the cache is on.
 *
 * The counters hold ids, never pixels, so measuring cannot itself change the cost of the
 * path being measured.
 *
 * Every counter is process-lifetime cumulative; take a [snapshot] around a phase and use
 * [Snapshot.since] for that phase's share.
 */
object TexturePixelStats {

    /** Ids generated at least once, process-wide. Ids only, never pixels. */
    private val seenIds = ConcurrentHashMap.newKeySet<Int>()

    private val generations = AtomicLong(0)
    private val firstGenerations = AtomicLong(0)
    private val repeatGenerations = AtomicLong(0)
    private val hits = AtomicLong(0)
    private val totalNanos = AtomicLong(0)
    private val repeatNanos = AtomicLong(0)
    private val distinctBytes = AtomicLong(0)

    /**
     * Record one completed generation.
     *
     * @param textureId the material generated
     * @param nanos wall time the generation took on the calling thread
     * @param pixels number of pixels produced, for the retained-bytes estimate
     */
    fun record(textureId: Int, nanos: Long, pixels: Int) {
        generations.incrementAndGet()
        totalNanos.addAndGet(nanos)
        if (seenIds.add(textureId)) {
            firstGenerations.incrementAndGet()
            distinctBytes.addAndGet(pixels.toLong() * Int.SIZE_BYTES)
        } else {
            // Only reachable with memoisation off (or past its budget): the same id has
            // already been produced once in this process, so this generation is pure repeat.
            repeatGenerations.incrementAndGet()
            repeatNanos.addAndGet(nanos)
        }
    }

    /** Record one request served from [TextureLoader]'s pixel cache instead of generated. */
    fun recordHit() {
        hits.incrementAndGet()
    }

    class Snapshot(
        val generations: Long,
        val firstGenerations: Long,
        val repeatGenerations: Long,
        val hits: Long,
        val totalNanos: Long,
        val repeatNanos: Long,
        val distinctBytes: Long,
        val distinctIds: Int
    ) {
        /** Counters accumulated between [earlier] and this snapshot. */
        fun since(earlier: Snapshot): Snapshot = Snapshot(
            generations = generations - earlier.generations,
            firstGenerations = firstGenerations - earlier.firstGenerations,
            repeatGenerations = repeatGenerations - earlier.repeatGenerations,
            hits = hits - earlier.hits,
            totalNanos = totalNanos - earlier.totalNanos,
            repeatNanos = repeatNanos - earlier.repeatNanos,
            distinctBytes = distinctBytes - earlier.distinctBytes,
            distinctIds = distinctIds - earlier.distinctIds
        )

        /** Requests that did not have to be generated from scratch, as a percentage. */
        val reuseRatePercent: Double
            get() {
                val requests = generations + hits
                return if (requests > 0) 100.0 * (hits + repeatGenerations) / requests else 0.0
            }

        /**
         * One line describing this window: how many requests were reused rather than freshly
         * generated, the generation time spent, and how much of it was avoidable (cache off)
         * or avoided (cache on).
         */
        fun describe(label: String): String =
            ("[TEXGEN] %-22s requests=%d gen=%d hit=%d reuse=%.1f%%  genTime=%.0f ms  " +
                "repeatGenTime=%.0f ms  resident=%.1f MiB")
                .format(
                    label, generations + hits, generations, hits, reuseRatePercent,
                    totalNanos / 1e6, repeatNanos / 1e6,
                    distinctBytes / (1024.0 * 1024.0)
                )
    }

    fun snapshot(): Snapshot = Snapshot(
        generations = generations.get(),
        firstGenerations = firstGenerations.get(),
        repeatGenerations = repeatGenerations.get(),
        hits = hits.get(),
        totalNanos = totalNanos.get(),
        repeatNanos = repeatNanos.get(),
        distinctBytes = distinctBytes.get(),
        distinctIds = seenIds.size
    )
}
