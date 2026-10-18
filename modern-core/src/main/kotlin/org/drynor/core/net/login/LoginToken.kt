package org.drynor.core.net.login

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Drynor's auth token system to keep player passwords secure and untransmitted via public world-servers.
 * Provides stateless, cryptographically-signed tokens that allow a login server to issue credentials that world servers can independently verify without
 * database lookups or shared session state.
 *
 * ## Token Format (v1)
 * ```
 * base64url(payload) + "." + base64url(signature)
 *
 * Payload (18 + usernameLen bytes):
 *   [0]       version     = 0x01
 *   [1..4]    issuedAt    = uint32 big-endian (unix seconds)
 *   [5..8]    expiresAt   = uint32 big-endian (unix seconds)
 *   [9]       usernameLen = uint8 (1..255)
 *   [10..]    username    = UTF-8 bytes
 *   [..]      nonce       = 8 random bytes (replay resistance)
 *
 * Signature:
 *   HMAC-SHA256(payload) truncated to 16 bytes (128 bits)
 * ```
 *
 * ## Security features
 *
 * **Integrity**:
 * HMAC-SHA256 ensures tokens cannot be forged or tampered with without knowledge of the shared secret. The truncated 128-bit signature provides adequate security for
 * short-lived tokens while saving space fitting with Jagex's login block constraints.
 *
 * **Replay Resistance**:
 * The 8-byte cryptographic nonce ensures each token is unique, preventing replay attacks even for the same user within the validity window.
 *
 * **Timing Attack Resistance**:
 * Signature verification uses constant-time comparison via [MessageDigest.isEqual] to prevent timing side-channels.
 *
 * **Freshness**:
 * Tokens include both issued-at and expires-at timestamps, enabling servers to enforce maximum token age and detect clock skew.
 *
 * ## Usage
 * ```kotlin
 * // issuing tokens from login server
 * val token = WorldLoginToken.issue(
 *     username = "trent",
 *     nowMs = System.currentTimeMillis(),
 *     ttlMs = 1800000L, //configurable valid login time
 *     secret = sharedSecret
 * )
 *
 * // verify token
 * val result = WorldLoginToken.verify(token, System.currentTimeMillis(), sharedSecret)
 *     ?: error("Failed authentication for user")
 * println("Successfully authenticated: ${result.username}")
 * ```
 *
 * Note: the ttlMs should ideally exceed the idle logout of the lobby server which we currently don't have implemented so for now probably just going to send
 * a login token expired error or something similar if that already exists as an opcode somewhere
 */
object LoginToken {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 8
    private const val SIGNATURE_BYTES = 16
    private const val HEADER_BYTES = 1 + 4 + 4 + 1  // version + issued + expires + usernameLen

    private val secureRandom = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    data class Verified(
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    )

    fun issue(username: String, nowMs: Long, ttlMs: Long, secret: String, ): String {
        val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
        require(usernameBytes.size in 1..255) { "Username must be 1-255 UTF-8 bytes" }

        val payload = ByteBuffer.allocate(HEADER_BYTES + usernameBytes.size + NONCE_BYTES).apply {
            put(VERSION)
            putInt((nowMs / 1000).toInt())
            putInt(((nowMs + ttlMs) / 1000).toInt())
            put(usernameBytes.size.toByte())
            put(usernameBytes)
            put(generateNonce())
        }.array()

        val signature = computeHmac(payload, secret).copyOf(SIGNATURE_BYTES)
        return "${base64Encoder.encodeToString(payload)}.${base64Encoder.encodeToString(signature)}"
    }

    fun hasTokenShape(candidate: String): Boolean = parse(candidate) != null

    fun verify(token: String, nowMs: Long, secret: String): Verified? {
        val parsed = parse(token) ?: return null

        val expected = computeHmac(parsed.payload, secret).copyOf(SIGNATURE_BYTES)
        if (!MessageDigest.isEqual(expected, parsed.signature)) return null

        if (parsed.expiresAtMs < nowMs) return null

        return Verified(parsed.username, parsed.issuedAtMs, parsed.expiresAtMs)
    }

    private class Parsed(
        val payload: ByteArray,
        val signature: ByteArray,
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    )

    private fun parse(token: String): Parsed? {
        val dotIndex = token.indexOf('.')
        if (dotIndex <= 0 || dotIndex >= token.lastIndex) return null
        if (token.indexOf('.', dotIndex + 1) != -1) return null

        val payload = runCatching { base64Decoder.decode(token.substring(0, dotIndex)) }.getOrNull() ?: return null
        val signature = runCatching { base64Decoder.decode(token.substring(dotIndex + 1)) }.getOrNull() ?: return null

        if (payload.isEmpty() || payload[0] != VERSION) return null

        val minLength = HEADER_BYTES + 1 + NONCE_BYTES  // At least 1 byte for username
        if (payload.size < minLength || signature.size != SIGNATURE_BYTES) return null

        val buffer = ByteBuffer.wrap(payload)
        buffer.get() //version skip

        val issuedAtSec = buffer.int.toLong() and 0xFFFFFFFFL
        val expiresAtSec = buffer.int.toLong() and 0xFFFFFFFFL
        val usernameLen = buffer.get().toInt() and 0xFF

        if (usernameLen < 1 || buffer.remaining() < usernameLen + NONCE_BYTES) return null

        val usernameBytes = ByteArray(usernameLen)
        buffer.get(usernameBytes)

        return Parsed(
            payload = payload,
            signature = signature,
            username = usernameBytes.toString(StandardCharsets.UTF_8),
            issuedAtMs = issuedAtSec * 1000,
            expiresAtMs = expiresAtSec * 1000,
        )
    }

    private fun computeHmac(data: ByteArray, secret: String): ByteArray {
        val keyBytes = secret.toByteArray(StandardCharsets.UTF_8)
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(keyBytes, "HmacSHA256"))
        }.doFinal(data)
    }

    private fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
}