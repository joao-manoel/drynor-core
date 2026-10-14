package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.DefinitionRecord
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import world.gregs.voidps.cache.definition.data.ShaderProgramDefine
import world.gregs.voidps.cache.definition.data.ShaderProgramMapping
import world.gregs.voidps.cache.definition.data.ShaderProgramVariable
import world.gregs.voidps.cache.definition.data.SynthEnvelope
import world.gregs.voidps.cache.definition.data.SynthFilter
import world.gregs.voidps.cache.definition.data.SynthFilterPair
import world.gregs.voidps.cache.definition.data.SynthInstrument
import world.gregs.voidps.cache.definition.data.SynthShape
import world.gregs.voidps.cache.definition.data.TextureOp
import world.gregs.voidps.cache.definition.data.TextureOpcode
import world.gregs.voidps.cache.definition.data.TextureShape
import world.gregs.voidps.cache.source.SourceJson
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap
import java.lang.Long as JavaLong
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Type as ReflectType

/**
 * One definition data class as an editable JSON file.
 *
 * The mapping is derived from the class itself rather than written out by hand, so a field added to a
 * decoder appears in the tree the moment it exists and no type can quietly lose one. The rules, which are
 * what `docs/cache-source.md` promises a file looks like:
 *
 * - **Keys are the fields, in declaration order.** The class file lists its fields in the order they were
 *   written, constructor properties first and the body's afterwards, so `id` leads and the layout fidelity
 *   fields ([world.gregs.voidps.cache.definition.OpcodeOrdered]) trail.
 * - **Defaults are kept, nulls are omitted.** Every non-null field is written whatever its value, so a
 *   reader never needs the decoder's defaults to understand a file; a null field is left out entirely and
 *   read back as the fresh definition's default, which for every nullable field here is null.
 * - **Ids are numbers, arrays are inline while they fit.** A line is broken only when it would run past
 *   [WIDTH], so `[1, 2, 3]` stays on one line and a 200 entry parameter map does not.
 * - **Maps keep their insertion order.** The encoders write parameters and enum entries in iteration
 *   order, so sorting the keys here would change the file.
 *
 * Writing is deterministic to the byte - the same definition always produces the same text - which is what
 * lets re-unpacking an unchanged cache leave the tree alone. Reading is tolerant: key order, whitespace and
 * unknown keys are all ignored, and a missing key leaves the field at its default.
 */
internal class DefinitionJson private constructor(private val properties: List<Property>) {

    /** [definition] as the exact text of its file, trailing newline included. */
    fun write(definition: Any): ByteArray {
        val entries = ArrayList<Pair<String, Node>>(properties.size)
        for (property in properties) {
            val value = property.field.get(definition)
            if (value == null) {
                continue
            }
            entries.add(property.name to property.codec.node(value))
        }
        val out = StringBuilder(properties.size * 24)
        ObjectNode(entries).expand(out, 0)
        out.append('\n')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Read [bytes] into [definition], which must be a fresh instance of the right id: a key the file does
     * not carry leaves the field exactly as the constructor left it.
     */
    fun read(bytes: ByteArray, definition: Any) {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        for (property in properties) {
            val element = root[property.name] ?: continue
            if (element is JsonNull) {
                continue
            }
            property.field.set(definition, property.codec.value(element))
        }
    }

    /** One field of the definition and how its value crosses to JSON and back. */
    private class Property(val name: String, val field: Field, val codec: ValueCodec)

    companion object {

        /** How wide a line may be before an array, object or map is broken across lines. */
        private const val WIDTH = 100

        private const val INDENT = "  "

        private val json = Json { ignoreUnknownKeys = true }

        private val cache = ConcurrentHashMap<Class<*>, DefinitionJson>()

        /**
         * A field that is derived from another field the file does carry, so it is not written.
         *
         * Writing one would put a value in the file that the encoder ignores and an editor could
         * contradict. Keyed by the fully qualified field so a same-named field elsewhere is unaffected.
         */
        private val DERIVED = setOf(
            // ClanVarDefinition.typeChar is the descriptor byte the file holds; CS2Type.forJagexDesc of it
            // is many to one, has no JSON form, and is what the encoder is careful never to use.
            "world.gregs.voidps.cache.definition.data.ClanVarDefinition.type",
            // A font's 65,536 entry kerning table is computed from the glyph offset profiles the file
            // does carry, and only for the variable width form; writing it would be a megabyte of
            // derived numbers per font that the encoder ignores.
            "world.gregs.voidps.cache.definition.data.FontDefinition.kerningAdjustments",
            // A world map area's bounds are the extremes of its sections, which the file does carry.
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.minX",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.minY",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.maxX",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.maxY",
            // The zoom byte is rawZoom; anInt9547 is it with the client's 255 to 0 fold applied.
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.anInt9547"
        )

        /**
         * The record classes that predate [DefinitionRecord] and are named here instead.
         *
         * They write their null fields as `null`, which is what the trees that already exist hold, so
         * they stay listed rather than being given the marker. Anything new implements the marker, and
         * either way [nested] gives it the same spelling - a nested object - so a record is free to
         * arrive by whichever route its class chose.
         */
        private val NESTED = setOf(
            InterfaceComponentSetting::class.java,
            InterfaceComponentHook::class.java,
            KeyedValue::class.java,
            TextureOp::class.java,
            TextureOpcode::class.java,
            TextureShape::class.java,
            SynthInstrument::class.java,
            SynthEnvelope::class.java,
            SynthShape::class.java,
            SynthFilter::class.java,
            SynthFilterPair::class.java,
            ShaderProgramVariable::class.java,
            ShaderProgramMapping::class.java,
            ShaderProgramDefine::class.java
        )

        /**
         * [type]'s mapping, built once.
         *
         * Built outside the map rather than in a `computeIfAbsent`: a definition holding a record class
         * asks for that class's mapping while its own is still being built, and a recursive update of a
         * [ConcurrentHashMap] either throws or deadlocks. Two threads racing on the same type build two
         * equivalent mappings and the first one to land wins.
         */
        fun of(type: Class<*>): DefinitionJson {
            cache[type]?.let { return it }
            val built = build(type)
            return cache.putIfAbsent(type, built) ?: built
        }

        private fun build(type: Class<*>): DefinitionJson {
            val properties = ArrayList<Property>()
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) {
                    continue
                }
                if (DERIVED.contains("${type.name}.${field.name}")) {
                    continue
                }
                field.isAccessible = true
                properties.add(Property(field.name, field, codec(type, field)))
            }
            check(properties.isNotEmpty()) { "${type.name} has no fields to write." }
            return DefinitionJson(properties)
        }

        /**
         * The codec for one field, from its declared type.
         *
         * Anything not listed is an error rather than a silently dropped field: a new decoder field of a
         * type nothing here can write must be given a spelling, not lost.
         */
        private fun codec(type: Class<*>, field: Field): ValueCodec {
            simple(field.type)?.let { return it }
            if (Map::class.java.isAssignableFrom(field.type) || List::class.java.isAssignableFrom(field.type)) {
                return element(type, field, field.genericType)
            }
            throw IllegalArgumentException(
                "${type.name}.${field.name} is a ${field.type.name}, which ${DefinitionJson::class.simpleName} " +
                    "has no spelling for; give it one rather than leaving the field out of the tree."
            )
        }

        /**
         * The codec of a field's type, of a map's key or value, or of a list's element.
         *
         * Recursive rather than flat because a list's element may itself be a list of records: a
         * texture is operations, each of which is opcodes, one of which is shapes.
         */
        private fun element(owner: Class<*>, field: Field, argument: ReflectType): ValueCodec {
            val type = erase(argument) ?: throw IllegalArgumentException(
                "${owner.name}.${field.name} is parameterised with $argument, which is not a class."
            )
            simple(type)?.let { return it }
            if (List::class.java.isAssignableFrom(type)) {
                return ListCodec(element(owner, field, arguments(owner, field, argument, 1)[0]))
            }
            if (Map::class.java.isAssignableFrom(type)) {
                val arguments = arguments(owner, field, argument, 2)
                return MapCodec(element(owner, field, arguments[0]), element(owner, field, arguments[1]))
            }
            throw IllegalArgumentException(
                "${owner.name}.${field.name} holds ${type.name}, which ${DefinitionJson::class.simpleName} " +
                    "has no spelling for."
            )
        }

        /** The codec of a type that needs nothing but itself - everything but a map or a list. */
        private fun simple(type: Class<*>): ValueCodec? = when (type) {
            Int::class.javaPrimitiveType, Integer::class.java -> IntCodec
            Long::class.javaPrimitiveType, JavaLong::class.java -> LongCodec
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> ShortCodec
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> ByteCodec
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> CharCodec
            // The boxed form is what a nullable field declares, and a nullable field is how a record
            // says "the file did not carry this" - see DefinitionRecord.
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> BooleanCodec
            String::class.java -> StringCodec
            Any::class.java -> AnyCodec
            IntArray::class.java -> ArrayCodec.INTS
            ShortArray::class.java -> ArrayCodec.SHORTS
            ByteArray::class.java -> ArrayCodec.BYTES
            BooleanArray::class.java -> ArrayCodec.BOOLEANS
            CharArray::class.java -> ArrayCodec.CHARS
            Array<String>::class.java -> ObjectArrayCodec(String::class.java, StringCodec)
            Array<IntArray>::class.java -> ObjectArrayCodec(IntArray::class.java, ArrayCodec.INTS)
            // The only byte[][] is a shadowed payload list: opaque record bytes, so hex, not numbers.
            Array<ByteArray>::class.java -> ObjectArrayCodec(ByteArray::class.java, HexCodec)
            Array<Any>::class.java -> ObjectArrayCodec(Any::class.java, AnyCodec)
            // A record, by either route; and an array of records - a synth sound's ten instrument
            // slots, a filter's pole pairs - as an array of nested objects. Every other array class is
            // named above, so the component check only ever catches one whose elements are records,
            // and nulls in it survive the trip.
            else -> nested(type) ?: type.componentType?.let { component ->
                nested(component)?.let { ObjectArrayCodec(component, it) }
            }
        }

        /**
         * [type] as a nested object, if it is a record at all.
         *
         * The two ways of being one meet here, because nothing downstream cares which was used: a class
         * listed in [NESTED] writes its null fields as `null`, and one carrying the [DefinitionRecord]
         * marker leaves them out.
         */
        private fun nested(type: Class<*>): NestedCodec? = when {
            NESTED.contains(type) -> NestedCodec(type, nulls = true)
            DefinitionRecord::class.java.isAssignableFrom(type) -> NestedCodec(type)
            else -> null
        }

        /**
         * The class a type argument stands for.
         *
         * Kotlin's collection types are declaration site covariant, so a `Map<Int, Any>` field's signature
         * reads `Map<Integer, ? extends Object>` and the value argument arrives as a wildcard rather than
         * as a class; its bound is the type that was written.
         */
        private fun erase(type: ReflectType): Class<*>? = when (type) {
            is Class<*> -> type
            is WildcardType -> type.upperBounds.firstOrNull()?.let { erase(it) }
            is ParameterizedType -> erase(type.rawType)
            else -> null
        }

        /** [type]'s [count] type arguments, unwrapping the wildcards Kotlin's covariance emits. */
        private fun arguments(
            owner: Class<*>,
            field: Field,
            type: ReflectType,
            count: Int
        ): Array<out ReflectType> {
            val generic = parameterized(type)
                ?: throw IllegalArgumentException("${owner.name}.${field.name} holds a raw $type.")
            val arguments = generic.actualTypeArguments
            require(arguments.size == count) {
                "${owner.name}.${field.name} holds a $type with ${arguments.size} type arguments, not $count."
            }
            return arguments
        }

        /** [type] as a parameterised type, through however many wildcards stand in front of it. */
        private fun parameterized(type: ReflectType): ParameterizedType? = when (type) {
            is ParameterizedType -> type
            is WildcardType -> type.upperBounds.firstOrNull()?.let { parameterized(it) }
            else -> null
        }

        // --- The JSON value tree -------------------------------------------------------------------

        /** A value on its way to or from a file. */
        private interface ValueCodec {
            fun node(value: Any): Node
            fun value(element: JsonElement): Any
        }

        private object IntCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toInt()
        }

        private object LongCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toLong()
        }

        private object ShortCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toShort()
        }

        private object ByteCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toByte()
        }

        private object BooleanCodec : ValueCodec {
            override fun node(value: Any) = BooleanNode(value as Boolean)
            override fun value(element: JsonElement) = element.jsonPrimitive.boolean
        }

        private object StringCodec : ValueCodec {
            override fun node(value: Any) = StringNode(value as String)
            override fun value(element: JsonElement) = element.jsonPrimitive.content
        }

        /**
         * A cp1252 type descriptor, written as the character itself so a file says `"i"` rather than
         * `105`. Code 0 - a definition that carries no descriptor - is the empty string.
         */
        private object CharCodec : ValueCodec {
            override fun node(value: Any): Node {
                val character = value as Char
                return StringNode(if (character.code == 0) "" else character.toString())
            }

            override fun value(element: JsonElement): Any {
                val content = element.jsonPrimitive.content
                return if (content.isEmpty()) 0.toChar() else content[0]
            }
        }

        /** A parameter value, an enum entry or a script argument: an int or a string, and nothing else. */
        private object AnyCodec : ValueCodec {
            override fun node(value: Any): Node = when (value) {
                is String -> StringNode(value)
                is Int -> NumberNode(value.toString())
                is Long -> NumberNode(value.toString())
                else -> throw IllegalArgumentException("A ${value::class.simpleName} is not an int or a string.")
            }

            override fun value(element: JsonElement): Any {
                val primitive = element.jsonPrimitive
                return if (primitive.isString) primitive.content else primitive.content.toInt()
            }
        }

        /** Opaque bytes - a shadowed record payload - as lower case hex. */
        private object HexCodec : ValueCodec {
            private val digits = "0123456789abcdef".toCharArray()

            override fun node(value: Any): Node {
                val bytes = value as ByteArray
                val out = CharArray(bytes.size * 2)
                for (index in bytes.indices) {
                    val byte = bytes[index].toInt() and 0xff
                    out[index * 2] = digits[byte shr 4]
                    out[index * 2 + 1] = digits[byte and 0xf]
                }
                return StringNode(String(out))
            }

            override fun value(element: JsonElement): Any {
                val text = element.jsonPrimitive.content
                require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
                return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
        }

        /** A primitive array as an array of numbers, or of booleans. */
        private class ArrayCodec(
            private val length: (Any) -> Int,
            private val element: (Any, Int) -> Node,
            private val build: (List<JsonElement>) -> Any
        ) : ValueCodec {

            override fun node(value: Any) = ArrayNode(List(length(value)) { element(value, it) })

            override fun value(source: JsonElement) = build(source.jsonArray)

            companion object {
                val INTS = ArrayCodec(
                    { (it as IntArray).size },
                    { array, index -> NumberNode((array as IntArray)[index].toString()) },
                    { items -> IntArray(items.size) { items[it].jsonPrimitive.content.toInt() } }
                )
                val SHORTS = ArrayCodec(
                    { (it as ShortArray).size },
                    { array, index -> NumberNode((array as ShortArray)[index].toString()) },
                    { items -> ShortArray(items.size) { items[it].jsonPrimitive.content.toShort() } }
                )
                val BYTES = ArrayCodec(
                    { (it as ByteArray).size },
                    { array, index -> NumberNode((array as ByteArray)[index].toString()) },
                    { items -> ByteArray(items.size) { items[it].jsonPrimitive.content.toByte() } }
                )
                val BOOLEANS = ArrayCodec(
                    { (it as BooleanArray).size },
                    { array, index -> BooleanNode((array as BooleanArray)[index]) },
                    { items -> BooleanArray(items.size) { items[it].jsonPrimitive.boolean } }
                )

                /** Single cp1252 characters, each as a string, and the empty string for code 0. */
                val CHARS = ArrayCodec(
                    { (it as CharArray).size },
                    { array, index -> CharCodec.node((array as CharArray)[index]) },
                    { items -> CharArray(items.size) { CharCodec.value(items[it]) as Char } }
                )
            }
        }

        /** An array of objects, any of which may be null. */
        private class ObjectArrayCodec(
            private val component: Class<*>,
            private val element: ValueCodec
        ) : ValueCodec {

            override fun node(value: Any): Node {
                val array = value as Array<*>
                return ArrayNode(array.map { if (it == null) NullNode else element.node(it) })
            }

            override fun value(source: JsonElement): Any {
                val items = source.jsonArray
                val array = ReflectArray.newInstance(component, items.size)
                for (index in items.indices) {
                    val item = items[index]
                    ReflectArray.set(array, index, if (item is JsonNull) null else element.value(item))
                }
                return array
            }
        }

        /**
         * A map as a JSON object keyed by the numeric id, in the order the file listed it.
         *
         * Insertion ordered on the way back in, because the encoders write a parameter list and an enum's
         * entries in iteration order and a rehash would rewrite the file.
         */
        private class MapCodec(private val key: ValueCodec, private val value: ValueCodec) : ValueCodec {

            override fun node(value: Any): Node {
                val map = value as Map<*, *>
                val entries = ArrayList<Pair<String, Node>>(map.size)
                for ((entryKey, entryValue) in map) {
                    val name = key.node(entryKey!!)
                    entries.add((if (name is StringNode) name.text else (name as NumberNode).text) to this.value.node(entryValue!!))
                }
                return ObjectNode(entries)
            }

            override fun value(element: JsonElement): Any {
                val map = LinkedHashMap<Any, Any>()
                for ((entryKey, entryValue) in element.jsonObject) {
                    map[key.value(JsonPrimitive(entryKey))] = value.value(entryValue)
                }
                return map
            }
        }

        /** A list of records, as an array of objects. */
        private class ListCodec(private val element: ValueCodec) : ValueCodec {
            override fun node(value: Any) = ArrayNode((value as List<*>).map { element.node(it!!) })
            override fun value(source: JsonElement): Any = source.jsonArray.map { element.value(it) }
        }

        /**
         * A record class - a parameter record, a component setting, a script hook - as a nested object.
         *
         * Its fields are mapped exactly like a definition's, and it is rebuilt through the constructor
         * that takes them all, in declaration order, since a record's fields may be read only.
         */
        private class NestedCodec(
            private val type: Class<*>,
            /** Whether a null field is written as `null` rather than left out; see [DefinitionRecord]. */
            private val nulls: Boolean = false
        ) : ValueCodec {

            private val json = of(type)

            private val constructor = type.declaredConstructors
                .firstOrNull { it.parameterCount == json.properties.size }
                ?.also { it.isAccessible = true }
                ?: throw IllegalArgumentException("${type.name} has no constructor taking all of its fields.")

            /**
             * The record's fields, nulls left out exactly as a definition's are: [value] reads a
             * missing key back as null, so a written null says nothing a missing key does not.
             */
            override fun node(value: Any): Node {
                val entries = ArrayList<Pair<String, Node>>(json.properties.size)
                for (property in json.properties) {
                    val field = property.field.get(value)
                    if (field == null) {
                        if (nulls) {
                            entries.add(property.name to NullNode)
                        }
                        continue
                    }
                    entries.add(property.name to property.codec.node(field))
                }
                return ObjectNode(entries)
            }

            override fun value(element: JsonElement): Any {
                val root = element.jsonObject
                val arguments = arrayOfNulls<Any>(json.properties.size)
                for (index in json.properties.indices) {
                    val property = json.properties[index]
                    val field = root[property.name]
                    arguments[index] = if (field == null || field is JsonNull) null else property.codec.value(field)
                }
                return constructor.newInstance(*arguments)
            }
        }

        // --- The printer ---------------------------------------------------------------------------

        /** A JSON value on its way out, which knows both how to fit on a line and how not to. */
        private sealed class Node {
            /** The whole value on one line. */
            abstract fun compact(out: StringBuilder)

            /** Whether breaking the value across lines would make it any narrower. */
            open val breakable: Boolean
                get() = false

            /** The value across lines, its first character at the caller's cursor and [indent] its column. */
            open fun expand(out: StringBuilder, indent: Int) = compact(out)

            /** [expand] when the compact form would run past [WIDTH] from [column], [compact] otherwise. */
            fun write(out: StringBuilder, indent: Int, column: Int) {
                val line = StringBuilder()
                compact(line)
                if (!breakable || column + line.length <= WIDTH) {
                    out.append(line)
                } else {
                    expand(out, indent)
                }
            }
        }

        private class NumberNode(val text: String) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(text)
            }
        }

        private class BooleanNode(private val value: Boolean) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(value)
            }
        }

        private class StringNode(val text: String) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(SourceJson.quote(text))
            }
        }

        private object NullNode : Node() {
            override fun compact(out: StringBuilder) {
                out.append("null")
            }
        }

        private class ArrayNode(private val items: List<Node>) : Node() {

            override fun compact(out: StringBuilder) {
                out.append('[')
                for (index in items.indices) {
                    if (index != 0) {
                        out.append(", ")
                    }
                    items[index].compact(out)
                }
                out.append(']')
            }

            override val breakable: Boolean
                get() = items.isNotEmpty()

            override fun expand(out: StringBuilder, indent: Int) {
                out.append("[\n")
                val inner = indent + INDENT.length
                for (index in items.indices) {
                    pad(out, inner)
                    items[index].write(out, inner, inner)
                    if (index != items.size - 1) {
                        out.append(',')
                    }
                    out.append('\n')
                }
                pad(out, indent)
                out.append(']')
            }
        }

        private class ObjectNode(private val entries: List<Pair<String, Node>>) : Node() {

            override fun compact(out: StringBuilder) {
                out.append('{')
                for (index in entries.indices) {
                    if (index != 0) {
                        out.append(", ")
                    }
                    out.append(SourceJson.quote(entries[index].first)).append(": ")
                    entries[index].second.compact(out)
                }
                out.append('}')
            }

            override val breakable: Boolean
                get() = entries.isNotEmpty()

            override fun expand(out: StringBuilder, indent: Int) {
                out.append("{\n")
                val inner = indent + INDENT.length
                for (index in entries.indices) {
                    pad(out, inner)
                    val key = SourceJson.quote(entries[index].first) + ": "
                    out.append(key)
                    entries[index].second.write(out, inner, inner + key.length)
                    if (index != entries.size - 1) {
                        out.append(',')
                    }
                    out.append('\n')
                }
                pad(out, indent)
                out.append('}')
            }
        }

        private fun pad(out: StringBuilder, columns: Int) {
            for (column in 0 until columns) {
                out.append(' ')
            }
        }
    }
}
