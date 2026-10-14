package org.darkan.core.net

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyProtocolTest {
    private fun read(bytes: ByteArray) = runBlocking { ProxyProtocol.readSource(ByteReadChannel(bytes)) }

    @Test
    fun `a v1 header yields the source address and leaves the payload unread`() {
        val channel = ByteReadChannel("PROXY TCP4 15.218.122.149 10.69.69.22 51234 43593\r\n".toByteArray() + byteArrayOf(14))
        runBlocking {
            assertEquals("15.218.122.149", ProxyProtocol.readSource(channel))
            assertEquals(14, channel.readByte().toInt())
        }
    }

    @Test
    fun `a v1 header without its line ending or with a hostname is refused`() {
        assertNull(read("PROXY TCP4 15.218.122.149 10.69.69.22 51234 43593\n".toByteArray()))
        assertNull(read("PROXY TCP4 example.com 10.69.69.22 51234 43593\r\n".toByteArray()))
        assertNull(read("PROXY UNKNOWN\r\n".toByteArray()))
    }

    @Test
    fun `a v2 proxy command yields the ipv4 source`() {
        val signature = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A)
        val addresses = byteArrayOf(15, 218.toByte(), 122, 149.toByte(), 10, 69, 69, 22, 0x13, 0x88.toByte(), 0xAA.toByte(), 0x29)
        assertEquals("15.218.122.149", read(signature + byteArrayOf(0x21, 0x11, 0, 12) + addresses))
    }

    @Test
    fun `anything that is not a header is refused`() {
        assertNull(read(byteArrayOf(14, 0, 0, 0)))
    }

    @Test
    fun `allow lists match exact addresses and cidr ranges only`() {
        val list = IpAllowList("15.218.122.149, 10.69.69.0/24")
        assertTrue(list.contains("15.218.122.149"))
        assertTrue(list.contains("10.69.69.119"))
        assertFalse(list.contains("10.69.70.1"))
        assertFalse(list.contains("51.81.202.151"))
        assertFalse(list.contains("localhost"))
        assertFalse(IpAllowList("").contains("127.0.0.1"))
    }
}
