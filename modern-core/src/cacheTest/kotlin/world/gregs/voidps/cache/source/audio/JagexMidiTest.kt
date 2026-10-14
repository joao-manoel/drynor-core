package world.gregs.voidps.cache.source.audio

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.audio.JagexMidi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The measurement `docs/cache-source.md` reports, and the reason indices 6 and 11 are stored as
 * `.mid` files at all: the compact encoding of every one of the 1,662 files in the 727 cache is
 * rebuilt, byte for byte, from the standard MIDI file it expands into.
 *
 * Two conventions were measured rather than deduced - a column's delta is written as the
 * non-negative byte congruent to the difference, and a delta time as the shortest variable-length
 * int - and this is what holds them.
 */
class JagexMidiTest {

    @Test
    fun `every compact MIDI file rebuilds from its standard MIDI file`() {
        val cache = AudioParity.cache() ?: return
        try {
            for (index in intArrayOf(Index.MIDI_SONGS, Index.MIDI_JINGLES)) {
                var files = 0
                var identical = 0
                var failed = 0
                var compact = 0L
                var standard = 0L
                val different = ArrayList<Int>()
                val errors = LinkedHashMap<String, Int>()
                for (archive in cache.archives(index)) {
                    val data = cache.data(index, archive) ?: continue
                    files++
                    compact += data.size
                    try {
                        val midi = JagexMidi.toStandard(data)
                        standard += midi.size
                        if (JagexMidi.toCompact(midi).contentEquals(data)) {
                            identical++
                        } else if (different.size < 12) {
                            different.add(archive)
                        }
                    } catch (e: Exception) {
                        failed++
                        val frame = e.stackTrace.firstOrNull { it.className.contains("JagexMidi") }
                        val key = "${e::class.simpleName}: ${e.message?.take(60)} at $frame"
                        errors[key] = (errors[key] ?: 0) + 1
                        if (different.size < 12) {
                            different.add(archive)
                        }
                    }
                }
                println(
                    "index $index: $files files, $identical identical, $failed threw, " +
                        "compact=$compact standard=$standard different=$different"
                )
                for ((error, count) in errors) {
                    println("   $count x $error")
                }
                assertEquals(0, failed, "no compact MIDI file may fail to expand")
                assertEquals(files, identical, "every compact MIDI file must be rebuilt exactly")
                assertEquals(if (index == Index.MIDI_SONGS) SONGS else JINGLES, files)
            }
        } finally {
            cache.close()
        }
    }

    private companion object {
        private const val SONGS = 1137
        private const val JINGLES = 525
    }
}
