package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.nio.file.Path
import kotlin.math.roundToInt

object Rs3Configs {

    private const val SCALE = 128

    private const val NONE = -1

    private val SEQ_KEYS = setOf(
        "id", "gameval", "durations", "frames", "loopOffset", "priority", "leftHandItem", "rightHandItem", "maxLoops",
        "animatingPrecedence", "walkingPrecedence", "replayMode", "stringId", "unusedRecords", "opcodeOrder"
    )

    private val RS3_ONLY_SEQ_RECORDS = setOf("119", "120")

    private val SPOTANIM_KEYS = setOf(
        "id", "modelId", "animationId", "scaleX", "scaleY", "rotation", "ambience", "unknown9a", "unknown9b", "unknown10",
        "stringId", "unknown8", "unknown44", "unknown45", "opcodeOrder"
    )

    private val SPOTANIM_DEFAULTS = mapOf("rotation" to 0, "ambience" to 0, "unknown8" to 0, "unknown9a" to 0, "unknown9b" to -1, "unknown44" to 0, "unknown45" to 0)

    fun seqJson(tree: Path, id: Int): JsonObject = SourceJsonText.read(tree.resolve("config_seq").resolve("$id.json"))

    fun spotanimJson(tree: Path, id: Int): JsonObject = SourceJsonText.read(tree.resolve("config_spotanim").resolve("$id.json"))

    fun seq(json: JsonObject, id: Int, archives: (Int) -> Int): AnimationDefinition {
        val unknown = json.keys.filter { it !in SEQ_KEYS }
        require(unknown.isEmpty()) { "RS3 seq ${json["id"]} has fields with no 727 form: $unknown" }
        for (field in listOf("leftHandItem", "rightHandItem")) {
            require(json.int(field) == NONE) { "RS3 seq ${json["id"]} holds an obj in $field; RS3 obj ids are not 727 ids." }
        }
        val frames = json.ints("frames")
        val out = AnimationDefinition(id)
        out.durations = json.ints("durations")
        out.frames = IntArray(frames.size) {
            val archive = frames[it] ushr AnimationFrameDecoder.BITS
            (archives(archive) shl AnimationFrameDecoder.BITS) or (frames[it] and FILE_MASK)
        }
        out.loopOffset = json.int("loopOffset")
        out.priority = json.int("priority")
        out.maxLoops = json.int("maxLoops")
        out.animatingPrecedence = json.int("animatingPrecedence")
        out.walkingPrecedence = json.int("walkingPrecedence")
        out.replayMode = json.int("replayMode")
        for ((opcode, records) in json["unusedRecords"]?.jsonObject.orEmpty()) {
            when (opcode) {
                "13" -> out.sounds = sounds(records.jsonArray.single().jsonArray.map { it.jsonPrimitive.int }, id)
                "14" -> out.aBoolean691 = true
                "15" -> out.tweened = true
                "16" -> out.unusedFlag16 = true
                "18" -> out.useSounds = true
                in RS3_ONLY_SEQ_RECORDS -> {}
                else -> throw IllegalArgumentException("RS3 seq ${json["id"]} carries opcode $opcode, which 727 cannot read.")
            }
        }
        return out
    }

    fun spotanim(json: JsonObject, id: Int, model: Int, seq: Int): SpotAnimDefinition {
        val unknown = json.keys.filter { it !in SPOTANIM_KEYS }
        require(unknown.isEmpty()) { "RS3 spotanim ${json["id"]} has fields with no 727 form: $unknown" }
        for ((field, default) in SPOTANIM_DEFAULTS) {
            val value = json[field]?.jsonPrimitive?.float?.roundToInt() ?: default
            require(value == default) { "RS3 spotanim ${json["id"]} sets $field to $value, which this port does not map yet." }
        }
        val out = SpotAnimDefinition(id)
        out.modelId = model
        out.animationId = seq
        out.sizeXY = (json["scaleX"]!!.jsonPrimitive.float * SCALE).roundToInt()
        out.sizeZ = (json["scaleY"]!!.jsonPrimitive.float * SCALE).roundToInt()
        out.aBoolean2402 = json["unknown10"]?.jsonPrimitive?.boolean ?: false
        return out
    }

    private fun sounds(bytes: List<Int>, id: Int): Array<IntArray?> {
        var offset = 0
        fun byte(): Int = bytes[offset++]
        val length = (byte() shl 8) or byte()
        val sounds = arrayOfNulls<IntArray>(length)
        for (frame in 0 until length) {
            val size = byte()
            if (size == 0) {
                continue
            }
            val sound = IntArray(size)
            sound[0] = (byte() shl 16) or (byte() shl 8) or byte()
            for (part in 1 until size) {
                sound[part] = ((byte() shl 8) or byte()).toShort().toInt()
            }
            sounds[frame] = sound
        }
        check(offset == bytes.size) { "RS3 seq $id sound record has ${bytes.size - offset} bytes past its $length frames." }
        return sounds
    }

    private const val FILE_MASK = (1 shl AnimationFrameDecoder.BITS) - 1

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.ints(key: String): IntArray {
        val values = this[key]!!.jsonArray
        return IntArray(values.size) { values[it].jsonPrimitive.int }
    }
}
