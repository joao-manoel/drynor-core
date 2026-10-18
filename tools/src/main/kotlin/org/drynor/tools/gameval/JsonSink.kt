package org.drynor.tools.gameval

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal streaming JSON writer for the gameval dump corpus.
 *
 * The corpus is consumed by tooling that diffs 727 records against RS3 dev names, so it needs
 * every value a decoder produced: arrays are always emitted inline and in full, never
 * summarised or digested. Records are appended one per line through a [BufferedWriter], so a
 * dump never holds more than one definition's text at a time.
 */
object Json {

    /** Package prefixes whose objects are walked field-by-field rather than stringified. */
    private val REFLECTED_PACKAGES = arrayOf("world.gregs.voidps", "org.drynor")

    /** Depth guard: no cache definition nests anywhere near this deep. */
    private const val MAX_DEPTH = 16

    private val HEX = "0123456789abcdef".toCharArray()

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * Every instance field of [type] and its superclasses, superclass-first and in declaration
     * order, which for a Kotlin data class is its primary-constructor order.
     */
    fun fields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        val chain = ArrayList<Class<*>>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            chain.add(current)
            current = current.superclass
        }
        chain.reverse()
        val fields = ArrayList<Field>()
        for (cls in chain) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) {
                    continue
                }
                if (field.name.contains('$')) {
                    continue
                }
                try {
                    field.isAccessible = true
                } catch (_: RuntimeException) {
                    continue
                }
                fields.add(field)
            }
        }
        fields
    }

    fun string(builder: StringBuilder, value: String) {
        builder.append('"')
        for (char in value) {
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char.code < 0x20 || char.code == 0x7f -> {
                    builder.append("\\u")
                    val hex = Integer.toHexString(char.code)
                    for (pad in hex.length until 4) {
                        builder.append('0')
                    }
                    builder.append(hex)
                }
                else -> builder.append(char)
            }
        }
        builder.append('"')
    }

    /**
     * Append [value] as JSON.
     *
     * Byte arrays are written as signed byte values, matching what the decoder holds; char
     * arrays are written as one-character strings so a NUL survives the round trip.
     */
    fun value(builder: StringBuilder, value: Any?, depth: Int = 0) {
        when (value) {
            null -> builder.append("null")
            is String -> string(builder, value)
            is Boolean -> builder.append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> builder.append(value.toString())
            is Float -> if (value.isFinite()) builder.append(value.toString()) else builder.append("null")
            is Double -> if (value.isFinite()) builder.append(value.toString()) else builder.append("null")
            is Char -> string(builder, value.toString())
            is Enum<*> -> string(builder, value.name)
            is ByteArray -> array(builder, value.size) { index -> builder.append(value[index].toInt()) }
            is ShortArray -> array(builder, value.size) { index -> builder.append(value[index].toInt()) }
            is IntArray -> array(builder, value.size) { index -> builder.append(value[index]) }
            is LongArray -> array(builder, value.size) { index -> builder.append(value[index]) }
            is BooleanArray -> array(builder, value.size) { index -> builder.append(if (value[index]) "true" else "false") }
            is CharArray -> array(builder, value.size) { index -> string(builder, value[index].toString()) }
            is FloatArray -> array(builder, value.size) { index -> this.value(builder, value[index], depth + 1) }
            is DoubleArray -> array(builder, value.size) { index -> this.value(builder, value[index], depth + 1) }
            is Array<*> -> array(builder, value.size) { index -> this.value(builder, value[index], depth + 1) }
            is Collection<*> -> {
                val items = value.toList()
                array(builder, items.size) { index -> this.value(builder, items[index], depth + 1) }
            }
            is Pair<*, *> -> {
                builder.append('[')
                this.value(builder, value.first, depth + 1)
                builder.append(',')
                this.value(builder, value.second, depth + 1)
                builder.append(']')
            }
            is Map<*, *> -> {
                builder.append('{')
                var first = true
                for ((key, entry) in value) {
                    if (!first) {
                        builder.append(',')
                    }
                    first = false
                    string(builder, key.toString())
                    builder.append(':')
                    this.value(builder, entry, depth + 1)
                }
                builder.append('}')
            }
            else -> {
                val name = value.javaClass.name
                if (depth < MAX_DEPTH && REFLECTED_PACKAGES.any { name.startsWith(it) }) {
                    obj(builder, value, depth)
                } else {
                    string(builder, value.toString())
                }
            }
        }
    }

    private inline fun array(builder: StringBuilder, size: Int, element: (Int) -> Unit) {
        builder.append('[')
        for (index in 0 until size) {
            if (index > 0) {
                builder.append(',')
            }
            element(index)
        }
        builder.append(']')
    }

    /** Append every readable field of [value] as a JSON object. */
    fun obj(builder: StringBuilder, value: Any, depth: Int = 0) {
        builder.append('{')
        var first = true
        for (field in fields(value.javaClass)) {
            val read = readField(field, value) ?: continue
            if (!first) {
                builder.append(',')
            }
            first = false
            string(builder, field.name)
            builder.append(':')
            this.value(builder, read.value, depth + 1)
        }
        builder.append('}')
    }

    /**
     * Read [field] from [value], distinguishing a genuine null from a field that cannot be read
     * at all (an uninitialised `lateinit` throws rather than reading as null).
     */
    fun readField(field: Field, value: Any): FieldRead? = try {
        FieldRead(field.get(value))
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** A field that read cleanly; [value] may still be null. A failed read is `null` instead. */
    class FieldRead(val value: Any?)

    fun sha1(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val int = byte.toInt() and 0xff
            hex.append(HEX[int ushr 4]).append(HEX[int and 0xf])
        }
        return hex.toString()
    }
}

/**
 * One JSONL output file: a single JSON object per line, written in ascending id order.
 */
class JsonLines(directory: File, val fileName: String) : Closeable {

    val file = File(directory, fileName)
    private val writer: BufferedWriter = file.bufferedWriter(Charsets.UTF_8)
    private val builder = StringBuilder(4096)

    /** Number of records written so far. */
    var count: Int = 0
        private set

    /**
     * Write one record: `id` first, then [extras], then every field of [definition] except its
     * own `id`, which is already written, so a record never carries a duplicate key.
     */
    fun write(id: Int, definition: Any?, extras: List<Pair<String, Any?>> = emptyList()) {
        builder.setLength(0)
        builder.append("{\"id\":").append(id)
        for ((key, value) in extras) {
            builder.append(',')
            Json.string(builder, key)
            builder.append(':')
            Json.value(builder, value, 1)
        }
        if (definition != null) {
            for (field in Json.fields(definition.javaClass)) {
                if (field.name == "id") {
                    continue
                }
                val read = Json.readField(field, definition) ?: continue
                builder.append(',')
                Json.string(builder, field.name)
                builder.append(':')
                Json.value(builder, read.value, 1)
            }
        }
        builder.append('}')
        writer.append(builder)
        writer.newLine()
        count++
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}
