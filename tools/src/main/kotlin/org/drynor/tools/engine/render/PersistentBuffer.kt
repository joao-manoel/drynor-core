package org.drynor.tools.engine.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER
import org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT
import org.lwjgl.opengl.GL32.*
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT
import org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT
import org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A triple-buffered persistent mapped buffer for streaming per-frame data to the GPU
 * without driver-side allocation churn.
 *
 * Uses `glBufferStorage` + `glMapBufferRange` with `GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`
 * to map the buffer once at creation time. The buffer is divided into [frames] sections;
 * each frame writes to a different section and a GL fence sync ensures the GPU has finished
 * reading a section before the CPU overwrites it.
 *
 * If persistent mapping is unavailable (no GL 4.4 / ARB_buffer_storage), the class falls back
 * to a traditional CPU-side ByteBuffer + `glBufferSubData` path.
 *
 * ### Frame protocol
 * ```
 * val buf = pb.beginFrame()      // waits on this section's fence, returns writable memory
 * ... write bytes ...
 * pb.endWrite(bytesWritten)      // fallback path uploads here; persistent path is a no-op
 * ... bind / issue draw calls, using readOffset() as the byte offset ...
 * pb.endFrame()                  // fence AFTER the draws that read this section, then advance
 * ```
 * The fence must be placed after the draws, not after the writes: a fence records
 * "everything issued before me has completed", so fencing at write time would let the
 * CPU reclaim a section while the GPU was still reading it.
 *
 * @param target         GL buffer target (`GL_SHADER_STORAGE_BUFFER` or `GL_DRAW_INDIRECT_BUFFER`)
 * @param requestedSectionSize Size in bytes of a single frame's section (rounded up for alignment)
 * @param frames         Number of buffered frames (default 3 for triple buffering)
 */
class PersistentBuffer(
    val target: Int,
    requestedSectionSize: Long,
    val frames: Int = BUFFER_FRAMES
) {
    /**
     * Size in bytes of a single frame's section.
     *
     * Rounded up to the binding alignment required by [target] so that every section
     * offset is a legal `glBindBufferRange` offset. Without this the ring only works
     * by accident, for section sizes that happen to be a multiple of the alignment.
     */
    val sectionSize: Long = alignUp(requestedSectionSize, bindingAlignment(target))

    /** GL buffer name. */
    var bufferId: Int = 0
        private set

    /** Persistently mapped memory spanning all [frames] sections, or null if using fallback. */
    private var persistentMap: ByteBuffer? = null

    /** CPU-side staging buffer used only in the fallback (non-persistent) path. */
    private var fallbackBuffer: ByteBuffer? = null

    /** True if we successfully created a persistent mapping. */
    var isPersistent: Boolean = false
        private set

    /** Index of the current frame section (0 until [frames]). */
    private var currentFrame: Int = 0

    /** True between [beginFrame] and [endFrame]. Guards against fencing a section never written. */
    private var frameOpen: Boolean = false

    /** GL fence sync objects, one per frame section. Null means no fence placed yet. */
    private val fences = arrayOfNulls<Long>(frames)

    /** Total buffer size in bytes (sectionSize * frames for persistent, sectionSize for fallback). */
    val totalSize: Long
        get() = if (isPersistent) sectionSize * frames else sectionSize

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Create the GL buffer and attempt persistent mapping.
     * Must be called on the GL thread after context creation.
     */
    fun init() {
        bufferId = glGenBuffers()

        if (tryPersistentInit()) {
            isPersistent = true
        } else {
            // Fallback: traditional mutable buffer + CPU staging
            initFallback()
            isPersistent = false
        }
    }

    /**
     * Attempt to create a persistent mapped buffer via glBufferStorage.
     * Returns true on success, false if the GL version/extension is unavailable
     * or the mapping call fails.
     */
    private fun tryPersistentInit(): Boolean {
        if (!PERSISTENT_MAPPING_ENABLED) return false
        val caps: GLCapabilities = GL.getCapabilities()
        if (!caps.GL_ARB_buffer_storage && !caps.OpenGL44) {
            return false
        }

        val totalBytes = sectionSize * frames

        val storageFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT
        val mapFlags = GL_MAP_WRITE_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT

        glBindBuffer(target, bufferId)

        // Clear any previous GL error
        GL11.glGetError()

        glBufferStorage(target, totalBytes, storageFlags)

        val storageError = GL11.glGetError()
        if (storageError != GL11.GL_NO_ERROR) {
            System.err.println(
                "[PersistentBuffer] glBufferStorage($totalBytes bytes) failed with GL error " +
                        "0x${storageError.toString(16)}; falling back to glBufferSubData"
            )
            glBindBuffer(target, 0)
            // The buffer name may now have immutable storage attached; start over with a fresh one.
            glDeleteBuffers(bufferId)
            bufferId = glGenBuffers()
            return false
        }

        val mapped = glMapBufferRange(target, 0, totalBytes, mapFlags)
        glBindBuffer(target, 0)

        if (mapped == null) {
            System.err.println("[PersistentBuffer] glMapBufferRange returned null; falling back to glBufferSubData")
            glDeleteBuffers(bufferId)
            bufferId = glGenBuffers()
            return false
        }

        // LWJGL hands back a native-order buffer, but every ByteBuffer view derived from it
        // (slice/duplicate) resets to BIG_ENDIAN per the java.nio spec. Normalise here so the
        // per-section slices handed out by beginFrame() can inherit the right order explicitly.
        persistentMap = mapped.order(ByteOrder.nativeOrder())
        return true
    }

    /**
     * Fallback initialization using glBufferData + CPU-side staging buffer.
     */
    private fun initFallback() {
        glBindBuffer(target, bufferId)
        glBufferData(target, sectionSize, GL_DYNAMIC_DRAW)
        glBindBuffer(target, 0)

        fallbackBuffer = MemoryUtil.memAlloc(sectionSize.toInt()).order(ByteOrder.nativeOrder())
    }

    // ====================================================================
    // Per-frame API
    // ====================================================================

    /**
     * Begin writing data for the current frame.
     *
     * For persistent buffers this waits on the fence for the current section (ensuring
     * the GPU is done reading it) and returns a ByteBuffer positioned at the section start.
     *
     * For fallback buffers this simply clears and returns the CPU staging buffer.
     *
     * @return A ByteBuffer positioned at the start of the writable region, in native byte
     *         order. The caller may write up to [sectionSize] bytes.
     */
    fun beginFrame(): ByteBuffer {
        frameOpen = true
        if (isPersistent) {
            waitForFence()
            val map = persistentMap!!
            val offset = currentFrame * sectionSize
            // Return a slice positioned at the current section
            map.position(offset.toInt())
            map.limit((offset + sectionSize).toInt())
            // ByteBuffer.slice() always produces a BIG_ENDIAN view regardless of the parent's
            // order. Writing GPU structs through a big-endian view byte-swaps every float and
            // int: matrices become garbage and DrawElementsIndirectCommand counts become
            // astronomically large, which wedges the GPU. Restore native order explicitly.
            val slice = map.slice().order(ByteOrder.nativeOrder())
            // Restore the parent buffer's full range
            map.position(0)
            map.limit(map.capacity())
            return slice
        } else {
            val fb = fallbackBuffer!!
            fb.clear()
            return fb
        }
    }

    /**
     * Finish *writing* for the current frame.
     *
     * For the fallback path this uploads the staging buffer via `glBufferSubData`.
     * For the persistent path it is a no-op: coherent mapping makes the writes visible.
     *
     * Draw calls that read this data may be issued after this call; [endFrame] must be
     * called once they have been.
     *
     * @param bytesWritten The number of bytes actually written this frame.
     */
    fun endWrite(bytesWritten: Long) {
        if (isPersistent) return
        val fb = fallbackBuffer!!
        if (bytesWritten > 0) {
            fb.position(0)
            fb.limit(bytesWritten.toInt())
            glBindBuffer(target, bufferId)
            glBufferSubData(target, 0, fb)
            glBindBuffer(target, 0)
        }
        fb.clear()
    }

    /**
     * Finish the frame: place a fence covering the draw calls that read this section and
     * advance the ring. Must be called *after* those draws have been issued.
     */
    fun endFrame() {
        if (!frameOpen) return
        frameOpen = false
        if (!isPersistent) return
        placeFence()
        currentFrame = (currentFrame + 1) % frames
    }

    /**
     * Bind the current frame's section of the buffer to an indexed binding point
     * using `glBindBufferRange`. This is used for SSBOs.
     *
     * For fallback buffers (single section, no offset), this binds the entire buffer.
     *
     * @param binding The indexed binding point
     * @param size    The number of bytes to bind (should match bytesWritten from endWrite)
     */
    fun bindRange(binding: Int, size: Long) {
        if (size <= 0L) return
        glBindBufferRange(target, binding, bufferId, readOffset(), size)
    }

    /**
     * Bind the buffer as the current target (e.g., GL_DRAW_INDIRECT_BUFFER).
     * For persistent mapping, the caller must add [readOffset] to any byte offset it passes
     * to the draw call. For fallback, the offset is always 0.
     */
    fun bind() {
        glBindBuffer(target, bufferId)
    }

    /**
     * Unbind the buffer from its target.
     */
    fun unbind() {
        glBindBuffer(target, 0)
    }

    /**
     * Byte offset of the section currently being written/read this frame.
     *
     * Valid between [beginFrame] and [endFrame]; always 0 for the fallback path.
     */
    fun readOffset(): Long = if (isPersistent) currentFrame * sectionSize else 0L

    // ====================================================================
    // Fence synchronization (persistent path only)
    // ====================================================================

    /**
     * Wait for the GPU to finish reading the current frame's section.
     */
    private fun waitForFence() {
        val fence = fences[currentFrame] ?: return
        // Wait with a 1-second timeout (should be nearly instant if triple buffered)
        val result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L)
        if (result == GL_WAIT_FAILED) {
            System.err.println("[PersistentBuffer] glClientWaitSync returned GL_WAIT_FAILED")
        }
        glDeleteSync(fence)
        fences[currentFrame] = null
    }

    /**
     * Place a fence after the current frame's draw calls.
     */
    private fun placeFence() {
        fences[currentFrame]?.let { glDeleteSync(it) }
        fences[currentFrame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Release all GPU and CPU resources.
     */
    fun cleanup() {
        // Delete any outstanding fence syncs
        for (i in fences.indices) {
            fences[i]?.let { glDeleteSync(it) }
            fences[i] = null
        }

        // Unmap persistent buffer before deleting
        if (isPersistent && persistentMap != null) {
            glBindBuffer(target, bufferId)
            glUnmapBuffer(target)
            glBindBuffer(target, 0)
            persistentMap = null
        }

        // Free fallback CPU buffer
        fallbackBuffer?.let { MemoryUtil.memFree(it) }
        fallbackBuffer = null

        // Delete GL buffer
        if (bufferId != 0) {
            glDeleteBuffers(bufferId)
            bufferId = 0
        }

        isPersistent = false
        currentFrame = 0
        frameOpen = false
    }

    companion object {
        /** Default number of buffered frames (triple buffering). */
        const val BUFFER_FRAMES = 3

        /**
         * `-Ddrynor.persistentMapping=false` forces the `glBufferSubData` path even where
         * ARB_buffer_storage is available, so the fallback stays exercisable on hardware
         * that would otherwise never take it.
         */
        private val PERSISTENT_MAPPING_ENABLED: Boolean =
            System.getProperty("drynor.persistentMapping", "true").toBoolean()

        /** Round [value] up to the next multiple of [alignment]. */
        private fun alignUp(value: Long, alignment: Long): Long =
            if (alignment <= 1L) value else ((value + alignment - 1L) / alignment) * alignment

        /**
         * Minimum legal `glBindBufferRange` offset granularity for [target].
         *
         * Indirect buffers are not bound by range, but their command offsets must still be a
         * multiple of 4, so a small alignment is applied uniformly.
         */
        private fun bindingAlignment(target: Int): Long = when (target) {
            GL_SHADER_STORAGE_BUFFER -> GL11.glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT).toLong().coerceAtLeast(4L)
            GL_UNIFORM_BUFFER -> GL11.glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT).toLong().coerceAtLeast(4L)
            else -> 4L
        }
    }
}
