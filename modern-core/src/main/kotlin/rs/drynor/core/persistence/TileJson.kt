package rs.drynor.core.persistence

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import world.gregs.voidps.type.Tile
import java.lang.reflect.Type

/** Reads coordinate-based content/configuration and writes the current packed tile format. */
class TileJson : JsonSerializer<Tile>, JsonDeserializer<Tile> {
    override fun serialize(value: Tile, type: Type, context: JsonSerializationContext): JsonElement =
        JsonObject().apply { addProperty("id", value.id) }

    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): Tile {
        if (json.isJsonPrimitive) return Tile(json.asInt)
        val value = json.asJsonObject
        value.get("id")?.let { return Tile(it.asInt) }
        return Tile(value.get("x").asInt, value.get("y").asInt,
            value.get("level")?.asInt ?: value.get("plane")?.asInt ?: 0)
    }
}
