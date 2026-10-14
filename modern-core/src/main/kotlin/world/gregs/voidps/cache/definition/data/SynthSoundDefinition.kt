package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One index 4 synth sound: ten instrument slots and the loop the mixer plays them over.
 *
 * The client's `SoundEffect` reads exactly this - ten optional [SynthInstrument]s, each an additive
 * synthesiser of up to five oscillators shaped by envelopes and a resonant filter, then the loop
 * points in milliseconds - and mixes it into 22,050 Hz 8-bit PCM at play time. Nothing here is the
 * audio; it is the recipe for it, which is why an index 4 archive is a few hundred bytes and the
 * sound it makes is a second long.
 *
 * [instruments] always has ten slots and a slot is null when the file wrote the single zero byte
 * that says the slot is empty; an instrument's own first byte is its pitch envelope's form, which
 * is never zero, so the two cannot be confused.
 */
class SynthSoundDefinition(
    override var id: Int = -1,
    var instruments: Array<SynthInstrument?> = arrayOfNulls(SLOTS),
    /** Where the mix starts looping, in milliseconds. Equal to [loopEnd] when there is no loop. */
    var loopBegin: Int = 0,
    /** Where the mix loops back from, in milliseconds. */
    var loopEnd: Int = 0
) : Definition {

    companion object {
        /** The client's `SoundEffect` reads a fixed ten instrument slots. */
        const val SLOTS = 10
    }
}
