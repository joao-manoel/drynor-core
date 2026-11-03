package org.drynor.core.model

import com.google.gson.Gson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.drynor.core.social.gateway.AccountMutation
import org.drynor.core.social.gateway.AccountMutationRequest
import org.drynor.core.social.gateway.SocialGatewayWireJson
import org.drynor.core.social.gateway.SocialGatewayWireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DonatorTierTest {
    @Test fun `old account views and explicit null donor fields default safely without staff privileges`() {
        val gson = Gson()
        for (suffix in listOf("", ",\"donatorTier\":null")) {
            val view = gson.fromJson("""{"username":"alice","displayName":"Alice","rights":"PLAYER","social":{}$suffix}""", AccountView::class.java)
            assertEquals(DonatorTier.NONE, view.donatorTier)
            assertEquals(0, view.chatCrown)
            assertFalse(view.hasRights(Rights.MOD))
            assertEquals(DonatorTier.NONE, view.copy(displayName = "Renamed").donatorTier)
        }
        val oldWire = Json.decodeFromString<AccountView>("""{"username":"alice","displayName":"Alice"}""")
        assertEquals(DonatorTier.NONE, oldWire.donatorTier)
    }

    @Test fun `donor status survives public projection copy gson and gateway wire independently from rights`() {
        val account = Account("alice", "alice@example.test", "not-public", "Alice").apply {
            donatorTier = DonatorTier.SUPREME
        }
        val public = account.view().copy(displayName = "Alicia")
        assertEquals(DonatorTier.SUPREME, public.donatorTier)
        assertEquals(9, public.chatCrown)
        assertEquals(0.30, public.donatorTier.xpBonus)
        assertEquals(Rights.PLAYER, public.rights)
        val gson = Gson()
        assertEquals(DonatorTier.SUPREME, gson.fromJson(gson.toJson(public), AccountView::class.java).donatorTier)
        val json = Json { encodeDefaults = true }
        assertEquals(public, json.decodeFromString<AccountView>(json.encodeToString(public)))
        assertFalse(json.encodeToString(public).contains("password"))
        public.rights = Rights.ADMIN
        assertEquals(2, public.chatCrown)
        assertEquals(DonatorTier.SUPREME, public.donatorTier)
    }

    @Test fun `donor account mutations retain their receipt and tier on the authenticated gateway envelope`() {
        val json = SocialGatewayWireJson.json
        for (change in listOf(AccountMutation.SetDonatorTier(DonatorTier.EXTREME),
            AccountMutation.RedeemDonatorBond("be43467b-7938-4e31-bcf5-897b051a94f2", DonatorTier.VIP))) {
            val request = AccountMutationRequest("alice", change, 7_000_000_001L)
            val wire = json.encodeToString(SocialGatewayWireMessage.serializer(), request)
            assertEquals(request, json.decodeFromString(SocialGatewayWireMessage.serializer(), wire))
        }
    }
}
