package world.gregs.voidps.buffer.write

import world.gregs.voidps.buffer.Cp1252

/**
 * All functions relative to writing directly to a packet are done by this class
 */
interface Writer {

    fun setByte(index: Int, value: Int)

    fun writeByte(value: Int)

    fun writeByteAdd(value: Int)

    fun writeByteInverse(value: Int)

    fun writeByteSubtract(value: Int)

    fun writeByte(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeShort(value: Int)

    fun writeShortAdd(value: Int)

    fun writeShortLittle(value: Int)

    fun writeShortAddLittle(value: Int)

    fun writeMedium(value: Int)

    fun writeInt(value: Int)

    fun writeIntMiddle(value: Int)

    fun writeIntInverse(value: Int)

    fun writeIntInverseMiddle(value: Int)

    fun writeIntLittle(value: Int)

    fun writeIntInverseLittle(value: Int)

    fun writeLong(value: Long)

    /**
     * Writes a 40-bit big-endian value, most significant byte first.
     *
     * Exact inverse of [world.gregs.voidps.buffer.read.Reader.read5].
     *
     * Only the low 40 bits of [value] are written.
     */
    fun write5(value: Long) {
        writeByte((value shr 32).toInt())
        writeInt((value and 0xffffffffL).toInt())
    }

    fun writeSmart(value: Int) {
        if (value >= 128) {
            writeShort(value + 32768)
        } else {
            writeByte(value)
        }
    }

    /**
     * Writes a *signed* smart, the exact inverse of
     * [world.gregs.voidps.buffer.read.Reader.readSignedSmart]: one byte biased by 64 for -64..63
     * and two bytes biased by 49152 otherwise.
     *
     * [wide] forces the two byte form for a value the one byte form would fit, which is what a
     * file that was packed that way needs to come back unchanged.
     */
    fun writeSignedSmart(value: Int, wide: Boolean = false) {
        require(value in -16384..16383) { "A signed smart holds -16384..16383, not $value." }
        if (!wide && value in -64..63) {
            writeByte(value + 64)
        } else {
            writeShort(value + 49152)
        }
    }

    fun writeBigSmart(value: Int) {
        when {
            value == -1 -> writeShort(32767)
            value in 0..32766 -> writeShort(value)
            else -> writeInt(value or 0x80000000.toInt())
        }
    }

    fun writeString(value: String?) {
        if (value != null) {
            for (char in value) {
                writeByte(Cp1252.encode(char).toInt())
            }
        }
        writeByte(0)
    }

    fun writePrefixedString(value: String) {
        writeByte(0)
        for (char in value) {
            writeByte(Cp1252.encode(char).toInt())
        }
        writeByte(0)
    }

    fun writeBytes(value: ByteArray)

    fun writeBytes(data: ByteArray, offset: Int, length: Int)

    fun startBitAccess()

    fun stopBitAccess()

    fun writeBits(bitCount: Int, value: Boolean) {
        writeBits(bitCount, if (value) 1 else 0)
    }

    fun writeBits(bitCount: Int, value: Int)

    fun skip(position: Int) {
        for (i in 0 until position) {
            writeByte(0)
        }
    }

    fun position(): Int

    fun position(index: Int)

    fun bitIndex(): Int

    fun bitIndex(index: Int)

    fun toArray(): ByteArray

    fun array(): ByteArray

    fun clear()

    fun remaining(): Int

}