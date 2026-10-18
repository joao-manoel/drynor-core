package rs.drynor.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drynor.core.social.SocialAction
import org.drynor.core.social.SocialJson

@Serializable data class SocialCommandRequest(val username: String, val action: SocialAction)
data class SocialCommandResponse(val success: Boolean, val reason: String)

/** Closed commands shared by world and lobby; no decoder class names cross this boundary. */
object SocialCommandJson {
    private val json = Json {
        classDiscriminator = "_type"
        encodeDefaults = true
        serializersModule = SocialJson.serializersModule
    }
    @JvmStatic fun encode(request: SocialCommandRequest): String = json.encodeToString(SocialCommandRequest.serializer(), request)
    @JvmStatic fun decode(text: String): SocialCommandRequest = json.decodeFromString(SocialCommandRequest.serializer(), text)
}
