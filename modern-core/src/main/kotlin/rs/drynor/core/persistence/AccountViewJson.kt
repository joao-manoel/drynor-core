package rs.drynor.core.persistence

import com.google.gson.*
import org.drynor.core.model.AccountView
import org.drynor.core.model.Rights
import org.drynor.core.model.Social
import java.lang.reflect.Type

/** Constructs the immutable identity normally, without reflective writes to final fields. */
class AccountViewJson : JsonSerializer<AccountView>, JsonDeserializer<AccountView> {
    override fun serialize(value: AccountView, type: Type, context: JsonSerializationContext): JsonElement = JsonObject().apply {
        addProperty("username", value.username)
        addProperty("displayName", value.displayName)
        addProperty("rights", value.rights.name)
        addProperty("banned", value.banned)
        addProperty("muted", value.muted)
        add("social", context.serialize(value.social))
        addProperty("bot", value.bot)
    }

    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): AccountView {
        val value = json.asJsonObject
        fun field(name: String) = value[name]?.takeUnless { it.isJsonNull }
        val username = field("username")?.asString?.takeIf { it.isNotBlank() } ?: throw JsonParseException("Missing account identity")
        return AccountView(username, field("displayName")?.asString ?: username,
            field("rights")?.asString?.let(Rights::valueOf) ?: Rights.PLAYER,
            field("banned")?.asLong ?: 0, field("muted")?.asLong ?: 0,
            field("social")?.let { context.deserialize<Social>(it, Social::class.java) } ?: Social(),
            field("bot")?.asBoolean ?: false)
    }
}
