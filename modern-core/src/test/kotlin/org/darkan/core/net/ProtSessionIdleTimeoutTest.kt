package org.darkan.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.darkan.core.net.client.ClientSession
import org.darkan.core.net.prot.packet.Ping
import org.darkan.core.net.prot.revision.rev727.register727
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtSessionIdleTimeoutTest {
    private val codec = register727()
    private val seed = intArrayOf(5, 6, 7, 8)

    @Test
    fun `a session that receives nothing for the idle timeout is lost`() = runBlocking {
        val session = Session(ByteChannel(autoFlush = true), Isaac(seed.copyOf()), null, "idle", codec, IDLE_TIMEOUT_MS)

        withTimeout(IDLE_TIMEOUT_MS * 10) { session.readPackets(ByteChannel(autoFlush = true)) }

        assertTrue(session.disconnected)
        assertTrue(session.connectionDropped)
    }

    @Test
    fun `inbound keepalives hold the session open past the idle timeout`() = runBlocking {
        val toServer = ByteChannel(autoFlush = true)
        val session = Session(ByteChannel(autoFlush = true), Isaac(seed.copyOf()), null, "pinging", codec, IDLE_TIMEOUT_MS)
        val client = ClientSession(toServer, Isaac(seed.copyOf()), Isaac(seed.copyOf()), "client", codec)
        val reader = async { session.readPackets(toServer) }

        repeat(PINGS) {
            client.send(Ping())
            client.flush()
            delay(IDLE_TIMEOUT_MS / 4)
        }

        assertFalse(session.disconnected)
        assertEquals(PINGS, List(PINGS) { session.readChannel.tryReceive().getOrNull() }.count { it is Ping })
        reader.cancel()
        session.disconnect()
        client.disconnect()
    }

    @Test
    fun `a peer that closes its end has not dropped the connection`() = runBlocking {
        val toServer = ByteChannel(autoFlush = true)
        val session = Session(ByteChannel(autoFlush = true), Isaac(seed.copyOf()), null, "closing", codec, IDLE_TIMEOUT_MS)
        toServer.flushAndClose()

        withTimeout(IDLE_TIMEOUT_MS * 10) { session.readPackets(toServer) }

        assertTrue(session.disconnected)
        assertFalse(session.connectionDropped)
    }

    @Test
    fun `a connection reset by the peer has not dropped the connection`() = runBlocking {
        val toServer = ByteChannel(autoFlush = true)
        val session = Session(ByteChannel(autoFlush = true), Isaac(seed.copyOf()), null, "reset", codec, IDLE_TIMEOUT_MS)
        toServer.cancel(IOException("Connection reset"))

        withTimeout(IDLE_TIMEOUT_MS * 10) { session.readPackets(toServer) }

        assertTrue(session.disconnected)
        assertFalse(session.connectionDropped)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 150L
        const val PINGS = 6
    }
}
