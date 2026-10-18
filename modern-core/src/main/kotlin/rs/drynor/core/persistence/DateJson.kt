package rs.drynor.core.persistence

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale

/** Writes unambiguous instants and reads the dates already saved by Gson in player documents. */
class DateJson(private val historicalZone: ZoneId = ZoneId.systemDefault()) : JsonSerializer<Date>, JsonDeserializer<Date> {
    override fun serialize(value: Date, type: Type, context: JsonSerializationContext): JsonElement = JsonPrimitive(value.toInstant().toString())
    override fun deserialize(value: JsonElement, type: Type, context: JsonDeserializationContext): Date {
        val text = value.asString.replace('\u00a0', ' ').replace('\u202f', ' ')
        runCatching { Instant.parse(text) }.getOrNull()?.let { return Date.from(it) }
        for (locale in listOf(Locale.US, Locale.getDefault()).distinct()) {
            for (pattern in listOf("MMM d, uuuu, h:mm:ss a", "MMM d, uuuu h:mm:ss a")) {
                val format = DateTimeFormatter.ofPattern(pattern, locale).withResolverStyle(ResolverStyle.STRICT)
                runCatching { LocalDateTime.parse(text, format) }.getOrNull()?.let {
                    return Date.from(it.atZone(historicalZone).toInstant())
                }
            }
        }
        throw JsonParseException("Unsupported persisted date format")
    }
}
