package org.darkan.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.darkan.core.model.ChatMessageType
import org.darkan.core.net.client.ClientSession
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.packet.BlockMinimapState
import org.darkan.core.net.prot.packet.ChatType
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.net.prot.packet.IfSetText
import org.darkan.core.net.prot.packet.OpNpc
import org.darkan.core.net.prot.packet.RunEnergy
import org.darkan.core.net.prot.packet.UpdateStat
import org.darkan.core.net.prot.packet.Walk
import org.darkan.core.net.prot.revision.rev727.register727
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtSessionLoopbackTest {
    private val codec = register727()

    @Test
    fun `a client session and a server session understand each other through isaac`() = runBlocking {
        val seed = intArrayOf(11, 22, 33, 44)
        val toClient = ByteChannel(autoFlush = true)
        val toServer = ByteChannel(autoFlush = true)
        val server = Session(toClient, Isaac(seed.copyOf()), Isaac(IntArray(4) { seed[it] + 50 }), "server", codec)
        val client = ClientSession(toServer, Isaac(IntArray(4) { seed[it] + 50 }), Isaac(seed.copyOf()), "client", codec)
        val serverReader = launch { server.readPackets(toServer) }
        val clientReader = launch { client.readPackets(toClient) }

        val serverProts = listOf<ServerProt>(
            GameMessage(ChatMessageType.GAME, "Welcome"),
            RunEnergy(87),
            UpdateStat(xp = 13_034_431, skillId = 3, level = 99),
            IfSetText(interfaceId = 752, componentId = 12, text = "chatbox"),
            BlockMinimapState(2),
        )
        serverProts.forEach { server.send(it) }
        server.flush()
        val clientProts = listOf<ClientProt>(Walk(3222, 3218, forceRun = true, minimap = false), OpNpc(2, 1234, false), ChatType(1))
        clientProts.forEach { client.send(it) }
        client.flush()

        withTimeout(5_000) {
            assertEquals(serverProts, List(serverProts.size) { client.readChannel.receive() })
            assertEquals(clientProts, List(clientProts.size) { server.readChannel.receive() })
        }
        serverReader.cancel()
        clientReader.cancel()
        server.disconnect()
        client.disconnect()
    }
}
