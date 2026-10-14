package org.darkan.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

class Rs3Frame(val file: Int, val base: Int, val version: Int, val masks: IntArray, val values: IntArray)

class Rs3Base(val id: Int, val version: Int, val skeletal: Boolean, val types: IntArray, val shadowed: IntArray, val submeshes: IntArray, val labels: List<IntArray>)

object Rs3Anims {

    const val ROTATE = 2

    private const val PAIRED = 7

    private const val FRAME_VERSION = 2

    private const val BASE_VERSION = 4

    private const val HEADER = 1

    private const val TURN = 4096.0

    val AXES = intArrayOf(0x1, 0x2, 0x4)

    fun exists(tree: Path, archive: Int): Boolean = Files.exists(framePath(tree, archive))

    fun frames(tree: Path, archive: Int): List<Rs3Frame> =
        SourceJsonText.read(framePath(tree, archive))["frames"]!!.jsonArray.map { element ->
            val frame = element.jsonObject
            Rs3Frame(
                file = frame.int("time"),
                base = frame.int("base"),
                version = frame.int("version"),
                masks = frame.ints("masks"),
                values = frame.ints("values")
            )
        }

    fun baseExists(tree: Path, id: Int): Boolean = Files.exists(basePath(tree, id))

    fun base(tree: Path, id: Int): Rs3Base {
        val root = SourceJsonText.read(basePath(tree, id))
        val transforms = root["transforms"]!!.jsonArray.map { it.jsonObject }
        val skeletal = (root["bones"]?.jsonArray?.isNotEmpty() ?: false) ||
            listOf("matrixSetCount", "remapCount", "extraCount").any { (root[it]?.jsonPrimitive?.int ?: 0) != 0 }
        return Rs3Base(
            id = id,
            version = root.int("version"),
            skeletal = skeletal,
            types = IntArray(transforms.size) { transforms[it].int("type") },
            shadowed = IntArray(transforms.size) { transforms[it].int("unusedA") },
            submeshes = IntArray(transforms.size) { transforms[it].int("unusedB") },
            labels = transforms.map { it.ints("labels") }
        )
    }

    fun convert(base: Rs3Base, id: Int): AnimationBaseDefinition {
        check(base.version == BASE_VERSION && !base.skeletal) {
            "RS3 base ${base.id} is version ${base.version}${if (base.skeletal) " with a skeleton" else ""}; " +
                "only version $BASE_VERSION label bases have a 727 form."
        }
        return AnimationBaseDefinition(
            id = id,
            types = base.types.copyOf(),
            shadowed = base.shadowed.copyOf(),
            submeshes = base.submeshes.copyOf(),
            labels = Array(base.labels.size) { base.labels[it].copyOf() }
        )
    }

    fun convert(frame: Rs3Frame, types: IntArray, archive: Int, base: Int): AnimationFrameDefinition {
        check(frame.version == FRAME_VERSION) {
            "RS3 frame $archive/${frame.file} is version ${frame.version}; only version $FRAME_VERSION is understood."
        }
        val values = ArrayList<Int>(frame.values.size)
        var offset = 0
        for ((transform, mask) in frame.masks.withIndex()) {
            val type = types.getOrElse(transform) { -1 }
            for ((axis, bit) in AXES.withIndex()) {
                if (mask and bit == 0) {
                    continue
                }
                when (type) {
                    ROTATE -> {
                        values.add(angle(frame.values[offset], frame.values[offset + 1], axis))
                        offset += 2
                    }
                    PAIRED -> {
                        values.add(frame.values[offset])
                        offset += 2
                    }
                    else -> values.add(frame.values[offset++])
                }
            }
        }
        check(offset == frame.values.size) {
            "RS3 frame $archive/${frame.file} holds ${frame.values.size} values; its masks ask for $offset."
        }
        return AnimationFrameDefinition(
            id = (archive shl AnimationFrameDecoder.BITS) or frame.file,
            baseId = base,
            header = HEADER,
            flags = frame.masks.copyOf(),
            values = values.toIntArray()
        )
    }

    fun angle(sin: Int, cos: Int, axis: Int): Int {
        val turn = atan2(sin.toDouble(), cos.toDouble()) * TURN / (2 * PI)
        return (if (axis == 0) -turn else turn).roundToInt()
    }

    fun components(flags: IntArray, values: IntArray): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        var offset = 0
        for ((transform, flag) in flags.withIndex()) {
            for ((axis, bit) in AXES.withIndex()) {
                if (flag and bit != 0) {
                    out[transform * 3 + axis] = values[offset++]
                }
            }
        }
        return out
    }

    private fun framePath(tree: Path, archive: Int): Path = tree.resolve("anims_rt7").resolve("$archive.json")

    private fun basePath(tree: Path, id: Int): Path = tree.resolve("bases").resolve("$id.json")

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.ints(key: String): IntArray {
        val values = this[key]?.jsonArray ?: return IntArray(0)
        return IntArray(values.size) { values[it].jsonPrimitive.int }
    }
}
