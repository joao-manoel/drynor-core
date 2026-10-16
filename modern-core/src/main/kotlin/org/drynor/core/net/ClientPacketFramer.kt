package org.drynor.core.net

import org.drynor.core.net.prot.ProtSize

/** Revision 727 client framing: one ISAAC byte, optional unsigned length, then an exact body. */
class ClientPacketFramer(
    private val isaac: Isaac,
    private val packetSize: (Int) -> ProtSize?,
    private val maxBodySize: Int = 65535,
    private val incompleteTimeoutNanos: Long = 30_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private var opcode = -1
    private var lengthBytes = 0
    private var length = 0
    private var body: ByteArray? = null
    private var position = 0
    private var started = 0L
    private var closed = false

    init {
        require(maxBodySize in 0..65535)
        require(incompleteTimeoutNanos > 0)
    }

    /** The caller owns dispatch/backpressure. Any decode or dispatch failure is terminal. */
    @Synchronized
    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset, accept: (Int, ByteArray) -> Unit) {
        check(!closed) { "Packet framer is closed" }
        require(offset >= 0 && count >= 0 && offset <= data.size - count)
        try {
            var cursor = offset
            val end = offset + count
            checkTimeout()
            while (cursor < end) {
                check(!closed) { "Packet framer is closed" }
                checkTimeout()
                if (opcode == -1) {
                    opcode = ((data[cursor++].toInt() and 255) - isaac.nextInt()) and 255
                    started = nanoTime()
                    length = 0
                    when (val size = packetSize(opcode) ?: error("Unknown client opcode $opcode")) {
                        is ProtSize.Fixed -> length = size.length
                        ProtSize.VarByte -> lengthBytes = 1
                        ProtSize.VarShort -> lengthBytes = 2
                    }
                }
                while (lengthBytes > 0 && cursor < end) {
                    length = (length shl 8) or (data[cursor++].toInt() and 255)
                    lengthBytes--
                }
                if (lengthBytes > 0) break
                require(length in 0..maxBodySize) { "Client packet body exceeds limit" }
                val target = body ?: ByteArray(length).also { body = it }
                val available = minOf(end - cursor, length - position)
                data.copyInto(target, position, cursor, cursor + available)
                position += available
                cursor += available
                if (position != length) break
                val completeOpcode = opcode
                opcode = -1
                body = null
                position = 0
                accept(completeOpcode, target)
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun checkTimeout() {
        check(opcode == -1 || nanoTime() - started < incompleteTimeoutNanos) { "Incomplete client packet timed out" }
    }

    @Synchronized
    override fun close() {
        closed = true
        body = null
        opcode = -1
        position = 0
    }
}
