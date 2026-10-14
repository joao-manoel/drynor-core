package world.gregs.voidps.cache.audio

import java.io.ByteArrayOutputStream

/**
 * Jagex's compact MIDI encoding - what indices 6 and 11 hold - and the standard MIDI file it is.
 *
 * The encoding is a **column store**. A standard MIDI file interleaves everything: a delta time, a
 * status byte, then that event's data bytes, over and over. Jagex's splits the same stream into one
 * column per kind of byte - every event's opcode first, then every delta time, then every
 * controller number, then the note keys, then the note-on velocities, and so on through twenty-one
 * columns - and delta-codes each column against its own running value. Similar bytes end up
 * adjacent, which is worth a third of the file to the gzip that wraps it.
 *
 * [toStandard] expands one; [toCompact] rebuilds it. The two are exact inverses over the whole 727
 * cache (see `docs/cache-source.md`), which is what lets the source tree store index 6 and index 11
 * as `.mid` files anyone can open.
 *
 * ### The layout
 *
 * ```
 * opcodes        one byte per event, tracks back to back, each track closed by 0x07
 * deltas         one variable-length int per event, in the same order
 * controllers    one byte per controller change: the delta of the controller number
 * <18 columns>   one byte per event that uses them, in event order (see COLUMNS below)
 * tempo          three bytes per tempo event
 * trackCount     one byte
 * division       two bytes
 * ```
 *
 * An opcode's low nibble is the event type and its high nibble is the **exclusive or** of the
 * channel with the running channel, so a run of events on one channel costs a nibble of zero. Two
 * opcodes are not channel events at all: `0x07` ends a track and `0x17` is a tempo change. A status
 * byte is written into the standard file only when the opcode differs from the previous opcode's low
 * nibble, which is where the standard file's running status comes from - and is why the two
 * directions agree on it without anything being recorded.
 */
object JagexMidi {

    /** MThd. */
    private const val HEADER = 0x4d546864

    /** MTrk. */
    private const val TRACK = 0x4d54726b

    /** The opcode that ends a track. */
    private const val END = 0x07

    /** The opcode of a tempo change: channel nibble 1, low nibble 7, and never a channel event. */
    private const val TEMPO = 0x17

    private const val NOTE_ON = 0
    private const val NOTE_OFF = 1
    private const val CONTROLLER = 2
    private const val PITCH_BEND = 3
    private const val CHANNEL_PRESSURE = 4
    private const val KEY_PRESSURE = 5
    private const val PROGRAM_CHANGE = 6

    /** The status byte of each event type, before the channel is added. */
    private val STATUS = intArrayOf(0x90, 0x80, 0xb0, 0xe0, 0xd0, 0xa0, 0xc0)

    /**
     * The column a controller change's value comes from, by controller number.
     *
     * Ten controllers have a column each because they carry continuous values that delta-code well;
     * the five switches share one, everything else shares another, and bank select - 0 and 32 - is
     * in with the program changes, because a bank select and a program change always come in pairs.
     */
    private const val COLUMN_SWITCH = 0
    private const val COLUMN_KEY_PRESSURE = 1
    private const val COLUMN_CHANNEL_PRESSURE = 2
    private const val COLUMN_PITCH_HIGH = 3
    private const val COLUMN_MOD_MSB = 4
    private const val COLUMN_VOLUME_MSB = 5
    private const val COLUMN_PAN_MSB = 6
    private const val COLUMN_NOTE_KEY = 7
    private const val COLUMN_NOTE_ON_VELOCITY = 8
    private const val COLUMN_OTHER = 9
    private const val COLUMN_NOTE_OFF_VELOCITY = 10
    private const val COLUMN_MOD_LSB = 11
    private const val COLUMN_VOLUME_LSB = 12
    private const val COLUMN_PAN_LSB = 13
    private const val COLUMN_PROGRAM = 14
    private const val COLUMN_PITCH_LOW = 15
    private const val COLUMN_NRPN_MSB = 16
    private const val COLUMN_NRPN_LSB = 17
    private const val COLUMN_RPN_MSB = 18
    private const val COLUMN_RPN_LSB = 19
    private const val COLUMN_TEMPO = 20
    private const val COLUMNS = 21

    /** Which column a controller number's value lives in. */
    private fun column(controller: Int): Int = when (controller) {
        0, 32 -> COLUMN_PROGRAM
        1 -> COLUMN_MOD_MSB
        33 -> COLUMN_MOD_LSB
        7 -> COLUMN_VOLUME_MSB
        39 -> COLUMN_VOLUME_LSB
        10 -> COLUMN_PAN_MSB
        42 -> COLUMN_PAN_LSB
        99 -> COLUMN_NRPN_MSB
        98 -> COLUMN_NRPN_LSB
        101 -> COLUMN_RPN_MSB
        100 -> COLUMN_RPN_LSB
        64, 65, 120, 121, 123 -> COLUMN_SWITCH
        else -> COLUMN_OTHER
    }

    // --- compact to standard ----------------------------------------------------------------

    /** [data], Jagex's compact encoding, as the standard MIDI file the client expands it into. */
    fun toStandard(data: ByteArray): ByteArray {
        val compact = Compact(data)
        val out = Output(data.size * 2 + 64)
        out.int(HEADER)
        out.int(6)
        out.short(if (compact.tracks > 1) 1 else 0)
        out.short(compact.tracks)
        out.short(compact.division)
        val state = State()
        for (track in 0 until compact.tracks) {
            val body = Output(1024)
            var previous = -1
            while (true) {
                body.varInt(compact.delta())
                val opcode = compact.opcode()
                val fresh = opcode != previous
                previous = opcode and 0xf
                if (opcode == END) {
                    if (fresh) {
                        body.byte(0xff)
                    }
                    body.byte(0x2f)
                    body.byte(0)
                    break
                }
                event(compact, body, state, opcode, fresh)
            }
            out.int(TRACK)
            out.int(body.length())
            out.bytes(body)
        }
        return out.toArray()
    }

    /** One event, expanded into [body]. */
    private fun event(compact: Compact, body: Output, state: State, opcode: Int, fresh: Boolean) {
        if (opcode == TEMPO) {
            if (fresh) {
                body.byte(0xff)
            }
            body.byte(0x51)
            body.byte(3)
            for (byte in 0 until 3) {
                body.byte(compact.value(COLUMN_TEMPO))
            }
            return
        }
        val type = opcode and 0xf
        state.channel = state.channel xor (opcode shr 4)
        if (fresh) {
            body.byte(STATUS[type] + state.channel)
        }
        when (type) {
            NOTE_ON -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.noteOnVelocity += compact.value(COLUMN_NOTE_ON_VELOCITY)
                body.byte(state.key and 0x7f)
                body.byte(state.noteOnVelocity and 0x7f)
            }
            NOTE_OFF -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.noteOffVelocity += compact.value(COLUMN_NOTE_OFF_VELOCITY)
                body.byte(state.key and 0x7f)
                body.byte(state.noteOffVelocity and 0x7f)
            }
            CONTROLLER -> {
                state.controller = (state.controller + compact.controller()) and 0x7f
                body.byte(state.controller)
                // The value is a signed delta against this controller number's own running value.
                state.values[state.controller] += compact.signed(column(state.controller))
                body.byte(state.values[state.controller] and 0x7f)
            }
            PITCH_BEND -> {
                state.bend += compact.value(COLUMN_PITCH_LOW)
                state.bend += compact.value(COLUMN_PITCH_HIGH) shl 7
                body.byte(state.bend and 0x7f)
                body.byte((state.bend shr 7) and 0x7f)
            }
            CHANNEL_PRESSURE -> {
                state.channelPressure += compact.value(COLUMN_CHANNEL_PRESSURE)
                body.byte(state.channelPressure and 0x7f)
            }
            KEY_PRESSURE -> {
                state.key += compact.value(COLUMN_NOTE_KEY)
                state.keyPressure += compact.value(COLUMN_KEY_PRESSURE)
                body.byte(state.key and 0x7f)
                body.byte(state.keyPressure and 0x7f)
            }
            PROGRAM_CHANGE -> body.byte(compact.value(COLUMN_PROGRAM))
            else -> throw IllegalStateException("A compact MIDI event type of $type is not a MIDI event.")
        }
    }

    /** The running values every column is delta-coded against, shared by every track. */
    private class State {
        var channel = 0
        var key = 0
        var noteOnVelocity = 0
        var noteOffVelocity = 0
        var bend = 0
        var channelPressure = 0
        var keyPressure = 0
        var controller = 0
        val values = IntArray(128)
    }

    /**
     * A compact file with its columns found: the two counting passes the client makes, and a cursor
     * per column that the expansion walks forward.
     */
    private class Compact(private val data: ByteArray) {

        val tracks: Int
        val division: Int
        private val positions = IntArray(COLUMNS)
        private var opcodes = 0
        private var deltas = 0
        private var controllers = 0

        init {
            require(data.size >= 3) { "A compact MIDI file is at least three bytes long." }
            tracks = data[data.size - 3].toInt() and 0xff
            division = ((data[data.size - 2].toInt() and 0xff) shl 8) or (data[data.size - 1].toInt() and 0xff)
            val counts = IntArray(8)
            var position = 0
            for (track in 0 until tracks) {
                while (true) {
                    val opcode = data[position++].toInt() and 0xff
                    if (opcode == END) {
                        break
                    }
                    if (opcode == TEMPO) {
                        counts[7]++
                    } else {
                        val type = opcode and 0xf
                        require(type in NOTE_ON..PROGRAM_CHANGE) {
                            "A compact MIDI opcode of $opcode is not an event."
                        }
                        counts[type]++
                    }
                }
            }
            deltas = position
            // One delta per event, the track terminators included.
            var events = tracks + counts[7]
            for (type in NOTE_ON..PROGRAM_CHANGE) {
                events += counts[type]
            }
            position = deltas
            for (event in 0 until events) {
                position = skipVarInt(position)
            }
            controllers = position
            // The controller numbers decide how many of each controller column there are, so they
            // are walked before any column position is known.
            val lengths = IntArray(COLUMNS)
            var controller = 0
            for (change in 0 until counts[CONTROLLER]) {
                controller = (controller + (data[position++].toInt() and 0xff)) and 0x7f
                lengths[column(controller)]++
            }
            lengths[COLUMN_KEY_PRESSURE] += counts[KEY_PRESSURE]
            lengths[COLUMN_CHANNEL_PRESSURE] += counts[CHANNEL_PRESSURE]
            lengths[COLUMN_PITCH_HIGH] += counts[PITCH_BEND]
            lengths[COLUMN_PITCH_LOW] += counts[PITCH_BEND]
            lengths[COLUMN_NOTE_KEY] += counts[NOTE_ON] + counts[NOTE_OFF] + counts[KEY_PRESSURE]
            lengths[COLUMN_NOTE_ON_VELOCITY] += counts[NOTE_ON]
            lengths[COLUMN_NOTE_OFF_VELOCITY] += counts[NOTE_OFF]
            lengths[COLUMN_PROGRAM] += counts[PROGRAM_CHANGE]
            lengths[COLUMN_TEMPO] += counts[7] * 3
            for (column in 0 until COLUMNS) {
                positions[column] = position
                position += lengths[column]
            }
            check(position == data.size - 3) {
                "A compact MIDI file's columns end at $position, not ${data.size - 3}."
            }
        }

        /** The next opcode. */
        fun opcode(): Int = data[opcodes++].toInt() and 0xff

        /** The next delta time. */
        fun delta(): Int {
            var value = 0
            while (true) {
                val byte = data[deltas++].toInt()
                value = (value shl 7) or (byte and 0x7f)
                if (byte >= 0) {
                    return value
                }
            }
        }

        /** The next controller number delta. */
        fun controller(): Int = data[controllers++].toInt() and 0xff

        /** The next unsigned byte of column [index]. */
        fun value(index: Int): Int = data[positions[index]++].toInt() and 0xff

        /** The next signed byte of column [index]. */
        fun signed(index: Int): Int = data[positions[index]++].toInt()

        private fun skipVarInt(from: Int): Int {
            var position = from
            while (data[position++] < 0) {
                // The continuation bit is the sign bit.
            }
            return position
        }
    }

    // --- standard to compact ----------------------------------------------------------------

    /**
     * [midi], a standard MIDI file, back into Jagex's compact encoding.
     *
     * Every column is delta-coded the way the expansion undoes it: the difference of the value and
     * the column's running value, taken as an unsigned byte for the columns the client accumulates
     * unsigned and as a signed one for the controller values it accumulates signed. Every one of the
     * 1,662 files in the 727 cache comes back byte for byte, so those are the conventions Jagex's
     * own packer used.
     */
    fun toCompact(midi: ByteArray): ByteArray {
        val file = Standard(midi)
        val opcodes = Output(1024)
        val deltas = Output(1024)
        val controllers = Output(256)
        val columns = Array(COLUMNS) { Output(256) }
        val state = State()
        for (track in file.tracks) {
            for (event in track) {
                deltas.varInt(event.delta)
                if (event.status == 0xff) {
                    if (event.meta == 0x2f) {
                        opcodes.byte(END)
                        continue
                    }
                    opcodes.byte(TEMPO)
                    for (byte in event.data) {
                        columns[COLUMN_TEMPO].byte(byte)
                    }
                    continue
                }
                val type = STATUS.indexOf(event.status and 0xf0)
                check(type >= 0) { "A status byte of ${event.status} has no compact MIDI opcode." }
                val channel = event.status and 0xf
                opcodes.byte(((channel xor state.channel) shl 4) or type)
                state.channel = channel
                encode(columns, controllers, state, type, event)
            }
        }
        val out = Output(midi.size)
        out.bytes(opcodes)
        out.bytes(deltas)
        out.bytes(controllers)
        for (column in columns) {
            out.bytes(column)
        }
        out.byte(file.trackCount)
        out.short(file.division)
        return out.toArray()
    }

    /** One channel event's data bytes, into the columns they belong to. */
    private fun encode(columns: Array<Output>, controllers: Output, state: State, type: Int, event: Event) {
        when (type) {
            NOTE_ON -> {
                key(columns, state, event.data[0])
                columns[COLUMN_NOTE_ON_VELOCITY].byte(delta(event.data[1], state.noteOnVelocity))
                state.noteOnVelocity = event.data[1]
            }
            NOTE_OFF -> {
                key(columns, state, event.data[0])
                columns[COLUMN_NOTE_OFF_VELOCITY].byte(delta(event.data[1], state.noteOffVelocity))
                state.noteOffVelocity = event.data[1]
            }
            CONTROLLER -> {
                val controller = event.data[0]
                controllers.byte(delta(controller, state.controller))
                state.controller = controller
                columns[column(controller)].byte(delta(event.data[1], state.values[controller]))
                state.values[controller] = event.data[1]
            }
            PITCH_BEND -> {
                // The two halves are one fourteen bit value, so the wrap is at 0x3fff and each
                // half still comes out inside seven bits.
                val bend = (event.data[1] shl 7) or event.data[0]
                val difference = (bend - state.bend) and 0x3fff
                columns[COLUMN_PITCH_LOW].byte(difference and 0x7f)
                columns[COLUMN_PITCH_HIGH].byte(difference shr 7)
                state.bend = bend
            }
            CHANNEL_PRESSURE -> {
                columns[COLUMN_CHANNEL_PRESSURE].byte(delta(event.data[0], state.channelPressure))
                state.channelPressure = event.data[0]
            }
            KEY_PRESSURE -> {
                key(columns, state, event.data[0])
                columns[COLUMN_KEY_PRESSURE].byte(delta(event.data[1], state.keyPressure))
                state.keyPressure = event.data[1]
            }
            // A program change is the program number itself; nothing accumulates it.
            PROGRAM_CHANGE -> columns[COLUMN_PROGRAM].byte(event.data[0])
        }
    }

    /** The note key column, which note on, note off and key pressure all delta-code against. */
    private fun key(columns: Array<Output>, state: State, key: Int) {
        columns[COLUMN_NOTE_KEY].byte(delta(key, state.key))
        state.key = key
    }

    /**
     * A column's delta, in the seven bits everything in a MIDI file fits in.
     *
     * The expansion adds the byte to a running total and keeps the low seven bits, so any byte
     * congruent to the difference would decode the same; Jagex's packer wrote the non-negative one,
     * which is what makes the round trip byte exact rather than merely equivalent.
     */
    private fun delta(value: Int, previous: Int): Int = (value - previous) and 0x7f

    /** One event of a standard MIDI file. */
    private class Event(val delta: Int, val status: Int, val meta: Int, val data: IntArray)

    /** A standard MIDI file, parsed into its tracks. */
    private class Standard(private val data: ByteArray) {

        val trackCount: Int
        val division: Int
        val tracks: List<List<Event>>
        private var position = 0

        init {
            require(int() == HEADER) { "This is not a standard MIDI file." }
            val length = int()
            require(length == 6) { "A MIDI header chunk is 6 bytes, not $length." }
            short()
            trackCount = short()
            division = short()
            tracks = List(trackCount) { track() }
            require(position == data.size) { "A MIDI file has ${data.size - position} bytes after its tracks." }
        }

        private fun track(): List<Event> {
            require(int() == TRACK) { "A MIDI track does not start with MTrk." }
            val length = int()
            val end = position + length
            val events = ArrayList<Event>()
            var status = 0
            while (position < end) {
                val delta = varInt()
                var byte = data[position].toInt() and 0xff
                if (byte >= 0x80) {
                    position++
                    status = byte
                } else {
                    byte = status
                }
                if (status == 0xff) {
                    val meta = data[position++].toInt() and 0xff
                    val size = varInt()
                    val payload = IntArray(size) { data[position + it].toInt() and 0xff }
                    position += size
                    events.add(Event(delta, 0xff, meta, payload))
                    continue
                }
                val arguments = if (status and 0xf0 == 0xc0 || status and 0xf0 == 0xd0) 1 else 2
                val payload = IntArray(arguments) { data[position + it].toInt() and 0xff }
                position += arguments
                events.add(Event(delta, status, 0, payload))
            }
            require(position == end) { "A MIDI track's events run ${position - end} bytes past its length." }
            return events
        }

        private fun int(): Int {
            var value = 0
            for (byte in 0 until 4) {
                value = (value shl 8) or (data[position++].toInt() and 0xff)
            }
            return value
        }

        private fun short(): Int = ((data[position++].toInt() and 0xff) shl 8) or (data[position++].toInt() and 0xff)

        private fun varInt(): Int {
            var value = 0
            while (true) {
                val byte = data[position++].toInt()
                value = (value shl 7) or (byte and 0x7f)
                if (byte >= 0) {
                    return value
                }
            }
        }
    }

    /** A growable byte sink, because both directions write before they know how much. */
    private class Output(capacity: Int) : ByteArrayOutputStream(capacity) {

        fun byte(value: Int) = write(value and 0xff)

        fun short(value: Int) {
            byte(value shr 8)
            byte(value)
        }

        fun int(value: Int) {
            byte(value shr 24)
            byte(value shr 16)
            byte(value shr 8)
            byte(value)
        }

        /** A MIDI variable-length quantity: seven bits a byte, most significant first. */
        fun varInt(value: Int) {
            var shift = 28
            while (shift > 0 && (value ushr shift) == 0) {
                shift -= 7
            }
            while (shift > 0) {
                byte(((value ushr shift) and 0x7f) or 0x80)
                shift -= 7
            }
            byte(value and 0x7f)
        }

        fun bytes(other: Output) = write(other.buf, 0, other.count)

        fun length(): Int = count

        fun toArray(): ByteArray = toByteArray()
    }
}
