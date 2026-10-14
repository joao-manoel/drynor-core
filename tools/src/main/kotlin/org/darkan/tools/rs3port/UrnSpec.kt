package org.darkan.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class UrnSet(private val element: JsonObject) {

    val skill: String = element["skill"]!!.jsonPrimitive.content
    val tier: Int = element["tier"]!!.jsonPrimitive.int
    val rs3Obj: Map<String, Int> = ints("rs3Obj")
    val obj: Map<String, Int> = ints("obj")
    val gameval: Map<String, String> = strings("gameval")
    val name: Map<String, String> = strings("name")
    val models: Map<Int, Int> = ints("models").mapKeys { it.key.toInt() }
    val modelNames: Map<Int, String> = strings("modelNames").mapKeys { it.key.toInt() }
    val seq: Map<String, Int> = ints("seq")
    val seqName: Map<String, String> = strings("seqName")
    val cloneSeq: Map<String, Int> = ints("cloneSeq")
    val rs3Seq: Map<String, Int> = ints("rs3Seq")
    val rs3Struct: Int = element["rs3Struct"]!!.jsonPrimitive.int
    val struct: Int = element["struct"]!!.jsonPrimitive.int
    val enumRow: Int = element["enumRow"]!!.jsonPrimitive.int
    val structName: String = element["structName"]!!.jsonPrimitive.content
    val structLabel: String = element["structLabel"]!!.jsonPrimitive.content
    val level: Int = element["level"]!!.jsonPrimitive.int
    val activator: Int = element["activator"]!!.jsonPrimitive.int
    val noruneOption: String = element["noruneOption"]!!.jsonPrimitive.content
    val obj2d: Map<String, Int> = ints("obj2d")
    val cost: Map<String, Int> = ints("cost")
    val members: Boolean = element["members"]!!.jsonPrimitive.boolean
    val geCategory: Map<String, Int> = ints("geCategory")
    val capacity: Double = element["capacity"]!!.jsonPrimitive.double

    private fun ints(key: String): Map<String, Int> =
        element[key]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.int }

    private fun strings(key: String): Map<String, String> =
        element[key]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
}

class ExistingUrnSet(element: JsonObject) {
    val obj: Map<String, Int> = element["obj"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
}

class UrnSpec(root: JsonObject) {

    val sets: List<UrnSet> = (root["sets"] as JsonArray).map { UrnSet(it.jsonObject) }

    val existing: List<ExistingUrnSet> = (root["existing"] as JsonArray).map { ExistingUrnSet(it.jsonObject) }

    val ceilings: Map<String, Int> = root["ceilings"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }

    val next: Map<String, Int> = root["next"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }

    companion object {

        val DEFAULT: Path = Path.of("docs", "reference", "rs3-urns.json")

        fun read(path: Path): UrnSpec = UrnSpec(Json.parseToJsonElement(Files.readString(path)).jsonObject)
    }
}
