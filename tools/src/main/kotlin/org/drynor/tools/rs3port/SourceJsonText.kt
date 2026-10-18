package org.drynor.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

object SourceJsonText {

    private const val WIDTH = 100

    private const val INDENT = "  "

    private val parser = Json { ignoreUnknownKeys = true }

    fun read(path: Path): JsonObject = parser.parseToJsonElement(Files.readString(path)).jsonObject

    fun write(path: Path, root: JsonObject) {
        val out = StringBuilder()
        expand(out, root, 0, 0)
        out.append('\n')
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
    }

    fun render(root: JsonObject): String {
        val out = StringBuilder()
        expand(out, root, 0, 0)
        out.append('\n')
        return out.toString()
    }

    private fun value(out: StringBuilder, element: JsonElement, depth: Int, used: Int) {
        val flat = inline(element)
        if (depth * INDENT.length + used + flat.length <= WIDTH || element is JsonPrimitive || element is JsonNull) {
            out.append(flat)
            return
        }
        expand(out, element, depth, used)
    }

    private fun expand(out: StringBuilder, element: JsonElement, depth: Int, used: Int) {
        when (element) {
            is JsonObject -> {
                if (element.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                var first = true
                for ((key, child) in element) {
                    if (!first) {
                        out.append(",\n")
                    }
                    first = false
                    val label = "${quote(key)}: "
                    out.append(INDENT.repeat(depth + 1)).append(label)
                    value(out, child, depth + 1, label.length)
                }
                out.append('\n').append(INDENT.repeat(depth)).append('}')
            }
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                var first = true
                for (child in element) {
                    if (!first) {
                        out.append(",\n")
                    }
                    first = false
                    out.append(INDENT.repeat(depth + 1))
                    value(out, child, depth + 1, 0)
                }
                out.append('\n').append(INDENT.repeat(depth)).append(']')
            }
            else -> out.append(inline(element))
        }
    }

    private fun inline(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.joinToString(", ", "{", "}") { "${quote(it.key)}: ${inline(it.value)}" }
        is JsonArray -> element.joinToString(", ", "[", "]") { inline(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) quote(element.content) else element.content
    }

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
