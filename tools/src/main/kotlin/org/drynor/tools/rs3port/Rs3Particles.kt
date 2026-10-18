package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import java.nio.file.Files
import java.nio.file.Path

class ParticleIds(val material: (Int) -> Int, val producer: (Int) -> Int, val effector: (Int) -> Int)

object Rs3Particles {

    private const val PRODUCERS = "producer"

    private const val EFFECTORS = "particle"

    private const val REPEATED = 24

    private const val SWALLOWED_26 = 26

    private const val SWALLOWED_30 = 30

    private const val HANDLING_PAYLOAD = 2

    private val RS3_ONLY_PRODUCER = setOf("unknown29", "unknown35", "unknown36")

    private val PRODUCER_KEYS = setOf(
        "id", "opcodeOrder", "shadowedPayloads",
        "unknown1a", "unknown1b", "unknown1c", "unknown1d", "unknown2", "unknown3a", "unknown3b", "unknown4a", "unknown4b",
        "unknown5", "unknown6a", "unknown6b", "unknown7a", "unknown7b", "unknown8a", "unknown8b", "unknown9", "unknown10",
        "unknown12", "unknown13", "unknown14", "unknown15", "unknown16a", "unknown16b", "unknown16c", "unknown16d",
        "unknown17", "unknown18", "unknown19", "unknown20", "unknown21", "unknown22", "unknown23", "unknown24", "unknown25",
        "unknown26", "unknown27", "unknown28", "unknown30", "unknown31a", "unknown31b", "unknown32", "unknown33", "unknown34"
    ) + RS3_ONLY_PRODUCER

    private val EFFECTOR_KEYS = setOf(
        "id", "opcodeOrder", "unknown1", "unknown2", "unknown4Type", "unknown4Value", "unknown6", "unknown8", "unknown9",
        "unknown10", "vector"
    )

    fun producerExists(tree: Path, id: Int): Boolean = Files.exists(path(tree, PRODUCERS, id))

    fun effectorExists(tree: Path, id: Int): Boolean = Files.exists(path(tree, EFFECTORS, id))

    fun producerJson(tree: Path, id: Int): JsonObject = SourceJsonText.read(path(tree, PRODUCERS, id))

    fun effectorJson(tree: Path, id: Int): JsonObject = SourceJsonText.read(path(tree, EFFECTORS, id))

    fun producer(json: JsonObject, id: Int, ids: ParticleIds): ParticleProducerDefinition {
        refuse(json, PRODUCER_KEYS, "producer")
        val out = ParticleProducerDefinition(id = id)
        json.int("unknown1a")?.let { out.minAngleHorizontal = it }
        json.int("unknown1b")?.let { out.maxAngleHorizontal = it }
        json.int("unknown1c")?.let { out.minAngleVertical = it }
        json.int("unknown1d")?.let { out.maxAngleVertical = it }
        json.int("unknown2")?.let { out.unused2 = it }
        json.int("unknown3a")?.let { out.minSpeed = it }
        json.int("unknown3b")?.let { out.maxSpeed = it }
        json.int("unknown4a")?.let { out.speedUpdateType = it }
        json.int("unknown4b")?.let { out.speedFallOffStep = it }
        json.int("unknown5")?.let { out.size = it }
        json.int("unknown6a")?.let { out.minStartColour = it }
        json.int("unknown6b")?.let { out.maxStartColour = it }
        json.int("unknown7a")?.let { out.minLifetime = it }
        json.int("unknown7b")?.let { out.maxLifetime = it }
        json.int("unknown8a")?.let { out.minParticleRate = it }
        json.int("unknown8b")?.let { out.maxParticleRate = it }
        json.ints("unknown9")?.let { list -> out.secondaryParticles = IntArray(list.size) { ids.producer(list[it]) } }
        json.ints("unknown10")?.let { list -> out.particles = IntArray(list.size) { ids.effector(list[it]) } }
        json.int("unknown12")?.let { out.lowestDisplayPlane = it.toByte().toInt() }
        json.int("unknown13")?.let { out.highestDisplayPlane = it.toByte().toInt() }
        json.int("unknown14")?.let { out.updatesPerCycle = it }
        json.int("unknown15")?.let { out.materialId = ids.material(it) }
        json.int("unknown16a")?.let { out.activeFirst = it }
        json.int("unknown16b")?.let { out.emissionEndTime = it }
        json.int("unknown16c")?.let { out.lifetime = it }
        json.int("unknown16d")?.let { out.periodic = it }
        json.int("unknown17")?.let { out.nonTexturedProducerId = ids.producer(it) }
        json.int("unknown18")?.let { out.faceColourRgb = it }
        json.int("unknown19")?.let { out.minimumSetting = it }
        json.int("unknown20")?.let { out.colourFading = it }
        json.int("unknown21")?.let { out.alphaFading = it }
        json.int("unknown22")?.let { out.endSpeed = it }
        json.int("unknown23")?.let { out.speedChange = it }
        json.int("unknown24")?.let { swallowed ->
            out.uniformColourVariance = false
            when (swallowed) {
                REPEATED -> {}
                SWALLOWED_26 -> out.unusedOpcode26 = false
                SWALLOWED_30 -> out.textured = true
                else -> throw IllegalArgumentException("RS3 producer $id reads opcode 24 with $swallowed; 727 has no such flag.")
            }
        }
        json.ints("unknown25")?.let { out.effectiveVertexUids = it }
        if (json.flag("unknown26")) {
            out.unusedOpcode26 = false
        }
        json.int("unknown27")?.let { out.endSize = it }
        json.int("unknown28")?.let { out.sizeChange = it }
        if (json.flag("unknown30")) {
            out.textured = true
        }
        json.int("unknown31a")?.let { out.minSize = it }
        json.int("unknown31b")?.let { out.maxSize = it }
        if (json.flag("unknown32")) {
            out.adjustsLightIntensity = false
        }
        if (json.flag("unknown33")) {
            out.killOverlapping = true
        }
        if (json.flag("unknown34")) {
            out.killAboveSurface = false
        }
        return out
    }

    fun effector(json: JsonObject, id: Int): ParticleDefinition {
        refuse(json, EFFECTOR_KEYS, "effector")
        val out = ParticleDefinition(id = id)
        json.int("unknown1")?.let { out.rotation = it }
        val handling = json.flag("unknown6")
        val payload = json.flag("unknown2")
        if (handling || payload) {
            check(handling && payload) {
                "RS3 effector $id sets opcode ${if (handling) 6 else 2} alone; only 6 followed by 2 is a 727 record."
            }
            out.particleHandlingType = HANDLING_PAYLOAD
        }
        json["vector"]?.jsonObject?.let { vector ->
            out.offsetX = vector.int("x")!!
            out.offsetY = vector.int("y")!!
            out.offsetZ = vector.int("z")!!
        }
        out.currentOffset = json.flag("unknown8")
        out.verticeCalculationType = json.flag("unknown9")
        out.unusedOpcode10 = json.flag("unknown10")
        return out
    }

    private fun refuse(json: JsonObject, known: Set<String>, kind: String) {
        val unknown = json.keys.filter { it !in known }
        require(unknown.isEmpty()) { "RS3 $kind ${json["id"]} has fields with no 727 form: $unknown" }
    }

    private fun path(tree: Path, directory: String, id: Int): Path =
        tree.resolve("config_particle").resolve(directory).resolve("$id.json")

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.ints(key: String): IntArray? {
        val values = this[key]?.jsonArray ?: return null
        return IntArray(values.size) { values[it].jsonPrimitive.int }
    }
}
