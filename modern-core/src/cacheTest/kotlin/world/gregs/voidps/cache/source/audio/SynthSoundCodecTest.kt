package world.gregs.voidps.cache.source.audio

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder
import world.gregs.voidps.cache.source.codec.AudioCodecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every one of the 727 cache's 10,084 synth sounds, twice over.
 *
 * First the encoder alone - `encode(decode(bytes)) == bytes` for every archive, which is the bar a
 * [world.gregs.voidps.cache.source.codec.DefinitionJsonCodec] needs before a type may be stored as
 * JSON at all - and then the whole trip the tree makes, `encode(read(write(decode(bytes))))`, so a
 * field the JSON mapper cannot spell shows up here rather than as a sidecar in a real tree.
 *
 * Skipped when there is no cache to test against.
 */
class SynthSoundCodecTest {

    @Test
    fun `every synth sound re-encodes to its own bytes`() {
        val cache = AudioParity.cache() ?: return
        try {
            val decoder = SynthSoundDecoder()
            val encoder = SynthSoundEncoder()
            var files = 0
            var identical = 0
            val different = ArrayList<Int>()
            for (archive in cache.archives(Index.SYNTH_SOUNDS)) {
                val data = cache.data(Index.SYNTH_SOUNDS, archive) ?: continue
                files++
                val definition = SynthSoundDefinition(archive)
                decoder.read(definition, BufferReader(data))
                val writer = BufferWriter(data.size + PADDING)
                with(encoder) { writer.encode(definition) }
                if (writer.toArray().contentEquals(data)) {
                    identical++
                } else if (different.size < LIMIT) {
                    different.add(archive)
                }
            }
            println("synth sound encoder: $files files, $identical identical, different=$different")
            assertEquals(SOUNDS, files, "the 727 cache has $SOUNDS synth sounds")
            assertEquals(files, identical, "every synth sound must re-encode to its own bytes")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every synth sound survives its JSON file`() {
        val cache = AudioParity.cache() ?: return
        val directory = AudioParity.scratch("synth-sounds")
        try {
            val report = AudioParity.roundTrip(cache, Index.SYNTH_SOUNDS, AudioCodecs.synthSounds, directory)
            println(report)
            assertEquals(SOUNDS, report.archives)
            assertEquals(report.archives, report.identical, "every archive must pack back identically")
            assertEquals(0, report.pristine, "no synth sound may need a pristine copy")
        } finally {
            cache.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a synth sound file is named by its id`() {
        val codec = AudioCodecs.synthSounds
        assertEquals(1234, codec.archiveOf("1234.json"))
        assertEquals(1234, codec.archiveOf("1234.json.pristine"))
        assertEquals(null, codec.archiveOf("1234.dat"))
        assertEquals(null, codec.archiveOf("nested/1234.json"))
        assertTrue(codec.id == "json-synth")
    }

    private companion object {
        private const val SOUNDS = 10_084
        private const val PADDING = 64
        private const val LIMIT = 8
    }
}
