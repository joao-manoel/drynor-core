package org.drynor.core.net.login

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldLoginTokenTest {

    @Test
    fun `token roundtrip verifies and exposes fields`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        val verified = LoginToken.verify(token, nowMs = now + 1, secret = secret)
        assertNotNull(verified)
        assertTrue(verified.username == "test_user")
        assertTrue(verified.expiresAtMs > now)
    }

    @Test
    fun `token tamper fails`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        // Flip a character in the payload portion to simulate tampering
        val dotIndex = token.indexOf('.')
        val payload = token.substring(0, dotIndex)
        val signature = token.substring(dotIndex)
        val flipped = payload.mapIndexed { i, c ->
            if (i == payload.lastIndex) (if (c == 'A') 'B' else 'A') else c
        }.joinToString("")
        val tampered = flipped + signature
        val verified = LoginToken.verify(tampered, nowMs = now + 1, secret = secret)
        assertNull(verified)
    }

    @Test
    fun `expired token fails`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = now,
            ttlMs = 10L,
            secret = secret,
        )

        val verified = LoginToken.verify(token, nowMs = now + 11, secret = secret)
        assertNull(verified)
    }

    @Test
    fun `issued token has token shape`() {
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = 1_000_000L,
            ttlMs = 60_000L,
            secret = "unit-test-secret",
        )

        assertTrue(LoginToken.hasTokenShape(token))
    }

    @Test
    fun `plaintext password never has token shape`() {
        assertFalse(LoginToken.hasTokenShape(""))
        assertFalse(LoginToken.hasTokenShape("hunter2"))
        assertFalse(LoginToken.hasTokenShape("correct horse battery staple"))
        assertFalse(LoginToken.hasTokenShape("p4ssw0rd.with.dots"))
        assertFalse(LoginToken.hasTokenShape(".leading"))
        assertFalse(LoginToken.hasTokenShape("trailing."))
    }

    @Test
    fun `garbage signature keeps token shape but fails verify`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val forged = token.substring(0, token.indexOf('.') + 1) + encoder.encodeToString(ByteArray(16) { 0x7F })

        assertTrue(LoginToken.hasTokenShape(forged))
        assertNull(LoginToken.verify(forged, nowMs = now + 1, secret = secret))
    }

    @Test
    fun `wrong version byte has no token shape`() {
        val secret = "unit-test-secret"
        val now = 1_000_000L
        val token = LoginToken.issue(
            username = "test_user",
            nowMs = now,
            ttlMs = 60_000L,
            secret = secret,
        )

        val dotIndex = token.indexOf('.')
        val payload = Base64.getUrlDecoder().decode(token.substring(0, dotIndex))
        payload[0] = 2
        val reversioned = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + token.substring(dotIndex)

        assertFalse(LoginToken.hasTokenShape(reversioned))
        assertNull(LoginToken.verify(reversioned, nowMs = now + 1, secret = secret))
    }
}
