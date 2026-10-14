package org.darkan.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class IdPair(val rs3: Int, val id: Int)

class NamedId(val id: Int, val gameval: String)

class MaterialImport(
    val rs3: Int,
    val gameval: String,
    val rs3Texture: Int,
    val graphic: Int,
    val opacityGraphic: Int?,
    val texture: Int,
    val material: Int
)

class SpotanimImport(
    val rs3Spotanim: Int,
    val rs3Model: Int,
    val rs3Seq: Int,
    val rs3Anim: Int,
    val anim: Int,
    val seq: NamedId,
    val model: NamedId,
    val spotanim: NamedId
)

class SpotanimSpec(root: JsonObject) {

    val ceilings: Map<String, Int> = root["ceilings"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }

    val materials: List<MaterialImport> = root["materials"]?.jsonArray.orEmpty().map { element ->
        val material = element.jsonObject
        MaterialImport(
            rs3 = material.int("rs3"),
            gameval = material.string("gameval"),
            rs3Texture = material.int("rs3Texture"),
            graphic = material.int("graphic"),
            opacityGraphic = material["opacityGraphic"]?.jsonPrimitive?.int,
            texture = material.int("texture"),
            material = material.int("material")
        )
    }

    val reusedMaterials: Map<Int, Int> = root["reusedMaterials"]?.jsonObject.orEmpty()
        .map { it.key.toInt() to it.value.jsonObject.int("material") }.toMap()

    val reusedBillboards: Set<Int> = root["reusedBillboards"]?.jsonObject.orEmpty()
        .values.map { it.jsonObject.int("billboard") }.toSet()

    val bases: List<IdPair> = pairs(root["bases"] ?: root["base"], "base")

    val producers: List<IdPair> = pairs(root["producers"], "producer")

    val effectors: List<IdPair> = pairs(root["effectors"], "effector")

    val spotanims: List<SpotanimImport> = (root["spotanims"] ?: root["tiers"])!!.jsonArray.map { element ->
        val entry = element.jsonObject
        val rs3 = entry["rs3"]!!.jsonObject
        SpotanimImport(
            rs3Spotanim = rs3.int("spotanim"),
            rs3Model = rs3.int("model"),
            rs3Seq = rs3.int("seq"),
            rs3Anim = rs3.int("anim"),
            anim = entry.int("anim"),
            seq = named(entry["seq"]!!.jsonObject),
            model = named(entry["model"]!!.jsonObject),
            spotanim = named(entry["spotanim"]!!.jsonObject)
        )
    }

    fun allocations(): Map<String, List<Int>> = linkedMapOf(
        "graphic" to materials.map { it.graphic } + materials.mapNotNull { it.opacityGraphic },
        "texture" to materials.map { it.texture },
        "material" to materials.map { it.material },
        "base" to bases.map { it.id },
        "anim" to spotanims.map { it.anim },
        "seq" to spotanims.map { it.seq.id },
        "producer" to producers.map { it.id },
        "effector" to effectors.map { it.id },
        "model" to spotanims.map { it.model.id },
        "spotanim" to spotanims.map { it.spotanim.id }
    )

    private fun pairs(element: JsonElement?, key: String): List<IdPair> {
        val entries = when (element) {
            null -> emptyList()
            is JsonObject -> listOf(element)
            else -> element.jsonArray.map { it.jsonObject }
        }
        return entries.map { IdPair(it.int("rs3"), it.int(key)) }
    }

    private fun named(element: JsonObject): NamedId = NamedId(element.int("id"), element.string("gameval"))

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    companion object {

        fun read(path: Path): SpotanimSpec = SpotanimSpec(Json.parseToJsonElement(Files.readString(path)).jsonObject)
    }
}
