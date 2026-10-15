package org.drynor.core.net.js5

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import org.drynor.core.net.RequestOpcode
import org.drynor.core.net.ResponseOpcode
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.write5
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.buffer.writeMedium
import world.gregs.voidps.buffer.writeRSString
import world.gregs.voidps.buffer.writeShort
import world.gregs.voidps.cache.secure.CRC
import java.io.Closeable
import java.io.IOException

class Js5Exception(val response: Int) : IOException("JS5 handshake refused with response $response")

class Js5Client private constructor(
    private val selector: SelectorManager,
    private val socket: Socket,
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
) : Closeable {
    private val lock = Mutex()

    suspend fun fetch(index: Int, archive: Int): ByteArray = fetchAll(listOf(index to archive)).getValue(index to archive)

    suspend fun fetchAll(requests: List<Pair<Int, Int>>, window: Int = DEFAULT_WINDOW, onReceived: (Int, Int, ByteArray) -> Unit = { _, _, _ -> }): Map<Pair<Int, Int>, ByteArray> = lock.withLock {
        val results = HashMap<Pair<Int, Int>, ByteArray>(requests.size)
        var sent = 0
        var received = 0
        while (received < requests.size) {
            while (sent < requests.size && sent - received < window) {
                val (index, archive) = requests[sent++]
                output.writeByte(RequestOpcode.JS5_FILE_HIGH_PRIORITY)
                output.write5((index.toLong() shl 32) or (archive.toLong() and 0xffffffffL))
            }
            output.flush()
            val (key, container) = readReply()
            results[key] = container
            onReceived(key.first, key.second, container)
            received++
        }
        results
    }

    suspend fun indexCrcs(): IntArray = versionTable().crcs

    suspend fun versionTable(): Js5VersionTable = Js5VersionTable.decode(fetch(VERSION_TABLE_INDEX, VERSION_TABLE_ARCHIVE))

    private suspend fun readReply(): Pair<Pair<Int, Int>, ByteArray> {
        val index = input.readUByte()
        val archive = input.readInt()
        val compression = input.readUByte() and PREFETCH_FLAG.inv()
        val length = input.readInt()
        val payloadLength = length + if (compression != 0) DECOMPRESSED_LENGTH_SIZE else 0
        val container = ByteArray(CONTAINER_HEADER_SIZE + payloadLength)
        container[0] = compression.toByte()
        container[1] = (length shr 24).toByte()
        container[2] = (length shr 16).toByte()
        container[3] = (length shr 8).toByte()
        container[4] = length.toByte()
        var position = REPLY_HEADER_SIZE
        for (offset in 0 until payloadLength) {
            if (position % BLOCK_SIZE == 0) {
                input.readByte()
                position++
            }
            container[CONTAINER_HEADER_SIZE + offset] = input.readByte()
            position++
        }
        return (index to archive) to container
    }

    override fun close() {
        socket.close()
        selector.close()
    }

    companion object {
        const val VERSION_TABLE_INDEX = 255
        const val VERSION_TABLE_ARCHIVE = 255
        private const val DEFAULT_WINDOW = 20
        private const val PREFETCH_FLAG = 0x80
        private const val REPLY_HEADER_SIZE = 10
        private const val CONTAINER_HEADER_SIZE = 5
        private const val DECOMPRESSED_LENGTH_SIZE = 4
        private const val BLOCK_SIZE = 512
        private const val SESSION_ID = 3
        private const val PREFETCH_KEY_COUNT = 27

        suspend fun connect(host: String, port: Int, revision: Int, subRevision: Int, token: String): Js5Client {
            val selector = SelectorManager(Dispatchers.IO)
            val socket = try {
                aSocket(selector).tcp().connect(host, port)
            } catch (e: Exception) {
                selector.close()
                throw e
            }
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            try {
                val handshake = ByteChannel()
                handshake.writeInt(revision)
                handshake.writeInt(subRevision)
                handshake.writeRSString(token)
                handshake.flushAndClose()
                val body = handshake.readRemaining().readByteArray()
                output.writeByte(RequestOpcode.JS5_INIT)
                output.writeByte(body.size)
                output.writeFully(body)
                output.flush()
                val response = input.readUByte()
                if (response != ResponseOpcode.JS5_SYNC) throw Js5Exception(response)
                repeat(PREFETCH_KEY_COUNT) { input.readInt() }
                output.writeByte(RequestOpcode.ACKNOWLEDGE)
                output.writeMedium(SESSION_ID)
                output.writeShort(0)
                output.writeByte(RequestOpcode.STATUS_LOGGED_OUT)
                output.write5(0)
                output.flush()
            } catch (e: Exception) {
                socket.close()
                selector.close()
                throw e
            }
            return Js5Client(selector, socket, input, output)
        }

        fun crc(container: ByteArray): Int = CRC.calculate(container, 0, container.size)
    }
}

class Js5VersionTable(val crcs: IntArray, val revisions: IntArray) {
    companion object {
        private const val CONTAINER_HEADER_SIZE = 5
        private const val ENTRY_SIZE = 72

        fun decode(container: ByteArray): Js5VersionTable {
            val count = container[CONTAINER_HEADER_SIZE].toInt() and 0xff
            val crcs = IntArray(count)
            val revisions = IntArray(count)
            for (index in 0 until count) {
                val position = CONTAINER_HEADER_SIZE + 1 + index * ENTRY_SIZE
                crcs[index] = int(container, position)
                revisions[index] = int(container, position + 4)
            }
            return Js5VersionTable(crcs, revisions)
        }

        private fun int(data: ByteArray, offset: Int) =
            (data[offset].toInt() and 0xff shl 24) or (data[offset + 1].toInt() and 0xff shl 16) or
                (data[offset + 2].toInt() and 0xff shl 8) or (data[offset + 3].toInt() and 0xff)
    }
}
