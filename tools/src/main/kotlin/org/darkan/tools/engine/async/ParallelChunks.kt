package org.darkan.tools.engine.async

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Helper for splitting an indexed workload into contiguous chunks that are
 * processed concurrently on [Dispatchers.Default].
 *
 * Chunks are always contiguous and returned in ascending index order, so callers
 * that merge per-chunk results by iterating the returned list reproduce exactly
 * the same ordering a plain serial `for (i in 0 until total)` loop would produce.
 * That property is what keeps parallel region loading bit-for-bit deterministic.
 */
object ParallelChunks {

    /**
     * Upper bound on concurrent workers for a single chunked workload.
     *
     * `-Ddarkan.loadWorkers=N` overrides it; `N = 1` makes every chunked workload
     * run inline on the calling coroutine, which is the serial code path the
     * pipeline used before it was parallelised (useful for A/B measurement).
     */
    val WORKER_COUNT: Int = (System.getProperty("darkan.loadWorkers")?.toIntOrNull()
        ?: Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

    /**
     * Worker cap for texture pixel generation specifically, via `-Ddarkan.texWorkers=N`,
     * defaulting to [WORKER_COUNT].
     *
     * Texture generation needs its own knob because [WORKER_COUNT] also governs the region
     * load pipeline: setting `-Ddarkan.loadWorkers=1` to get a serial texture baseline would
     * serialise terrain meshing and model decoding along with it, and the resulting load wall
     * would say nothing about textures. `-Ddarkan.texWorkers=1` restores exactly the old
     * one-at-a-time generation and changes nothing else, which is the only honest A/B for
     * this phase.
     */
    val TEXTURE_WORKER_COUNT: Int = (System.getProperty("darkan.texWorkers")?.toIntOrNull()
        ?: WORKER_COUNT).coerceAtLeast(1)

    /**
     * Minimum items per chunk. Below this the coroutine dispatch overhead
     * outweighs the parallel speed-up, so small workloads stay single-chunked.
     */
    const val MIN_ITEMS_PER_CHUNK: Int = 16

    /**
     * How many chunks to create per worker. Per-item cost varies wildly (a fence
     * post and a cathedral are both "one model"), so cutting the work finer than
     * the worker count lets the dispatcher balance the tail instead of leaving one
     * oversized chunk running alone at the end.
     */
    const val CHUNKS_PER_WORKER: Int = 4

    /**
     * Split `[0, total)` into contiguous half-open ranges.
     * Returns arrays of (fromInclusive, toExclusive) in ascending order.
     */
    fun chunkBounds(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK
    ): List<IntArray> {
        if (total <= 0) return emptyList()
        if (maxWorkers <= 1) return listOf(intArrayOf(0, total))

        val bySize = (total + minItemsPerChunk - 1) / minItemsPerChunk
        val chunks = bySize.coerceIn(1, maxWorkers * CHUNKS_PER_WORKER)
        if (chunks == 1) return listOf(intArrayOf(0, total))

        val base = total / chunks
        val remainder = total % chunks
        val bounds = ArrayList<IntArray>(chunks)
        var from = 0
        for (w in 0 until chunks) {
            val size = base + (if (w < remainder) 1 else 0)
            if (size == 0) continue
            bounds.add(intArrayOf(from, from + size))
            from += size
        }
        return bounds
    }

    /**
     * Run [block] over contiguous chunks of `[0, total)` concurrently and return
     * the per-chunk results in ascending chunk order.
     *
     * [block] receives (fromInclusive, toExclusive). Each invocation runs on its
     * own coroutine, so any scratch state it needs (matrices, transformers,
     * accumulator maps) must be allocated inside the block, never shared.
     */
    suspend fun <T> mapChunks(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: suspend (Int, Int) -> T
    ): List<T> {
        val bounds = chunkBounds(total, maxWorkers, minItemsPerChunk)
        if (bounds.isEmpty()) return emptyList()
        if (bounds.size == 1) return listOf(block(bounds[0][0], bounds[0][1]))
        return coroutineScope {
            bounds.map { range ->
                async(Dispatchers.Default) { block(range[0], range[1]) }
            }.awaitAll()
        }
    }

    /**
     * Run [block] over contiguous chunks of `[0, total)` concurrently, discarding
     * results. Use when workers write into disjoint slots of a pre-sized array.
     */
    suspend fun forEachChunk(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: suspend (Int, Int) -> Unit
    ) {
        mapChunks(total, maxWorkers, minItemsPerChunk, block)
    }

    /**
     * Blocking bridge for non-suspend callers: run [block] for every index in
     * `[0, total)` across concurrent chunks and return once all of them have finished.
     *
     * [block] is handed one index at a time and is expected to write its result into a
     * slot the caller pre-sized, so results land in index order no matter which worker
     * produced them. That keeps the output bit-identical to a serial `for` loop, which is
     * what lets this be dropped into ordering-sensitive builds (atlas packing, array
     * texture layer assignment) without perturbing them.
     *
     * ONLY call this from a thread that is not itself a [Dispatchers.Default] worker —
     * in practice the GL thread. `runBlocking` parks the calling thread until the children
     * complete, so calling it from inside the pool it is waiting on can starve the
     * dispatcher. The `runBlocking` body deliberately stays on the caller's own event loop
     * and only the children go to [Dispatchers.Default], so this consumes no pool thread
     * while it waits.
     *
     * With `-Ddarkan.loadWorkers=1` (or any workload small enough to be one chunk) the
     * work runs inline on the calling thread with no coroutine involvement at all, which
     * is the serial path this replaced and the A/B baseline for measuring it.
     */
    fun forEachIndexBlocking(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: (Int) -> Unit
    ) {
        if (total <= 0) return
        val bounds = chunkBounds(total, maxWorkers, minItemsPerChunk)
        if (bounds.size <= 1) {
            for (i in 0 until total) block(i)
            return
        }
        runBlocking {
            bounds.map { range ->
                async(Dispatchers.Default) {
                    for (i in range[0] until range[1]) block(i)
                }
            }.awaitAll()
        }
    }
}
