package org.drynor.core.net.login

import io.ktor.utils.io.core.*
import org.drynor.core.model.MachineInformation
import org.drynor.core.net.ResponseOpcode
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginRequestsTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    private val public = LoginRsaKey((keys.public as RSAPublicKey).modulus, (keys.public as RSAPublicKey).publicExponent)
    private val private = LoginRsaKey((keys.private as RSAPrivateKey).modulus, (keys.private as RSAPrivateKey).privateExponent)
    private val crcs = IntArray(LOGIN_CRC_COUNT) { it * 7919 - 1 }

    private fun ByteArray.afterRevision(): Pair<Pair<Int, Int>, ByteReadPacket> {
        val packet = ByteReadPacket(this)
        return (packet.readInt() to packet.readInt()) to packet
    }

    @Test
    fun `a lobby login block decodes to the request the client encoded`() {
        val request = LobbyLoginRequest(727, 1, intArrayOf(1, -2, 3, Int.MAX_VALUE), "testes", "hunter2", "login-token", "client-key", "js5-token", crcs, preferences = intArrayOf(1, 0, 255))
        val (revision, packet) = request.encode(public).afterRevision()
        val decoded = assertIs<LoginDecode.Decoded<LobbyLoginRequest>>(LobbyLoginRequest.decode(revision.first, revision.second, packet, private, LOGIN_CRC_COUNT)).request
        assertEquals(727 to 1, decoded.revision to decoded.subRevision)
        assertContentEquals(request.isaacSeed, decoded.isaacSeed)
        assertEquals(listOf("testes", "hunter2", "login-token", "client-key", "js5-token"), listOf(decoded.username, decoded.password, decoded.loginServerToken, decoded.clientKey, decoded.js5ServerToken))
        assertContentEquals(crcs, decoded.indexCrcs)
        assertContentEquals(intArrayOf(1, 0, 255), decoded.preferences)
    }

    @Test
    fun `a world login block carries the lobby token, world and machine details`() {
        val machine = MachineInformation(version = WorldLoginRequest.MACHINE_INFO_VERSION, operatingSystem = 3, javaBuild = 26, javaVersion = "26.0.0", ram = 16384, gpuModel = "gpu", dwDirectXVersion = "", cpuType = "cpu", cpuData = "", cpuFeatureFlags = intArrayOf(1, 2, 3))
        val request = WorldLoginRequest(727, 1, intArrayOf(9, 8, 7, 6), "testes", "token.signature", "world-token", 1, crcs, machine, screenWidth = 1024, screenHeight = 768)
        val (revision, packet) = request.encode(public).afterRevision()
        val decoded = assertIs<LoginDecode.Decoded<WorldLoginRequest>>(WorldLoginRequest.decode(revision.first, revision.second, false, packet, private)).request
        assertEquals(listOf("testes", "token.signature", "world-token"), listOf(decoded.username, decoded.authToken, decoded.worldToken))
        assertEquals(1, decoded.worldId)
        assertEquals(1024 to 768, decoded.screenWidth to decoded.screenHeight)
        assertEquals(machine, decoded.machine)
        assertContentEquals(crcs, decoded.indexCrcs)
    }

    @Test
    fun `the reconnect flag the client writes after its revision marks a game login as a reconnect`() {
        val machine = MachineInformation(version = WorldLoginRequest.MACHINE_INFO_VERSION, operatingSystem = 3, javaBuild = 26, javaVersion = "26.0.0", ram = 16384, gpuModel = "gpu", dwDirectXVersion = "", cpuType = "cpu", cpuData = "", cpuFeatureFlags = intArrayOf(1, 2, 3))
        val request = WorldLoginRequest(727, 1, intArrayOf(9, 8, 7, 6), "testes", "token.signature", "world-token", 1, crcs, machine, reconnecting = true)
        val (revision, packet) = request.encode(public).afterRevision()
        val decoded = assertIs<LoginDecode.Decoded<WorldLoginRequest>>(WorldLoginRequest.decode(revision.first, revision.second, false, packet, private)).request
        assertTrue(decoded.reconnecting)
        assertEquals("testes", decoded.username)
    }

    @Test
    fun `a block sealed with another key is refused as a bad session`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        val request = LobbyLoginRequest(727, 1, intArrayOf(1, 2, 3, 4), "testes", "pw", "", "", "", crcs)
        val (revision, packet) = request.encode(LoginRsaKey(other.modulus, other.publicExponent)).afterRevision()
        val decoded = LobbyLoginRequest.decode(revision.first, revision.second, packet, private, LOGIN_CRC_COUNT)
        assertEquals(LoginDecode.Rejected(ResponseOpcode.BAD_SESSION_ID), decoded)
    }
}
