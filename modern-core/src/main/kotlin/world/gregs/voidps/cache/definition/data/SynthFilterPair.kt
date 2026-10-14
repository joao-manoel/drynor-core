package world.gregs.voidps.cache.definition.data

/**
 * One conjugate pole pair of a [SynthFilter], at the start and at the end of the note.
 *
 * [phase] is the pole angle in 1/8192ths of an octave above 32.703 Hz and [magnitude] its attenuation
 * in 1/655.36ths of a decibel. The end values equal the start values for a pair whose bit in
 * [SynthFilter.varying] is clear, and the encoder writes them only for a pair whose bit is set, which
 * is what keeps the file byte for byte what it was.
 */
class SynthFilterPair(
    var phase: Int,
    var magnitude: Int,
    var endPhase: Int,
    var endMagnitude: Int
)
