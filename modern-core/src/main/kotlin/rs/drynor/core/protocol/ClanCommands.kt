package rs.drynor.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drynor.core.clan.ClanAction

@Serializable data class ClanCommandRequest(val username: String, val action: ClanAction)
data class ClanCommandResponse(val success: Boolean, val reason: String)

/** The same closed action types as the current core gateway, over the local HTTP transport. */
object ClanCommandJson {
    private val json = Json { classDiscriminator = "_type"; encodeDefaults = true }
    @JvmStatic fun encode(request: ClanCommandRequest): String = json.encodeToString(ClanCommandRequest.serializer(), request)
    @JvmStatic fun decode(text: String): ClanCommandRequest = json.decodeFromString(ClanCommandRequest.serializer(), text)
}
