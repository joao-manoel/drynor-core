package world.gregs.voidps.buffer

import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import io.ktor.utils.io.readByteArray
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round trips every copy of the RS string primitives through [Cp1252].
 */
class JagStringTest {

    @Test
    fun `writer and buffer reader round trip`() {
        for (value in VALUES) {
            val writer = BufferWriter(64)
            writer.writeString(value)
            val bytes = writer.toArray()
            assertEquals(value.length + 1, bytes.size, "one byte per character plus terminator: $value")
            assertEquals(expected(value), BufferReader(bytes).readString(), value)
        }
    }

    @Test
    fun `writer prefixed string round trips`() {
        for (value in VALUES) {
            val writer = BufferWriter(64)
            writer.writePrefixedString(value)
            val bytes = writer.toArray()
            assertEquals(value.length + 2, bytes.size, "leading zero, one byte per character, terminator: $value")
            assertEquals(0, bytes[0].toInt())
            val reader = BufferReader(bytes)
            reader.readByte()
            assertEquals(expected(value), reader.readString(), value)
        }
    }

    @Test
    fun `packet builder and source round trip`() {
        for (value in VALUES) {
            val buffer = Buffer()
            buffer.writeRSString(value)
            assertEquals(expected(value), buffer.readRSString(), value)
        }
    }

    @Test
    fun `packet builder and source round trip a jag string`() {
        for (value in VALUES) {
            val buffer = Buffer()
            buffer.writeJagString(value)
            assertEquals(expected(value), buffer.readJagString(), value)
        }
    }

    @Test
    fun `byte channel round trips`() = runBlocking {
        for (value in VALUES) {
            val channel = ByteChannel()
            channel.writeRSString(value)
            channel.flush()
            assertEquals(expected(value), channel.readRSString(), value)
        }
    }

    @Test
    fun `byte channel round trips a jag string`() = runBlocking {
        for (value in VALUES) {
            val channel = ByteChannel()
            channel.writeJagString(value)
            channel.flush()
            assertEquals(expected(value), channel.readJagString(), value)
        }
    }

    @Test
    fun `every writer produces the same bytes`() = runBlocking {
        for (value in VALUES) {
            val writer = BufferWriter(64)
            writer.writeString(value)
            val expected = writer.toArray()

            val buffer = Buffer()
            buffer.writeRSString(value)
            assertContentEquals(expected, buffer.readByteArray(), value)

            val channel = ByteChannel()
            channel.writeRSString(value)
            channel.flush()
            assertContentEquals(expected, channel.readByteArray(expected.size), value)
        }
    }

    @Test
    fun `ascii is byte identical to the previous utf-8 behaviour`() {
        val writer = BufferWriter(128)
        writer.writeString(ASCII)
        assertContentEquals(ASCII.toByteArray(Charsets.UTF_8) + 0, writer.toArray())
    }

    private fun expected(value: String) = String(CharArray(value.length) { Cp1252.decode(Cp1252.encode(value[it])) })

    private companion object {
        const val ASCII = "Hello, world! 0123456789 ~!@#$%^&*()_+-=[]{}|;':\",./<>?"

        val VALUES = listOf(
            "",
            ASCII,
            "Zezima",
            "£10 for a rune scimmy",
            "smart quotes: ‘hi’ “there”",
            "€100",
            "Motörhead - ÿ - Ÿ - š",
            "unmappable: 中 becomes ?"
        )
    }
}
