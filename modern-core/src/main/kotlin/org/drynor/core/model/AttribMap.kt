package org.drynor.core.model

import java.util.concurrent.ConcurrentHashMap

class AttribMap {
    private val attribs = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> setO(name: String, value: Any?): T? {
        return if (value == null) {
            attribs.remove("O$name") as? T
        } else {
            attribs.put("O$name", value) as? T
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getO(name: String): T? = attribs["O$name"] as? T

    fun setI(name: String, value: Int) { attribs["I$name"] = value }
    fun setD(name: String, value: Double) { attribs["D$name"] = value }
    fun setB(name: String, value: Boolean) { attribs["B$name"] = value }
    fun setL(name: String, value: Long) { attribs["L$name"] = value }

    fun getB(name: String): Boolean = attribs["B$name"] as? Boolean ?: false

    @JvmOverloads
    fun getI(name: String, def: Int = 0): Int {
        val v = attribs["I$name"] ?: return def
        return when (v) {
            is Int -> v
            is Double -> v.toInt()
            is Number -> v.toInt()
            else -> def
        }
    }

    @JvmOverloads
    fun getD(name: String, def: Double = 0.0): Double = attribs["D$name"] as? Double ?: def

    fun getL(name: String): Long = attribs["L$name"] as? Long ?: 0L

    fun incI(name: String) { setI(name, getI(name) + 1) }
    fun decI(name: String) { setI(name, getI(name) - 1) }
    fun clear() { attribs.clear() }

    fun removeI(name: String): Int { val i = getI(name); attribs.remove("I$name"); return i }
    fun removeI(name: String, defaultVal: Int): Int { val i = getI(name, defaultVal); attribs.remove("I$name"); return i }
    fun removeB(name: String): Boolean { val b = getB(name); attribs.remove("B$name"); return b }
    @Suppress("UNCHECKED_CAST")
    fun <T> removeO(name: String): T? { val o: T? = getO(name); attribs.remove("O$name"); return o }
    fun removeL(name: String): Long { val l = getL(name); attribs.remove("L$name"); return l }
}
