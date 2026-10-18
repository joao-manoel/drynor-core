package org.drynor.tools.engine.render

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjectui
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64

/**
 * Per-pass GPU timing with `GL_TIME_ELAPSED` queries that never stalls the pipeline.
 *
 * CPU-side frame timings say nothing about where GPU time goes, and the obvious way to
 * find out — issue a query and read it back at the end of the frame — forces a sync and
 * changes the very thing being measured. This pool instead keeps [DEPTH] full sets of
 * query objects and only ever harvests a set that is [DEPTH] frames old, by which point
 * the result is sitting there for the taking. Every read is guarded by
 * `GL_QUERY_RESULT_AVAILABLE` as well, so a slow frame just leaves the previous number on
 * screen rather than blocking.
 *
 * ### Usage
 * ```
 * timers.beginFrame()
 * timers.begin(PASS_TERRAIN); ...draws...; timers.end()
 * timers.begin(PASS_OBJECTS); ...draws...; timers.end()
 * timers.endFrame()
 * ```
 *
 * Only one `GL_TIME_ELAPSED` query may be active at a time in GL, so passes must not
 * overlap or nest; [begin] silently closes a pass left open by mistake.
 */
class GpuTimerPool(val passNames: List<String>) {

    /** Milliseconds for each pass, from the most recently harvested frame. */
    val passMillis = DoubleArray(passNames.size)

    /** Sum of [passMillis]. Not the whole frame — only the passes that are instrumented. */
    val totalMillis: Double
        get() = passMillis.sum()

    /** True when the context can run timer queries at all. */
    var supported: Boolean = false
        private set

    /** How many frames back the numbers in [passMillis] come from. */
    var resultAgeFrames: Int = 0
        private set

    // queryIds[frameSlot][pass]
    private var queryIds: Array<IntArray> = emptyArray()

    // Whether a given slot/pass query has been issued and not yet harvested.
    private var pending: Array<BooleanArray> = emptyArray()

    private var currentSlot = 0
    private var activePass = -1
    private var initialized = false

    /**
     * Create the query objects. Must run on the GL thread.
     *
     * @return true if timing is available
     */
    fun init(): Boolean {
        val caps = GL.getCapabilities()
        // The window asks for a 3.3 core context, so test the capability rather than the
        // reported version string.
        supported = caps.OpenGL33 || caps.GL_ARB_timer_query
        if (!supported) {
            println("[GpuTimerPool] GL_TIME_ELAPSED queries unavailable; GPU pass timing disabled")
            return false
        }

        queryIds = Array(DEPTH) { IntArray(passNames.size) { glGenQueries() } }
        pending = Array(DEPTH) { BooleanArray(passNames.size) }
        initialized = true
        return true
    }

    /**
     * Start a frame: harvest whatever the slot about to be reused produced [DEPTH] frames
     * ago, then hand the slot to the new frame.
     */
    fun beginFrame() {
        if (!initialized) return
        harvest(currentSlot)
    }

    /** Begin timing [pass]. Closes any pass the caller left open. */
    fun begin(pass: Int) {
        if (!initialized || pass !in passNames.indices) return
        if (activePass >= 0) end()
        glBeginQuery(GL_TIME_ELAPSED, queryIds[currentSlot][pass])
        activePass = pass
    }

    /** End the pass opened by [begin]. */
    fun end() {
        if (!initialized || activePass < 0) return
        glEndQuery(GL_TIME_ELAPSED)
        pending[currentSlot][activePass] = true
        activePass = -1
    }

    /** Close the frame and advance to the next slot in the ring. */
    fun endFrame() {
        if (!initialized) return
        if (activePass >= 0) end()
        currentSlot = (currentSlot + 1) % DEPTH
    }

    /**
     * Read back every completed query in [slot]. Availability is checked first, so this
     * never blocks; an unavailable query keeps its previous reading and is dropped, since
     * the slot is about to be reused.
     */
    private fun harvest(slot: Int) {
        val ids = queryIds[slot]
        val slotPending = pending[slot]
        for (pass in ids.indices) {
            if (!slotPending[pass]) continue
            slotPending[pass] = false
            if (glGetQueryObjectui(ids[pass], GL_QUERY_RESULT_AVAILABLE) == 0) continue
            val nanos = glGetQueryObjectui64(ids[pass], GL_QUERY_RESULT)
            passMillis[pass] = nanos / 1_000_000.0
        }
        resultAgeFrames = DEPTH
    }

    fun cleanup() {
        if (!initialized) return
        if (activePass >= 0) {
            glEndQuery(GL_TIME_ELAPSED)
            activePass = -1
        }
        for (ids in queryIds) for (id in ids) glDeleteQueries(id)
        queryIds = emptyArray()
        pending = emptyArray()
        initialized = false
    }

    companion object {
        /**
         * Frames of slack between issuing a query and reading it. Three is enough to clear
         * a frame of GPU latency plus the driver's own buffering without the results
         * feeling stale on screen.
         */
        const val DEPTH = 3
    }
}
