package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.SynthEnvelope
import world.gregs.voidps.cache.definition.data.SynthFilter
import world.gregs.voidps.cache.definition.data.SynthInstrument
import world.gregs.voidps.cache.definition.data.SynthShape
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition

/**
 * The exact inverse of [world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder].
 *
 * The format is a fixed sequence rather than an opcode list, so there is no order to replay and no
 * presence rule to get wrong: every branch the encoder takes is one the decoder recorded. The two
 * that are worth naming are the empty slot, written as the single zero byte the client peeks for,
 * and the filter's sweep envelope, whose presence the client derives from the mask and the gains -
 * so it is written when and only when those say it is there.
 */
class SynthSoundEncoder {

    fun Writer.encode(definition: SynthSoundDefinition) {
        require(definition.instruments.size == SynthSoundDefinition.SLOTS) {
            "Synth sound ${definition.id} has ${definition.instruments.size} instrument slots, " +
                "not ${SynthSoundDefinition.SLOTS}."
        }
        for (instrument in definition.instruments) {
            if (instrument == null) {
                writeByte(0)
            } else {
                encode(instrument)
            }
        }
        writeShort(definition.loopBegin)
        writeShort(definition.loopEnd)
    }

    private fun Writer.encode(instrument: SynthInstrument) {
        // The pitch envelope's form is the instrument's first byte, and a zero there is the empty
        // slot marker, so a present instrument's is never zero. Every other form may be.
        marker(instrument.pitch, "instrument")
        encode(instrument.pitch)
        encode(instrument.volume)
        pair(instrument.pitchModifier, instrument.pitchModifierAmplitude)
        pair(instrument.volumeMultiplier, instrument.volumeAmplitude)
        pair(instrument.release, instrument.attack)
        val volumes = instrument.oscillatorVolume
        require(
            volumes.size == SynthInstrument.OSCILLATORS &&
                instrument.oscillatorPitch.size == SynthInstrument.OSCILLATORS &&
                instrument.oscillatorDelay.size == SynthInstrument.OSCILLATORS
        ) { "A synth instrument's oscillator arrays are ${SynthInstrument.OSCILLATORS} long." }
        for (oscillator in volumes.indices) {
            if (volumes[oscillator] == 0) {
                break
            }
            writeSmart(volumes[oscillator])
            writeSignedSmart(instrument.oscillatorPitch[oscillator])
            writeSmart(instrument.oscillatorDelay[oscillator])
        }
        // The list is always terminated: the client reads the terminator before it indexes its
        // five-long arrays, so even an instrument with five oscillators carries one.
        writeSmart(0)
        writeSmart(instrument.delayTime)
        writeSmart(instrument.delayDecay)
        writeShort(instrument.duration)
        writeShort(instrument.offset)
        encode(instrument.filter)
    }

    /** An optional envelope pair, or the zero byte that stands in for both of them. */
    private fun Writer.pair(first: SynthEnvelope?, second: SynthEnvelope?) {
        if (first == null || second == null) {
            require(first == null && second == null) { "A synth envelope pair is present or absent, not half." }
            writeByte(0)
            return
        }
        marker(first, "envelope pair")
        encode(first)
        encode(second)
    }

    /** An envelope whose form byte doubles as "something is here", so it must not be zero. */
    private fun marker(envelope: SynthEnvelope, what: String) {
        require(envelope.form != 0) {
            "A present $what's first envelope has a non-zero form; zero is what says it is absent."
        }
    }

    private fun Writer.encode(envelope: SynthEnvelope) {
        writeByte(envelope.form)
        writeInt(envelope.start)
        writeInt(envelope.end)
        shape(envelope.phaseDuration, envelope.phasePeak)
    }

    private fun Writer.shape(shape: SynthShape) = shape(shape.phaseDuration, shape.phasePeak)

    private fun Writer.shape(durations: IntArray, peaks: IntArray) {
        require(durations.size == peaks.size) {
            "A synth envelope has ${durations.size} phase durations and ${peaks.size} peaks."
        }
        writeByte(durations.size)
        for (phase in durations.indices) {
            writeShort(durations[phase])
            writeShort(peaks[phase])
        }
    }

    private fun Writer.encode(filter: SynthFilter) {
        val forward = filter.forward
        val backward = filter.backward
        if (forward.isEmpty() && backward.isEmpty()) {
            writeByte(0)
            return
        }
        writeByte((forward.size shl 4) or backward.size)
        require(filter.unity.size == 2) { "A synth filter has two gains, not ${filter.unity.size}." }
        writeShort(filter.unity[0])
        writeShort(filter.unity[1])
        writeByte(filter.varying)
        val pairs = arrayOf(forward, backward)
        for (direction in pairs.indices) {
            for (pair in pairs[direction].indices) {
                writeShort(pairs[direction][pair].phase)
                writeShort(pairs[direction][pair].magnitude)
            }
        }
        for (direction in pairs.indices) {
            for (pair in pairs[direction].indices) {
                if (filter.varying and (1 shl (direction * 4 + pair)) != 0) {
                    writeShort(pairs[direction][pair].endPhase)
                    writeShort(pairs[direction][pair].endMagnitude)
                }
            }
        }
        val sweeps = filter.varying != 0 || filter.unity[0] != filter.unity[1]
        val envelope = filter.envelope
        require(sweeps == (envelope != null)) {
            "A synth filter sweeps when its mask is set or its gains differ, and carries a shape " +
                "exactly then; this one says $sweeps but ${if (envelope == null) "has no" else "has a"} shape."
        }
        if (envelope != null) {
            shape(envelope)
        }
    }
}
