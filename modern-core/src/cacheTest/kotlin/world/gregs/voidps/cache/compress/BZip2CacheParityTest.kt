package world.gregs.voidps.cache.compress

import org.drynor.core.EnvVars
import world.gregs.voidps.cache.Cache
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Every bzip2 container in the packed cache, recompressed from what it decompresses to and held
 * byte-identical to the shipped bytes. Skipped when there is no cache to read.
 *
 * Two of the 3,796 bzip2 containers in the shipped 727 cache - reference tables 2 and 9 - are not
 * libbzip2 output at all but Apache commons-compress output, byte for byte, from whatever tool
 * last rewrote those two tables. The system `bzip2` disagrees with them at every level too,
 * while agreeing with this encoder byte for byte. They are counted apart rather than excused,
 * and the test reconfirms the classification whenever the `bzip2` binary is there to ask.
 */
class BZip2CacheParityTest {

    @Test
    fun `every bzip2 container in the cache recompresses byte-identically`() {
        val cache = cache() ?: return
        val containers = mutableListOf<Container>()
        for (index in 0..36) {
            for (archive in cache.archives(index)) {
                containers.add(Container(index, archive))
            }
        }
        for (index in cache.indices()) {
            containers.add(Container(255, index))
        }

        val matched = IntArray(256)
        val mismatched = IntArray(256)
        val bzip2 = IntArray(256)
        val other = AtomicLong()
        val bytes = AtomicLong()
        val problems = Collections.synchronizedList(mutableListOf<Result>())
        val decompressor = ThreadLocal.withInitial { BZIP2Compressor() }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val start = System.nanoTime()

        try {
            val tasks = containers.map { container ->
                Callable {
                    val data = cache.sector(container.index, container.archive) ?: return@Callable null
                    if (data.size < 9 || data[0].toInt() != BZIP2) {
                        other.incrementAndGet()
                        return@Callable null
                    }
                    val compressedSize = int(data, 1)
                    val decompressedSize = int(data, 5)
                    if (compressedSize < 0 || 9 + compressedSize > data.size) {
                        return@Callable Result(container, "container truncated: $compressedSize bytes at offset 9 of ${data.size}")
                    }
                    val decompressed = ByteArray(decompressedSize)
                    try {
                        decompressor.get().decompress(decompressed, decompressedSize, data, 9)
                    } catch (e: Exception) {
                        return@Callable Result(container, "decompression failed: $e")
                    }
                    val encoded = BZip2Encoder.compress(decompressed)
                    bytes.addAndGet(decompressedSize.toLong())
                    val expected = data.copyOfRange(9, 9 + compressedSize)
                    if (encoded.contentEquals(expected)) {
                        Result(container, null)
                    } else {
                        val at = expected.indices.firstOrNull { it >= encoded.size || expected[it] != encoded[it] }
                        Result(
                            container,
                            "re-encoded to ${encoded.size} bytes, cache holds ${expected.size}" +
                                ", first difference at byte $at ($decompressedSize bytes decompressed)",
                            decompressed,
                            encoded,
                        )
                    }
                }
            }
            for (future in pool.invokeAll(tasks)) {
                val result = future.get() ?: continue
                bzip2[result.container.index]++
                if (result.problem == null) {
                    matched[result.container.index]++
                } else {
                    mismatched[result.container.index]++
                    problems.add(result)
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }

        // Sort out the containers that are not libbzip2 output from real encoder failures.
        val haveBinary = SystemBzip2.available
        val foreign = mutableListOf<Result>()
        val failures = mutableListOf<String>()
        for (result in problems.sortedWith(compareBy({ it.container.index }, { it.container.archive }))) {
            val decompressed = result.decompressed
            val encoded = result.encoded
            val notLibbzip2 = when {
                decompressed == null || encoded == null -> false
                haveBinary -> {
                    val system = SystemBzip2.compress(decompressed)
                    system.copyOfRange(4, system.size).contentEquals(encoded)
                }
                else -> result.container in KNOWN_FOREIGN
            }
            if (notLibbzip2) {
                foreign.add(result)
            } else {
                failures.add("  ${result.container}: ${result.problem}")
            }
        }

        val totalBzip2 = bzip2.sum()
        val totalMatched = matched.sum()
        println("Recompressed ${bytes.get()} bytes of bzip2 source in ${(System.nanoTime() - start) / 1_000_000} ms")
        println("bzip2 containers: $totalBzip2 ($totalMatched byte-identical, ${mismatched.sum()} not)")
        println("containers using another compression: ${other.get()}")
        for (index in bzip2.indices) {
            if (bzip2[index] != 0) {
                val label = if (index == 255) "reference tables" else "index $index"
                println("  $label: ${bzip2[index]} bzip2, ${matched[index]} identical, ${mismatched[index]} not")
            }
        }
        for (result in foreign) {
            println("  not libbzip2 output, ${if (haveBinary) "confirmed against ${SystemBzip2.BINARY}" else "known"}: ${result.container}: ${result.problem}")
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $totalBzip2 bzip2 containers do not recompress identically\n${failures.take(EXAMPLE_LIMIT).joinToString("\n")}")
        }
        assertEquals(EXPECTED_BZIP2_CONTAINERS, totalBzip2, "bzip2 containers found in the cache")
        assertEquals(EXPECTED_BZIP2_CONTAINERS - KNOWN_FOREIGN.size, totalMatched, "byte-identical bzip2 containers")
        assertEquals(KNOWN_FOREIGN, foreign.map { it.container }.toSet(), "containers that are not libbzip2 output")
    }

    private data class Container(val index: Int, val archive: Int) {
        override fun toString() = if (index == 255) "reference table $archive" else "index $index archive $archive"
    }

    private class Result(
        val container: Container,
        val problem: String?,
        val decompressed: ByteArray? = null,
        val encoded: ByteArray? = null,
    )

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping the whole-cache recompression; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }

    private fun int(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private companion object {
        private const val BZIP2 = 1

        /**
         * Every container in the shipped 727 cache whose compression byte is 1: 3,783 archives
         * across the indexes plus 13 of the 37 reference tables.
         */
        private const val EXPECTED_BZIP2_CONTAINERS = 3796

        /**
         * Reference tables 2 and 9, the two bzip2 containers in the shipped cache that libbzip2
         * cannot reproduce at any level; they are byte-identical to what Apache commons-compress
         * produces instead. Used only when there is no `bzip2` binary to confirm it.
         */
        private val KNOWN_FOREIGN = setOf(Container(255, 2), Container(255, 9))
        private const val EXAMPLE_LIMIT = 10
}
}
