package world.gregs.voidps.buffer

import io.ktor.utils.io.*
import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.BytePacketBuilder
import io.ktor.utils.io.core.*
import kotlinx.io.Source
import kotlinx.io.readUByte
import org.drynor.core.currentTimeTicks
import org.drynor.core.hashToShort
import world.gregs.voidps.buffer.write.BufferWriter

suspend fun ByteWriteChannel.writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeBooleanAdd(value: Boolean) = writeByteAdd(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeBooleanSubtract(value: Boolean) = writeByteSubtract(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeBooleanInverse(value: Boolean) = writeByteInverse(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeByte(value: Int) = writeByte(value.toByte())

suspend fun ByteWriteChannel.writeShort(value: Int) = writeShort(value.toShort())

suspend fun ByteWriteChannel.writeByteAdd(value: Boolean) = writeByteAdd(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeByteInverse(value: Boolean) = writeByteInverse(if (value) 1 else 0)

suspend fun ByteWriteChannel.writeByteAdd(value: Int) = writeByte(value + 128)

suspend fun ByteWriteChannel.writeByteInverse(value: Int) = writeByte(-value)

suspend fun ByteWriteChannel.writeByteSubtract(value: Int) = writeByte(-value + 128)

suspend fun ByteWriteChannel.writeBytes(value: ByteArray) = writeFully(value)

suspend fun ByteWriteChannel.writeShortAdd(value: Int) {
    writeByte(value shr 8)
    writeByteAdd(value)
}

suspend fun ByteWriteChannel.writeShortLittle(value: Int) = writeShort(value.toShort().reverseByteOrder())

suspend fun ByteWriteChannel.writeShortAddLittle(value: Int) {
    writeByteAdd(value)
    writeByte(value shr 8)
}

suspend fun ByteWriteChannel.writeIntMiddle(value: Int) {
    writeByte(value shr 8)
    writeByte(value)
    writeByte(value shr 24)
    writeByte(value shr 16)
}

suspend fun ByteWriteChannel.writeIntInverse(value: Int) {
    writeByte(value shr 8)
    writeByte(value shr 24)
    writeByte(value shr 16)
    writeByteInverse(value)
}

suspend fun ByteWriteChannel.writeIntLittle(value: Int) = writeInt(value.reverseByteOrder())

suspend fun ByteWriteChannel.writeIntInverseMiddle(value: Int) {
    writeByte(value shr 16)
    writeByte(value shr 24)
    writeByte(value)
    writeByte(value shr 8)
}

suspend fun ByteWriteChannel.writeMedium(value: Int) {
    writeByte(value shr 16)
    writeByte(value shr 8)
    writeByte(value)
}

suspend fun ByteWriteChannel.writeMediumReverseEnd(value: Int) {
    writeByte(value shr 16)
    writeByte(value)
    writeByte(value shr 8)
}

/**
 * Writes a 40-bit big-endian value, most significant byte first.
 *
 * Exact inverse of [ByteReadChannel.read40BitULong] / [Source.read40BitULong]
 * (and of [world.gregs.voidps.buffer.read.Reader.read5]).
 *
 * Only the low 40 bits of [value] are written.
 */
suspend fun ByteWriteChannel.write5(value: Long) {
    writeByte((value shr 32).toByte())
    writeInt((value and 0xffffffffL).toInt())
}

suspend fun ByteWriteChannel.writeSmart(value: Int) {
    if (value >= 128) {
        writeShort(value + 32768)
    } else {
        writeByte(value)
    }
}

/**
 * Inverse of [world.gregs.voidps.buffer.read.Reader.readBigSmart] / [world.gregs.voidps.buffer.read.BufferReader.readBigSmart].
 *
 * - Encodes -1 as 32767 (ushort)
 * - Encodes 0..32766 as ushort
 * - Encodes 32767+ as int with the sign bit set (0x80000000)
 */
suspend fun ByteWriteChannel.writeBigSmart(value: Int) {
    when {
        value == -1 -> writeShort(32767)
        value in 0..32766 -> writeShort(value)
        else -> writeInt(value or 0x80000000.toInt())
    }
}

suspend fun ByteWriteChannel.writeFlags(flags: Int) {
    var flags = flags
    while ((flags and 0x7F.inv()) != 0) {
        writeByte((flags and 0x7F) or 0x80)
        flags = flags ushr 7
    }
    writeByte(flags and 0x7F)
}

suspend fun ByteWriteChannel.writeRSString(value: String) {
    writeFully(Cp1252.encode(value))
    writeByte(0)
}

suspend fun ByteWriteChannel.writeJagString(value: String) {
    writeByte(0)
    writeRSString(value)
}

fun BytePacketBuilder.writeRSString(value: String) {
    writeFully(Cp1252.encode(value))
    writeByte(0)
}

fun BytePacketBuilder.writeJagString(value: String) {
    writeByte(0)
    writeRSString(value)
}

/**
 * TODO
 * absolutely zero idea what this function actually should do
 * if anyone knows, please let me know
 */
suspend fun ByteWriteChannel.writeHashedMessageTimestamp(message: String) {
    writeShort(message.hashToShort())
    writeMedium((currentTimeTicks and 0xFFFFFF).toInt())
}

suspend fun ByteWriteChannel.writeHashedQCMessageTimestamp(messageId: Int) {
    writeShort(messageId.toShort())
    writeMedium((currentTimeTicks and 0xFFFFFF).toInt())
}

class BitAccessor {
    private var bitIndex = 0
    private val data = ByteArray(4096 * 2)

    fun writeBit(value: Boolean) = writeBits(1, if (value) 1 else 0)

    fun writeBits(count: Int, value: Int) {
        var numBits = count

        var byteIndex = bitIndex shr 3
        var bitOffset = 8 - (bitIndex and 7)
        bitIndex += numBits

        var tmp: Int
        var max: Int
        while (numBits > bitOffset) {
            tmp = data[byteIndex].toInt()
            max = BufferWriter.BIT_MASKS[bitOffset]
            tmp = tmp and max.inv() or (value shr numBits - bitOffset and max)
            data[byteIndex++] = tmp.toByte()
            numBits -= bitOffset
            bitOffset = 8
        }

        tmp = data[byteIndex].toInt()
        max = BufferWriter.BIT_MASKS[numBits]
        if (numBits == bitOffset) {
            tmp = tmp and max.inv() or (value and max)
        } else {
            tmp = tmp and (max shl bitOffset - numBits).inv()
            tmp = tmp or (value and max shl bitOffset - numBits)
        }
        data[byteIndex] = tmp.toByte()
    }

    suspend fun write(channel: ByteWriteChannel) {
        channel.writeFully(data, 0, (bitIndex + 7) / 8)
    }
}

suspend fun ByteWriteChannel.bitAccess(block: BitAccessor.() -> Unit) {
    val accessor = BitAccessor()
    block.invoke(accessor)
    accessor.write(this)
}

suspend fun ByteWriteChannel.respond(value: Int) {
    writeByte(value)
    flush()
}

suspend fun ByteWriteChannel.finish(value: Int) {
    respond(value)
    flushAndClose()
}

fun Source.readRSString(): String {
    val sb = StringBuilder()
    var b: Int
    while (remaining > 0) {
        b = readByte().toInt()
        if (b == 0) {
            break
        }
        sb.append(Cp1252.decode(b))
    }
    return sb.toString()
}

fun Source.readJagString(): String {
    readByte()
    return readRSString()
}

fun Source.readFlags(): Int {
    var result = 0
    var shift = 0
    var currValue: Int
    do {
        currValue = readByte().toInt() and 0xFF
        result = result or ((currValue and 0x7F) shl shift)
        shift += 7
        if (shift >= 32 && (currValue and 0x80) != 0)
            error("Variable length quantity is too long")
    } while ((currValue and 0x80) != 0)
    return result
}

fun Source.readUByte(): Int = readByte().toInt() and 0xff

fun Source.readUShort(): Int = (readUByte().toInt() shl 8) or readUByte().toInt()

/**
 * Reads a signed 24-bit big-endian value (-8388608..8388607).
 *
 * Each byte is masked before shifting; the result is sign-corrected from bit 23,
 * matching the client's `readMedium`. Use [readUMedium] for the unsigned form.
 */
fun Source.readMedium(): Int {
    val value = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 8) or readUByte().toInt()
    return if (value > 0x7fffff) value - 0x1000000 else value
}

fun Source.readUMedium() = (readUByte().toInt() shl 16) + (readUByte().toInt() shl 8) + readUByte().toInt()

/**
 * Reads a signed 40-bit big-endian value (-549755813888..549755813887).
 *
 * Every byte is masked before shifting; only bit 39 carries the sign.
 * Use [read40BitULong] for the unsigned form (that is the one the JS5 protocol uses).
 */
fun Source.read40BitLong(): Long {
    val value = read40BitULong()
    return if (value > 0x7fffffffffL) value - 0x10000000000L else value
}

fun Source.read40BitULong() = (readUByte().toLong() shl 32) + (readUByte().toLong() shl 24) + (readUByte().toLong() shl 16) + (readUByte().toLong() shl 8) + readUByte().toLong()

fun Source.readBoolean(): Boolean = readByte().toInt() == 1

fun Source.readBooleanInverse() = readByteInverse() == 1

fun Source.readBooleanSubtract() = readByteSubtract() == 1

fun Source.readBooleanAdd() = readByteAdd() == 1

fun Source.readByteAdd(): Int = (readByte() - 128).toByte().toInt()

fun Source.readByteInverse(): Int = (-readByte()).toByte().toInt()

fun Source.readByteSubtract(): Int = (readByteInverse() + 128).toByte().toInt()

/**
 * Reads a signed 16-bit big-endian value whose low byte is offset by 128 (-32768..32767).
 *
 * Both bytes are masked before being combined; the result is sign-corrected from bit 15,
 * matching the client's `readShortAdd`. Use [readUShortAdd] for the unsigned form.
 */
fun Source.readShortAdd(): Int {
    val value = (readUByte().toInt() shl 8) or ((readByte() - 128) and 0xff)
    return if (value > 0x7fff) value - 0x10000 else value
}

fun Source.readShortAddLittle(): Int = ((readByte().toInt() - 128) and 0xff) or ((readByte().toInt() shl 8) and 0xff00)

fun Source.readUShortAdd(): Int = (readUByte().toInt() shl 8) or ((readByte() - 128) and 0xff)

fun Source.readUShortLittle(): Int = readUByte().toInt() or (readUByte().toInt() shl 8)

fun Source.readShortLittle(): Int {
    val value = readUByte().toInt() or (readUByte().toInt() shl 8)
    if (value > 0x7FFF)
        return value - 0x10000
    return value
}

fun Source.readUShortAddLittle(): Int = (readByte() - 128 and 0xff) + (readByte().toInt() shl 8 and 0xff00)

fun Source.readUIntMiddle(): Int = (readUByte().toInt() shl 8) or readUByte().toInt() or (readUByte().toInt() shl 24) or (readUByte().toInt() shl 16)

/**
 * Reads a 32-bit value in the inverse-middle byte order (bytes arrive 2, 3, 0, 1).
 *
 * All four bytes are masked; a full 32-bit read fills every bit of an [Int], so this is
 * bit-identical to [readUIntInverseMiddle] - both are kept for call-site symmetry.
 */
fun Source.readIntInverseMiddle(): Int = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24) or readUByte().toInt() or (readUByte().toInt() shl 8)

fun Source.readUIntInverseMiddle(): Int = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24) or readUByte().toInt() or (readUByte().toInt() shl 8)

fun Source.readUIntLittle(): Int = (readUByte().toInt()) or (readUByte().toInt() shl 8) or (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24)

fun Source.readSmart(): Int {
    val peek = readUByte().toInt()
    return if (peek < 128) {
        peek and 0xFF
    } else {
        (peek shl 8 or readUByte().toInt()) - 32768
    }
}

fun Source.readIntLittle(): Int = readUIntLittle()

fun Source.readIntMiddle(): Int = readUIntMiddle()

fun Source.readIntInverse(): Int {
    val second = readUByte().toInt()
    val fourth = readUByte().toInt()
    val third = readUByte().toInt()
    val first = readByteInverse() and 0xff
    return (fourth shl 24) or (third shl 16) or (second shl 8) or first
}

fun Source.readMediumReverseEnd(): Int {
    val high = readUByte().toInt()
    val low = readUByte().toInt()
    val middle = readUByte().toInt()
    return (high shl 16) or (middle shl 8) or low
}

fun Source.readBigSmart(): Int {
    if (peek().readByte() < 0) return readInt() and 0x7fffffff
    val value = readUShort()
    return if (value == 32767) -1 else value
}

fun Source.readName(): Pair<String, String> {
    val different = readBoolean()
    val displayName = readRSString()
    return displayName to if (different) readRSString() else displayName
}

fun Source.skipHashedMessageTimestamp() = skip(5)

class BitReader(private val source: Source) {
    private var current = 0
    private var bitsLeft = 0

    fun readBit(): Boolean = readBits(1) == 1

    fun readBits(count: Int): Int {
        var value = 0
        var needed = count
        while (needed > 0) {
            if (bitsLeft == 0) {
                current = source.readByte().toInt() and 0xff
                bitsLeft = 8
            }
            val taken = minOf(needed, bitsLeft)
            bitsLeft -= taken
            value = (value shl taken) or ((current shr bitsLeft) and ((1 shl taken) - 1))
            needed -= taken
        }
        return value
    }
}

inline fun <T> Source.bitAccess(block: BitReader.() -> T): T = BitReader(this).block()

suspend fun ByteReadChannel.readRSString(): String {
    val sb = StringBuilder()
    var b: Int
    while (true) {
        b = readByte().toInt()
        if (b == 0)
            break
        sb.append(Cp1252.decode(b))
    }
    return sb.toString()
}

suspend fun ByteReadChannel.readJagString(): String {
    readByte()
    return readRSString()
}

suspend fun ByteReadChannel.readFlags(): Int {
    var result = 0
    var shift = 0
    var currValue: Int
    do {
        currValue = readByte().toInt() and 0xFF
        result = result or ((currValue and 0x7F) shl shift)
        shift += 7
        if (shift >= 32 && (currValue and 0x80) != 0)
            error("Variable length quantity is too long")
    } while ((currValue and 0x80) != 0)
    return result
}

suspend fun ByteReadChannel.readUByte(): Int = readByte().toInt() and 0xff

suspend fun ByteReadChannel.readUShort(): Int = (readUByte().toInt() shl 8) or readUByte().toInt()

/**
 * Reads a signed 24-bit big-endian value (-8388608..8388607).
 *
 * Each byte is masked before shifting; the result is sign-corrected from bit 23,
 * matching the client's `readMedium`. Use [readUMedium] for the unsigned form.
 */
suspend fun ByteReadChannel.readMedium(): Int {
    val value = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 8) or readUByte().toInt()
    return if (value > 0x7fffff) value - 0x1000000 else value
}

suspend fun ByteReadChannel.readUMedium() = (readUByte().toInt() shl 16) + (readUByte().toInt() shl 8) + readUByte().toInt()

/**
 * Reads a signed 40-bit big-endian value (-549755813888..549755813887).
 *
 * Every byte is masked before shifting; only bit 39 carries the sign.
 * Use [read40BitULong] for the unsigned form (that is the one the JS5 protocol uses).
 */
suspend fun ByteReadChannel.read40BitLong(): Long {
    val value = read40BitULong()
    return if (value > 0x7fffffffffL) value - 0x10000000000L else value
}

suspend fun ByteReadChannel.read40BitULong() = (readUByte().toLong() shl 32) + (readUByte().toLong() shl 24) + (readUByte().toLong() shl 16) + (readUByte().toLong() shl 8) + readUByte().toLong()

suspend fun ByteReadChannel.readBoolean(): Boolean = readByte().toInt() == 1

suspend fun ByteReadChannel.readBooleanInverse() = readByteInverse() == 1

suspend fun ByteReadChannel.readBooleanSubtract() = readByteSubtract() == 1

suspend fun ByteReadChannel.readBooleanAdd() = readByteAdd() == 1

suspend fun ByteReadChannel.readByteAdd(): Int = (readByte() - 128).toByte().toInt()

suspend fun ByteReadChannel.readByteInverse(): Int = (-readByte()).toByte().toInt()

suspend fun ByteReadChannel.readByteSubtract(): Int = (readByteInverse() + 128).toByte().toInt()

/**
 * Reads a signed 16-bit big-endian value whose low byte is offset by 128 (-32768..32767).
 *
 * Both bytes are masked before being combined; the result is sign-corrected from bit 15,
 * matching the client's `readShortAdd`. Use [readUShortAdd] for the unsigned form.
 */
suspend fun ByteReadChannel.readShortAdd(): Int {
    val value = (readUByte().toInt() shl 8) or ((readByte() - 128) and 0xff)
    return if (value > 0x7fff) value - 0x10000 else value
}

suspend fun ByteReadChannel.readShortAddLittle(): Int = ((readByte().toInt() - 128) and 0xff) or ((readByte().toInt() shl 8) and 0xff00)

suspend fun ByteReadChannel.readUShortAdd(): Int = (readUByte().toInt() shl 8) or ((readByte() - 128) and 0xff)

suspend fun ByteReadChannel.readUShortLittle(): Int = readUByte().toInt() or (readUByte().toInt() shl 8)

suspend fun ByteReadChannel.readShortLittle(): Int {
    val value = readUByte().toInt() or (readUByte().toInt() shl 8)
    if (value > 0x7FFF)
        return value - 0x10000
    return value
}

suspend fun ByteReadChannel.readUShortAddLittle(): Int = (readByte() - 128 and 0xff) + (readByte().toInt() shl 8 and 0xff00)

suspend fun ByteReadChannel.readUIntMiddle(): Int = (readUByte().toInt() shl 8) or readUByte().toInt() or (readUByte().toInt() shl 24) or (readUByte().toInt() shl 16)

/**
 * Reads a 32-bit value in the inverse-middle byte order (bytes arrive 2, 3, 0, 1).
 *
 * All four bytes are masked; a full 32-bit read fills every bit of an [Int], so this is
 * bit-identical to [readUIntInverseMiddle] - both are kept for call-site symmetry.
 */
suspend fun ByteReadChannel.readIntInverseMiddle(): Int = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24) or readUByte().toInt() or (readUByte().toInt() shl 8)

suspend fun ByteReadChannel.readUIntInverseMiddle(): Int = (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24) or readUByte().toInt() or (readUByte().toInt() shl 8)

suspend fun ByteReadChannel.readUIntLittle(): Int = (readUByte().toInt()) or (readUByte().toInt() shl 8) or (readUByte().toInt() shl 16) or (readUByte().toInt() shl 24)

suspend fun ByteReadChannel.readSmart(): Int {
    val peek = readUByte().toInt()
    return if (peek < 128) {
        peek and 0xFF
    } else {
        (peek shl 8 or readUByte().toInt()) - 32768
    }
}

suspend fun ByteWriteChannel.writeName(displayName: String, responseName: String = displayName) {
    val different = displayName != responseName
    writeBoolean(different)
    writeRSString(displayName)
    if (different)
        writeRSString(responseName)
}

/**
 * Writes a string as an RS long
 */
suspend fun ByteWriteChannel.writeLong(string: String) {
    var long = 0L
    for (i in 0 until string.length.coerceAtMost(12)) {
        val char = string[i].code
        long *= 37L
        when (char) {
            in 65..90 -> long += char - 64L
            in 97..122 -> long += char - 96L
            in '0'.code..'9'.code -> long += char - 21L
        }
    }
    while (long % 37L == 0L && long != 0L)
        long /= 37L
    writeLong(long)
}

val VALID_CHARS = charArrayOf('_', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

fun Long.toRSString(): String {
    val result = CharArray(12)
    var value = this
    var length = 0

    while (value != 0L) {
        val remainder = value % 37L
        value /= 37L
        result[11 - length++] = VALID_CHARS[remainder.toInt()]
    }

    return String(result, 12 - length, length)
}
