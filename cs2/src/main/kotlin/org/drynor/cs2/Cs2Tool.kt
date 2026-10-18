package org.drynor.cs2

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Loads clientscripts out of cache index 12.
 *
 * Each script is a single-file archive keyed by script id, matching the
 * client's `Resource.CS2.getFile(scriptId, 0)`.
 */
class Cs2Cache(private val cache: Cache) {

    fun scriptIds(): IntArray = cache.archives(Index.CLIENT_SCRIPTS)

    fun raw(scriptId: Int): ByteArray? =
        cache.data(Index.CLIENT_SCRIPTS, scriptId, 0)?.takeIf { it.size > 1 }

    fun load(scriptId: Int): Cs2Script? = raw(scriptId)?.let { Cs2Codec.decode(it) }
}

/** Result of decoding then re-encoding every script in the cache. */
data class RoundTripReport(
    val total: Int,
    val identical: Int,
    val mismatched: List<Int>,
    val failed: List<Pair<Int, String>>,
) {
    val ok: Boolean get() = mismatched.isEmpty() && failed.isEmpty()
}

/**
 * Decodes and re-encodes every clientscript, asserting the bytes come back
 * unchanged. This is the foundation the decompiler round-trip builds on: if the
 * codec is not exact, nothing above it can be.
 */
fun verifyCodecRoundTrip(cache: Cache, log: (String) -> Unit = {}): RoundTripReport {
    val scripts = Cs2Cache(cache)
    val ids = scripts.scriptIds()
    var identical = 0
    val mismatched = ArrayList<Int>()
    val failed = ArrayList<Pair<Int, String>>()

    for (id in ids) {
        val raw = try {
            scripts.raw(id) ?: continue
        } catch (e: Exception) {
            failed.add(id to "read: ${e.message}")
            continue
        }
        try {
            val decoded = Cs2Codec.decode(raw)
            val reencoded = Cs2Codec.encode(decoded)
            if (raw.contentEquals(reencoded)) {
                identical++
            } else {
                mismatched.add(id)
                if (mismatched.size <= 5) {
                    log("script $id: ${raw.size} bytes in, ${reencoded.size} out, " +
                        "first diff at ${firstDifference(raw, reencoded)}")
                }
            }
        } catch (e: Exception) {
            failed.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }
    return RoundTripReport(ids.size, identical, mismatched, failed)
}

private fun firstDifference(a: ByteArray, b: ByteArray): Int {
    val limit = minOf(a.size, b.size)
    for (i in 0 until limit) if (a[i] != b[i]) return i
    return limit
}
