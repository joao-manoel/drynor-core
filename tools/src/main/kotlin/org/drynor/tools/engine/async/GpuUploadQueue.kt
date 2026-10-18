package org.drynor.tools.engine.async

import org.drynor.tools.engine.region.RegionLoadProfiler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe queue for GPU upload tasks.
 *
 * Background threads call [enqueue] to submit work.
 * The main GL thread calls [drain] each frame with a nanosecond budget.
 */
class GpuUploadQueue {
    private companion object {
        /** Wall time spent inside [drain] on frames that did work. */
        const val DRAIN_WALL = "drain:wall"
        /** Of that wall time, the part actually spent inside task processors. */
        const val DRAIN_WORK = "drain:work"
        /** Call count only: frames that emptied the queue before spending their budget. */
        const val DRAIN_STARVED = "drain:starved"
        /** Call count only: frames that found nothing to do at all. */
        const val DRAIN_EMPTY = "drain:empty"
    }

    private val queue = ConcurrentLinkedQueue<GpuUploadTask>()
    private val count = AtomicInteger(0)

    /**
     * Enqueue a GPU upload task from any thread.
     */
    fun enqueue(task: GpuUploadTask) {
        queue.add(task)
        count.incrementAndGet()
    }

    /**
     * Drain tasks on the main GL thread within the given time budget.
     * Returns the number of tasks processed.
     *
     * @param budgetNanos Maximum time to spend processing tasks
     * @param processor Callback invoked for each non-cancelled task (on GL thread)
     * @param onCancelled Optional callback for cancelled tasks (to decrement region counters)
     * @return Number of tasks processed
     */
    fun drain(budgetNanos: Long, processor: (GpuUploadTask) -> Unit, onCancelled: ((GpuUploadTask) -> Unit)? = null): Int {
        val startNanos = System.nanoTime()
        var processed = 0
        var workNanos = 0L
        var starved = false
        // The timestamp taken after each task doubles as the budget check for the
        // next one, so the loop costs no more clock reads than it needs.
        var now = startNanos

        while (now - startNanos < budgetNanos) {
            val task = queue.poll()
            if (task == null) {
                starved = true
                break
            }
            count.decrementAndGet()

            if (task.cancelled) {
                onCancelled?.invoke(task)
                now = System.nanoTime()
                continue
            }

            val taskStart = System.nanoTime()
            processor(task)
            now = System.nanoTime()
            workNanos += now - taskStart
            processed++
        }

        // Split the drain into work and overhead so a load profile can tell a slow
        // upload apart from a budget that is being left on the table.
        if (RegionLoadProfiler.enabled) {
            if (processed > 0) {
                RegionLoadProfiler.record(DRAIN_WALL, System.nanoTime() - startNanos)
                RegionLoadProfiler.record(DRAIN_WORK, workNanos)
                if (starved) RegionLoadProfiler.record(DRAIN_STARVED, 0L)
            } else {
                RegionLoadProfiler.record(DRAIN_EMPTY, 0L)
            }
        }

        return processed
    }

    /**
     * Cancel all pending tasks for a given region.
     */
    fun cancelRegion(regionKey: Long) {
        for (task in queue) {
            if (task.regionKey == regionKey) {
                task.cancelled = true
            }
        }
    }

    /**
     * Cancel all pending tasks.
     */
    fun cancelAll() {
        for (task in queue) {
            task.cancelled = true
        }
    }

    /**
     * Number of pending tasks (approximate, for metrics).
     */
    fun pendingCount(): Int = count.get()

    /**
     * Whether the queue is empty.
     */
    fun isEmpty(): Boolean = queue.isEmpty()

    /**
     * Clear all tasks from the queue.
     */
    fun clear() {
        queue.clear()
        count.set(0)
    }
}
