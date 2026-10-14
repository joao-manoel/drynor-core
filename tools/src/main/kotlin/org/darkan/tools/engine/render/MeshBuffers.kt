package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Where one vertex attribute lives inside a [MeshBuffers.vertexData] block.
 *
 * Attributes are stored one after another rather than interleaved, so each is still a
 * tightly packed run and [byteOffset] is all the vertex fetch needs — the stride stays 0.
 */
class VertexAttribute(
    /** Shader attribute location this run feeds. */
    val location: Int,
    /** Components per vertex: 3 for a vec3, 2 for a vec2, 1 for a float, 4 for a vec4. */
    val components: Int,
    /** GL component type, e.g. `GL_FLOAT` or `GL_UNSIGNED_BYTE`. */
    val type: Int,
    /** Whether integer components are scaled into [0,1] (or [-1,1]) on fetch. */
    val normalized: Boolean,
    /** Start of the run, in bytes from the beginning of the vertex block. */
    val byteOffset: Int
)

/**
 * Vertex and index data packed into native memory, ready for `glBufferData`.
 *
 * ## One vertex buffer, not one per attribute
 * Every attribute goes into a single allocation, laid out back to back, so a mesh costs two
 * buffer objects (vertices, indices) rather than up to seven. Each attribute is still its
 * own tightly packed run, so what the shaders see is unchanged.
 *
 * This is a resource win, not a speed one. A Canifis load at render distance 1 uploads
 * ~390 MiB of batch geometry, and `glBufferData` was measured at ~850 MB/s regardless of
 * whether that arrived as six calls per mesh or two — the driver's cost tracks bytes, not
 * calls. Paired A/B runs put the difference in `gl:batchUpload` at -9 ms, inside the noise.
 *
 * ## The lever that does work: a smaller vertex
 * Since the driver charges by the byte, the way to make the upload cheaper is to send fewer
 * of them. Colour is the free win: [packColorBytes] stores it as four `GL_UNSIGNED_BYTE`
 * components read back normalized instead of three floats, taking a full vertex from 44 to
 * 36 bytes. This is invisible to the shaders — a `vec3` input reads the first three
 * components of a four-component attribute, and normalized bytes arrive as the same floats
 * in [0,1] — so no GLSL changes and no attribute-location changes.
 *
 * Canifis at render distance 3, medians of four paired runs: 2236 -> 1893 MiB uploaded
 * (-15%, less than 18% because index bytes are along for the ride and do not shrink), the
 * driver's own copy 2148 -> 1769 ms, `gl:batchUpload` 2789 -> 2397 ms, and the load wall
 * 7209 -> 6857 ms. The conversion is not free — it turns a bulk float copy into per-vertex
 * arithmetic and costs about 58 ms of the 379 ms saved — which is why [writeColorBytes] is
 * written the way it is; an earlier version with a multiply and a branch in the inner loop
 * gave back twice as much and left the wall unmoved.
 *
 * Quantisation error is bounded by half a step, 1/510 in colour space, against a framebuffer
 * whose own resolution is 1/255. Measured against a float-colour capture of the same scene:
 * 12% of pixels differ, 99.98% of those by a single 8-bit code, six pixels in a 1280x720
 * frame by three or more. Set `-Ddarkan.byteColors=false` to fall back to float colours for
 * an A/B.
 *
 * Normals are the obvious next candidate (`GL_INT_2_10_10_10_REV` would take them 12 -> 4 B)
 * and are deliberately left alone: 10-bit normals perturb shading enough to show up in a
 * diff, and roof and terrain shading are hard-won.
 *
 * ## Byte order
 * Buffers come from `MemoryUtil.memAlloc*`, which returns views onto raw malloc'd memory in
 * the platform's native order — what OpenGL expects. This type never slices a
 * [java.nio.ByteBuffer] it hands out: `ByteBuffer.slice()` resets the view to big-endian,
 * which silently corrupts every float written through it. The typed views used for writing
 * come from `MemoryUtil.memFloatBuffer`/`memIntBuffer` over a raw address, which are always
 * native order, and the one place the packing depends on that order — assembling four colour
 * bytes into an int — reads [ByteOrder.nativeOrder] explicitly rather than assuming.
 *
 * ## Ownership
 * Native memory the GC will not reclaim. Exactly one party owns it at a time: the producer
 * hands it to a [Mesh], which frees it once uploaded. Anything that drops a packed mesh
 * without uploading it must call [free] itself; [free] is idempotent and thread-safe, so a
 * double release is harmless.
 *
 * ## Which thread should pack
 * [pack] does no GL work, so in principle a loader thread can run it. In practice, for the
 * map load path, it should not: concatenating batch geometry straight into native memory on
 * the loader pool was measured and made the load *slower*, by around 850 ms of extra CPU in
 * `ctx:batchMerge` with no change at all to `gl:batchUpload`. Every pending batch then sits
 * in the upload queue holding freshly malloc'd pages that fault in on first touch, where the
 * GC heap hands back pages that are already resident. Packing on the consumer side reuses
 * one warm block of native memory over and over — allocate, fill, upload, free — and that
 * reuse is worth more than the parallelism. Packing is only ~22% of `gl:batchUpload`
 * (~140 ms of ~610 ms); the rest is the driver's own copy, which no thread choice avoids.
 */
class MeshBuffers private constructor(
    /** Vertices described by [vertexData]. */
    val vertexCount: Int,
    /** Entries in [indices]; becomes the mesh's draw element count. */
    val indexCount: Int,
    /** Every attribute's data, concatenated into one allocation. */
    val vertexData: ByteBuffer,
    /** Where each attribute's run starts inside [vertexData]. */
    val attributes: List<VertexAttribute>,
    val indices: IntBuffer
) {
    private val freed = AtomicBoolean(false)

    /** Whether [free] has already run. */
    val isFreed: Boolean get() = freed.get()

    /**
     * Release the native memory. Idempotent, and safe to call from any thread, so
     * releasing a mesh that was already uploaded is a no-op rather than a double free.
     */
    fun free() {
        if (!freed.compareAndSet(false, true)) return
        MemoryUtil.memFree(vertexData)
        MemoryUtil.memFree(indices)
    }

    companion object {
        /**
         * Whether vertex colours are quantised to normalized bytes. See the class docs.
         * `-Ddarkan.byteColors=false` restores the float layout for measurement.
         */
        val packColorBytes: Boolean = System.getProperty("darkan.byteColors") != "false"

        /**
         * Running total of vertex + index bytes handed to `glBufferData` through this type.
         *
         * Counted at pack time rather than upload time because that is where the layout is
         * decided, which is the number worth watching: upload duration tracks bytes almost
         * exactly. Read by [org.darkan.tools.engine.region.RegionLoadProfiler], which reports
         * the delta across a load wave.
         */
        val packedBytes = AtomicLong(0)

        /**
         * Nanoseconds spent inside [pack], the CPU half of an upload.
         *
         * Quantising colour trades driver copy bytes for arithmetic here, so the two counters
         * have to be read together: a byte saving that costs more conversion than it saves
         * copy is not a saving. Reported next to [packedBytes] by
         * [org.darkan.tools.engine.region.RegionLoadProfiler].
         */
        val packNanos = AtomicLong(0)

        /**
         * Copy heap vertex arrays into one native block, recording where each lands.
         *
         * Absent attributes are simply skipped: offsets come from the arrays actually
         * supplied, so nothing assumes the arrays agree on a vertex count.
         */
        fun pack(
            positions: FloatArray,
            colors: FloatArray? = null,
            texCoords: FloatArray? = null,
            normals: FloatArray? = null,
            indices: IntArray,
            alphas: FloatArray? = null,
            atlasTransforms: FloatArray? = null,
            colorComponents: Int = 3
        ): MeshBuffers {
            val packStart = System.nanoTime()
            // Colour vertices are counted from the colour array itself, not from the position
            // count, so a caller that supplies mismatched arrays still gets a valid buffer.
            val colorVertices = if (colors != null) colors.size / colorComponents else 0
            val quantiseColors = packColorBytes && colors != null

            var totalBytes = positions.size * Float.SIZE_BYTES
            if (colors != null) {
                totalBytes += if (quantiseColors) colorVertices * COLOR_BYTES
                else colors.size * Float.SIZE_BYTES
            }
            if (texCoords != null) totalBytes += texCoords.size * Float.SIZE_BYTES
            if (normals != null) totalBytes += normals.size * Float.SIZE_BYTES
            if (alphas != null) totalBytes += alphas.size * Float.SIZE_BYTES
            if (atlasTransforms != null) totalBytes += atlasTransforms.size * Float.SIZE_BYTES

            val vertexData = MemoryUtil.memAlloc(totalBytes)
            val baseAddress = MemoryUtil.memAddress(vertexData)
            // Every run is a whole number of 4-byte units — floats, or four colour bytes — so
            // one float view over the whole block addresses all of them at byteOffset / 4.
            val floatView = MemoryUtil.memFloatBuffer(baseAddress, totalBytes / Float.SIZE_BYTES)
            val attributes = ArrayList<VertexAttribute>(6)
            var offset = 0

            fun append(values: FloatArray?, location: Int, components: Int) {
                if (values == null) return
                floatView.put(offset / Float.SIZE_BYTES, values, 0, values.size)
                attributes.add(VertexAttribute(location, components, GL_FLOAT, false, offset))
                offset += values.size * Float.SIZE_BYTES
            }

            fun appendColors() {
                if (colors == null) return
                if (!quantiseColors) {
                    append(colors, LOCATION_COLOR, colorComponents)
                    return
                }
                writeColorBytes(baseAddress + offset, colors, colorVertices, colorComponents)
                attributes.add(
                    VertexAttribute(LOCATION_COLOR, 4, GL_UNSIGNED_BYTE, true, offset)
                )
                offset += colorVertices * COLOR_BYTES
            }

            append(positions, LOCATION_POSITION, 3)
            appendColors()
            append(texCoords, LOCATION_TEX_COORD, 2)
            append(normals, LOCATION_NORMAL, 3)
            append(alphas, LOCATION_ALPHA, 1)
            append(atlasTransforms, LOCATION_ATLAS_TRANSFORM, 4)

            val packed = MeshBuffers(
                vertexCount = positions.size / 3,
                indexCount = indices.size,
                vertexData = vertexData,
                attributes = attributes,
                indices = MemoryUtil.memAllocInt(indices.size).put(0, indices, 0, indices.size)
            )

            packedBytes.addAndGet(totalBytes.toLong() + indices.size * Int.SIZE_BYTES)
            packNanos.addAndGet(System.nanoTime() - packStart)
            return packed
        }

        /**
         * Quantise float colours into RGBA8 at [address].
         *
         * Written an int at a time rather than a byte at a time: the four components of a
         * vertex are assembled in registers and stored once, which is the difference between
         * one bounds-checked native write per vertex and four. The component order inside
         * that int depends on how the machine lays an int out in memory, so it comes from
         * [ByteOrder.nativeOrder] rather than an assumption about the platform.
         *
         * A missing alpha channel becomes opaque, so a shader declaring `vec4` still sees
         * what it saw when the attribute was three floats and alpha defaulted to 1.
         *
         * Shared with [org.darkan.tools.engine.render.IndirectRenderer], which quantises the
         * object path's shared colour VBO the same way. It writes to a raw address rather
         * than a [java.nio.ByteBuffer] precisely so a caller with its own allocation can
         * point it at an offset inside that allocation.
         */
        internal fun writeColorBytes(
            address: Long,
            colors: FloatArray,
            vertices: Int,
            components: Int
        ) {
            val out = MemoryUtil.memIntBuffer(address, vertices)
            val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            // Tens of millions of vertices go through here on a town load, so the two shapes
            // get their own loops: a walking cursor instead of a multiply, and no per-vertex
            // branch on whether there is an alpha channel to read.
            var base = 0
            if (components >= 4) {
                for (i in 0 until vertices) {
                    val r = toByte(colors[base])
                    val g = toByte(colors[base + 1])
                    val b = toByte(colors[base + 2])
                    val a = toByte(colors[base + 3])
                    out.put(
                        i,
                        if (littleEndian) r or (g shl 8) or (b shl 16) or (a shl 24)
                        else (r shl 24) or (g shl 16) or (b shl 8) or a
                    )
                    base += components
                }
            } else {
                val opaque = if (littleEndian) 0xFF shl 24 else 0xFF
                for (i in 0 until vertices) {
                    val r = toByte(colors[base])
                    val g = toByte(colors[base + 1])
                    val b = toByte(colors[base + 2])
                    out.put(
                        i,
                        if (littleEndian) r or (g shl 8) or (b shl 16) or opaque
                        else (r shl 24) or (g shl 16) or (b shl 8) or opaque
                    )
                    base += components
                }
            }
        }

        /** Round a [0,1] colour component to its nearest 8-bit code, clamping out-of-range input. */
        private fun toByte(value: Float): Int {
            val scaled = (value * 255f + 0.5f).toInt()
            return if (scaled < 0) 0 else if (scaled > 255) 255 else scaled
        }

        /** Bytes one vertex colour occupies when quantised: RGBA8. */
        internal const val COLOR_BYTES = 4

        /** Vertex attribute locations, matching the shared shader vertex layout. */
        const val LOCATION_POSITION = 0
        const val LOCATION_COLOR = 1
        const val LOCATION_TEX_COORD = 2
        const val LOCATION_NORMAL = 3
        const val LOCATION_ALPHA = 4
        const val LOCATION_ATLAS_TRANSFORM = 5
    }
}
