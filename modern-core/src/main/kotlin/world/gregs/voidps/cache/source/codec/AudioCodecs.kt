package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.NativeLibrary
import world.gregs.voidps.cache.audio.JagexMidi
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder

/**
 * The codecs for the audio and native-asset indices: 4, 6, 11, 14, 15, 30 and 31.
 *
 * One [register] call from [SourceCodecs.reset] claims all seven, so the file that decides who owns
 * which index gains one line rather than seven.
 *
 * Four of the seven are a decoded or a named form: index 4 is JSON, indices 6 and 11 are standard
 * MIDI files, index 31 is text and JSON under the names its archives spell out, and index 30 is its
 * libraries under the names the client asks for them by. The other two are bytes, and
 * `docs/cache-source.md` says why - a bare vorbis stream cannot be re-wrapped into an Ogg without
 * inventing what Ogg carries, and a sound bank patch's decode throws its own curves away.
 */
object AudioCodecs {

    /** Index 4's synth sounds as one JSON file per sound, `synth_sounds/<id>.json`. */
    val synthSounds: DefinitionJsonCodec<SynthSoundDefinition> = run {
        val decoder = SynthSoundDecoder()
        val encoder = SynthSoundEncoder()
        DefinitionJsonCodec(
            id = "json-synth",
            // One file per archive, so the archive id is the definition id and no bits are packed
            // below it: `synth_sounds/1234.json` is archive 1234's only file.
            layout = DefinitionLayout.Packed(0),
            create = { SynthSoundDefinition(it) },
            decode = { definition, bytes -> decoder.read(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    /**
     * Indices 6 and 11 as standard MIDI files: `midi_songs/42.mid`.
     *
     * The cache holds Jagex's compact MIDI encoding, which is the same events in a column store,
     * and [JagexMidi] is the pair of exact inverses between it and a `.mid` - so the tree stores the
     * file a sequencer opens and rebuilds the compact one when it packs. One codec instance for both
     * indices; the two are the same format and hold nothing per index.
     */
    val midi = TranscodedArchiveCodec("midi", MIDI_EXTENSION, JagexMidi::toStandard, JagexMidi::toCompact)

    /** Index 15's sound bank patches, as bytes. */
    val midiInstruments = BinaryArchiveCodec("jagpatch", PATCH_EXTENSION)

    /** Index 30's native libraries, under the names the client asks for them by. */
    val nativeLibraries = NamedBytesCodec("dlls") { hash -> NativeLibrary.name(hash) }

    /** Claim every index this file owns. Called by [SourceCodecs.reset]. */
    fun register() {
        SourceCodecs.builtin(Index.SYNTH_SOUNDS, synthSounds)
        SourceCodecs.builtin(Index.MIDI_SONGS, midi)
        SourceCodecs.builtin(Index.MIDI_JINGLES, midi)
        SourceCodecs.builtin(Index.VORBIS, VorbisCodec)
        SourceCodecs.builtin(Index.MIDI_INSTRUMENTS, midiInstruments)
        SourceCodecs.builtin(Index.NATIVE_LIBRARIES, nativeLibraries)
        SourceCodecs.builtin(Index.SHADERS, ShaderCodec)
    }

    /** A standard MIDI file, which is what index 6's and index 11's compact encoding expands into. */
    const val MIDI_EXTENSION = ".mid"

    /** A sound bank patch, the column store index 15 holds. */
    const val PATCH_EXTENSION = ".jagpatch"
}
