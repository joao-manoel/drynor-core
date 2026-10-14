package world.gregs.voidps.cache.definition

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Encodes every component of every interface in the cache with [InterfaceEncoder] and compares the
 * result to the shipped bytes.
 *
 * [InterfaceEncoderRoundTripTest] proves the encoder's output decodes back to the same fields; this
 * proves it is the same file. That is what the cache source tree's `DefinitionJsonCodec` needs
 * before it can store interfaces as editable JSON with no pristine copy beside them.
 */
class InterfaceEncoderParityTest {

    @Test
    fun `every cache interface component re-encodes byte for byte`() {
        val cacheDir = File(EnvVars.cachePath)
        if (!cacheDir.isDirectory) {
            println("Skipping interface encoder parity; no cache at ${cacheDir.absolutePath}")
            return
        }
        val cache = Cache.get()
        val decoder = InterfaceDecoder()
        val encoder = InterfaceEncoder()
        var interfaces = 0
        var components = 0
        var identical = 0
        var differing = 0
        val byType = sortedMapOf<Int, Int>()
        val examples = StringBuilder()

        for (archive in cache.archiveIds(Index.INTERFACES)) {
            var counted = false
            for (file in cache.files(Index.INTERFACES, archive)) {
                val data = cache.data(Index.INTERFACES, archive, file) ?: continue
                if (!counted) {
                    interfaces++
                    counted = true
                }
                components++
                val definition = InterfaceComponentDefinition(id = InterfaceDefinition.pack(archive, file))
                with(decoder) { definition.read(BufferReader(data)) }
                val writer = BufferWriter(CAPACITY)
                with(encoder) { writer.encode(definition) }
                val encoded = writer.toArray()
                if (encoded.contentEquals(data)) {
                    identical++
                    continue
                }
                differing++
                byType[definition.type] = (byType[definition.type] ?: 0) + 1
                if (differing <= EXAMPLE_LIMIT) {
                    val offset = (0 until minOf(encoded.size, data.size)).firstOrNull { encoded[it] != data[it] }
                        ?: minOf(encoded.size, data.size)
                    examples.append("\n    interface $archive component $file (type ${definition.type}): ${data.size} cache bytes, ${encoded.size} encoded, first difference at $offset")
                    examples.append("\n      cache   ${hex(data, offset)}")
                    examples.append("\n      encoded ${hex(encoded, offset)}")
                }
            }
        }

        println("Interface encoder parity: $interfaces interfaces / $components components")
        println("  identical: $identical")
        println("  differing: $differing")
        println("  differing by component type: $byType")
        println(examples)

        if (differing != 0) {
            fail("Interface encoder is not byte exact: $differing of $components components differ$examples")
        }
    }

    /**
     * The bytes around [offset], so a difference is readable without dumping a whole component.
     */
    private fun hex(data: ByteArray, offset: Int): String {
        val from = maxOf(0, offset - CONTEXT)
        val to = minOf(data.size, offset + CONTEXT)
        return "[$from..${to - 1}] " + (from until to).joinToString(" ") { (data[it].toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        private const val CAPACITY = 64 * 1024
        private const val EXAMPLE_LIMIT = 8
        private const val CONTEXT = 24
    }
}
