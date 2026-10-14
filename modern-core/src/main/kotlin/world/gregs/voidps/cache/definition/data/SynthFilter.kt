package world.gregs.voidps.cache.definition.data

/**
 * The resonant filter one [SynthInstrument] runs its oscillators through.
 *
 * Two directions - [forward] is the feed-forward half and [backward] the feedback half - of up to
 * four conjugate pole pairs each, interpolated from their start values to their end values across
 * the note. [unity] is the gain at each end.
 *
 * [varying] is the file's own mask byte, one bit per (direction, pair), saying which pairs actually
 * wrote end values; the client copies the start values into the end for the rest. It is kept as the
 * byte rather than derived from the pairs because a pair may legitimately write end values equal to
 * its start ones, and because the mask's unused high bits are what decide whether an [envelope]
 * follows. A filter with no pairs at all writes a single zero byte and nothing else.
 */
class SynthFilter(
    /** The filter's gain at the start and at the end of the note. Always two entries. */
    var unity: IntArray,
    /** The file's mask byte: bit `direction * 4 + pair` is set when that pair wrote end values. */
    var varying: Int,
    var forward: Array<SynthFilterPair>,
    var backward: Array<SynthFilterPair>,
    /** How the filter sweeps from its start to its end values, when the file says it sweeps at all. */
    var envelope: SynthShape?
)
