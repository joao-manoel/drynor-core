package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.Parameters

/**
 * Payloads shared by the definition encoders. Each writes one record's payload, the opcode byte already
 * written by [world.gregs.voidps.cache.OpcodeEncoder], so unlike the mixin helpers on
 * [world.gregs.voidps.cache.definition.Recolourable] and friends they never decide whether to emit at all.
 */

/** Opcodes 40 and 41: a count followed by that many source/destination short pairs. */
internal fun Writer.writeColours(original: ShortArray?, modified: ShortArray?) {
    val count = original?.size ?: 0
    writeByte(count)
    for (index in 0 until count) {
        writeShort(original!![index].toInt())
        writeShort(modified!![index].toInt())
    }
}

/** Opcode 42: a count followed by that many palette bytes. */
internal fun Writer.writeColourPalette(palette: ByteArray?) {
    val bytes = palette ?: ByteArray(0)
    writeByte(bytes.size)
    for (colour in bytes) {
        writeByte(colour.toInt())
    }
}

/**
 * Opcode 249: a count followed by that many parameters, each a string flag, a 3 byte id and either a string
 * or an int. A record with no parameters is legal and leaves the map null, so a null map writes a count of 0.
 *
 * [records] is the file's own record list, which a definition only keeps when the map cannot reproduce it -
 * a file that lists the same id twice.
 */
internal fun Writer.writeParams(id: Int, params: Map<Int, Any>?, records: List<KeyedValue>?) {
    if (records != null) {
        writeByte(records.size)
        for (record in records) {
            writeParam(id, record.key, record.value)
        }
        return
    }
    writeByte(params?.size ?: 0)
    for ((key, value) in params ?: emptyMap()) {
        writeParam(id, key, value)
    }
}

/**
 * Opcode 249 for an [Extra] definition, whose params are keyed by name rather than by id.
 *
 * [Extra.readParameters] keys the map by param *name* when it is given a [Parameters] catalog, and a name
 * cannot be turned back into the 3 byte key. A decoder whose output is going to be encoded again has to be
 * built with [Parameters.EMPTY], which keys by the id as a decimal string; that is the default for every
 * decoder here.
 */
internal fun Writer.writeExtras(id: Int, extras: Map<String, Any>?) {
    val parameters = extras ?: emptyMap()
    writeByte(parameters.size)
    for ((key, value) in parameters) {
        val param = key.toIntOrNull()
            ?: error("Param '$key' of definition $id is a name, not an id; decode with Parameters.EMPTY to encode again")
        writeParam(id, param, value)
    }
}

private fun Writer.writeParam(id: Int, key: Int, value: Any) {
    writeByte(value is String)
    writeMedium(key)
    when (value) {
        is String -> writeString(value)
        is Int -> writeInt(value)
        else -> error("Param $key of definition $id is a ${value::class.simpleName}, not an int or a string")
    }
}
