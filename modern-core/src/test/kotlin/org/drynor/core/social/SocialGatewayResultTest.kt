package org.drynor.core.social

import org.drynor.core.social.gateway.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SocialGatewayResultTest {
    private val json = SocialGatewayWireJson.json
    @Test fun `account operations preserve long ids and punishment deltas without credential fields`() {
        val account = org.drynor.core.model.AccountView("alice", "Alice")
        val messages = listOf(
            AccountLookup("Alice", AccountLookupKind.DISPLAY_NAME, 7_000_000_000),
            AccountLookupResult("Alice", AccountLookupKind.DISPLAY_NAME, 7_000_000_000, AccountOperationStatus.SUCCESS, account),
            AccountMutationRequest("alice", AccountMutation.Punishments(muted = Long.MAX_VALUE), 7_000_000_001),
            AccountMutationResult("alice", AccountMutationKind.PUNISHMENTS, 7_000_000_001, AccountOperationStatus.SUCCESS, account),
        )
        messages.forEach {
            val wire = json.encodeToString(SocialGatewayWireMessage.serializer(), it)
            assertEquals(it, json.decodeFromString(SocialGatewayWireMessage.serializer(), wire))
            kotlin.test.assertFalse(wire.contains("password"))
        }
    }
    @Test fun `world login correlation retains long ids public account and typed failures`() {
        for (status in WorldLoginStatus.entries) {
            val account = if (status == WorldLoginStatus.SUCCESS) org.drynor.core.model.AccountView("alice", "Alice") else null
            val message = WorldLoginAuthResult("alice", 7_000_000_000, account, status)
            val encoded = json.encodeToString(SocialGatewayWireMessage.serializer(), message)
            assertEquals(message, json.decodeFromString(SocialGatewayWireMessage.serializer(), encoded))
            kotlin.test.assertFalse(encoded.contains("password"))
        }
    }
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
