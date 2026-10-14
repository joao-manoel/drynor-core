package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame set - a whole index 0 archive - as a glTF 2.0 binary file.
 *
 * Index 7's models are `.glb` files ([ModelGltf]) so that a model in the tree is a model Blender
 * opens; an animation is the other half of the same job, and this is it. A frame set is *one* file
 * rather than one per frame, because a glTF animation is a list of keyframes and that is exactly
 * what a frame set is: 4,085 files instead of 490,049.
 *
 * ```
 * glb
 *   header      "glTF", version 2, total length
 *   JSON chunk  nodes, one glTF animation, and extras.rs - the frames themselves
 *   BIN chunk   the animation samplers: keyframe times, then keyframe values
 * ```
 *
 * **The nodes are the skeleton's label groups.** A base ([AnimationBaseDefinition], index 1) is a
 * list of transforms, each with a kind - origin, translate, rotate, scale, alpha, colour, billboard
 * - and a set of model labels it moves. It is a *list*, not a bone tree, so the nodes are flat
 * scene roots named by their group index, and each carries the base's transform type and the group's
 * labels in `extras.rs`. The base file (`bases/<id>.json`) is still the truth for index 1; what a
 * node holds is the copy a viewer needs to draw the animation without opening another index.
 *
 * **The frames are a glTF animation.** A group whose RS transform type has a glTF equivalent -
 * translate, rotate, scale - gets a channel, the frame's own file id is the keyframe time, and the
 * values are the RS integers, **unscaled**, exactly as [ModelGltf] writes positions: a `translation`
 * or `scale` output is the frame's x, y, z, and a `rotation` output is `[x, y, z, 0]`, because the
 * glTF `rotation` path takes a `VEC4`. It is therefore not a unit quaternion, and a viewer converts:
 * an RS rotate value is an angle of `(v shl 2) and 0x3fff` out of 16,384 to the turn about that axis,
 * a scale is `v / 128`, and a translation is model units. A component the frame's flag byte leaves
 * clear is filled with what the client substitutes - `0`, or `128` for a scale - so the sampler is a
 * whole keyframe; which components the *file* held is the flag byte in the extras. The types with no
 * glTF equivalent - origin, alpha, colour, the three billboard kinds - get no channel and live in the
 * extras alone.
 *
 * **`extras.rs` on the scene is the whole truth for the re-encoder.** Nothing in the glTF proper is
 * read back: the base id, each frame's file id and header byte, its declared transform count, the
 * groups it moves with their raw flag bytes, its value run exactly as the file holds it, the smart
 * widths where they are not the narrowest form, and the three chunk group layout. So a `.glb` that
 * loses its extras fails loudly instead of packing wrong bytes, and a Blender plugin that means to
 * change an animation writes the extras rather than only the samplers.
 *
 * The frame data is in the extras *and* in the samplers, which is the one place this format spends
 * bytes twice: the samplers are what makes the file an animation any glTF tool can play, and the
 * extras are what makes it byte-exact. `docs/cache-source.md`, *anims*, measures what that costs.
 */
internal object AnimationGltf {

    /** A base transform kind that moves the label group's origin - the pivot later rotations use. */
    const val ORIGIN = 0

    /** Translate: a glTF `translation` channel. */
    const val TRANSLATE = 1

    /** Rotate: a glTF `rotation` channel. `AnimBase` folds the equivalent type 6 into this one. */
    const val ROTATE = 2

    /** Scale: a glTF `scale` channel. Its absent components default to 128 rather than 0. */
    const val SCALE = 3

    /** Alpha, colour and the three billboard kinds: extras only, glTF has no channel for them. */
    const val ALPHA = 5

    /** The second type the client folds into [ROTATE]; no 727 base carries one. */
    private const val ROTATE_ALIAS = 6

    /** The billboard scale, whose absent components default to 128 like [SCALE]'s. */
    private const val BILLBOARD_SCALE = 10

    /** The flag bits that each ask for one signed smart, in the order the file writes them. */
    private val COMPONENTS = intArrayOf(0x1, 0x2, 0x4)

    /** What the client substitutes for a component the flag byte leaves clear, for most types. */
    private const val ABSENT = 0

    /** What it substitutes for [SCALE] and [BILLBOARD_SCALE], which are multipliers of 128. */
    private const val ABSENT_SCALE = 128

    /** A group the base does not declare - a frame longer than its skeleton, which 727 has none of. */
    const val UNDECLARED = -1

    /** [set] as the exact bytes of its `.glb`; [base] is the skeleton the frames name. */
    fun write(set: AnimationFrameSet, base: AnimationBaseDefinition?): ByteArray {
        val groups = groups(set, base)
        val types = IntArray(groups) { if (base != null && it < base.types.size) base.types[it] else UNDECLARED }
        val channels = channels(set, types)
        val binary = binary(channels)
        return Gltf.assemble(asset(set, base, types, channels, binary.size), binary)
    }

    /**
     * Read a `.glb` back into the frame set of [archive].
     *
     * Everything comes from `extras.rs` on the scene - see the class comment - so a file whose
     * samplers have been rewritten by a tool that did not update the extras is packed from the
     * extras, and a file with no extras at all is refused.
     */
    fun read(bytes: ByteArray, archive: Int): AnimationFrameSet {
        val root = Gltf.root(Gltf.chunks(bytes).first)
        val scene = root["scenes"]?.jsonArray?.getOrNull(root["scene"]?.jsonPrimitive?.int ?: 0)?.jsonObject
            ?: throw IllegalArgumentException("Frame set $archive has no glTF scene.")
        val rs = scene["extras"]?.jsonObject?.get("rs")?.jsonObject
            ?: throw IllegalArgumentException(
                "Frame set $archive carries no rs extras on its scene; it is not a cache frame set."
            )
        val baseId = rs["base"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("Frame set $archive names no base.")
        val layout = rs["layout"]?.jsonObject ?: JsonObject(emptyMap())
        val frames = ArrayList<AnimationFrameDefinition>()
        for (element in rs["frames"]?.jsonArray ?: emptyList()) {
            frames.add(frame(element.jsonObject, archive, baseId))
        }
        return AnimationFrameSet(
            archive = archive,
            baseId = baseId,
            frames = frames,
            chunks = layout["chunks"]?.jsonPrimitive?.int ?: if (frames.size == 1) 1 else CHUNKS,
            header = layout["header"]?.jsonPrimitive?.int ?: HEADER
        )
    }

    /** The `.glb`'s JSON chunk, for a report or a test that wants to show what a file says. */
    fun json(bytes: ByteArray): String = Gltf.text(bytes)

    /** One frame out of the extras, rebuilt into the definition the encoder writes. */
    private fun frame(frame: JsonObject, archive: Int, baseId: Int): AnimationFrameDefinition {
        val file = frame["file"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("A frame of set $archive has no file id.")
        val moved = Gltf.ints(frame["transforms"]) ?: IntArray(0)
        val bytes = Gltf.ints(frame["flags"]) ?: IntArray(0)
        require(moved.size == bytes.size) {
            "Frame set $archive frame $file lists ${moved.size} transforms and ${bytes.size} flag bytes."
        }
        val count = frame["count"]?.jsonPrimitive?.int ?: (moved.maxOrNull()?.plus(1) ?: 0)
        require(count <= AnimationFrameDefinition.WRAPPED_COUNT) {
            "Frame set $archive frame $file declares $count transforms; the count byte holds at most " +
                "${AnimationFrameDefinition.WRAPPED_COUNT}."
        }
        val flags = IntArray(count)
        for (index in moved.indices) {
            val group = moved[index]
            require(group in 0 until count) {
                "Frame set $archive frame $file moves group $group, which is outside its $count transforms."
            }
            require(flags[group] == 0) { "Frame set $archive frame $file lists group $group twice." }
            flags[group] = bytes[index]
        }
        val values = Gltf.ints(frame["values"]) ?: IntArray(0)
        val expected = components(flags)
        require(values.size == expected) {
            "Frame set $archive frame $file has ${values.size} values for the $expected its flags ask for."
        }
        return AnimationFrameDefinition(
            id = (archive shl AnimationFrameDecoder.BITS) or file,
            baseId = frame["base"]?.jsonPrimitive?.int ?: baseId,
            header = frame["header"]?.jsonPrimitive?.int ?: 1,
            flags = flags,
            values = values,
            wideValues = Gltf.ints(frame["wide"])
        )
    }

    // --- writing -----------------------------------------------------------------------------

    /** How many label groups the file describes: the base's transforms, or the longest frame. */
    private fun groups(set: AnimationFrameSet, base: AnimationBaseDefinition?): Int {
        var groups = base?.types?.size ?: 0
        for (frame in set.frames) {
            if (frame.flags.size > groups) {
                groups = frame.flags.size
            }
        }
        return groups
    }

    /** One group's keyframes: when it moves, and what to. */
    private class Channel(val group: Int, val path: String, val arity: Int, val times: IntArray, val values: IntArray)

    /**
     * A channel per label group whose RS type glTF can spell, in group order.
     *
     * A keyframe exists exactly where the frame's flag byte for that group is not zero, which is
     * where the file has something to say; the components it left out are filled with the client's
     * own substitute so that the sampler is a whole `VEC3` or `VEC4`.
     */
    private fun channels(set: AnimationFrameSet, types: IntArray): List<Channel> {
        val paths = arrayOfNulls<String>(types.size)
        val counts = IntArray(types.size)
        for (group in types.indices) {
            paths[group] = path(types[group])
        }
        for (frame in set.frames) {
            val limit = minOf(frame.flags.size, types.size)
            for (group in 0 until limit) {
                if (frame.flags[group] != 0 && paths[group] != null) {
                    counts[group]++
                }
            }
        }
        val times = arrayOfNulls<IntArray>(types.size)
        val values = arrayOfNulls<IntArray>(types.size)
        val arity = IntArray(types.size)
        val absent = IntArray(types.size)
        val keyframes = IntArray(types.size)
        for (group in types.indices) {
            if (paths[group] == null || counts[group] == 0) {
                continue
            }
            arity[group] = if (paths[group] == ROTATION) 4 else 3
            absent[group] = if (types[group] == SCALE || types[group] == BILLBOARD_SCALE) ABSENT_SCALE else ABSENT
            times[group] = IntArray(counts[group])
            values[group] = IntArray(counts[group] * arity[group])
        }
        // One pass over the frames, carrying the offset into the frame's single run of values: a
        // group's components sit after every component the groups before it asked for, channelled
        // or not.
        for (frame in set.frames) {
            val time = frame.id and MASK
            var offset = 0
            for (group in frame.flags.indices) {
                val flag = frame.flags[group]
                if (flag == 0) {
                    continue
                }
                val moments = if (group < times.size) times[group] else null
                if (moments == null) {
                    for (bit in COMPONENTS) {
                        if (flag and bit != 0) {
                            offset++
                        }
                    }
                    continue
                }
                val output = values[group]!!
                val keyframe = keyframes[group]++
                moments[keyframe] = time
                // Three components are written; a rotation's fourth - the quaternion's w, which
                // these raw values are not - is left at zero.
                val components = arity[group]
                for (part in COMPONENTS.indices) {
                    output[keyframe * components + part] = if (flag and COMPONENTS[part] != 0) {
                        frame.values[offset++]
                    } else {
                        absent[group]
                    }
                }
            }
        }
        val channels = ArrayList<Channel>()
        for (group in types.indices) {
            val moments = times[group] ?: continue
            channels.add(Channel(group, paths[group]!!, arity[group], moments, values[group]!!))
        }
        return channels
    }

    /** How many values [flags] ask for in total. */
    private fun components(flags: IntArray): Int {
        var components = 0
        for (flag in flags) {
            for (bit in COMPONENTS) {
                if (flag and bit != 0) {
                    components++
                }
            }
        }
        return components
    }

    /** The glTF animation path an RS transform type maps onto, or null when it has none. */
    private fun path(type: Int): String? = when (type) {
        TRANSLATE -> TRANSLATION
        ROTATE, ROTATE_ALIAS -> ROTATION
        SCALE -> SCALING
        else -> null
    }

    /**
     * The samplers' bytes: every distinct keyframe *time* array once, then the value arrays.
     *
     * Times are shared because a frame set's groups usually move on the same frames, and an input
     * accessor per group would then be the same 4 bytes per keyframe written eighty times over.
     * The order is first use, so the layout is a function of the frames and nothing else.
     */
    private fun binary(channels: List<Channel>): ByteArray {
        val inputs = inputs(channels)
        var length = 0
        for (times in inputs) {
            length += times.size * 4
        }
        for (channel in channels) {
            length += channel.values.size * 4
        }
        val binary = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        for (times in inputs) {
            for (time in times) {
                binary.putFloat(time.toFloat())
            }
        }
        for (channel in channels) {
            for (value in channel.values) {
                binary.putFloat(value.toFloat())
            }
        }
        return binary.array()
    }

    /** The distinct keyframe time arrays, in first use order. */
    private fun inputs(channels: List<Channel>): List<IntArray> {
        val inputs = ArrayList<IntArray>()
        val seen = HashMap<Times, Int>()
        for (channel in channels) {
            val key = Times(channel.times)
            if (seen.putIfAbsent(key, inputs.size) == null) {
                inputs.add(channel.times)
            }
        }
        return inputs
    }

    /** Which input accessor each channel uses, after the duplicates are folded together. */
    private fun sampled(channels: List<Channel>, inputs: List<IntArray>): IntArray {
        val seen = HashMap<Times, Int>(inputs.size)
        for (index in inputs.indices) {
            seen[Times(inputs[index])] = index
        }
        return IntArray(channels.size) { seen.getValue(Times(channels[it].times)) }
    }

    /** An `IntArray` that can be a map key, for folding identical time arrays together. */
    private class Times(val values: IntArray) {
        override fun equals(other: Any?): Boolean = other is Times && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    /** The glTF asset, compact and in a fixed key order so the same frame set is the same bytes. */
    private fun asset(
        set: AnimationFrameSet,
        base: AnimationBaseDefinition?,
        types: IntArray,
        channels: List<Channel>,
        binaryLength: Int
    ): String {
        val out = StringBuilder(1024 + set.frames.size * 64)
        out.append("{\"asset\":{\"generator\":\"").append(Gltf.GENERATOR).append("\",\"version\":\"2.0\"}")
        out.append(",\"scene\":0,\"scenes\":[{")
        if (types.isNotEmpty()) {
            out.append("\"nodes\":[")
            for (group in types.indices) {
                if (group != 0) {
                    out.append(',')
                }
                out.append(group)
            }
            out.append("],")
        }
        out.append("\"extras\":{\"rs\":")
        extras(out, set, types.size)
        out.append("}}]")
        nodes(out, base, types)
        if (channels.isEmpty()) {
            out.append('}')
            return out.toString()
        }
        val inputs = inputs(channels)
        val samplers = sampled(channels, inputs)
        animation(out, set.archive, channels, samplers, inputs.size)
        accessors(out, channels, inputs)
        views(out, channels, inputs)
        out.append(",\"buffers\":[{\"byteLength\":").append(binaryLength).append("}]")
        out.append('}')
        return out.toString()
    }

    /** One node per label group, named by its index, with the base's own facts on it. */
    private fun nodes(out: StringBuilder, base: AnimationBaseDefinition?, types: IntArray) {
        if (types.isEmpty()) {
            return
        }
        out.append(",\"nodes\":[")
        for (group in types.indices) {
            if (group != 0) {
                out.append(',')
            }
            out.append("{\"name\":\"").append(group).append('"')
            out.append(",\"extras\":{\"rs\":{\"group\":").append(group)
            out.append(",\"type\":").append(types[group])
            val labels = base?.labels?.getOrNull(group)
            if (labels != null && labels.isNotEmpty()) {
                Gltf.scalars(out, "labels", labels.size) { labels[it] }
            }
            out.append("}}}")
        }
        out.append(']')
    }

    /** The one glTF animation: a channel and a sampler per label group that moves. */
    private fun animation(
        out: StringBuilder,
        archive: Int,
        channels: List<Channel>,
        samplers: IntArray,
        inputs: Int
    ) {
        out.append(",\"animations\":[{\"name\":\"frameset_").append(archive).append('"')
        out.append(",\"channels\":[")
        for (index in channels.indices) {
            if (index != 0) {
                out.append(',')
            }
            out.append("{\"sampler\":").append(index)
                .append(",\"target\":{\"node\":").append(channels[index].group)
                .append(",\"path\":\"").append(channels[index].path).append("\"}}")
        }
        out.append("],\"samplers\":[")
        for (index in channels.indices) {
            if (index != 0) {
                out.append(',')
            }
            out.append("{\"input\":").append(samplers[index])
                .append(",\"interpolation\":\"LINEAR\",\"output\":").append(inputs + index).append('}')
        }
        out.append("]}]")
    }

    /** The input accessors first, then one output accessor per channel, in the buffer's order. */
    private fun accessors(out: StringBuilder, channels: List<Channel>, inputs: List<IntArray>) {
        out.append(",\"accessors\":[")
        var offset = 0
        for (index in inputs.indices) {
            if (index != 0) {
                out.append(',')
            }
            val times = inputs[index]
            // glTF requires min and max on an animation's input accessor; a viewer reads the
            // animation's length off them.
            out.append("{\"bufferView\":").append(index)
                .append(",\"componentType\":").append(Gltf.FLOAT)
                .append(",\"count\":").append(times.size)
                .append(",\"max\":[").append(bound(times, least = false)).append(']')
                .append(",\"min\":[").append(bound(times, least = true)).append(']')
                .append(",\"type\":\"SCALAR\"}")
            offset++
        }
        for (index in channels.indices) {
            out.append(",{\"bufferView\":").append(offset + index)
                .append(",\"componentType\":").append(Gltf.FLOAT)
                .append(",\"count\":").append(channels[index].times.size)
                .append(",\"type\":\"").append(if (channels[index].arity == 4) "VEC4" else "VEC3")
                .append("\"}")
        }
        out.append(']')
    }

    /** One buffer view per accessor, in the same order, over the single buffer. */
    private fun views(out: StringBuilder, channels: List<Channel>, inputs: List<IntArray>) {
        out.append(",\"bufferViews\":[")
        var start = 0
        for (index in inputs.indices) {
            if (index != 0) {
                out.append(',')
            }
            val length = inputs[index].size * 4
            view(out, length, start)
            start += length
        }
        for (channel in channels) {
            val length = channel.values.size * 4
            out.append(',')
            view(out, length, start)
            start += length
        }
        out.append(']')
    }

    /** The first or last keyframe time, which glTF requires an input accessor to declare. */
    private fun bound(times: IntArray, least: Boolean): Int {
        var bound = times[0]
        for (index in 1 until times.size) {
            val time = times[index]
            if (if (least) time < bound else time > bound) {
                bound = time
            }
        }
        return bound
    }

    private fun view(out: StringBuilder, length: Int, offset: Int) {
        out.append("{\"buffer\":0,\"byteLength\":").append(length).append(",\"byteOffset\":").append(offset).append('}')
    }

    /**
     * The frames themselves, on the scene as `extras.rs`.
     *
     * A frame is its file id, the header byte nothing reads, the transform count it declares, and
     * the groups it actually moves: the group index, the raw flag byte, and one run of the values
     * exactly as the file holds them. A group that is not listed had a flag byte of zero, which the
     * count is what puts back.
     */
    private fun extras(out: StringBuilder, set: AnimationFrameSet, groups: Int) {
        out.append("{\"archive\":").append(set.archive)
        out.append(",\"base\":").append(set.baseId)
        out.append(",\"groups\":").append(groups)
        out.append(",\"layout\":{\"chunks\":").append(set.chunks)
            .append(",\"header\":").append(set.header).append('}')
        out.append(",\"frames\":[")
        for (index in set.frames.indices) {
            if (index != 0) {
                out.append(',')
            }
            frame(out, set, set.frames[index])
        }
        out.append("]}")
    }

    private fun frame(out: StringBuilder, set: AnimationFrameSet, frame: AnimationFrameDefinition) {
        out.append("{\"file\":").append(frame.id and MASK)
        if (frame.baseId != set.baseId) {
            out.append(",\"base\":").append(frame.baseId)
        }
        out.append(",\"header\":").append(frame.header)
        out.append(",\"count\":").append(frame.flags.size)
        var moved = 0
        for (flag in frame.flags) {
            if (flag != 0) {
                moved++
            }
        }
        out.append(",\"transforms\":[")
        var written = 0
        for (group in frame.flags.indices) {
            if (frame.flags[group] == 0) {
                continue
            }
            if (written++ != 0) {
                out.append(',')
            }
            out.append(group)
        }
        out.append("],\"flags\":[")
        written = 0
        for (flag in frame.flags) {
            if (flag == 0) {
                continue
            }
            if (written++ != 0) {
                out.append(',')
            }
            out.append(flag)
        }
        out.append(']')
        check(written == moved) { "Frame ${frame.id} wrote $written of $moved flag bytes." }
        Gltf.scalars(out, "values", frame.values.size) { frame.values[it] }
        frame.wideValues?.let { wide -> Gltf.scalars(out, "wide", wide.size) { wide[it] } }
        out.append('}')
    }

    /** The glTF animation paths, spelled once. */
    private const val TRANSLATION = "translation"
    private const val ROTATION = "rotation"
    private const val SCALING = "scale"

    /** A frame's header - a byte, the base id and the transform count - which is chunk 0. */
    const val HEADER = 4

    /** How many chunks a multi-frame group of index 0 is stored in. */
    const val CHUNKS = 3

    private const val MASK = (1 shl AnimationFrameDecoder.BITS) - 1
}

/**
 * One index 0 archive: the frames of a frame set, the skeleton they name, and how the group is laid
 * out.
 *
 * [chunks] and [header] are the group layout facts [AnimationFrameCodec] reproduces - index 0's
 * multi-frame groups are stored in three chunks whose lengths are the frame format's own three
 * sections - and they are on the file rather than in `index.json` so that nothing about the layout
 * can go stale against the frames.
 */
internal class AnimationFrameSet(
    val archive: Int,
    val baseId: Int,
    val frames: List<AnimationFrameDefinition>,
    /** 3 for a multi-frame group, 1 for a single frame archive, which carries no chunk table. */
    val chunks: Int,
    /** The length of chunk 0, which is a frame's four byte header. */
    val header: Int
) {

    /** The file ids the reference table has to list, in the order the group holds them. */
    fun fileIds(): IntArray = IntArray(frames.size) { frames[it].id and MASK }

    private companion object {
        private const val MASK = (1 shl AnimationFrameDecoder.BITS) - 1
    }
}
