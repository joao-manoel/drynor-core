package org.darkan.core.net.prot

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun Codec.serverBytes(prot: ServerProt, context: CoroutineContext = EmptyCoroutineContext): ByteArray = runBlocking(context) {
    val channel = ByteChannel()
    encodeServerProt(prot, channel)
    channel.flushAndClose()
    channel.readRemaining().readByteArray()
}

fun Codec.clientBytes(prot: ClientProt, context: CoroutineContext = EmptyCoroutineContext): ByteArray = runBlocking(context) {
    val channel = ByteChannel()
    encodeClientProt(prot, channel)
    channel.flushAndClose()
    channel.readRemaining().readByteArray()
}

fun Codec.decodeServer(prot: ServerProt, bytes: ByteArray, context: CoroutineContext = EmptyCoroutineContext): ServerProt = runBlocking(context) {
    val entry = serverProts[prot::class] ?: error("No codec for ${prot::class.simpleName}")
    val source = Buffer().apply { write(bytes) }
    val decoded = decode(entry, entry.opcode, source) ?: error("Could not decode ${prot::class.simpleName}")
    assertTrue(source.exhausted(), "${prot::class.simpleName} left ${source.size} unread bytes")
    decoded
}

fun Codec.decodeClient(prot: ClientProt, bytes: ByteArray, context: CoroutineContext = EmptyCoroutineContext): ClientProt = runBlocking(context) {
    val entry = clientProts[prot::class] ?: error("No codec for ${prot::class.simpleName}")
    val source = Buffer().apply { write(bytes) }
    val decoded = decode(entry, entry.opcodeFor(prot), source) ?: error("Could not decode ${prot::class.simpleName}")
    assertTrue(source.exhausted(), "${prot::class.simpleName} left ${source.size} unread bytes")
    decoded
}

fun Codec.assertServerRoundTrip(prot: ServerProt, expected: ServerProt = prot, context: CoroutineContext = EmptyCoroutineContext) {
    val bytes = serverBytes(prot, context)
    val size = serverProts.getValue(prot::class).size
    if (size is ProtSize.Fixed) assertEquals(size.length, bytes.size, "${prot::class.simpleName} fixed size")
    assertEquals(expected, decodeServer(prot, bytes, context), "${prot::class.simpleName} round trip")
}

fun Codec.assertClientRoundTrip(prot: ClientProt, expected: ClientProt = prot, context: CoroutineContext = EmptyCoroutineContext) {
    val bytes = clientBytes(prot, context)
    val size = clientProts.getValue(prot::class).size
    if (size is ProtSize.Fixed) assertEquals(size.length, bytes.size, "${prot::class.simpleName} fixed size")
    assertEquals(expected, decodeClient(prot, bytes, context), "${prot::class.simpleName} round trip")
}
