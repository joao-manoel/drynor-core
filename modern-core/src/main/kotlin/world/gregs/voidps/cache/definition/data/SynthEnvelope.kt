package world.gregs.voidps.cache.definition.data

/**
 * A piecewise-linear envelope: a wave [form], the value range it sweeps, and the shape it sweeps it in.
 *
 * [phaseDuration] and [phasePeak] are the same length - the client reads them as one interleaved list -
 * and are 16ths of 65536: a duration is a fraction of the note's length and a peak a fraction of the
 * range between [start] and [end].
 *
 * The envelope a filter carries is shape-only: it has no form, start or end of its own because the
 * file does not write them, so [SynthFilter.envelope] is a [SynthShape] rather than one of these.
 */
class SynthEnvelope(
    /** 1 square, 2 sine, 3 saw, 4 noise; 0 is silence and never appears as an instrument's first byte. */
    var form: Int,
    var start: Int,
    var end: Int,
    var phaseDuration: IntArray,
    var phasePeak: IntArray
)
