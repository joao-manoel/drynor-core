package org.drynor.core.social

import org.drynor.core.social.gateway.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SocialGatewayResultTest {
    private val json = SocialGatewayWireJson.json
    @Test fun `command and refusal preserve request correlation on the gateway wire`() {
        val command = SocialCommand("alice", SocialAction.AddFriend("neto"), 27)
        val result = SocialCommandResult(27, false, "TARGET_NOT_FOUND")
        for (message in listOf(command, result)) {
            val text = json.encodeToString(SocialGatewayWireMessage.serializer(), message)
            assertEquals(message, json.decodeFromString(SocialGatewayWireMessage.serializer(), text))
        }
        assertEquals("""{"_type":"SocialCommandResult","requestId":27,"success":false,"reason":"TARGET_NOT_FOUND"}""",
            json.encodeToString(SocialGatewayWireMessage.serializer(), result))
    }
}
