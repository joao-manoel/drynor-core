package world.gregs.voidps.cache.audio

/**
 * The header of an index 14 file, which is the only part of one that is not vorbis.
 *
 * Index 14 is 16,824 mono samples and, in archive 0, the one vorbis **setup header** they all share
 * - Jagex's own framing around packets that are otherwise stock vorbis. A sample is five big endian
 * ints and then its audio packets, each preceded by its length as a run of bytes that add up (0xff
 * means "255 and more"). There is no Ogg anywhere: no page headers, no granule positions, no
 * serial number, no identification or comment header. `docs/cache-source.md` says what that costs.
 *
 * Nothing here decodes audio. It reads the twenty byte header so the index can describe itself,
 * and the packet payloads are left exactly where they are.
 */
object JagexVorbis {

    /** The fixed part of a sample file. */
    class Sample(
        val sampleRate: Int,
        /** How many mono samples the packets decode into. */
        val samples: Int,
        val loopStart: Int,
        val loopEnd: Int,
        /**
         * The sign bit of the stored loop end, which the client reads as a flag and inverts the
         * value for. It has no name in the client beyond the field it sets.
         */
        val loopConsistent: Boolean,
        val packets: Int
    ) {
        /** The sample's length in milliseconds, for a report that wants a human number. */
        val millis: Int
            get() = if (sampleRate <= 0) 0 else (samples.toLong() * 1000 / sampleRate).toInt()
    }

    /** The two things the shared setup header says before its codebooks start. */
    class Setup(val blockSize0: Int, val blockSize1: Int, val codebooks: Int)

    /** The header of a sample file. Only the first [HEADER] bytes are read. */
    fun sample(data: ByteArray): Sample {
        require(data.size >= HEADER) { "An index 14 sample is at least $HEADER bytes long." }
        val end = int(data, 12)
        return Sample(
            sampleRate = int(data, 0),
            samples = int(data, 4),
            loopStart = int(data, 8),
            loopEnd = if (end < 0) end.inv() else end,
            loopConsistent = end < 0,
            packets = int(data, 16)
        )
    }

    /**
     * The shared setup header's first two bytes: the two block sizes as their base two logarithms
     * in a nibble each, then the codebook count less one.
     *
     * The bit reader is least significant bit first, as vorbis' own is, so the low nibble of the
     * first byte is the first field.
     */
    fun setup(data: ByteArray): Setup {
        require(data.size >= 2) { "An index 14 setup header is at least two bytes long." }
        val sizes = data[0].toInt() and 0xff
        return Setup(
            blockSize0 = 1 shl (sizes and 0xf),
            blockSize1 = 1 shl (sizes shr 4),
            codebooks = (data[1].toInt() and 0xff) + 1
        )
    }

    /** The fixed header's size: five big endian ints. */
    const val HEADER = 20

    private fun int(data: ByteArray, position: Int): Int =
        ((data[position].toInt() and 0xff) shl 24) or ((data[position + 1].toInt() and 0xff) shl 16) or
            ((data[position + 2].toInt() and 0xff) shl 8) or (data[position + 3].toInt() and 0xff)
}
