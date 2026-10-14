package org.darkan.tools.rs3port

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Modifier

object DefinitionFields {

    fun definition(definition: Any, skip: Set<String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (field in definition.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.name in skip) {
                continue
            }
            field.isAccessible = true
            out[field.name] = text(field.get(definition))
        }
        return out
    }

    fun json(json: JsonObject, names: Collection<String>): Map<String, String?> = names.associateWith { json[it]?.let(::text) }

    private fun text(value: Any?): String? = when (value) {
        null, is JsonNull -> null
        is IntArray -> value.joinToString(",", "[", "]")
        is JsonArray -> value.joinToString(",", "[", "]") { it.jsonPrimitive.content }
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}

class FieldTally {

    private val matched = LinkedHashMap<String, Int>()

    private val total = LinkedHashMap<String, Int>()

    private val examples = LinkedHashMap<String, MutableList<String>>()

    fun add(id: Int, converted: Map<String, String?>, shipped: Map<String, String?>): List<String> {
        val differing = ArrayList<String>()
        for ((field, value) in converted) {
            total.merge(field, 1, Int::plus)
            if (value == shipped[field]) {
                matched.merge(field, 1, Int::plus)
            } else {
                differing.add(field)
                val list = examples.getOrPut(field) { ArrayList() }
                if (list.size < EXAMPLES) {
                    list.add("$id: ${shipped[field]} expected, $value converted")
                }
            }
        }
        return differing
    }

    fun report() {
        for ((field, count) in total) {
            val hits = matched[field] ?: 0
            println("  %-26s %5d / %-5d %s".format(field, hits, count, percent(hits, count)))
            for (example in examples[field].orEmpty()) {
                println("      $example")
            }
        }
    }

    private companion object {
        private const val EXAMPLES = 4
    }
}

fun percent(part: Int, whole: Int): String = if (whole == 0) "-" else "%.2f%%".format(part * 100.0 / whole)
