package world.gregs.voidps.cache.definition.data

/**
 * One voice of a [SynthSoundDefinition]: up to five oscillators, the envelopes that shape them, an
 * echo and a filter.
 *
 * The three envelope pairs are optional and are written as a pair or not at all, exactly as the
 * client reads them: a zero byte in place of the first envelope's form means the pair is absent.
 * [pitch] and [volume] are never absent.
 *
 * The oscillator arrays are five long because the client's are; only the leading run of non-zero
 * [oscillatorVolume] entries is in the file, terminated by a zero, so a trailing zero volume means
 * "no oscillator" and the pitch and delay beside it are not written.
 */
class SynthInstrument(
    /** The oscillators' base pitch envelope. Its `form` chooses the wave shape for every oscillator. */
    var pitch: SynthEnvelope,
    var volume: SynthEnvelope,
    /** Vibrato: the modulating pitch envelope and its amplitude, present together or not at all. */
    var pitchModifier: SynthEnvelope?,
    var pitchModifierAmplitude: SynthEnvelope?,
    /** Tremolo: the modulating volume envelope and its amplitude, present together or not at all. */
    var volumeMultiplier: SynthEnvelope?,
    var volumeAmplitude: SynthEnvelope?,
    /** The gater: release and attack envelopes, present together or not at all. */
    var release: SynthEnvelope?,
    var attack: SynthEnvelope?,
    /** Per-oscillator volume in percent; a zero ends the list. */
    var oscillatorVolume: IntArray,
    /** Per-oscillator pitch offset in semitone-hundredths, signed. */
    var oscillatorPitch: IntArray,
    /** Per-oscillator start delay, as a fraction of the instrument's duration. */
    var oscillatorDelay: IntArray,
    /** Echo delay in the same units as [duration]; zero for no echo. */
    var delayTime: Int,
    /** Echo feedback in percent. */
    var delayDecay: Int,
    /** How long the instrument sounds, in milliseconds. */
    var duration: Int,
    /** How long after the sound starts this instrument does, in milliseconds. */
    var offset: Int,
    var filter: SynthFilter
) {

    companion object {
        /** The client's oscillator arrays are five long, and a sixth entry would overrun them. */
        const val OSCILLATORS = 5
    }
}
