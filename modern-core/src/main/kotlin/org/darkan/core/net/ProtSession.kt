package org.darkan.core.net

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Source
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logSevere
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ProtSize
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.buffer.writeSmart
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean

abstract class ProtSession<I : Any, O : Any>(
    private val write: ByteWriteChannel,
    val isaacIn: Isaac,
    private val isaacOut: Isaac?,
    val ip: String,
    val codec: Codec,
    inboundCapacity: Int,
    private val outboundCapacity: Int,
    private val inboundIdleTimeoutMs: Long? = null,
) {
    enum class State { CONNECTED, LOST_CONNECTION, DISCONNECTED }

    private sealed interface Outbound {
        data class Packet(val prot: Any, val noIsaac: Boolean) : Outbound
        data class Flush(val completion: CompletableDeferred<Unit>?) : Outbound
    }

    val readChannel = Channel<I>(capacity = inboundCapacity)

    private val outbound = Channel<Outbound>(capacity = outboundCapacity)
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("session-writer-$ip"))
    private val writerJob: Job = writerScope.launch { writeLoop() }

    private val hasPendingWrites = AtomicBoolean(false)

    @Volatile
    var disconnected: Boolean = false
    private var disconnect: (() -> Unit)? = null
    @Volatile
    private var state: State = State.CONNECTED
    @Volatile
    private var timedOut = false

    val connectionDropped: Boolean get() = state == State.LOST_CONNECTION && timedOut

    protected abstract fun inboundCodec(opcode: Int): Codec.ProtCodec<I>?

    protected abstract fun outboundCodec(prot: O): Codec.ProtCodec<O>?

    protected open fun onWrite(prot: O, opcode: Int) {}

    protected open suspend fun decodeInbound(prot: Codec.ProtCodec<I>, opcode: Int, packet: Source): I? = codec.decode(prot, opcode, packet)

    fun close() {
        disconnected = true
        state = State.DISCONNECTED
        // Close the queue rather than killing the writer so anything already queued (the logout
        // packet, typically) still reaches the other end before the writer coroutine exits.
        outbound.close()
        disconnect?.invoke()
    }

    fun onDisconnected(block: () -> Unit) {
        disconnect = block
    }

    suspend fun disconnect(reason: Int) {
        if (disconnected) return
        disconnected = true
        // The response code bypasses the packet queue, so the writer has to be off the socket first.
        if (stopWriter())
            write.writeByte(reason)
        finishDisconnect()
    }

    suspend fun disconnect() {
        if (disconnected) return
        disconnected = true
        stopWriter()
        finishDisconnect()
    }

    fun exit() {
        if (state == State.CONNECTED)
            state = State.LOST_CONNECTION
    }

    @JvmOverloads
    fun queuePacket(packet: O, noIsaac: Boolean = false) {
        enqueue(Outbound.Packet(packet, noIsaac))
    }

    open suspend fun send(prot: O, noIsaac: Boolean = false) {
        enqueue(Outbound.Packet(prot, noIsaac))
    }

    fun requestFlush() {
        if (!hasPendingWrites.compareAndSet(true, false)) return
        enqueue(Outbound.Flush(null))
    }

    open suspend fun flush() {
        if (disconnected) return
        hasPendingWrites.set(false)
        val completion = CompletableDeferred<Unit>()
        if (!enqueue(Outbound.Flush(completion))) return
        if (withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { completion.await() } == null)
            logTrace("Timed out waiting on flush for $ip")
    }

    fun pollPacket(): I? = readChannel.tryReceive().getOrNull()

    suspend fun readPackets(input: ByteReadChannel) {
        try {
            while (!disconnected) {
                val opcode = idleBounded { readOpcode(input) } ?: return logIdleTimeout()
                val prot = inboundCodec(opcode)
                if (prot == null) {
                    logSevere("Missing inbound prot for opcode $opcode")
                    return
                }
                val size = when (prot.size) {
                    is ProtSize.Fixed -> prot.size.length
                    ProtSize.VarByte -> idleBounded { input.readUByte() } ?: return logIdleTimeout()
                    ProtSize.VarShort -> idleBounded { input.readUShort() } ?: return logIdleTimeout()
                }
                val packet = idleBounded { input.readPacket(size) } ?: return logIdleTimeout()

                val decoded = try {
                    decodeInbound(prot, opcode, packet)
                } catch (e: Exception) {
                    logSevere("Decoder exception for opcode $opcode (${prot.protClass.simpleName}, size=$size): ${e::class.simpleName}: ${e.message}")
                    continue
                }
                if (decoded == null) {
                    logSevere("Failed to create packet instance for opcode $opcode")
                    continue
                }
                logTrace("Decoded packet: ${decoded::class.simpleName} from opcode $opcode (size=$size)")
                readChannel.send(decoded)
            }
        } catch (e: Exception) {
            if (isExpectedDisconnect(e)) {
                logTrace("Session read ended: ${e::class.simpleName}: ${e.message}")
            } else {
                throw e
            }
        } finally {
            if (!disconnected) {
                exit()
                disconnected = true
            }
            outbound.close()
        }
    }

    private suspend fun <T : Any> idleBounded(read: suspend () -> T): T? {
        val timeout = inboundIdleTimeoutMs ?: return read()
        return withTimeoutOrNull(timeout) { read() }
    }

    private fun logIdleTimeout() {
        timedOut = true
        logInfo("Session for $ip received nothing for ${inboundIdleTimeoutMs}ms, treating the connection as lost")
    }

    private suspend fun readOpcode(input: ByteReadChannel): Int {
        val first = (input.readUByte() - isaacIn.nextInt()) and 0xff
        if (first < LARGE_OPCODE_FLAG) return first
        return ((first - LARGE_OPCODE_FLAG) shl 8) + ((input.readUByte() - isaacIn.nextInt()) and 0xff)
    }

    private fun enqueue(item: Outbound): Boolean {
        if (disconnected) return false
        val result = outbound.trySend(item)
        if (result.isSuccess) {
            if (item is Outbound.Packet) hasPendingWrites.set(true)
            return true
        }
        if (result.isClosed) return false
        // Dropping individual packets would desync the peer and an unbounded queue is a memory/DoS
        // hazard, so the session goes instead - explicitly and loudly.
        logWarn("Outbound queue overflow ($outboundCapacity packets) for $ip, dropping session")
        dropSlowPeer()
        return false
    }

    private suspend fun writeLoop() {
        try {
            for (item in outbound) {
                when (item) {
                    is Outbound.Packet -> {
                        @Suppress("UNCHECKED_CAST")
                        writePacket(item.prot as O, item.noIsaac)
                    }
                    is Outbound.Flush -> {
                        write.flush()
                        item.completion?.complete(Unit)
                    }
                }
            }
            write.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isExpectedDisconnect(e))
                logTrace("Session write ended: ${e::class.simpleName}: ${e.message}")
            else
                logWarn("Session write error:", e)
            exit()
            disconnected = true
        } finally {
            releaseFlushWaiters()
        }
    }

    private suspend fun writePacket(prot: O, noIsaac: Boolean) {
        val encoder = outboundCodec(prot)
        if (encoder == null) {
            logWarn("Missing outbound encoder: ${prot::class}")
            return
        }
        val opcode = encoder.opcodeFor(prot)
        onWrite(prot, opcode)

        when (encoder.size) {
            is ProtSize.Fixed -> {
                writeOpcode(opcode, if (noIsaac) null else isaacOut)
                encoder.encoder?.invoke(prot, write)
            }
            ProtSize.VarByte, ProtSize.VarShort -> {
                val dataChannel = ByteChannel()
                encoder.encoder?.invoke(prot, dataChannel)
                val packetData = dataChannel.toByteReadPacket()
                val packetLength = packetData.remaining

                if (encoder.size == ProtSize.VarByte && packetLength > 255)
                    logWarn("Packet length exceeds VarByte maximum (${packetLength} > 255)")
                else if (encoder.size == ProtSize.VarShort && packetLength > 65535)
                    logWarn("Packet length exceeds VarShort maximum (${packetLength} > 65535)")

                writeOpcode(opcode, if (noIsaac) null else isaacOut)

                if (encoder.size == ProtSize.VarByte)
                    write.writeByte(packetLength.toByte())
                else
                    write.writeShort(packetLength.toShort())

                write.writePacket(packetData)
            }
        }
    }

    private suspend fun stopWriter(): Boolean {
        outbound.close()
        val drained = withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { writerJob.join() } != null
        if (!drained)
            logTrace("Timed out draining outbound packets for $ip")
        shutdownWriter()
        return drained
    }

    private fun shutdownWriter() {
        outbound.close()
        writerScope.cancel()
        releaseFlushWaiters()
    }

    private fun releaseFlushWaiters() {
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            if (item is Outbound.Flush) item.completion?.complete(Unit)
        }
    }

    private fun dropSlowPeer() {
        if (disconnected) return
        exit()
        disconnected = true
        shutdownWriter()
        write.cancel(IOException("Outbound packet queue overflow"))
    }

    private suspend fun finishDisconnect() {
        if (withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { write.flushAndClose() } == null)
            write.cancel(IOException("Timed out closing session for $ip"))
        state = State.DISCONNECTED
        disconnect?.invoke()
    }

    private suspend fun writeOpcode(opcode: Int, cipher: Isaac?) {
        if (opcode < 0) return
        if (cipher != null) {
            if (opcode >= LARGE_OPCODE_FLAG) {
                write.writeByte(((opcode shr 8) + LARGE_OPCODE_FLAG) + cipher.nextInt())
                write.writeByte(opcode + cipher.nextInt())
            } else
                write.writeByte(opcode + cipher.nextInt())
        } else
            write.writeSmart(opcode)
    }

    private suspend fun ByteChannel.toByteReadPacket(): Source {
        flush()
        val packet = ByteReadPacket(ByteArray(availableForRead).also { readFully(it) })
        close()
        return packet
    }

    companion object {
        private const val WRITER_DRAIN_TIMEOUT_MS = 1000L
        private const val LARGE_OPCODE_FLAG = 128

        fun isExpectedDisconnect(e: Throwable): Boolean {
            return e is ClosedByteChannelException
                || e is EOFException
                || e is SocketException
                || e is ClosedChannelException
                || (e is IOException && e.message?.let {
                    it.contains("reset", ignoreCase = true)
                    || it.contains("broken pipe", ignoreCase = true)
                    || it.contains("closed", ignoreCase = true)
                } == true)
                || (e.cause != null && isExpectedDisconnect(e.cause!!))
        }
    }
}
