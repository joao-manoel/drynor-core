package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.data.SynthEnvelope
import world.gregs.voidps.cache.definition.data.SynthFilter
import world.gregs.voidps.cache.definition.data.SynthFilterPair
import world.gregs.voidps.cache.definition.data.SynthInstrument
import world.gregs.voidps.cache.definition.data.SynthShape
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition

/**
 * Index 4's synth sounds, read exactly as the client's `SoundEffect` reads them.
 *
 * There is no opcode loop here - the format is a fixed sequence, so there is no
 * [world.gregs.voidps.cache.definition.OpcodeOrdered] shape to record - and every value the file
 * carries lands on a field, including the two the client derives away
 * ([SynthFilter.varying] and a pair's end values), so [SynthSoundEncoder]
 * writes the file back byte for byte.
 *
 * A slot's presence is a single byte: the client peeks it, and a non-zero byte is the first byte of
 * an instrument (its pitch envelope's `form`, never zero) rather than a marker. The same peek
 * decides each of the three optional envelope pairs.
 */
class SynthSoundDecoder {

    /** [data] into [definition], which must be a fresh definition of the right id. */
    fun read(definition: SynthSoundDefinition, data: ByteArray) = read(definition, BufferReader(data))

    fun read(definition: SynthSoundDefinition, buffer: Reader) {
        val instruments = arrayOfNulls<SynthInstrument>(SynthSoundDefinition.SLOTS)
        for (slot in 0 until SynthSoundDefinition.SLOTS) {
            val form = buffer.readUnsignedByte()
            if (form != 0) {
                instruments[slot] = instrument(buffer, form)
            }
        }
        definition.instruments = instruments
        definition.loopBegin = buffer.readUnsignedShort()
        definition.loopEnd = buffer.readUnsignedShort()
        check(buffer.remaining == 0) {
            "Synth sound ${definition.id} has ${buffer.remaining} bytes left over after its loop points."
        }
    }

    /** One instrument, whose pitch envelope's form byte [form] the caller has already read. */
    private fun instrument(buffer: Reader, form: Int): SynthInstrument {
        val pitch = envelope(buffer, form)
        val volume = envelope(buffer)
        val vibrato = pair(buffer)
        val tremolo = pair(buffer)
        val gater = pair(buffer)
        val volumes = IntArray(SynthInstrument.OSCILLATORS)
        val pitches = IntArray(SynthInstrument.OSCILLATORS)
        val delays = IntArray(SynthInstrument.OSCILLATORS)
        for (oscillator in 0..SynthInstrument.OSCILLATORS) {
            // The terminating zero is read before the array is indexed, so a file with five
            // oscillators still carries one; a sixth non-zero volume would overrun the client.
            val volume = buffer.readSmart()
            if (volume == 0) {
                break
            }
            check(oscillator < SynthInstrument.OSCILLATORS) {
                "A synth instrument has more than ${SynthInstrument.OSCILLATORS} oscillators."
            }
            volumes[oscillator] = volume
            pitches[oscillator] = buffer.readSignedSmart()
            delays[oscillator] = buffer.readSmart()
        }
        return SynthInstrument(
            pitch = pitch,
            volume = volume,
            pitchModifier = vibrato?.first,
            pitchModifierAmplitude = vibrato?.second,
            volumeMultiplier = tremolo?.first,
            volumeAmplitude = tremolo?.second,
            release = gater?.first,
            attack = gater?.second,
            oscillatorVolume = volumes,
            oscillatorPitch = pitches,
            oscillatorDelay = delays,
            delayTime = buffer.readSmart(),
            delayDecay = buffer.readSmart(),
            duration = buffer.readUnsignedShort(),
            offset = buffer.readUnsignedShort(),
            filter = filter(buffer)
        )
    }

    /** An optional envelope pair: a zero in place of the first form byte means neither is there. */
    private fun pair(buffer: Reader): Pair<SynthEnvelope, SynthEnvelope>? {
        val form = buffer.readUnsignedByte()
        if (form == 0) {
            return null
        }
        return envelope(buffer, form) to envelope(buffer)
    }

    /** An envelope: form, the range it sweeps, then its shape. [form] may already have been read. */
    private fun envelope(buffer: Reader, form: Int = buffer.readUnsignedByte()): SynthEnvelope {
        val start = buffer.readInt()
        val end = buffer.readInt()
        val shape = shape(buffer)
        return SynthEnvelope(form, start, end, shape.phaseDuration, shape.phasePeak)
    }

    private fun shape(buffer: Reader): SynthShape {
        val phases = buffer.readUnsignedByte()
        val durations = IntArray(phases)
        val peaks = IntArray(phases)
        for (phase in 0 until phases) {
            durations[phase] = buffer.readUnsignedShort()
            peaks[phase] = buffer.readUnsignedShort()
        }
        return SynthShape(durations, peaks)
    }

    /**
     * The filter, whose first byte packs the two directions' pair counts.
     *
     * A zero there is the whole filter. Otherwise the two gains and the mask come next, then every
     * pair's start values in direction order, then the end values of the pairs the mask names, and
     * last the sweep envelope's shape - which the client reads whenever the mask is non-zero or the
     * two gains differ, so its presence is a function of what came before it and is not recorded.
     */
    private fun filter(buffer: Reader): SynthFilter {
        val counts = buffer.readUnsignedByte()
        if (counts == 0) {
            return SynthFilter(IntArray(2), 0, emptyArray(), emptyArray(), null)
        }
        check((counts shr 4) <= PAIRS && (counts and 0xf) <= PAIRS) {
            "A synth filter has more than $PAIRS pole pairs in one direction."
        }
        val forward = arrayOfNulls<SynthFilterPair>(counts shr 4)
        val backward = arrayOfNulls<SynthFilterPair>(counts and 0xf)
        val unity = intArrayOf(buffer.readUnsignedShort(), buffer.readUnsignedShort())
        val varying = buffer.readUnsignedByte()
        val pairs = arrayOf(forward, backward)
        for (direction in pairs.indices) {
            for (pair in pairs[direction].indices) {
                val phase = buffer.readUnsignedShort()
                val magnitude = buffer.readUnsignedShort()
                pairs[direction][pair] = SynthFilterPair(phase, magnitude, phase, magnitude)
            }
        }
        for (direction in pairs.indices) {
            for (pair in pairs[direction].indices) {
                if (varying and (1 shl (direction * 4 + pair)) != 0) {
                    pairs[direction][pair]!!.endPhase = buffer.readUnsignedShort()
                    pairs[direction][pair]!!.endMagnitude = buffer.readUnsignedShort()
                }
            }
        }
        val envelope = if (varying != 0 || unity[0] != unity[1]) shape(buffer) else null
        @Suppress("UNCHECKED_CAST")
        return SynthFilter(
            unity = unity,
            varying = varying,
            forward = forward as Array<SynthFilterPair>,
            backward = backward as Array<SynthFilterPair>,
            envelope = envelope
        )
    }

    private companion object {
        /** The client's filter arrays hold four pole pairs per direction, and the mask four bits. */
        private const val PAIRS = 4
    }
}
