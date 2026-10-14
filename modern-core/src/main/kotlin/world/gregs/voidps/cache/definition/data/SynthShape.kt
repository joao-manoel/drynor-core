package world.gregs.voidps.cache.definition.data

/**
 * The shape half of an envelope, on its own.
 *
 * A [SynthFilter] that varies over the note carries one of these and no form, start or end: the
 * client decodes it into the same `Envelope` class it uses everywhere else, leaving the other three
 * fields at their defaults, and a file that does not vary carries no shape at all.
 */
class SynthShape(
    var phaseDuration: IntArray,
    var phasePeak: IntArray
)
