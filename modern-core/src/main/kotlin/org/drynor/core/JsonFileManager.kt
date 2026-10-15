package org.drynor.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.io.IOException
import java.io.IOException as JavaIOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.File

object JsonFileManager {
    private val mapSerializer: KSerializer<Map<String, Any?>> = object : KSerializer<Map<String, Any?>> {
        override val descriptor = buildClassSerialDescriptor("Map<String, Any?>")

        override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
            val jsonEncoder = encoder as JsonEncoder
            val jsonObject = buildJsonObject {
                value.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, JsonPrimitive(value))
                        is Number -> put(key, JsonPrimitive(value))
                        is Boolean -> put(key, JsonPrimitive(value))
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            put(key, Json.encodeToJsonElement(mapSerializer, value as Map<String, Any?>))
                        }
                        is List<*> -> put(key, Json.encodeToJsonElement(listSerializer, value))
                        null -> put(key, JsonNull)
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            }
            jsonEncoder.encodeJsonElement(jsonObject)
        }

        override fun deserialize(decoder: Decoder): Map<String, Any?> {
            val jsonDecoder = decoder as JsonDecoder
            val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
            return buildMap {
                jsonObject.forEach { (key, element) ->
                    when (element) {
                        is JsonPrimitive -> {
                            when {
                                element.isString -> put(key, element.content)
                                element.booleanOrNull != null -> put(key, element.boolean)
                                element.intOrNull != null -> put(key, element.int)
                                element.longOrNull != null -> put(key, element.long)
                                element.doubleOrNull != null -> put(key, element.double)
                                else -> put(key, element.content)
                            }
                        }
                        is JsonObject -> put(key, Json.decodeFromJsonElement(mapSerializer, element))
                        is JsonArray -> put(key, Json.decodeFromJsonElement(listSerializer, element))
                        JsonNull -> put(key, null)
                    }
                }
            }
        }
    }

    private val listSerializer: KSerializer<List<Any?>> = object : KSerializer<List<Any?>> {
        override val descriptor = buildClassSerialDescriptor("List<Any?>")

        override fun serialize(encoder: Encoder, value: List<Any?>) {
            val jsonEncoder = encoder as JsonEncoder
            val jsonArray = buildJsonArray {
                value.forEach { item ->
                    when (item) {
                        is String -> add(JsonPrimitive(item))
                        is Number -> add(JsonPrimitive(item))
                        is Boolean -> add(JsonPrimitive(item))
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            add(Json.encodeToJsonElement(mapSerializer, item as Map<String, Any?>))
                        }
                        is List<*> -> add(Json.encodeToJsonElement(listSerializer, item))
                        null -> add(JsonNull)
                        else -> add(JsonPrimitive(item.toString()))
                    }
                }
            }
            jsonEncoder.encodeJsonElement(jsonArray)
        }

        override fun deserialize(decoder: Decoder): List<Any?> {
            val jsonDecoder = decoder as JsonDecoder
            val jsonArray = jsonDecoder.decodeJsonElement().jsonArray
            return buildList {
                jsonArray.forEach { element ->
                    when (element) {
                        is JsonPrimitive -> {
                            when {
                                element.isString -> add(element.content)
                                element.booleanOrNull != null -> add(element.boolean)
                                element.intOrNull != null -> add(element.int)
                                element.longOrNull != null -> add(element.long)
                                element.doubleOrNull != null -> add(element.double)
                                else -> add(element.content)
                            }
                        }
                        is JsonObject -> add(Json.decodeFromJsonElement(mapSerializer, element))
                        is JsonArray -> add(Json.decodeFromJsonElement(listSerializer, element))
                        JsonNull -> add(null)
                    }
                }
            }
        }
    }

    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun writeMapToJson(data: Map<String, Any?>, filePath: String) {
        try {
            val jsonString = Json.encodeToString(mapSerializer, data)
            File(filePath).writeText(jsonString)
        } catch (e: Exception) {
            throw when (e) {
                is SerializationException -> e
                else -> IOException("Failed to write JSON file: ${e.message}", e)
            }
        }
    }

    fun readMapFromJson(filePath: String): Map<String, Any?> {
        try {
            val jsonString = File(filePath).readText()
            return Json.decodeFromString(mapSerializer, jsonString)
        } catch (e: Exception) {
            throw when (e) {
                is SerializationException -> e
                else -> IOException("Failed to read JSON file: ${e.message}", e)
            }
        }
    }

    inline fun <reified T> writeToJson(data: T, filePath: String) {
        try {
            val jsonString = json.encodeToString(data)
            File(filePath).writeText(jsonString)
        } catch (e: Exception) {
            throw when (e) {
                is SerializationException -> e
                else -> IOException("Failed to write JSON file: ${e.message}", e)
            }
        }
    }

    inline fun <reified T> readFromJson(filePath: String): T {
        try {
            val jsonString = File(filePath).readText()
            return json.decodeFromString(jsonString)
        } catch (e: Exception) {
            throw when (e) {
                is SerializationException -> e
                else -> IOException("Failed to read JSON file: ${e.message}", e)
            }
        }
    }

    fun readAsDynamic(filePath: String): JsonElement {
        try {
            val jsonString = File(filePath).readText()
            return json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            throw IOException("Failed to read JSON file: ${e.message}", e)
        }
    }

    fun fileExists(filePath: String) = File(filePath).exists()

    fun deleteFile(filePath: String) = File(filePath).delete()

    // --- Gson backward-compatibility for legacy Java callers ---

    private var gsonInstance: Gson? = null

    private val gson: Gson get() = gsonInstance ?: GsonBuilder()
        .registerTypeAdapter(org.drynor.core.model.AccountView::class.java, rs.drynor.core.persistence.AccountViewJson())
        .registerTypeAdapter(world.gregs.voidps.type.Tile::class.java, rs.drynor.core.persistence.TileJson())
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create().also { gsonInstance = it }

    @JvmStatic
    fun setGSON(gson: Gson) {
        gsonInstance = gson
    }

    @JvmStatic
    fun getGSON(): Gson = gson

    @JvmStatic
    fun <T> loadJsonFile(file: File, clazz: Class<T>): T? {
        if (!file.exists()) return null
        return try {
            file.reader().use { reader ->
                gson.fromJson(reader, clazz)
            }
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun <T> loadJsonFile(file: File, type: java.lang.reflect.Type): T? {
        if (!file.exists()) return null
        return try {
            file.reader().use { reader ->
                gson.fromJson<T>(reader, type)
            }
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    @Throws(JavaIOException::class)
    fun saveJsonFile(data: Any, file: File) {
        file.parentFile?.mkdirs()
        file.writer().use { writer ->
            gson.toJson(data, writer)
        }
    }

    @JvmStatic
    fun toJson(data: Any): String = gson.toJson(data)

    @JvmStatic
    @Throws(JavaIOException::class)
    fun <T> fromJSONString(jsonStr: String, clazz: Class<T>): T = gson.fromJson(jsonStr, clazz)
}
