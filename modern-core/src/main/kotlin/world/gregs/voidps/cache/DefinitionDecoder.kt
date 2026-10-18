package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.IntArrayList
import org.drynor.core.Logger.logError
import org.drynor.core.Logger.logInfo
import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.OpcodeOrdered
import java.nio.BufferUnderflowException

abstract class DefinitionDecoder<T : Definition>(val index: Int) {

    abstract fun create(size: Int): Array<T>

    open fun load(definitions: Array<T>, reader: Reader) {
        val id = readId(reader)
        read(definitions, id, reader)
    }

    open fun readId(reader: Reader) = reader.readInt()

    /**
     * Load from cache
     */
    open fun load(cache: Cache): Array<T> {
        val start = System.currentTimeMillis()
        // A cache with no index at all sizes negative; every table is then simply empty.
        val size = (size(cache) + 1).coerceAtLeast(0)
        val definitions = create(size)
        for (id in 0 until size) {
            try {
                load(definitions, cache, id)
            } catch (e: BufferUnderflowException) {
                logError("Error reading definition $id", e)
                throw e
            }
        }
        logInfo("$size ${this::class.simpleName} definitions loaded in ${System.currentTimeMillis() - start}ms")
        return definitions
    }

    open fun size(cache: Cache): Int {
        return cache.lastArchiveId(index) * 256 + (cache.fileCount(index, cache.lastArchiveId(index)))
    }

    open fun load(definitions: Array<T>, cache: Cache, id: Int) {
        val archive = getArchive(id)
        val file = getFile(id)
        val data = cache.data(index, archive, file) ?: return
        read(definitions, id, BufferReader(data))
    }

    open fun getFile(id: Int) = id

    open fun getArchive(id: Int) = id

    protected fun read(definitions: Array<T>, id: Int, reader: Reader) {
        val definition = definitions[id]
        readLoop(definition, reader)
        changeValues(definitions, definition)
    }

    /**
     * Reads opcodes until the 0 terminator.
     *
     * A definition that implements [OpcodeOrdered] also has recorded on it what the decoded fields cannot
     * hold - the order the file listed its records in and the payload of every record a later record of
     * the same opcode overwrote - which is what lets its encoder write the file back byte for byte; see
     * [OpcodeOrdered] and `docs/cache-source.md`.
     *
     * Everything the encoder needs is recorded here, in the read path, rather than in [changeValues]:
     * a codec decodes with this and nothing else, because [changeValues] derives values that are not in
     * the file at all - an obj's note and lend templates are copied wholesale from another definition -
     * and a definition re-encoded after that no longer matches its file.
     */
    open fun readLoop(definition: T, buffer: Reader) {
        val ordered = definition as? OpcodeOrdered
        if (ordered == null) {
            while (true) {
                val opcode = buffer.readUnsignedByte()
                if (opcode == 0) {
                    break
                }
                definition.read(opcode, buffer)
            }
            return
        }
        val order = IntArrayList()
        // Payload start and end offset of each record, two entries per record.
        val bounds = IntArrayList()
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            val start = buffer.position()
            definition.read(opcode, buffer)
            order.add(opcode)
            bounds.add(start)
            bounds.add(buffer.position())
        }
        val opcodes = order.toIntArray()
        val shadowed = shadowedPayloads(ordered, buffer, opcodes, bounds)
        ordered.shadowedPayloads = shadowed
        // Self validating: the order is dropped only when the encoder's own order reproduces it exactly,
        // so a presence rule that is wrong keeps the file's order instead of quietly changing the file.
        // A definition with shadowed payloads always keeps it, because the two are indexed against each other.
        ordered.opcodeOrder = if (shadowed == null && opcodes.contentEquals(canonicalOpcodes(definition))) null else opcodes
    }

    /**
     * The opcodes this type's encoder writes for [definition] on its own, which is what decides whether
     * [OpcodeOrdered.opcodeOrder] has to be kept; see [OpcodeEncoder.opcodes].
     *
     * Null for a type with no byte exact encoder, which keeps the file's order whatever it was.
     */
    protected open fun canonicalOpcodes(definition: T): IntArray? = null

    /**
     * Reads a definition exactly like [readLoop] but reports every opcode and the offset its byte sat at,
     * terminator included. Lets a caller say which opcode a given offset in the file belongs to, which is
     * what the encoder parity tests need to explain a differing byte.
     */
    fun readLoopTraced(definition: T, buffer: Reader, onOpcode: (opcode: Int, position: Int) -> Unit) {
        while (true) {
            val position = buffer.position()
            val opcode = buffer.readUnsignedByte()
            onOpcode(opcode, position)
            if (opcode == 0) {
                break
            }
            definition.read(opcode, buffer)
        }
    }

    /**
     * The payload of every record a later record of the same opcode overwrote with a different value, so
     * the encoder can put it back; null when nothing was overwritten, which is all but a handful of files.
     *
     * @see OpcodeOrdered.shadowedPayloads
     */
    private fun shadowedPayloads(ordered: OpcodeOrdered, buffer: Reader, opcodes: IntArray, bounds: IntArrayList): Array<ByteArray?>? {
        val last = HashMap<Int, Int>(opcodes.size)
        var repeats = false
        for (index in opcodes.indices) {
            if (last.put(opcodes[index], index) != null) {
                repeats = true
            }
        }
        if (!repeats) {
            return null
        }
        val end = buffer.position()
        val shadowed = arrayOfNulls<ByteArray>(opcodes.size)
        var overwritten = false
        for (index in opcodes.indices) {
            val opcode = opcodes[index]
            // A record type that repeats one record per array element is written from the fields every time.
            if (ordered.repeats(opcode) || last.getValue(opcode) == index) {
                continue
            }
            val payload = payload(buffer, bounds, index)
            // A repeat that carries the same bytes as the write that replaced it re-encodes to those bytes.
            if (payload.contentEquals(payload(buffer, bounds, last.getValue(opcode)))) {
                continue
            }
            shadowed[index] = payload
            overwritten = true
        }
        buffer.position(end)
        return if (overwritten) shadowed else null
    }

    /** The bytes of one record's payload, the opcode byte itself excluded. */
    private fun payload(buffer: Reader, bounds: IntArrayList, index: Int): ByteArray {
        val start = bounds.getInt(index * 2)
        val payload = ByteArray(bounds.getInt(index * 2 + 1) - start)
        buffer.position(start)
        buffer.readBytes(payload)
        return payload
    }

    protected abstract fun T.read(opcode: Int, buffer: Reader)

    open fun changeValues(definitions: Array<T>, definition: T) {
    }

    companion object {
        /**
         * Decodes a single cp1252 byte, rejecting the string terminator.
         *
         * @see Cp1252
         */
        fun byteToChar(b: Byte): Char {
            val i = 0xff and b.toInt()
            require(i != 0) { "Non cp1252 character 0x" + i.toString(16) + " provided" }
            return Cp1252.decode(i)
        }
    }
}
