package org.darkan.core.gameval

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.darkan.core.Logger.logWarn
import java.util.concurrent.ConcurrentHashMap

/**
 * Gson adapter for a seq-id field a data file can carry in either form: the raw id, or the
 * sequence's gameval name (`Gameval.SEQ`), which is what the combat defs tree is written in now
 * that the catalogs name almost every sequence. Ids with no proven name stay ids, so a
 * placeholder never round-trips through this as a name.
 *
 * An unresolvable name reads as `-1`, the field's own "no animation", with one warning per
 * distinct name rather than an exception: the combat defs loader drops a whole file when its
 * Gson read throws, and one bad field should not also cost an NPC its hitpoints and drops.
 */
class SeqGamevalAdapter(private val catalog: GamevalCatalog) : TypeAdapter<Int>() {

    /** The form `@JsonAdapter` needs - a named no-arg constructor, resolved against [Gameval]. */
    constructor() : this(Gameval)

    override fun write(out: JsonWriter, value: Int?) {
        if (value == null) {
            out.nullValue()
            return
        }
        val name = catalog.name(Gameval.SEQ, value)
        if (name != null) out.value(name) else out.value(value.toLong())
    }

    override fun read(`in`: JsonReader): Int {
        if (`in`.peek() != JsonToken.STRING) {
            return `in`.nextInt()
        }
        val text = `in`.nextString()
        return text.toIntOrNull() ?: catalog.id(Gameval.SEQ, text) ?: unknown(text)
    }

    /**
     * A placeholder string like `seq_5` is not a name in either direction ([GamevalCatalog.id]
     * never matches one), so it lands here too. Only a catalog that exists gets to complain, and
     * only once per distinct name per process.
     */
    private fun unknown(name: String): Int {
        if (catalog.has(Gameval.SEQ) && WARNED.add(name)) {
            logWarn("no seq gameval named '$name' - reading it as -1")
        }
        return -1
    }

    private companion object {
        val WARNED = ConcurrentHashMap.newKeySet<String>()
    }
}