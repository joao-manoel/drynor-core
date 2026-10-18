package world.gregs.voidps.cache.definition

import org.drynor.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.INTERFACES
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.fail

/**
 * Encodes every component of every interface in the cache with [InterfaceEncoder], decodes the result
 * straight back with [InterfaceDecoder] and compares every field of the definition.
 *
 * The interface editor authors components in memory and saves them through the encoder, so anything the
 * encoder cannot reproduce is data silently lost on save. The decoder is the authority here: it is what
 * reads the shipped cache, so the encoder is expected to produce a stream the decoder reads back identically.
 *
 * Byte identity against the shipped cache is proven separately by [InterfaceEncoderParityTest], which is
 * what the cache source tree needs; this test is the field level view of the same thing and reports which
 * field went wrong when one does.
 */
class InterfaceEncoderRoundTripTest {

    @Test
    fun `every cache interface component round trips through the encoder`() {
        val cacheDir = File(EnvVars.cachePath)
        if (!cacheDir.isDirectory) {
            println("Skipping interface round trip; no cache at ${cacheDir.absolutePath}")
            return
        }
        val cache = Cache.get()
        val decoder = InterfaceDecoder()
        val encoder = InterfaceEncoder()
        val size = decoder.size(cache) + 1
        val definitions = decoder.create(size)
        for (id in 0 until size) {
            decoder.load(definitions, cache, id)
        }

        var interfaces = 0
        var components = 0
        var decodeFailures = 0
        var fieldMismatches = 0
        var byteDifferences = 0
        val failureExamples = StringBuilder()
        val byteExamples = StringBuilder()
        val mismatchesByField = LinkedHashMap<String, Int>()
        val mismatchExamples = LinkedHashMap<String, String>()

        for (definition in definitions) {
            val original = definition.components ?: continue
            interfaces++
            for (component in original) {
                components++
                val writer = BufferWriter(BUFFER_CAPACITY)
                with(encoder) { writer.encode(component) }
                // Round tripping through our own decoder only proves self consistency; comparing the
                // bytes against the shipped cache proves the client reads the saved component the same
                // way it read the original.
                val cached = cache.data(INTERFACES, definition.id, component.id and 0xffff)
                if (cached != null && !cached.contentEquals(writer.toArray())) {
                    byteDifferences++
                    if (byteDifferences <= EXAMPLE_LIMIT) {
                        byteExamples.append("\n  ${name(component)} cache ${cached.size} bytes, encoded ${writer.position()} bytes")
                    }
                }
                val decoded = InterfaceComponentDefinition(id = component.id)
                try {
                    with(decoder) { decoded.read(BufferReader(writer.toArray())) }
                } catch (e: Throwable) {
                    decodeFailures++
                    if (decodeFailures <= EXAMPLE_LIMIT) {
                        failureExamples.append("\n  ${name(component)} ${e::class.simpleName}: ${e.message}")
                    }
                    continue
                }
                var differed = false
                for (field in FIELDS) {
                    val expected = field.get(component)
                    val actual = field.get(decoded)
                    if (same(expected, actual)) {
                        continue
                    }
                    differed = true
                    mismatchesByField[field.name] = (mismatchesByField[field.name] ?: 0) + 1
                    mismatchExamples.putIfAbsent(field.name, "${name(component)} expected=${show(expected)} actual=${show(actual)}")
                }
                if (differed) {
                    fieldMismatches++
                }
            }
        }

        println("Interface round trip: $interfaces interfaces / $components components")
        println("  decode failures: $decodeFailures")
        println("  components with field differences: $fieldMismatches")
        println("  components differing from the cache bytes: $byteDifferences")
        for ((field, count) in mismatchesByField) {
            println("    $field x$count e.g. ${mismatchExamples[field]}")
        }

        if (decodeFailures != 0 || fieldMismatches != 0 || byteDifferences != 0) {
            val message = StringBuilder("Interface encoder does not round trip: $decodeFailures decode failures, $fieldMismatches components with field differences, $byteDifferences components differing from the cache bytes")
            message.append(failureExamples)
            message.append(byteExamples)
            for ((field, count) in mismatchesByField) {
                message.append("\n  $field x$count e.g. ${mismatchExamples[field]}")
            }
            fail(message.toString())
        }
    }

    private fun name(component: InterfaceComponentDefinition) = "interface ${component.id shr 16} component ${component.id and 0xffff}"

    private fun show(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        is Array<*> -> value.contentToString()
        else -> value.toString()
    }

    private fun same(expected: Any?, actual: Any?): Boolean = when {
        expected is IntArray && actual is IntArray -> expected.contentEquals(actual)
        expected is ByteArray && actual is ByteArray -> expected.contentEquals(actual)
        expected is Array<*> && actual is Array<*> -> expected.contentDeepEquals(actual)
        else -> expected == actual
    }

    companion object {
        /** Largest shipped component encodes to a few kb; give the writer plenty of headroom. */
        private const val BUFFER_CAPACITY = 64 * 1024
        private const val EXAMPLE_LIMIT = 5

        private val FIELDS: List<Field> = InterfaceComponentDefinition::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
    }
}
