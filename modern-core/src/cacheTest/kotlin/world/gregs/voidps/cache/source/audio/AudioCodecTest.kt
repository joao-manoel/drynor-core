package world.gregs.voidps.cache.source.audio

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.audio.JagexVorbis
import world.gregs.voidps.cache.source.codec.AudioCodecs
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.VorbisCodec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four audio indices that are not index 4, each through its own codec over the whole cache.
 *
 * Indices 6 and 11 are standard MIDI files and have to pack back to the shipped compact encoding;
 * indices 14 and 15 are bytes and are exact by construction, so what is measured for them is that
 * the layout addresses every archive and that index 14's derived listing describes them.
 */
class AudioCodecTest {

    @Test
    fun `every song and jingle packs back from its standard MIDI file`() {
        val cache = AudioParity.cache() ?: return
        try {
            for (index in intArrayOf(Index.MIDI_SONGS, Index.MIDI_JINGLES)) {
                val directory = AudioParity.scratch("midi")
                try {
                    val report = AudioParity.roundTrip(cache, index, AudioCodecs.midi, directory)
                    println(report)
                    assertEquals(if (index == Index.MIDI_SONGS) SONGS else JINGLES, report.archives)
                    assertEquals(report.archives, report.files, "one .mid per archive and no sidecars")
                    assertEquals(report.archives, report.identical, "every archive must pack back identically")
                    assertEquals(0, report.pristine, "no song may need a pristine copy")
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every vorbis stream is kept whole and described`() {
        val cache = AudioParity.cache() ?: return
        val directory = AudioParity.scratch("vorbis")
        try {
            val report = AudioParity.roundTrip(cache, Index.VORBIS, VorbisCodec, directory)
            println(report)
            assertEquals(SAMPLES, report.archives)
            assertEquals(report.archives, report.identical, "bytes are exact by construction")
            assertEquals(0, report.pristine)

            val manifest = VorbisCodec.unpackIndex(directory, Index.VORBIS, cache.archives(Index.VORBIS))
            assertEquals(1, manifest.size)
            assertEquals(VorbisCodec.MANIFEST, manifest[0].path)
            val text = String(manifest[0].bytes)
            println("  ${VorbisCodec.MANIFEST}: ${manifest[0].bytes.size} bytes, ${text.lines().size} lines")
            println("  " + text.lines().take(4).joinToString("\n  "))
            assertTrue(text.contains(""""blockSize0": 1024"""), "the setup header's block sizes: $text")
            assertTrue(text.contains(""""sampleRate": 22050"""), "every 727 sample is 22050 Hz")
            assertEquals(
                SAMPLES - 1,
                text.split(""""sampleRate":""").size - 1,
                "every archive but the setup header is listed"
            )
            assertEquals(Owner.Index, VorbisCodec.owner(directory, VorbisCodec.MANIFEST), "the listing is derived")
            assertEquals(Owner.Archive(7), VorbisCodec.owner(directory, "7.jagvorbis"))

            // The header the listing reports is the one the payload carries.
            val first = Files.readAllBytes(directory.resolve("1.jagvorbis"))
            val sample = JagexVorbis.sample(first)
            assertEquals(22050, sample.sampleRate)
            assertTrue(sample.packets > 0)
        } finally {
            cache.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `every sound bank patch is kept whole`() {
        val cache = AudioParity.cache() ?: return
        val directory = AudioParity.scratch("patches")
        try {
            val report = AudioParity.roundTrip(cache, Index.MIDI_INSTRUMENTS, AudioCodecs.midiInstruments, directory)
            println(report)
            assertEquals(PATCHES, report.archives)
            assertEquals(report.archives, report.identical, "bytes are exact by construction")
            assertEquals(0, report.pristine)
            assertTrue(Files.isRegularFile(directory.resolve("0${AudioCodecs.PATCH_EXTENSION}")))
        } finally {
            cache.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `every audio index claims its own codec`() {
        SourceCodecs.reset()
        assertEquals("json-synth", SourceCodecs.codec(Index.SYNTH_SOUNDS).id)
        assertEquals("midi", SourceCodecs.codec(Index.MIDI_SONGS).id)
        assertEquals("midi", SourceCodecs.codec(Index.MIDI_JINGLES).id)
        assertEquals("vorbis", SourceCodecs.codec(Index.VORBIS).id)
        assertEquals("jagpatch", SourceCodecs.codec(Index.MIDI_INSTRUMENTS).id)
        assertEquals("dlls", SourceCodecs.codec(Index.NATIVE_LIBRARIES).id)
        assertEquals("shaders", SourceCodecs.codec(Index.SHADERS).id)
        assertEquals(42, AudioCodecs.midi.archiveOf("42.mid"))
        assertEquals(42, AudioCodecs.midi.archiveOf("42.mid.pristine"))
        assertEquals(null, AudioCodecs.midi.archiveOf("42.jagmidi"))
        assertEquals(9, AudioCodecs.midiInstruments.archiveOf("9${AudioCodecs.PATCH_EXTENSION}"))
    }

    private companion object {
        private const val SONGS = 1137
        private const val JINGLES = 525
        private const val SAMPLES = 16_825
        private const val PATCHES = 247
    }
}
